package com.mwalczak.player.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import com.mwalczak.player.PlaybackQueue
import com.mwalczak.player.data.CatalogRepository
import com.mwalczak.player.data.Credentials
import com.mwalczak.player.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit

/**
 * Phone -> TV remote over the local network.
 *
 * Every running Bunny Player is both:
 * - a **receiver**: a tiny HTTP server advertised via mDNS/DNS-SD (`_bunnyplayer._tcp`), which
 *   accepts play / next / previous / stop / login commands and reports what it is playing;
 * - a **sender**: it discovers other receivers, and when one is picked as [target], playing a
 *   channel sends it there instead of playing locally.
 *
 * Both run while the app is in the foreground ([start] / [stop] from the activity).
 */
class RemoteControl(
    private val context: Context,
    http: OkHttpClient,
    private val settings: SettingsStore,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
) {
    private val http = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    /** Other Bunny Players on the network. */
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _target = MutableStateFlow<RemoteDevice?>(null)
    /** Where channels are sent instead of playing locally; null = play on this device. */
    val target: StateFlow<RemoteDevice?> = _target.asStateFlow()

    private val _commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 8)
    /** Commands received from another device, for this app's UI to act on. */
    val commands: SharedFlow<RemoteCommand> = _commands.asSharedFlow()

    /** Set by the player screen so remote devices can see what is on. */
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)

    val deviceName: String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

    private var server: ServerSocket? = null
    private var ownServiceName: String? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun select(device: RemoteDevice?) {
        _target.value = device
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle

    fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = socket
        Thread({ serve(socket) }, "remote-receiver").apply { isDaemon = true }.start()
        register(socket.localPort)
        discover()
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        runCatching { server?.close() }
        server = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        _devices.value = emptyList()
    }

    // ---------------------------------------------------------------------------------------
    // Sender

    /** Plays [queue] on [device]; signs the device in with our account first if needed. */
    suspend fun play(device: RemoteDevice, queue: PlaybackQueue): Result<RemoteStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val request = RemoteRequest("play", queue.title, queue.channels, queue.startIndex)
            var status = send(device, request)
            if (status.error == NOT_SIGNED_IN) {
                val creds = settings.currentCredentials() ?: throw IOException("Sign in on this device first")
                send(device, RemoteRequest("login", server = creds.server, username = creds.username, password = creds.password))
                status = send(device, request)
            }
            status.error?.let { throw IOException(it) }
            status
        }
    }

    suspend fun command(device: RemoteDevice, type: String): Result<RemoteStatus> = withContext(Dispatchers.IO) {
        runCatching { send(device, RemoteRequest(type)) }
    }

    suspend fun status(device: RemoteDevice): Result<RemoteStatus> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("${device.baseUrl}/status").build()).execute().use { r ->
                remoteJson.decodeFromString(RemoteStatus.serializer(), r.body?.string().orEmpty())
            }
        }
    }

    private fun send(device: RemoteDevice, request: RemoteRequest): RemoteStatus {
        val body = remoteJson.encodeToString(RemoteRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        return http.newCall(Request.Builder().url("${device.baseUrl}/command").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            runCatching { remoteJson.decodeFromString(RemoteStatus.serializer(), text) }
                .getOrElse { throw IOException("HTTP ${r.code}") }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Receiver

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            }
            client.use { c ->
                runCatching {
                    c.soTimeout = 10_000
                    val request = MiniHttp.readRequest(c.getInputStream())
                    val (code, status) = handle(request)
                    MiniHttp.writeJson(c.getOutputStream(), code, remoteJson.encodeToString(RemoteStatus.serializer(), status))
                }
            }
        }
    }

    private fun handle(request: MiniHttp.Request): Pair<Int, RemoteStatus> {
        if (request.method == "GET" && request.path == "/status") return 200 to currentStatus()
        if (request.method != "POST" || request.path != "/command") return 404 to currentStatus(error = "not found")

        val cmd = runCatching { remoteJson.decodeFromString(RemoteRequest.serializer(), request.body) }.getOrNull()
            ?: return 400 to currentStatus(error = "bad request")
        when (cmd.type) {
            "login" -> {
                val server = cmd.server ?: return 400 to currentStatus(error = "missing server")
                val creds = Credentials(server, cmd.username.orEmpty(), cmd.password.orEmpty())
                runBlocking {
                    settings.saveCredentials(creds)
                    scope.launch { catalog.load(creds, force = true) }
                }
            }
            "play" -> {
                if (runBlocking { settings.currentCredentials() } == null) return 409 to currentStatus(error = NOT_SIGNED_IN)
                if (cmd.channels.isEmpty()) return 400 to currentStatus(error = "no channels")
                val queue = PlaybackQueue(cmd.title ?: "From phone", cmd.channels, cmd.index.coerceIn(cmd.channels.indices))
                emit(RemoteCommand.Play(queue))
                val channel = queue.channels[queue.startIndex]
                return 200 to currentStatus().copy(playing = channel.name, title = queue.title, index = queue.startIndex, count = queue.channels.size)
            }
            "next" -> emit(RemoteCommand.Next)
            "previous" -> emit(RemoteCommand.Previous)
            "last" -> emit(RemoteCommand.Last)
            "stop" -> emit(RemoteCommand.Stop)
            else -> return 400 to currentStatus(error = "unknown command")
        }
        return 200 to currentStatus()
    }

    private fun emit(command: RemoteCommand) {
        scope.launch(Dispatchers.Main) { _commands.emit(command) }
    }

    private fun currentStatus(error: String? = null): RemoteStatus {
        val now = nowPlaying.value
        return RemoteStatus(
            device = deviceName,
            signedIn = runBlocking { settings.currentCredentials() } != null,
            playing = now?.channel,
            title = now?.title,
            index = now?.index ?: -1,
            count = now?.count ?: 0,
            error = error,
        )
    }

    // ---------------------------------------------------------------------------------------
    // mDNS advertising and discovery

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                ownServiceName = info.serviceName
                _devices.update { list -> list.filterNot { it.name == info.serviceName } }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == ownServiceName) return
                synchronized(resolveQueue) { resolveQueue.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _devices.update { list -> list.filterNot { it.name == info.serviceName } }
                if (_target.value?.name == info.serviceName) _target.value = null
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** Older Android versions allow only one resolve at a time, so resolves are queued. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            resolveQueue.removeFirst()
        }
        val done = {
            synchronized(resolveQueue) { resolving = false }
            resolveNext()
        }
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()

                override fun onServiceResolved(info: NsdServiceInfo) {
                    // Prefer IPv4: link-local IPv6 addresses need a scope id that URLs handle poorly.
                    val host = if (Build.VERSION.SDK_INT >= 34) {
                        info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
                    } else {
                        info.host
                    }
                    val isSelf = info.serviceName == ownServiceName ||
                        (host != null && info.port == server?.localPort && isLocalAddress(host))
                    if (host != null && !isSelf) {
                        val device = RemoteDevice(info.serviceName, host.hostAddress ?: host.hostName, info.port)
                        _devices.update { list -> (list.filterNot { it.name == device.name } + device).sortedBy { it.name } }
                    }
                    done()
                }
            })
        }.onFailure { done() }
    }

    private fun isLocalAddress(address: InetAddress): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { nif -> nif.inetAddresses.toList().any { it == address } }
    }.getOrDefault(false)

    companion object {
        const val SERVICE_TYPE = "_bunnyplayer._tcp"
        const val NOT_SIGNED_IN = "not_signed_in"
    }
}
