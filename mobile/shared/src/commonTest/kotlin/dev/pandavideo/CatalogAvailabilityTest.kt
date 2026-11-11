package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.CatalogPage
import dev.pandavideo.data.repository.CatalogAvailability
import dev.pandavideo.data.repository.ResourceStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogAvailabilityTest {
    private val page = CatalogPage(
        listOf("有资源", "未收录", "暂时异常").mapIndexed { index, title ->
            CatalogItem(title, "https://movie.douban.com/subject/${index + 1}/", "2026")
        }, 20, 20, true, "分类",
    )

    @Test fun onlyConfirmedMissingIsHiddenWithoutChangingCursorOrOrder() = runTest {
        val cache = CatalogAvailability(null, { "rules" }, check = {
            when (it.title) {
                "有资源" -> ResourceStatus.Available
                "未收录" -> ResourceStatus.Missing
                else -> error("offline")
            }
        })
        assertEquals(page, cache.visible(page))
        val hidden = mutableListOf<String>()
        cache.inspect(page.items) { hidden += it.title }
        assertEquals(listOf("未收录"), hidden)
        assertEquals(page.copy(items = listOf(page.items[0], page.items[2])), cache.visible(page))
        assertEquals(40, cache.visible(page).nextStart())
    }

    @Test fun missingExpiresAndNewResourcesCanReappear() = runTest {
        var time = 1_000L
        var status = ResourceStatus.Missing
        var requests = 0
        val cache = CatalogAvailability(null, { "rules" }, check = { requests++; status }, now = { time })
        val item = page.items.take(1)
        cache.inspect(item) {}
        cache.inspect(item) {}
        assertEquals(1, requests)
        assertEquals(2, cache.visible(page).items.size)
        time += 15 * 60 * 1000
        status = ResourceStatus.Available
        assertEquals(page, cache.visible(page))
        cache.inspect(item) {}
        assertEquals(2, requests)
        assertEquals(page, cache.visible(page))
    }

    @Test fun ruleAndReleaseYearChangesCannotReuseMissingStatus() = runTest {
        var rules = "old"
        val cache = CatalogAvailability(null, { rules }, check = { ResourceStatus.Missing })
        cache.inspect(page.items.take(1)) {}
        assertEquals(2, cache.visible(page).items.size)
        assertEquals(3, cache.visible(page.copy(
            items = page.items.map { it.copy(year = "1994") },
        )).items.size)
        rules = "new"
        assertEquals(page, cache.visible(page))
    }

    @Test fun timeoutsAreVisibleAndRetryableAndRequestsAreBounded() = runTest {
        var active = 0
        var maxActive = 0
        var attempts = 0
        val cache = CatalogAvailability(null, { "rules" }, check = {
            attempts++
            active++
            maxActive = maxOf(maxActive, active)
            try { delay(10_000); ResourceStatus.Missing } finally { active-- }
        })
        val items = (1..9).map { CatalogItem("$it", "https://movie.douban.com/subject/$it/") }
        cache.inspect(items) { fail("A timed-out check must never hide an item") }
        assertEquals(3, maxActive)
        assertEquals(0, active)
        assertEquals(9, attempts)
        cache.inspect(items.take(1)) {}
        assertEquals(10, attempts)
    }

    @Test fun cancellingInspectionCannotHideOrCacheAnUnfinishedResult() = runTest {
        val cache = CatalogAvailability(null, { "rules" }, check = {
            delay(10_000)
            ResourceStatus.Missing
        })
        val job = launch { cache.inspect(page.items) { fail("Cancelled result") } }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertEquals(page, cache.visible(page))
    }
}
