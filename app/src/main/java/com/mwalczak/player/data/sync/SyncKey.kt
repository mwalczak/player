package com.mwalczak.player.data.sync

import com.mwalczak.player.data.Credentials
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The key that finds this person's saved setup on the sync server: derived from the mybunny login
 * on the device, so every device signed in with that login finds the same setup while the server
 * never sees the login itself (only a hash of this key).
 */
object SyncKey {
    private const val SALT = "bunny-player-sync-v1"
    private const val ITERATIONS = 50_000

    fun forLogin(creds: Credentials): String {
        // The host only: the same provider may be typed with or without http://, a port or a path.
        val host = runCatching { URI(if ("://" in creds.server) creds.server else "http://${creds.server}").host }
            .getOrNull()?.lowercase() ?: creds.server.trim().lowercase()
        return pbkdf2(
            "$host\n${creds.username.trim()}\n${creds.password}".toByteArray(),
            SALT.toByteArray(),
            ITERATIONS,
        ).joinToString("") { "%02x".format(it) }
    }

    /** PBKDF2-HMAC-SHA256 with one 32-byte block (javax's PBKDF2WithHmacSHA256 needs API 26). */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }
}

/** A share code as people read it: "ABCD-EFGH". */
fun formatShareCode(code: String): String = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code
