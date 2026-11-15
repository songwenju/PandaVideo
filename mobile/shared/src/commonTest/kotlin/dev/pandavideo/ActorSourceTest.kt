package dev.pandavideo

import dev.pandavideo.app.ActorBrowser
import dev.pandavideo.core.model.*
import dev.pandavideo.data.repository.ActorSource
import dev.pandavideo.data.repository.ApiError
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ActorSourceTest {
    @Test fun playingCreditSupplementsPartialFilmographyWithoutMergingRemakesOrSubstringNames() {
        val actor = ActorRef("iqiyi:214761605", "倪大红")
        val info = PlaybackInfo("都挺好", "https://media/episode.m3u8", "hls",
            listOf(Episode("ep1", "第1集", 1), Episode("ep2", "第2集", 1)),
            pageUrl = pomoPage)
        val search = SearchItem("都挺好", "Pomo", year = "2019", cast = "姚晨,倪大红,郭京飞")
        val snapshot = ActorFilmography(listOf(ActorWork("iqiyi:old", "都挺好", "movie", "2000")))
        val merged = snapshot.withPlayingCredit(actor, info, null, search, pomoPage)
        assertEquals(2, merged.works.size)
        val credit = merged.works.first()
        assertEquals("tv", credit.kind)
        assertEquals("2019", credit.year)
        assertEquals(pomoPage, credit.catalog().url)
        assertEquals(merged, merged.withPlayingCredit(actor, info, null, search, pomoPage))
        assertEquals(snapshot, snapshot.withPlayingCredit(actor, info, null,
            search.copy(cast = "倪大红客串,姚晨"), pomoPage))
        assertEquals(snapshot, snapshot.withPlayingCredit(actor, info, null, search, null))
        val known = ActorFilmography(listOf(ActorWork("iqiyi:existing", "都挺好", "tv", "2019")))
            .withPlayingCredit(actor, info, null, search, pomoPage)
        assertEquals("iqiyi:existing", known.works.single().id)
        assertEquals(pomoPage, known.works.single().playbackPageUrl)
        assertEquals("tv", ActorFilmography(emptyList()).withPlayingCredit(actor,
            info.copy(episodes = info.episodes.take(1)), null, search, pomoPage).works.single().kind)
    }

    @Test fun playbackVerifiedCreditsDoNotRequireDoubanMetadata() {
        val work = ActorWork("iqiyi:1", "悬崖之上", "movie", "2021", playbackPageUrl = pomoPage)
        assertEquals(listOf(work), actorPlaybackWorks(listOf(work), emptySet()))
        assertTrue(actorPlaybackWorks(listOf(work), setOf(pomoPage)).isEmpty())
    }
    @Test fun profilePropertiesPreserveSourceLabelsOrderRawValuesAndAdditionalFields() {
        val actor = ActorSource.parseSuggestions("""{"data":{"templates":[
            {"template":105,"albumInfo":{"qipuId":200505405,"title":"赵本山",
                "region":{"key":"区域","value":"中国大陆"},
                "occupation":{"key":"职业","value":"演员,导演,编剧"},
                "birthday":{"key":"生日","value":"19571002"},
                "height":{"key":"身高","value":""},
                "brief":{"key":"简介","value":"出生于辽宁省铁岭市开原市。"},
                "constellation":{"key":"星座","value":"天秤座"},
                "properties":["occupation","birthday","region","brief","occupation"],
                "invalid":{"key":"无效","value":[]}
            }}
        ]}}""").single()
        assertEquals(listOf("occupation", "birthday", "region", "brief", "height", "constellation"),
            actor.profileFields.map { it.id })
        assertEquals(listOf("职业", "生日", "区域", "简介", "身高", "星座"),
            actor.profileFields.map { it.label })
        assertEquals("19571002", actor.profileFields[1].value)
        assertEquals("1957年10月2日", actor.profileFields[1].displayValue())
        assertEquals("出生于辽宁省铁岭市开原市。", actor.profileFields[3].value)
        assertEquals("", actor.alternateName, "Birthdays must no longer occupy the alias slot")
        assertFalse(actor.visibleProfileFields().any { it.id == "height" })
        assertEquals(actor, Json.decodeFromString<ActorRef>(Json.encodeToString(actor)),
            "Navigation and cache serialization must preserve the complete profile")
    }

    @Test fun profileRefreshRequiresExactSourceIdentityDespiteSameNames() = runTest {
        val source = ActorSource { """{"data":{"templates":[
            {"template":105,"albumInfo":{"qipuId":1,"title":"张三","brief":{"key":"简介","value":"甲"}}},
            {"template":105,"albumInfo":{"qipuId":2,"title":"张三","brief":{"key":"简介","value":"乙"}}}
        ]}}""" }
        assertEquals("乙", source.profile(ActorRef("iqiyi:2", "张三")).profileFields.single().value)
        assertFailsWith<ApiError> { source.profile(ActorRef("iqiyi:3", "张三")) }
        assertFailsWith<IllegalArgumentException> { source.profile(ActorRef("other:2", "张三")) }
    }

    @Test fun legacyProfilesAndMalformedBirthdaysDoNotInventPersonFacts() {
        val legacy = Json.decodeFromString<ActorRef>(
            """{"id":"iqiyi:123","name":"演员","alternateName":"19571002"}""")
        assertEquals("生日", legacy.visibleProfileFields().single().label)
        assertEquals("1957年10月2日", legacy.visibleProfileFields().single().displayValue())
        assertEquals("别名", legacy.copy(alternateName = "演员1980").visibleProfileFields().single().label)
        assertEquals("20230230", ActorProfileField("birthday", "生日", "20230230").displayValue())
        assertEquals("2000年2月29日", ActorProfileField("birthday", "生日", "20000229").displayValue())
        assertEquals("19000229", ActorProfileField("birthday", "生日", "19000229").displayValue())
        assertEquals("19571302", ActorProfileField("birthday", "生日", "19571302").displayValue())
        assertEquals("未知", ActorProfileField("birthday", "生日", "未知").displayValue())
        assertTrue(legacy.copy(alternateName = "").visibleProfileFields().isEmpty())
        val empty = legacy.copy(profileFields = listOf(ActorProfileField("height", "身高", "")))
        assertTrue(empty.visibleProfileFields().isEmpty(), "Current empty source fields override legacy guesses")
    }

    @Test fun suggestionsKeepSameNameIdentitiesAndRejectFilmsAndInvalidIds() {
        val result = ActorSource.parseSuggestions("""{"data":{"templates":[
            {"template":105,"albumInfo":{"qipuId":1,"title":"张三","img":"https://image/1.jpg"}},
            {"template":105,"albumInfo":{"qipuId":2,"title":"张三"}},
            {"template":105,"albumInfo":{"qipuId":1,"title":"重复"}},
            {"template":101,"albumInfo":{"qipuId":3,"title":"张三"}},
            {"template":105,"albumInfo":{"qipuId":"bad","title":"张三"}}
        ]}}""")
        assertEquals(listOf("iqiyi:1", "iqiyi:2"), result.map { it.id })
        assertEquals("https://image/1.jpg", result.first().avatar)
        assertEquals("", result.last().avatar)
    }

    private fun credit(
        person: String = "123", role: String = "饰演 主角", channel: Int = 1,
        episode: Boolean = false, type: String = "FEATURE_FILM", year: Int = 2024, id: String = "100",
    ) = Json.parseToJsonElement("""{
        "entity_id":"$id","title":"作品","channel_id":$channel,"content_type":"$type",
        "contributor":[{"id":"$person","name":"演员"}],"creator":[{"id":"456"}],
        "desc":"$role","is_episode":$episode,"date":{"year":$year},
        "page_url":"https://www.iqiyi.com/v_abc.html","image_cover":"https://image/poster.jpg"
    }""").jsonObject

    @Test fun workRequiresActualCastAndExcludesCrewOnlyAndEpisode() {
        assertNotNull(ActorSource.parseWork(credit(), "123"))
        assertNull(ActorSource.parseWork(credit(person = "456"), "123"))
        listOf("导演", "编剧", "制片人", "出品人").forEach { role ->
            assertNotNull(ActorSource.parseWork(credit(role = role), "123"))
            assertNull(ActorSource.parseWork(credit(role = role, person = "456"), "123"))
        }
        assertNull(ActorSource.parseWork(credit(episode = true), "123"))
        assertNull(ActorSource.parseWork(credit(type = "TRAILER"), "123"))
        assertNull(ActorSource.parseWork(credit(channel = 6), "123"))
        val creatorOnly = JsonObject(credit().toMutableMap().apply {
            put("contributor", JsonArray(emptyList()))
            put("creator", Json.parseToJsonElement("""[{"id":"123"}]"""))
        })
        assertNull(ActorSource.parseWork(creatorOnly, "123"))
    }

    @Test fun voiceCreditKeepsSourceIdentityAndMediaKind() {
        val work = ActorSource.parseWork(credit(role = "配音 主角", channel = 2), "123")!!
        assertTrue(work.voice)
        assertEquals("tv", work.kind)
        assertEquals("iqiyi:100", work.id)
        assertEquals("https://www.iqiyi.com/v_abc.html", work.catalog().url)
        assertFalse(work.verifiedCatalog)
    }

    @Test fun malformedResponsesFailRatherThanReplacingCachedData() = runTest {
        assertFailsWith<ApiError> { ActorSource.parseSuggestions("""{"code":500}""") }
        assertFailsWith<ApiError> {
            ActorSource { """{"code":500}""" }.filmography(ActorRef("iqiyi:123", "演员"))
        }
    }

    @Test fun alternativeArtworkRejectsIqiyiAndPreservesOtherProviders() {
        assertEquals("", ActorSource.externalPoster("https://pic0.iqiyipic.com/image/poster.webp"))
        assertEquals("", ActorSource.externalPoster("https://www.iqiyi.com/poster.jpg"))
        assertEquals("", ActorSource.externalPoster(""))
        val douban = "https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"
        assertEquals(douban, ActorSource.externalPoster(douban))
        val playback = "https://images.example.test/poster.jpg"
        assertEquals(playback, ActorSource.externalPoster(playback))
    }

    @Test fun catalogTranslationRequiresUniqueTitleYearAndKind() = runTest {
        val work = ActorWork("iqiyi:100", "作品", "movie", "2024", sourceUrl = "https://www.iqiyi.com/v_abc.html")
        val wrong = ActorSource { """[{"type":"movie","id":"456","title":"作品","year":"1998","episode":""}]""" }
        assertEquals(work, wrong.catalogWork(work))
        val correct = ActorSource { """[{"type":"movie","id":"123","title":"作品","year":"2024","episode":"","img":"https://image/right.jpg"}]""" }
        val mapped = correct.catalogWork(work)
        assertEquals("123", mapped.subjectId)
        assertEquals("https://image/right.jpg", mapped.cover)
        assertTrue(mapped.verifiedCatalog)
        val ambiguous = ActorSource { """[
            {"type":"movie","id":"123","title":"作品","year":"2024","episode":""},
            {"type":"movie","id":"456","title":"作品","year":"2024","episode":""}
        ]""" }
        assertEquals(work, ambiguous.catalogWork(work))
        assertEquals(work.copy(kind = "tv"), correct.catalogWork(work.copy(kind = "tv")))
    }

    @Test fun catalogMatchingAcceptsFirstSeasonTypographyAndOneYearOffset() = runTest {
        val work = ActorWork("iqiyi:91598901", "马大帅1", "tv", "2003",
            sourceUrl = "https://www.iqiyi.com/v_19rrjckqds.html")
        val source = ActorSource {
            """[{"type":"movie","id":"2997297","title":"马大帅","year":"2004","episode":"27"}]"""
        }
        for (title in listOf("马大帅1", "马大帅 第一季", "马大帅第1部", "马 大 帅")) {
            val mapped = source.catalogWork(work.copy(title = title))
            assertEquals("2997297", mapped.subjectId)
            assertEquals("马大帅", mapped.title)
            assertEquals("2004", mapped.year)
            assertTrue(mapped.verifiedCatalog)
            assertEquals(work.sourceUrl, mapped.sourceUrl)
        }
        val typography = ActorSource {
            """[{"type":"movie","id":"100","title":"蜘蛛侠：英雄归来","year":"2017","episode":""}]"""
        }
        assertTrue(typography.catalogWork(ActorWork("iqiyi:1", "蜘蛛侠: 英雄归来", "movie", "2016")).verifiedCatalog)
    }

    @Test fun relaxedCatalogMatchingRejectsSequelsRemakesAndAmbiguousYearOffsets() = runTest {
        val work = ActorWork("iqiyi:1", "马大帅1", "tv", "2003")
        val sequel = ActorSource {
            """[{"type":"movie","id":"4043850","title":"马大帅2","year":"2004","episode":"30"}]"""
        }
        assertEquals(work, sequel.catalogWork(work))
        val first = ActorSource {
            """[{"type":"movie","id":"2997297","title":"马大帅","year":"2004","episode":"27"}]"""
        }
        for (title in listOf("马大帅11", "马大帅21", "马大帅2", "马大帅第三季")) {
            assertFalse(first.catalogWork(work.copy(title = title)).verifiedCatalog)
        }
        assertFalse(first.catalogWork(work.copy(year = "2000")).verifiedCatalog)
        assertFalse(first.catalogWork(work.copy(kind = "movie")).verifiedCatalog)
        val ambiguous = ActorSource {
            """[{"type":"movie","id":"100","title":"马大帅","year":"2002","episode":"27"},
                {"type":"movie","id":"200","title":"马大帅","year":"2004","episode":"27"}]"""
        }
        assertEquals(work, ambiguous.catalogWork(work))
        val missingYear = ActorSource {
            """[{"type":"movie","id":"100","title":"马大帅","year":"","episode":"27"}]"""
        }
        assertEquals(work, missingYear.catalogWork(work))
    }

    @Test fun exactCatalogMatchWinsBeforeRelaxedYearCandidates() = runTest {
        val source = ActorSource {
            """[{"type":"movie","id":"100","title":"作品","year":"2023","episode":""},
                {"type":"movie","id":"200","title":"作品","year":"2024","episode":""}]"""
        }
        assertEquals("200", source.catalogWork(ActorWork("iqiyi:1", "作品", "movie", "2024")).subjectId)
    }

    @Test fun filmographySortsVerifiedWorksAndReportsSourceLimit() = runTest {
        val source = ActorSource { address ->
            assertTrue(address.startsWith("https://mesh.if.iqiyi.com/"))
            assertTrue("peopleId=123" in address)
            """{"code":0,"items":[{"video":[
                {"block_id":"film_works","data":[${credit()},${credit(year = 2026, id = "200", channel = 2)},${credit(person = "456", role = "导演", id = "300")}]},
                {"block_id":"recommendations","data":[${credit(id = "999")}]}
            ]}]}"""
        }
        val result = source.filmography(ActorRef("iqiyi:123", "演员"))
        assertEquals(listOf("iqiyi:200", "iqiyi:100"), result.works.map { it.id })
        assertEquals(listOf("tv", "movie"), result.works.map { it.kind })
        assertTrue(result.limited)
    }

    @Test fun lateCancelledSearchCannotOverwriteCurrentPersonResults() = runTest {
        val browser = ActorBrowser(backgroundScope) { query ->
            if (query == "old") withContext(NonCancellable) { delay(200) } else delay(10)
            ActorSuggestions(listOf(ActorRef(if (query == "old") "iqiyi:1" else "iqiyi:2", query)))
        }
        browser.search("old")
        runCurrent()
        browser.search("new")
        advanceTimeBy(250)
        runCurrent()
        assertEquals("iqiyi:2", browser.state.value.result!!.actors.single().id)
        browser.clear()
        assertNull(browser.state.value.result)
    }

    @Test fun legacyCatalogNamesRemainCompatible() {
        val item = Json.decodeFromString<CatalogItem>(
            """{"title":"旧电影","url":"https://movie.douban.com/subject/123/","casts":["成龙"]}""")
        assertEquals(listOf("成龙"), item.casts)
    }

    @Test fun playbackWorksHideUnmappedCreditsAndConfirmedMissingCatalogUrls() {
        val credit = ActorWork("iqiyi:100", "作品", "movie", "2024",
            sourceUrl = "https://www.iqiyi.com/v_abc.html")
        val mapped = credit.copy(subjectId = "123", verifiedCatalog = true)
        val available = mapped.copy(id = "iqiyi:200", subjectId = "456")
        assertEquals(listOf(mapped, available),
            actorPlaybackWorks(listOf(credit, mapped, available), emptySet()),
            "Unmapped person-page credits must not expose a search-only row")
        assertEquals(listOf(available),
            actorPlaybackWorks(listOf(credit, mapped, available), setOf(mapped.catalog().url)),
            "Missing resources must match the translated catalog URL used by playback")
        assertEquals(listOf(mapped),
            actorPlaybackWorks(listOf(mapped), setOf(credit.sourceUrl)),
            "A person-page URL is not a playback availability identity")
        assertTrue(actorPlaybackWorks(listOf(mapped.copy(subjectId = "")), emptySet()).isEmpty())
    }
}
