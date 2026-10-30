package com.mwalczak.player.data.epg

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.mwalczak.player.data.ChannelMatcher
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The guide on disk: one row per programme, indexed by channel and time. With tens of thousands of
 * channels the guide is far too big for memory, so screens query just what they show.
 *
 * A refresh writes a complete new database next to the current one ([Builder]) and then swaps it
 * in, so the old guide keeps working until the new one is ready.
 */
class EpgDatabase(context: Context) {

    private val file: File = context.getDatabasePath("epg.db").apply { parentFile?.mkdirs() }
    private val lock = ReentrantReadWriteLock()
    private var db: SQLiteDatabase = openOrCreate(file)

    inner class Builder internal constructor() {
        private val target = File(file.parentFile, "epg-new.db")
        internal val db: SQLiteDatabase

        init {
            SQLiteDatabase.deleteDatabase(target)
            db = SQLiteDatabase.openOrCreateDatabase(target, null)
            db.execSQL(CREATE_TABLE)
            db.execSQL(CREATE_META)
        }

        private val insert = db.compileStatement(
            "INSERT INTO programme (stream_id, start, stop, title, title_key, descr) VALUES (?, ?, ?, ?, ?, ?)",
        )

        /** Adds one guide file's worth of programmes in a single transaction. */
        fun add(programmes: Map<Int, List<Programme>>) {
            db.beginTransaction()
            try {
                programmes.forEach { (streamId, list) ->
                    list.forEach { p ->
                        insert.bindLong(1, streamId.toLong())
                        insert.bindLong(2, p.start)
                        insert.bindLong(3, p.stop)
                        insert.bindString(4, p.title)
                        insert.bindString(5, ChannelMatcher.searchKey(p.title))
                        insert.bindString(6, p.desc.take(MAX_DESC))
                        insert.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        internal fun finish(meta: Map<String, String>) {
            db.execSQL(CREATE_INDEXES)
            db.execSQL(CREATE_STOP_INDEX)
            meta.forEach { (k, v) ->
                db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", k); put("value", v) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.close()
        }

        fun discard() {
            runCatching { db.close() }
            SQLiteDatabase.deleteDatabase(target)
        }

        internal val file: File get() = target
    }

    fun newBuilder(): Builder = Builder()

    /** Replaces the current guide with a finished [builder]. */
    fun swapIn(builder: Builder, meta: Map<String, String>) {
        builder.finish(meta)
        lock.write {
            db.close()
            SQLiteDatabase.deleteDatabase(file)
            if (!builder.file.renameTo(file)) throw IllegalStateException("Could not install the new guide")
            db = openOrCreate(file)
        }
    }

    fun meta(key: String): String? = query {
        it.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    /** The programme on air right now on every channel that has one. */
    fun onAir(nowSec: Long): Map<Int, Programme> = query {
        it.rawQuery(
            "SELECT stream_id, start, stop, title, descr FROM programme WHERE stop > ? AND start <= ?",
            arrayOf(nowSec.toString(), nowSec.toString()),
        ).use { c ->
            val out = HashMap<Int, Programme>(c.count * 2)
            while (c.moveToNext()) out[c.getInt(0)] = Programme(c.getLong(1), c.getLong(2), c.getString(3), c.getString(4))
            out
        }
    } ?: emptyMap()

    /** One channel's programmes overlapping [fromSec]..[toSec], in time order. */
    fun programmes(streamId: Int, fromSec: Long, toSec: Long): List<Programme> = query {
        it.rawQuery(
            "SELECT start, stop, title, descr FROM programme WHERE stream_id = ? AND stop > ? AND start < ? ORDER BY start",
            arrayOf(streamId.toString(), fromSec.toString(), toSec.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Programme(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))) }
        }
    }.orEmpty()

    /** Programmes not yet over whose folded title contains [term]; refined further by the caller. */
    fun searchCandidates(term: String, nowSec: Long, limit: Int = 5000): Map<Int, List<Programme>> = query {
        it.rawQuery(
            "SELECT stream_id, start, stop, title, descr FROM programme WHERE stop > ? AND title_key LIKE ? ESCAPE '\\' LIMIT $limit",
            arrayOf(nowSec.toString(), "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"),
        ).use { c ->
            val out = HashMap<Int, MutableList<Programme>>()
            while (c.moveToNext()) {
                out.getOrPut(c.getInt(0)) { mutableListOf() }.add(Programme(c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
            out
        }
    }.orEmpty()

    fun channelCount(): Int = query {
        it.rawQuery("SELECT COUNT(DISTINCT stream_id) FROM programme", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } ?: 0

    private fun <T> query(block: (SQLiteDatabase) -> T): T? = lock.read { runCatching { block(db) }.getOrNull() }

    private fun openOrCreate(file: File): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL(CREATE_TABLE)
            execSQL(CREATE_META)
            execSQL(CREATE_INDEXES)
            execSQL(CREATE_STOP_INDEX)
        }

    private companion object {
        const val MAX_DESC = 200
        const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS programme (" +
            "stream_id INTEGER NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, " +
            "title TEXT NOT NULL, title_key TEXT NOT NULL, descr TEXT NOT NULL)"
        const val CREATE_META = "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)"
        const val CREATE_INDEXES = "CREATE INDEX IF NOT EXISTS programme_channel ON programme (stream_id, start)"
        const val CREATE_STOP_INDEX = "CREATE INDEX IF NOT EXISTS programme_stop ON programme (stop)"
    }
}
