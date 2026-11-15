package dev.pandavideo

import dev.pandavideo.core.model.*
import dev.pandavideo.core.platform.currentEpochMillis
import dev.pandavideo.data.repository.CatalogDatabase
import dev.pandavideo.data.repository.ContentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.*

class ActorCacheTest {
    @Test fun verifiedSearchPosterSurvivesSparseDetailAndFreshEmptyArtworkCache() = runTest {
        val file = File.createTempFile("pandavideo-actor-poster-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val work = ActorWork("iqiyi:100", "老兵", "tv", "2014")
            val poster = "https://vod.xiguazyimg.com/poster.jpg"
            database.writeResponse("actors:iqiyi:catalog:v4:${work.id}:${work.title}:${work.year}:${work.kind}",
                Json.encodeToString(work.copy(playbackPageUrl = pomoPage)), currentEpochMillis())
            var searches = 0
            val repo = ContentRepository(HttpClient(mockEngine { request ->
                when {
                    request.url.host == "movie.douban.com" -> respond("[]")
                    request.url.parameters["type"] == "auto_search" -> {
                        searches++
                        val row = JsonObject(pomoRow(work.title, year = work.year,
                            genre = "国产剧").toMutableMap().apply { put("cover", JsonPrimitive(poster)) })
                        respond(pomoSearch(row))
                    }
                    request.url.parameters["type"] == "auto_detail" ->
                        respond("""{"code":200,"data":{"name":"老兵","routes":[{"name":"线路1",
                            "episodes":[{"name":"第1集","url":"https://media/episode.m3u8"}]}]}}""")
                    else -> respond("", HttpStatusCode.ServiceUnavailable)
                }
            }), database)
            try {
                val mapped = repo.actorCatalogWork(work)
                assertEquals(pomoPage, mapped.playbackPageUrl)
                assertEquals(poster, mapped.cover)
                val previous = searches
                assertEquals(mapped, repo.actorCatalogWork(work))
                assertEquals(previous, searches, "Repaired artwork must retain its normal cache TTL")
                assertEquals(poster, repo.actorCatalogWork(work.copy(playbackPageUrl = pomoPage)).cover)
            } finally { repo.close() }
        } finally { file.delete() }
    }

    @Test fun confirmedReleaseNeverBorrowsSameNameOtherYearPoster() = runTest {
        val work = ActorWork("iqiyi:100", "老兵", "tv", "2014", playbackPageUrl = pomoPage)
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when (request.url.parameters["type"]) {
                "auto_search" -> respond(pomoSearch(JsonObject(pomoRow(work.title, year = "2024",
                    genre = "国产剧").toMutableMap().apply {
                    put("cover", JsonPrimitive("https://images.example/wrong.jpg"))
                })))
                "auto_detail" -> respond("""{"code":200,"data":{"name":"老兵","routes":[{"name":"线路1",
                    "episodes":[{"name":"第1集","url":"https://media/episode.m3u8"}]}]}}""")
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        }))
        try { assertEquals(work, repo.actorCatalogWork(work)) }
        finally { repo.close() }
    }

    @Test fun truncatedArtworkCacheRefreshesToFullCastWithoutWaitingForExpiry() = runTest {
        val file = File.createTempFile("pandavideo-full-cast-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val names = (1..11).map { "演员$it" } + "倪大红" + "演员13"
            val preview = CatalogItem("三国", "https://movie.douban.com/subject/3237723/", "2010",
                casts = names.take(3), kind = "tv")
            database.writeResponse("artwork:douban:v1:3237723",
                Json.encodeToString(preview.copy(casts = names.take(10))), currentEpochMillis())
            var requests = 0
            val rows = names.joinToString(",") { """{"name":"$it"}""" }
            val repo = ContentRepository(HttpClient(mockEngine {
                requests++
                respond("""{"title":"三国","year":"2010","is_tv":true,"actors":[$rows]}""")
            }), database)
            try {
                assertEquals(names, repo.artworkDetail(preview, "", "")?.casts)
                assertNotNull(database.readResponse("artwork:douban:v2:3237723"))
                assertEquals(names, repo.artworkDetail(preview, "", "")?.casts)
                assertEquals(1, requests, "Fresh legacy truncations refresh once into the complete cache")
            } finally { repo.close() }
        } finally { file.delete() }
    }

    @Test fun verifiedV1CatalogIdentityStillHasBoundedOfflineFallbackAfterV3Migration() = runTest {
        val file = File.createTempFile("pandavideo-actor-legacy-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val work = ActorWork("iqiyi:1", "悬崖之上", "movie", "2021")
            val mapped = work.copy(subjectId = "32493124", verifiedCatalog = true)
            database.writeResponse("actors:iqiyi:catalog:v1:${work.id}:${work.title}:${work.year}:${work.kind}",
                Json.encodeToString(mapped), currentEpochMillis())
            val repo = ContentRepository(HttpClient(mockEngine { respond("", HttpStatusCode.ServiceUnavailable) }), database)
            try {
                assertEquals(mapped, repo.actorCatalogWork(work))
                assertNull(database.readResponse("actors:iqiyi:catalog:v4:${work.id}:${work.title}:${work.year}:${work.kind}"))
            } finally { repo.close() }
        } finally { file.delete() }
    }
    @Test fun verifiedPlaybackPageIsRecheckedWithoutSearchingOrTreatingOfflineAsMissing() = runTest {
        var searches = 0
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            if (request.url.parameters["type"] == "auto_detail")
                respond("""{"code":200,"data":{"name":"都挺好","routes":[{"name":"线路1",
                    "episodes":[{"name":"第1集","url":"https://media/episode.m3u8"}]}]}}""")
            else { searches++; respond("", HttpStatusCode.ServiceUnavailable) }
        }))
        try {
            val work = ActorWork("playback:1", "都挺好", "tv", "2019", playbackPageUrl = pomoPage,
                cover = "https://images.example/confirmed.jpg")
            assertEquals(work, repo.actorCatalogWork(work))
            val missing = mutableListOf<CatalogItem>()
            repo.inspectCatalog(listOf(work.catalog())) { missing += it }
            assertTrue(missing.isEmpty())
            assertEquals(0, searches)
        } finally { repo.close() }
        val offline = ContentRepository(HttpClient(mockEngine { respond("", HttpStatusCode.ServiceUnavailable) }))
        try {
            val missing = mutableListOf<CatalogItem>()
            offline.inspectCatalog(listOf(CatalogItem("都挺好", pomoPage, "2019", kind = "tv"))) { missing += it }
            assertTrue(missing.isEmpty())
        } finally { offline.close() }
    }

    @Test fun playbackFallbackRejectsSameNameMovieForSeriesAndDoesNotCacheUncertainty() = runTest {
        val work = ActorWork("iqiyi:1", "都挺好", "tv", "2019")
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when {
                request.url.host == "movie.douban.com" -> respond("[]")
                request.url.parameters["type"] == "auto_search" ->
                    respond(pomoSearch(pomoRow("都挺好", year = "2019", genre = "国产剧", remark = "已完结")))
                request.url.parameters["type"] == "auto_detail" ->
                    respond("""{"code":200,"data":{"name":"都挺好","year":"2019",
                        "routes":[{"name":"线路1","episodes":[{"name":"正片","url":"https://media/film.m3u8"}]}]}}""")
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        }))
        try {
            val result = runCatching { repo.actorCatalogWork(work) }.getOrNull()
            assertTrue(result == null || result.playbackPageUrl.isBlank())
        } finally { repo.close() }
        assertNull(dev.pandavideo.data.repository.ContentParsers.playbackPageOrNull("https://www.iqiyi.com/v_abc.html"))
        assertNull(dev.pandavideo.data.repository.ContentParsers.playbackPageOrNull("https://media/video.m3u8"))
    }
    @Test fun actorWorkFindsPlaybackWhenDoubanSuggestReturnsNoSubjects() = runTest {
        val work = ActorWork("iqiyi:100", "悬崖之上", "movie", "2021",
            sourceUrl = "https://www.iqiyi.com/v_25a0rbs0ar4.html")
        val repo = ContentRepository(HttpClient(mockEngine { request ->
            when {
                request.url.host == "movie.douban.com" -> respond("[]")
                request.url.parameters["type"] == "auto_search" ->
                    respond(pomoSearch(pomoRow(work.title, year = work.year)))
                request.url.parameters["type"] == "auto_detail" ->
                    respond("""{"code":200,"data":{"name":"悬崖之上","year":"2021",
                        "routes":[{"name":"线路1","episodes":[{"name":"正片","url":"https://media/film.m3u8"}]}]}}""")
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        }))
        try {
            val mapped = repo.actorCatalogWork(work)
            assertEquals(pomoPage, mapped.playbackPageUrl)
            assertEquals(listOf(mapped), actorPlaybackWorks(listOf(mapped), emptySet()))
            assertFalse(mapped.verifiedCatalog, "A playback match must not pretend to be a Douban subject")
            assertEquals(mapped.playbackPageUrl, mapped.catalog().url)
        } finally { repo.close() }
    }

    @Test fun staleUnmappedActorCacheDoesNotHideNewlyVerifiedPlayback() = runTest {
        val file = File.createTempFile("pandavideo-actor-playback-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val work = ActorWork("iqiyi:100", "悬崖之上", "movie", "2021",
                sourceUrl = "https://www.iqiyi.com/v_25a0rbs0ar4.html")
            database.writeResponse("actors:iqiyi:catalog:v2:${work.id}:${work.title}:${work.year}:${work.kind}",
                Json.encodeToString(work), currentEpochMillis())
            val repo = ContentRepository(HttpClient(mockEngine { request ->
                when {
                    request.url.host == "movie.douban.com" -> respond("[]")
                    request.url.parameters["type"] == "auto_search" ->
                        respond(pomoSearch(pomoRow(work.title, year = work.year)))
                    request.url.parameters["type"] == "auto_detail" ->
                        respond("""{"code":200,"data":{"name":"悬崖之上","year":"2021",
                            "routes":[{"name":"线路1","episodes":[{"name":"正片","url":"https://media/film.m3u8"}]}]}}""")
                    else -> respond("", HttpStatusCode.ServiceUnavailable)
                }
            }), database)
            try {
                assertEquals(pomoPage, repo.actorCatalogWork(work).playbackPageUrl)
            } finally { repo.close() }
        } finally { file.delete() }
    }

    @Test fun freshLegacyOmissionsRefreshIntoNewActorCaches() = runTest {
        val file = File.createTempFile("pandavideo-actor-migration-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val actor = ActorRef("iqiyi:200505405", "赵本山")
            val work = ActorWork("iqiyi:91598901", "马大帅1", "tv", "2003",
                sourceUrl = "https://www.iqiyi.com/v_19rrjckqds.html")
            database.writeResponse("actors:iqiyi:works:v1:${actor.id}",
                Json.encodeToString(ActorFilmography(emptyList())), currentEpochMillis())
            database.writeResponse("actors:iqiyi:catalog:v1:${work.id}:${work.title}:${work.year}:${work.kind}",
                Json.encodeToString(work), currentEpochMillis())
            var requests = 0
            val repo = ContentRepository(HttpClient(mockEngine { request ->
                requests++
                if (request.url.host == "mesh.if.iqiyi.com") respond("""{"code":0,"items":[{"video":[
                    {"block_id":"film_works","data":[{"entity_id":91598901,"album_id":91598901,
                        "title":"马大帅1","channel_id":2,"content_type":"FEATURE_FILM",
                        "contributor":[{"id":200505405}],"desc":"导演","is_episode":false,
                        "date":{"year":2003},"page_url":"https://www.iqiyi.com/v_19rrjckqds.html"}]}
                ]}]}""")
                else respond("""[{"type":"movie","id":"2997297","title":"马大帅","year":"2004","episode":"27",
                    "img":"https://img3.doubanio.com/poster.jpg"}]""")
            }), database)
            try {
                assertEquals(listOf(work), repo.actorWorks(actor).works)
                val mapped = repo.actorCatalogWork(work)
                assertEquals("2997297", mapped.subjectId)
                assertEquals("马大帅", mapped.title)
                assertEquals("2004", mapped.year)
                assertNotNull(database.readResponse("actors:iqiyi:works:v2:${actor.id}"))
                assertNotNull(database.readResponse("actors:iqiyi:catalog:v4:${work.id}:${work.title}:${work.year}:${work.kind}"))
                assertEquals(listOf(work), repo.actorWorks(actor).works)
                assertEquals(mapped, repo.actorCatalogWork(work))
                assertEquals(2, requests, "Only legacy snapshots must refresh; new snapshots retain their TTL")
            } finally { repo.close() }
        } finally { file.delete() }
    }

    @Test fun completeProfileUsesIndependentCacheAndSurvivesOfflineReload() = runTest {
        val file = File.createTempFile("pandavideo-actor-profile-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val actor = ActorRef("iqiyi:123", "演员", profileFields = listOf(
                ActorProfileField("occupation", "职业", "演员,导演"),
                ActorProfileField("birthday", "生日", "19571002"),
                ActorProfileField("brief", "简介", "来源简介"),
            ))
            database.writeResponse("actors:iqiyi:profile:v1:iqiyi:123", Json.encodeToString(actor),
                currentEpochMillis() - 8L * 24 * 60 * 60 * 1000)
            // A fresh legacy search cache must not keep the profile stuck on birthday-only metadata.
            database.writeResponse("actors:iqiyi:suggest:v1:演员",
                Json.encodeToString(ActorSuggestions(listOf(actor.copy(profileFields = emptyList())))),
                currentEpochMillis())
            val repo = ContentRepository(HttpClient(mockEngine { respond("", HttpStatusCode.ServiceUnavailable) }), database)
            try {
                assertEquals(actor, repo.actorProfile(ActorRef("iqiyi:123", "演员")))
                assertFails { repo.actorProfile(ActorRef("iqiyi:124", "演员")) }
                repo.clearCache()
                assertFails { repo.actorProfile(actor) }
            } finally { repo.close() }
        } finally { file.delete() }
    }

    @Test fun expiredActorSnapshotSurvivesNetworkFailureAndCacheClearEvictsIt() = runTest {
        val file = File.createTempFile("pandavideo-actor-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val actor = ActorRef("iqiyi:123", "演员")
            val snapshot = ActorFilmography(listOf(ActorWork("iqiyi:100", "影片", "movie", "2024", "456")))
            database.writeResponse("actors:iqiyi:works:v1:iqiyi:123", Json.encodeToString(snapshot),
                currentEpochMillis() - 8L * 24 * 60 * 60 * 1000)
            val repo = ContentRepository(HttpClient(mockEngine { respond("", HttpStatusCode.ServiceUnavailable) }), database)
            try {
                val cached = repo.actorWorks(actor)
                assertTrue(cached.stale)
                assertEquals(snapshot.works, cached.works)
                assertFalse(repo.actorWorks(actor).works.isEmpty())
                assertFails { repo.actorWorks(ActorRef("iqiyi:124", "演员")) }
                repo.clearCache()
                assertFails { repo.actorWorks(actor) }
            } finally { repo.close() }
        } finally { file.delete() }
    }

    @Test fun challengeResponseCannotReplacePersistedPersonCandidates() = runTest {
        val file = File.createTempFile("pandavideo-actor-search-", ".db")
        try {
            val database = CatalogDatabase(file.absolutePath)
            val snapshot = ActorSuggestions(listOf(ActorRef("iqiyi:123", "演员")))
            database.writeResponse("actors:iqiyi:suggest:v1:演员", Json.encodeToString(snapshot),
                currentEpochMillis() - 8L * 24 * 60 * 60 * 1000)
            val repo = ContentRepository(HttpClient(mockEngine { respond("<html>captcha</html>") }), database)
            try {
                assertEquals(snapshot.copy(stale = true), repo.searchActors("演员"))
                assertEquals(Json.encodeToString(snapshot), database.readResponse("actors:iqiyi:suggest:v1:演员")!!.payload)
            } finally { repo.close() }
        } finally { file.delete() }
    }
}
