package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.core.model.appendCatalog
import dev.pandavideo.data.repository.isWebUrl
import kotlin.test.*

class ModelsTest {
    @Test fun appendCatalogDeduplicatesByUrlAndKeepsFirstOrder() {
        val first = listOf(
            CatalogItem("一", "https://x/1"),
            CatalogItem("二", "https://x/2"),
        )
        val incoming = listOf(
            CatalogItem("二-重复", "https://x/2"),
            CatalogItem("三", "https://x/3"),
        )
        val merged = appendCatalog(first, incoming)
        assertEquals(listOf("https://x/1", "https://x/2", "https://x/3"), merged.map { it.url })
        assertEquals("二", merged.single { it.url == "https://x/2" }.title)
    }

    @Test fun catalogPageNextStartAdvancesByPageSize() {
        val page = CatalogPage(emptyList(), start = 40, pageSize = 20, hasMore = true)
        assertEquals(60, page.nextStart())
    }

    @Test fun webUrlGuardAcceptsHttpAndRejectsUnsafeValues() {
        assertTrue(isWebUrl("https://movie.douban.com/subject/1/"))
        assertTrue(isWebUrl("http://example.com/video"))
        assertFalse(isWebUrl("ftp://example.com/video"))
        assertFalse(isWebUrl("javascript:alert(1)"))
        assertFalse(isWebUrl("https://user:pass@example.com/secret"))
        assertFalse(isWebUrl(""))
        assertFalse(isWebUrl("只是一段文字"))
    }
}
