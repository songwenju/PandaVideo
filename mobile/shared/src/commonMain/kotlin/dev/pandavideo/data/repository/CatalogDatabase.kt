package dev.pandavideo.data.repository

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import dev.pandavideo.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

internal data class StoredCatalog(val page: CatalogPage, val savedAt: Long)
internal data class StoredResponse(val payload: String, val savedAt: Long)
internal data class ContentCacheUsage(val bytes: Long = 0, val entries: Long = 0)

/** Verified website exports, shared by new and existing installations. */
@Serializable
internal data class BundledCatalogSnapshot(val pages: Map<String, BundledCatalogEntry>)
@Serializable
internal data class BundledCatalogEntry(val page: CatalogPage, val savedAt: Long)

/** Subject-ID keyed corrections keep catalog metadata separate from provider identities. */
@Serializable
internal data class BundledPlaybackIdentities(val subjects: Map<String, BundledPlaybackIdentity>)
@Serializable
internal data class BundledPlaybackIdentity(
    val year: String,
    @SerialName("is_series") val isSeries: Boolean,
    /** Stable Pomo site:id pair, present only after manifest and segment verification. */
    val pomo: String = "",
)

/** Persistent catalog pages; each film is stored as an ordered row in a SQLite transaction. */
@OptIn(ExperimentalResourceApi::class)
class CatalogDatabase(private val path: String, private val initializeFromBundle: Boolean = false) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var bundledTop250: List<CatalogPage>? = null
    private var bundledCategories: BundledCatalogSnapshot? = null
    private var bundledPlaybackIdentities: BundledPlaybackIdentities? = null

    /** Applies shipped identity corrections to cached cards without replacing refreshed pages. */
    internal suspend fun playbackIdentity(item: CatalogItem): CatalogItem {
        if (!initializeFromBundle) return item
        val identities = bundledPlaybackIdentities ?: json.decodeFromString<BundledPlaybackIdentities>(
            Res.readBytes("files/catalog-playback-identities.json").decodeToString(),
        ).also { bundledPlaybackIdentities = it }
        val identity = DoubanArtwork.subjectId(item.url)?.let { identities.subjects[it] }
        if (identity != null) {
            val provider = identity.pomo.split(':')
            val page = if (provider.size == 2) ContentParsers.playbackPageOrNull(
                PomoSource.address("auto_detail", "site" to provider[0], "id" to provider[1]),
            ).orEmpty() else ""
            return item.copy(
                year = identity.year,
                isSeries = identity.isSeries,
                playbackPageUrl = page.ifBlank { item.playbackPageUrl },
            )
        }
        val snapshot = bundledCategories ?: json.decodeFromString<BundledCatalogSnapshot>(
            Res.readBytes("files/catalog-categories.json").decodeToString(),
        ).also { bundledCategories = it }
        val source = snapshot.pages.values.asSequence().flatMap { it.page.items.asSequence() }
            .firstOrNull { it.url == item.url } ?: return item
        return item.copy(
            year = item.year.ifBlank { source.year },
            playbackPageUrl = source.playbackPageUrl.ifBlank { item.playbackPageUrl },
            isSeries = item.isSeries ?: source.isSeries,
        )
    }

    /** Reads the complete offline ranking and fills metadata omitted by older installations. */
    internal suspend fun top250Snapshot(): List<CatalogPage> {
        val bundle = if (initializeFromBundle) bundledTop250
            ?: json.decodeFromString<List<CatalogPage>>(Res.readBytes("files/top250-categories.json").decodeToString())
                .also { bundledTop250 = it }
        else emptyList()
        val metadata = bundle.flatMap { it.items }.associateBy { it.url }
        return (0..225 step 25).mapNotNull { start ->
            val stored = read("top250:$start")?.page
            val page = stored ?: bundle.find { it.start == start } ?: return@mapNotNull null
            page.copy(items = page.items.map { item ->
                val source = metadata[item.url] ?: return@map item
                // Fill only absent fields. New upstream metadata and rating/artwork remain
                // authoritative; cached subject identity must match before enrichment.
                item.copy(
                    genres = item.genres.ifEmpty { source.genres },
                    countries = item.countries.ifEmpty { source.countries },
                    kind = if (item.genres.isEmpty()) source.kind else item.kind,
                )
            })
        }
    }

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

    internal suspend fun read(key: String): StoredCatalog? {
        val stored = readStored(key)
        if (stored != null || !initializeFromBundle) return stored
        // Existing installations already have a database, so seed copying cannot add
        // newly shipped categories. Fill missing keys from JSON without overwriting
        // refreshed SQLite pages. Bundle timestamps preserve normal cache expiration.
        val snapshot = bundledCategories ?: json.decodeFromString<BundledCatalogSnapshot>(
            Res.readBytes("files/catalog-categories.json").decodeToString(),
        ).also { bundledCategories = it }
        return snapshot.pages[key]?.let { StoredCatalog(it.page, it.savedAt) }
    }

    /** Reads existing catalog ratings once for optional search enrichment, without networking. */
    internal suspend fun searchCatalogSnapshot(): List<CatalogItem> = access { db ->
        db.prepare("SELECT i.item, p.key FROM catalog_items i JOIN catalog_pages p ON p.key = i.page_key ORDER BY p.saved_at DESC, i.position").use { statement ->
            buildList {
                while (statement.step()) {
                    runCatching { json.decodeFromString<CatalogItem>(statement.getText(0)) }
                        .getOrNull()?.let {
                            // Legacy seed TV rows omit kind. Their owning page restores it,
                            // just as browse() does, including for optional detail requests.
                            add(if (statement.getText(1).startsWith("tv:")) it.copy(kind = "tv") else it)
                        }
                }
            }.distinctBy { it.url }
        }
    }

    private suspend fun readStored(key: String): StoredCatalog? = access { db ->
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

    /** Measures regenerable payloads without counting the preinstalled offline catalogs. */
    internal suspend fun cacheUsage(): ContentCacheUsage = access { db ->
        val responses = db.prepare("SELECT COALESCE(SUM(byte_size), 0), COUNT(*) FROM response_cache").use {
            it.step()
            ContentCacheUsage(it.getLong(0), it.getLong(1))
        }
        val pages = db.prepare(
            "SELECT COALESCE(SUM(length(CAST(metadata AS BLOB))), 0), COUNT(*) FROM catalog_pages " +
                "WHERE key NOT LIKE 'top250:%' AND key NOT LIKE 'tv:%'",
        ).use {
            it.step()
            ContentCacheUsage(it.getLong(0), it.getLong(1))
        }
        val items = db.prepare(
            "SELECT COALESCE(SUM(length(CAST(item AS BLOB))), 0) FROM catalog_items " +
                "WHERE page_key NOT LIKE 'top250:%' AND page_key NOT LIKE 'tv:%'",
        ).use {
            it.step()
            it.getLong(0)
        }
        ContentCacheUsage(responses.bytes + pages.bytes + items, responses.entries + pages.entries)
    }

    /**
     * Clears fetched search/detail/filter payloads and reclaims unused database pages.
     * Built-in catalog snapshots are retained; personal and offline-media stores are separate.
     */
    internal suspend fun clearCache() = access { db ->
        db.execute("BEGIN IMMEDIATE")
        try {
            db.execute("DELETE FROM response_cache")
            db.execute("DELETE FROM catalog_items WHERE page_key NOT LIKE 'top250:%' AND page_key NOT LIKE 'tv:%'")
            db.execute("DELETE FROM catalog_pages WHERE key NOT LIKE 'top250:%' AND key NOT LIKE 'tv:%'")
            db.execute("COMMIT")
        } catch (error: Throwable) {
            db.execute("ROLLBACK")
            throw error
        }
        // Deleting rows alone leaves free pages inside SQLite. Compact the existing database
        // under the same mutex instead of deleting its file or reseeding the user's catalogs.
        db.execute("VACUUM")
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
