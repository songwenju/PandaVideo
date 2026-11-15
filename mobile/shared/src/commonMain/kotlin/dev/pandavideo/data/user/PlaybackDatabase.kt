package dev.pandavideo.data.user

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pandavideo.core.platform.KeyValueStorage

/**
 * Durable personal playback data, stored separately from expendable catalog caches.
 * Each connection is operation-scoped; a snapshot write commits history and progress together.
 */
internal class PlaybackDatabase(private val path: String, legacy: KeyValueStorage) : KeyValueStorage {
    init {
        access { db ->
            val migrated = db.prepare("SELECT value FROM playback_data WHERE key = 'migrated'").use { it.step() }
            if (!migrated) {
                // Commit all legacy records before removing any preference. The marker prevents
                // deleted history from being resurrected on subsequent launches.
                write(db, buildMap {
                    listOf("history", "progress", "episodes").forEach { key ->
                        legacy.getString(key)?.let { put(key, it) }
                    }
                    put("migrated", "1")
                })
            }
        }
        listOf("history", "progress", "episodes").forEach(legacy::remove)
    }

    override fun getString(key: String): String? = access { db ->
        db.prepare("SELECT value FROM playback_data WHERE key = ?").use {
            it.bindText(1, key)
            if (it.step()) it.getText(0) else null
        }
    }

    override fun putString(key: String, value: String) = writeSnapshot(mapOf(key to value))

    override fun remove(key: String) = access { db ->
        db.prepare("DELETE FROM playback_data WHERE key = ?").use {
            it.bindText(1, key)
            it.step()
            Unit
        }
    }

    /** Atomically replaces related playback snapshots, including an explicitly empty history. */
    fun writeSnapshot(values: Map<String, String>) = access { write(it, values) }

    private fun write(db: SQLiteConnection, values: Map<String, String>) {
        db.prepare("BEGIN IMMEDIATE").use { it.step() }
        try {
            db.prepare("INSERT OR REPLACE INTO playback_data(key, value) VALUES (?, ?)").use { statement ->
                values.forEach { (key, value) ->
                    statement.bindText(1, key)
                    statement.bindText(2, value)
                    statement.step()
                    statement.reset()
                    statement.clearBindings()
                }
            }
            db.prepare("COMMIT").use { it.step() }
        } catch (error: Throwable) {
            db.prepare("ROLLBACK").use { it.step() }
            throw error
        }
    }

    private fun <T> access(block: (SQLiteConnection) -> T): T =
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
            db.prepare("CREATE TABLE IF NOT EXISTS playback_data (key TEXT PRIMARY KEY, value TEXT NOT NULL)").use { it.step() }
            block(db)
        }
}
