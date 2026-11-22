package dev.pandavideo

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class BilibiliCacheTest {
    @Test fun restartKeepsVideoMetadataButNeverPersistsPlaybackTokens() = runTest {
        val file = File.createTempFile("pandavideo-bilibili-cache-", ".db")
        var requests = 0
        val first = ContentRepository(HttpClient(mockEngine { request ->
            requests++
            respond(when (request.url.encodedPath) {
                "/x/web-interface/view" -> biliView
                "/x/player/playurl" -> biliMedia()
                else -> error("Unexpected source")
            })
        }), CatalogDatabase(file.absolutePath))
        try {
            val expected = try {
                val row = first.search("BV1A6ZMB3EtZ", "bilibili")
                first.player(PlayerRequest(page = row.results.single().detailUrl))
                row
            } finally { first.close() }
            assertEquals(3, requests)
            val restarted = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("", HttpStatusCode.ServiceUnavailable)
            }), CatalogDatabase(file.absolutePath))
            try {
                assertEquals(expected, restarted.search("BV1A6ZMB3EtZ", "bilibili"))
                assertEquals(3, requests, "Fresh metadata remains available after restart without network")
                assertFailsWith<ApiError> {
                    restarted.player(PlayerRequest(page = expected.results.single().detailUrl))
                }
                assertEquals(4, requests, "Playback must request fresh source data even with cached metadata")
            } finally { restarted.close() }
            BundledSQLiteDriver().open(file.absolutePath).use { db ->
                db.prepare("SELECT payload FROM response_cache").use {
                    assertTrue(it.step())
                    assertFalse("bilivideo.com" in it.getText(0), "Expiring media URLs must never enter metadata cache")
                    assertFalse("buvid" in it.getText(0), "Anonymous visitor cookies must remain in memory")
                }
            }
        } finally { file.delete() }
    }
}
