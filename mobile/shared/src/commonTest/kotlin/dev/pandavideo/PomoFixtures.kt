package dev.pandavideo

import dev.pandavideo.data.repository.PomoSource
import kotlinx.serialization.json.*

internal val pomoPage = PomoSource.address("auto_detail", "site" to "西瓜", "id" to "1")
internal fun pomoRow(name: String, id: Int = 1, year: String = "2020",
    actor: String = "张三 李四", remark: String = "HD", genre: String = "剧情") = buildJsonObject {
    put("name", name); put("id", id); put("year", year); put("actor", actor)
    put("remarks", remark); put("type_name", genre); put("area", "中国")
    put("play_url", "正片\$https://media/video.m3u8")
}
internal fun pomoSearch(vararg rows: JsonObject): String = buildJsonObject {
    put("code", 200); put("data", buildJsonObject { put("西瓜", JsonArray(rows.toList())) })
}.toString()
internal val pomoSearchFixture = pomoSearch(pomoRow("测试电影"), pomoRow("测试剧", 2, remark = "全2集", genre = "国产剧"))
internal val pomoDetailFixture = """{"code":200,"data":{"name":"测试电影","year":"2020","content":"一位旅人和同伴踏上旅程。",
    "routes":[{"name":"线路1","episodes":[{"name":"第 1 集","url":"https://media/1.m3u8"},{"name":"第 2 集","url":"https://media/2.m3u8"}]},
    {"name":"线路2","episodes":[{"name":"备用 1","url":"https://media/embed"}]}]}}"""
internal fun pomoMedia(signature: String = "x") =
    """{"code":200,"direct":"https://media/stream.m3u8?signature=$signature","data":"https://pomo.mom/content/plugins/plyr_player/api.php?type=proxy&url=stream"}"""
