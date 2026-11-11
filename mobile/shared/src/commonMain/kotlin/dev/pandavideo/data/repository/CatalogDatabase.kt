package dev.pandavideo.data.repository

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import dev.pandavideo.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

internal data class StoredCatalog(val page: CatalogPage, val savedAt: Long)
internal data class StoredResponse(val payload: String, val savedAt: Long)

/** Persistent catalog pages; each film is stored as an ordered row in a SQLite transaction. */
@OptIn(ExperimentalResourceApi::class)
class CatalogDatabase(private val path: String, private val initializeFromBundle: Boolean = false) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun <T> access(block: (SQLiteConnection) -> T): T = withContext(Dispatchers.Default) {
        lock.withLock {
            val file = path.toPath()
            val fs = FileSystem.SYSTEM
            if (initializeFromBundle && (fs.metadataOrNull(file)?.size ?: 0L) == 0L) {
                // Ship a verified SQLite snapshot so first launch needs no catalog request.
                // Copy only when there is no existing database; never replace user refreshes.
                val temporary = (path + ".seed.tmp").toPath()
                val bytes = Res.readBytes("files/catalog-seed.db")
                val sink = fs.sink(temporary).buffer()
                try { sink.write(bytes) } finally { sink.close() }
                fs.atomicMove(temporary, file)
            }
            // Connections are scoped to a single operation, so cancellation and app teardown
            // cannot leave an open native handle. The mutex protects transactions on all targets.
            BundledSQLiteDriver().open(path).use { db ->
                db.execute("PRAGMA busy_timeout = 5000")
                db.execute("CREATE TABLE IF NOT EXISTS catalog_pages (key TEXT PRIMARY KEY, metadata TEXT NOT NULL, saved_at INTEGER NOT NULL)")
                db.execute("CREATE TABLE IF NOT EXISTS catalog_items (page_key TEXT NOT NULL, position INTEGER NOT NULL, item TEXT NOT NULL, PRIMARY KEY(page_key, position))")
                db.execute("CREATE TABLE IF NOT EXISTS response_cache (key TEXT PRIMARY KEY, payload TEXT NOT NULL, saved_at INTEGER NOT NULL, byte_size INTEGER NOT NULL)")
                block(db)
            }
        }
    }

    internal suspend fun read(key: String): StoredCatalog? = access { db ->
        val metadata = db.prepare("SELECT metadata, saved_at FROM catalog_pages WHERE key = ?").use { statement ->
            statement.bindText(1, key)
            if (!statement.step()) null else statement.getText(0) to statement.getLong(1)
        } ?: return@access null
        val items = db.prepare("SELECT item FROM catalog_items WHERE page_key = ? ORDER BY position").use { statement ->
            statement.bindText(1, key)
            buildList { while (statement.step()) add(json.decodeFromString<CatalogItem>(statement.getText(0))) }
        }
        StoredCatalog(json.decodeFromString<CatalogPage>(metadata.first).copy(items = items), metadata.second)
    }

    internal suspend fun write(key: String, page: CatalogPage, savedAt: Long) {
        // An upstream challenge/empty response must never erase a previously usable catalog.
        if (page.items.isEmpty() || page.stale) return
        access { db ->
            db.execute("BEGIN IMMEDIATE")
            try {
                db.prepare("INSERT OR REPLACE INTO catalog_pages(key, metadata, saved_at) VALUES (?, ?, ?)").use {
                    it.bindText(1, key)
                    it.bindText(2, json.encodeToString(page.copy(items = emptyList(), stale = false)))
                    it.bindLong(3, savedAt)
                    it.step()
                }
                db.prepare("DELETE FROM catalog_items WHERE page_key = ?").use {
                    it.bindText(1, key)
                    it.step()
                }
                db.prepare("INSERT INTO catalog_items(page_key, position, item) VALUES (?, ?, ?)").use { statement ->
                    page.items.forEachIndexed { index, item ->
                        statement.bindText(1, key)
                        statement.bindLong(2, index.toLong())
                        statement.bindText(3, json.encodeToString(item))
                        statement.step()
                        statement.reset()
                        statement.clearBindings()
                    }
                }
                // Preserve the bundled Top 250/TV keys and retain at most 256 additional
                // filter/hot-list pages. Remove their item rows in the same transaction.
                db.execute("DELETE FROM catalog_pages WHERE key IN (SELECT key FROM catalog_pages WHERE key NOT LIKE 'top250:%' AND key NOT LIKE 'tv:%' ORDER BY saved_at DESC, rowid DESC LIMIT -1 OFFSET 256)")
                db.execute("DELETE FROM catalog_items WHERE page_key NOT IN (SELECT key FROM catalog_pages)")
                db.execute("COMMIT")
            } catch (error: Throwable) {
                db.execute("ROLLBACK")
                throw error
            }
        }
    }

    internal suspend fun readResponse(key: String): StoredResponse? = access { db ->
        db.prepare("SELECT payload, saved_at FROM response_cache WHERE key = ?").use {
            it.bindText(1, key)
            if (it.step()) StoredResponse(it.getText(0), it.getLong(1)) else null
        }
    }

    internal suspend fun writeResponse(key: String, payload: String, savedAt: Long) {
        val size = payload.encodeToByteArray().size.toLong()
        if (size > 2 * 1024 * 1024) return
        access { db ->
            db.execute("BEGIN IMMEDIATE")
            try {
                db.prepare("INSERT OR REPLACE INTO response_cache VALUES (?, ?, ?, ?)").use {
                    it.bindText(1, key)
                    it.bindText(2, payload)
                    it.bindLong(3, savedAt)
                    it.bindLong(4, size)
                    it.step()
                }
                // Bound search/detail storage by both count and encoded bytes. Eviction is
                // oldest-first; the preinstalled catalogs live in separate tables.
                while (db.prepare("SELECT COUNT(*), COALESCE(SUM(byte_size), 0) FROM response_cache").use {
                        it.step()
                        it.getLong(0) > 256 || it.getLong(1) > 16L * 1024 * 1024
                    }) {
                    db.execute("DELETE FROM response_cache WHERE key = (SELECT key FROM response_cache ORDER BY saved_at, rowid LIMIT 1)")
                }
                db.execute("COMMIT")
            } catch (error: Throwable) {
                db.execute("ROLLBACK")
                throw error
            }
        }
    }
}

private fun SQLiteConnection.execute(sql: String) {
    prepare(sql).use { it.step() }
}
