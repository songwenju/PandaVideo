package dev.pandavideo

import dev.pandavideo.core.model.CatalogItem
import dev.pandavideo.core.model.Episode
import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.core.model.SearchItem
import dev.pandavideo.data.repository.DoubanArtwork
import dev.pandavideo.data.repository.playerRecommendationScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerRecommendationsTest {
    @Test fun relevanceUsesFiftyThirtyTwentyWeightsWithoutInflatingMissingSignals() {
        val current = item("当前历史剧").copy(genres = listOf("历史", "剧情"),
            casts = listOf("演员甲", "演员乙", "演员丙"))
        val scope = playerRecommendationScope(current, null, null)
        val related = item("相关历史剧", rating = 9.0).copy(
            genres = current.genres, casts = current.casts)
        assertEquals(0.98, scope.relevanceScore(related), 0.000001)
        assertEquals(0.18, scope.relevanceScore(item("资料缺失", rating = 9.0)), 0.000001)
        val oneGenre = related.copy(genres = listOf("历史"))
        assertEquals(0.73, scope.relevanceScore(oneGenre), 0.000001)
    }

    @Test fun genreAndCastRelevanceOutrankUnrelatedHigherRatedTitles() {
        val current = item("当前历史剧").copy(genres = listOf("历史"),
            casts = listOf("演员甲", "演员乙", "演员丙"))
        val scope = playerRecommendationScope(current, null, null)
        val genreRelated = item("历史剧", rating = 8.2).copy(genres = current.genres)
        val castRelated = item("演员相关剧", rating = 9.0).copy(casts = current.casts)
        val unrelated = item("其他高分剧", rating = 10.0).copy(genres = listOf("爱情"))
        assertEquals(listOf(genreRelated, castRelated, unrelated),
            scope.select(listOf(unrelated, castRelated, genreRelated)))
    }

    @Test fun earlyCastOverlapOutweighsLaterCastOverlapWithoutSubstringMatching() {
        val current = item("当前剧").copy(genres = listOf("历史"),
            casts = listOf("演员甲", "演员乙", "演员丙", "演员丁"))
        val scope = playerRecommendationScope(current, null, null)
        val prominent = item("主要演员相关", rating = 8.0).copy(
            genres = current.genres, casts = listOf("演员甲"))
        val later = item("后排演员相关", rating = 10.0).copy(genres = current.genres,
            casts = listOf("演员一", "演员二", "演员三", "演员甲"))
        val substring = item("不同演员", rating = 10.0).copy(genres = current.genres,
            casts = listOf("演员甲乙"))
        assertEquals(listOf(prominent, later, substring), scope.select(listOf(substring, later, prominent)))
    }

    @Test fun cachedCandidateMetadataEnrichesRankingWithoutBorrowingAnotherRelease() {
        val current = item("当前剧").copy(genres = listOf("历史"),
            casts = listOf("演员甲"))
        val sparse = item("相关剧", rating = 9.0)
        val complete = sparse.copy(genres = current.genres, casts = current.casts)
        val unrelated = item("无关高分剧", rating = 10.0)
        val scope = playerRecommendationScope(current, null, null)
        val result = scope.select(listOf(sparse, unrelated, complete))
        assertEquals(complete, result.first())
        assertEquals(2, result.size)
        val wrongRelease = complete.copy(year = "2024")
        assertEquals(listOf(unrelated, sparse), scope.select(listOf(sparse, unrelated, wrongRelease)))
    }

    @Test fun weightedRankingStillRejectsRelevantRowsOutsideOriginRegionAndScoreFloor() {
        val current = item("当前剧").copy(genres = listOf("历史"), casts = listOf("演员甲"))
        val permitted = item("范围内", rating = 8.0)
        val outside = item("范围外", rating = 10.0).copy(genres = current.genres, casts = current.casts)
        val foreign = outside.copy(title = "外国剧", url = "https://fixture/foreign",
            countries = listOf("美国"))
        val lowScore = outside.copy(title = "低分剧", url = "https://fixture/low", rating = 7.9)
        val scope = playerRecommendationScope(current, null, null)
        assertEquals(listOf(permitted), scope.select(listOf(outside, foreign, lowScore, permitted),
            listOf(permitted, foreign, lowScore)))
    }

    @Test fun sourceMetadataFallbackKeepsFullActorNamesAndChangesScopeWhenDetailsArrive() {
        val current = item("当前剧").copy(countries = listOf("中国大陆"))
        val search = SearchItem(current.title, "provider", genre = "国产剧/历史",
            cast = "Tom Hanks,演员甲")
        val fallback = playerRecommendationScope(current, search, null)
        val candidate = item("相关剧").copy(genres = listOf("历史"),
            casts = listOf("Tom Hanks", "演员甲"))
        assertEquals(0.98, fallback.relevanceScore(candidate), 0.000001)
        val updated = playerRecommendationScope(current.copy(genres = listOf("爱情"),
            casts = listOf("演员乙")), search, null)
        assertTrue(updated != fallback)
        assertEquals(0.18, updated.relevanceScore(candidate), 0.000001)
    }

    @Test fun expandedRailKeepsEighteenDistinctScopedHighRatedTitles() {
        val current = item("当前剧")
        val scope = playerRecommendationScope(current, null, null)
        val permitted = (1..22).map { item("作品$it", rating = 8.0 + it / 100.0) }
        val outside = item("其他筛选范围", rating = 10.0)
        val selected = scope.select(
            permitted + permitted + current + outside + item("低分", rating = 7.9),
            permitted + current,
        )
        assertEquals(18, selected.size)
        assertEquals(permitted.reversed().take(18), selected)
        assertEquals(selected.size, selected.map { it.url }.distinct().size)
    }

    private fun item(
        title: String, country: String = "中国大陆", kind: String = "tv", rating: Double? = 9.0,
    ) = CatalogItem(title, "https://movie.douban.com/subject/$title/", "2007",
        rating = rating, kind = kind, countries = listOf(country))

    @Test fun chineseDramaOnlyRecommendsHighRatedChineseDramas() {
        val current = item("大明王朝1566")
        val scope = playerRecommendationScope(current, null, null)
        val lower = item("八分电视剧", rating = 8.0)
        val higher = item("高分电视剧", rating = 9.5)
        val programs = listOf(current, lower, higher, item("低分", rating = 7.9),
            item("未评分", rating = null), item("美国剧", country = "美国"),
            item("中国电影", kind = "movie"), item("香港剧", country = "香港"),
            item("综艺", kind = "variety"), item("纪录节目").copy(genres = listOf("纪录片")))
        assertEquals("cn", scope.country)
        assertEquals(listOf(higher, lower), scope.select(programs))
    }

    @Test fun otherRegionsAndMoviesFollowTheSameScoreAndTypeRules() {
        for (country in listOf("日本", "美国", "英国", "韩国", "中国大陆")) {
            for (kind in listOf("movie", "tv")) {
                val current = item("当前影片", country, kind)
                val expected = item("同地区同类型", country, kind)
                val scope = playerRecommendationScope(current, null, null)
                assertEquals(listOf(expected), scope.select(listOf(
                    expected, item("其他地区", if (country == "日本") "美国" else "日本", kind),
                    item("其他类型", country, if (kind == "tv") "movie" else "tv"),
                    item("不足八分", country, kind, 7.0))))
            }
        }
    }

    @Test fun legacyMetadataUsesProviderRegionWithoutTreatingTwoMovieRoutesAsSeries() {
        val movie = item("当前电影", kind = "movie").copy(countries = emptyList())
        val info = PlaybackInfo(movie.title, "https://fixture.test/movie.m3u8", "hls",
            episodes = listOf(Episode("1-0", "HD中字", 1), Episode("2-0", "HD国语", 2)),
            pageUrl = "https://fixture.test/detail", region = "内地")
        val scope = playerRecommendationScope(movie, null, info)
        assertEquals("cn", scope.country)
        assertEquals("movie", scope.kind)
        val expected = item("中国电影", kind = "movie")
        assertEquals(listOf(expected), scope.select(listOf(expected, item("中国电视剧"))))
        assertEquals("movie", playerRecommendationScope(null, null, info).kind)
    }

    @Test fun originatingFilterMembershipSurvivesRegionalMetadataEnrichment() {
        val current = item("当前战争剧")
        val original = item("筛选范围内").copy(countries = emptyList())
        val enriched = original.copy(countries = listOf("中国大陆"))
        val outside = item("筛选范围外", rating = 9.9)
        val scope = playerRecommendationScope(current, null, null)
        assertEquals(listOf(enriched), scope.select(listOf(outside, enriched), listOf(original)))
        assertTrue(scope.select(listOf(enriched), emptyList()).isEmpty())
        assertTrue(scope.select(listOf(original)).isEmpty())
    }

    @Test fun verifiedCountriesOverrideSearchAndUnknownRegionsNeverUseGlobalSuggestions() {
        val current = item("中国剧", country = "中国")
        val search = SearchItem(current.title, "provider", region = "美国", genre = "电视剧")
        assertEquals("cn", playerRecommendationScope(current, search, null).country)
        val unknown = playerRecommendationScope(current.copy(countries = emptyList()), null, null)
        assertTrue(unknown.select(listOf(item("中国高分"), item("美国高分", country = "美国"))).isEmpty())
        assertEquals("hk", playerRecommendationScope(current.copy(countries = listOf("香港")), null, null).country)
    }

    @Test fun movieAndSeriesVersionsOfAnimationStaySeparateWhenMetadataIsKnown() {
        val series = item("当前动画", kind = "animation").copy(isSeries = true)
        val expected = item("动画剧集", kind = "animation").copy(isSeries = true)
        val movie = item("动画电影", kind = "animation").copy(isSeries = false)
        assertEquals(listOf(expected), playerRecommendationScope(series, null, null).select(listOf(movie, expected)))
    }

    @Test fun detailMetadataRetainsProductionCountriesForRecommendations() {
        val details = DoubanArtwork.detail(
            """{"title":"大明王朝1566","is_tv":true,"countries":["中国大陆","中国大陆"," "]}""",
            "1", "tv",
        )
        assertEquals(listOf("中国大陆"), details?.countries)
        assertEquals("tv", details?.kind)
    }
}
