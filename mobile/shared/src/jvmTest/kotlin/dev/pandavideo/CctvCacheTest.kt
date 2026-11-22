package dev.pandavideo

import dev.pandavideo.data.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

class CctvCacheTest {
    @Test fun columnMonthAndPageCachesRemainIsolatedAfterRestart() = runTest {
        val file = File.createTempFile("pandavideo-cctv-period-", ".db")
        val columns = CctvSource.columns.keys.take(2)
        val scopes = listOf(Triple(columns[0], "202610", 0), Triple(columns[0], "202609", 0),
            Triple(columns[0], "202610", 20), Triple(columns[1], "202610", 0))
        var calls = 0
        val first = ContentRepository(HttpClient(mockEngine { request ->
            calls++
            val index = scopes.indexOf(Triple(request.url.parameters["id"], request.url.parameters["d"],
                (request.url.parameters["p"]!!.toInt() - 1) * 20))
            assertTrue(index >= 0)
            val id = (index + 1).toString().padStart(32, '0')
            respond("""{"data":{"total":40,"list":[{"guid":"$id","title":"节目$index","mode":0,"time":"2026-10-30 13:02:00"}]}}""")
        }), CatalogDatabase(file.absolutePath))
        try {
            val expected = try {
                scopes.map { (column, period, start) -> first.cctvPrograms(column, start, period) }
            } finally { first.close() }
            assertEquals(4, calls)
            val restarted = ContentRepository(HttpClient(mockEngine { error("Fresh filtered cache must not request network") }),
                CatalogDatabase(file.absolutePath))
            try {
                for ((index, scope) in scopes.withIndex()) {
                    val (column, period, start) = scope
                    assertEquals(expected[index], restarted.cctvPrograms(column, start, period))
                }
                assertEquals(4, expected.map { it.items.single().url }.toSet().size)
                assertEquals("2026-10-30", expected.first().items.single().broadcastDate)
            } finally { restarted.close() }
        } finally { file.delete() }
    }
}
