package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.PlayerRequest
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogPlaybackMatchTest {
    @Test fun yearlessCatalogPlaysTheOnlyYearSuffixedReleaseLikeSearch() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(when (request.url.parameters["type"]) {
                "auto_search" -> pomoSearch(pomoRow("美丽人生1997", year = "1997"))
                "auto_detail" -> pomoDetailFixture.replace("测试电影", "美丽人生").replace("2020", "1997")
                else -> pomoMedia()
            })
        }))
        try {
            val search = repo.search("美丽人生", "all").results.single()
            val searchPlayback = repo.player(PlayerRequest(page = search.detailUrl))
            val match = repo.resolve(CatalogItem("美丽人生", "", kind = "movie"))
            assertTrue(match.playable)
            assertEquals(searchPlayback.pageUrl, match.pageUrl)
            assertEquals(searchPlayback.selected, repo.player(PlayerRequest(page = match.pageUrl)).selected)
        } finally { repo.close() }
    }

    @Test fun animationAndDocumentaryCatalogsCanPlaySeriesLikeSearch() = runTest {
        for (kind in listOf("animation", "documentary")) {
            val repo = ContentRepository(HttpClient(mockEngine { request ->
                respond(when (request.url.parameters["type"]) {
                    "auto_search" -> pomoSearch(pomoRow("测试剧", remark = "全2集", genre = "国产剧"))
                    "auto_detail" -> pomoDetailFixture.replace("测试电影", "测试剧")
                    else -> pomoMedia()
                })
            }))
            try {
                val search = repo.search("测试剧", "all").results.single()
                assertEquals(search.detailUrl, repo.resolve(CatalogItem("测试剧", "", kind = kind)).pageUrl, kind)
            } finally { repo.close() }
        }
    }

    @Test fun yearlessCatalogDoesNotPreferBareTitleOverAnotherRemake() = runTest {
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            respond(if (request.url.parameters["type"] == "auto_search")
                pomoSearch(pomoRow("美丽人生", year = "1997"), pomoRow("美丽人生2023", 2, "2023"))
            else pomoDetailFixture.replace("2020",
                if (request.url.parameters["id"] == "2") "2023" else "1997"))
        }))
        try { assertFalse(repo.resolve(CatalogItem("美丽人生", "", kind = "movie")).playable) }
        finally { repo.close() }
    }
}
