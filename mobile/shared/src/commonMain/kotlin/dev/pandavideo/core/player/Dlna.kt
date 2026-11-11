package dev.pandavideo.core.player

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom

internal const val DLNA_SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

/** A renderer and its advertised AVTransport endpoint, without media credentials. */
internal data class DlnaDevice(val id: String, val name: String, val controlUrl: String, val serviceType: String)
internal data class DlnaPosition(val position: Long, val duration: Long)
internal class DlnaException(message: String) : Exception(message)

internal fun ssdpRequest(host: String = "239.255.255.250"): ByteArray =
    ("M-SEARCH * HTTP/1.1\r\nHOST: $host:1900\r\nMAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\nST: $DLNA_SEARCH_TARGET\r\n\r\n").encodeToByteArray()

internal fun ssdpLocation(response: String): String? {
    if (!response.lineSequence().firstOrNull().orEmpty().startsWith("HTTP/1.1 200", ignoreCase = true)) return null
    return response.lineSequence().firstOrNull {
        it.substringBefore(':').trim().equals("location", ignoreCase = true)
    }?.substringAfter(':')?.trim()?.takeIf(::dlnaHttpUrl)
}

internal fun dlnaHttpUrl(value: String): Boolean = try {
    val url = Url(value)
    url.protocol.name in setOf("http", "https") && url.host.isNotBlank() &&
        url.user.orEmpty().isEmpty() && url.password.orEmpty().isEmpty()
} catch (_: Exception) { false }

internal fun dlnaIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    } && parts.first().toIntOrNull() in 1..223
}

internal fun xmlEscape(value: String): String = value.replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

private fun xmlUnescape(value: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
    .replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                when {
                    code == null || code !in 1..0x10ffff || code in 0xd800..0xdfff -> ""
                    code < 0x10000 -> code.toChar().toString()
                    else -> {
                        val pair = code - 0x10000
                        "${(0xd800 + (pair shr 10)).toChar()}${(0xdc00 + (pair and 1023)).toChar()}"
                    }
                }
            }
        }
    }

/** Bounded XML reader for UPnP descriptors and SOAP, supporting namespaces and CDATA. */
private class DlnaXml(val tag: String) {
    val children = mutableListOf<DlnaXml>()
    private val text = StringBuilder()
    val value: String get() = text.toString().trim()
    fun child(tag: String): DlnaXml? = children.firstOrNull { it.tag == tag.lowercase() }
    fun field(tag: String): String = child(tag)?.value.orEmpty()
    fun descendants(): Sequence<DlnaXml> = sequence {
        yield(this@DlnaXml)
        children.forEach { yieldAll(it.descendants()) }
    }
    companion object {
        fun parse(xml: String): DlnaXml {
            require(xml.length <= 512_000 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
            val root = DlnaXml("")
            val stack = mutableListOf(root)
            val tokens = Regex("<!\\[CDATA\\[[\\s\\S]*?]]>|<!--[\\s\\S]*?-->|<[^>]+>|[^<]+")
            for (match in tokens.findAll(xml)) {
                val token = match.value
                when {
                    token.startsWith("<![CDATA[") -> stack.last().text.append(token.removePrefix("<![CDATA[").removeSuffix("]]>"))
                    token.startsWith("<?") || token.startsWith("<!") -> Unit
                    token.startsWith("</") -> {
                        require(stack.size > 1)
                        val tag = token.drop(2).dropLast(1).trim().substringAfter(':').lowercase()
                        require(stack.last().tag == tag)
                        stack.removeAt(stack.lastIndex)
                    }
                    token.startsWith("<") -> {
                        val tag = token.drop(1).trimStart().takeWhile { !it.isWhitespace() && it != '/' && it != '>' }
                            .substringAfter(':').lowercase()
                        val node = DlnaXml(tag)
                        stack.last().children.add(node)
                        if (!token.endsWith("/>")) stack.add(node)
                        require(stack.size <= 48)
                    }
                    else -> stack.last().text.append(xmlUnescape(token))
                }
            }
            require(stack.size == 1)
            return root
        }
    }
}

internal fun parseDlnaDevice(xml: String, location: String): DlnaDevice? {
    if (!dlnaHttpUrl(location)) return null
    return try {
        val root = DlnaXml.parse(xml)
        val base = root.descendants().firstOrNull { it.tag == "urlbase" }?.value
            ?.takeIf(::dlnaHttpUrl) ?: location
        root.descendants().filter { it.tag == "device" }.firstNotNullOfOrNull { device ->
            if (!device.field("deviceType").startsWith("urn:schemas-upnp-org:device:MediaRenderer:")) return@firstNotNullOfOrNull null
            val service = device.child("serviceList")?.children?.firstOrNull {
                it.field("serviceType").matches(Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+"))
            } ?: return@firstNotNullOfOrNull null
            val path = service.field("controlURL").takeIf(String::isNotBlank) ?: return@firstNotNullOfOrNull null
            val control = URLBuilder(base).apply { takeFrom(path) }.buildString()
            if (!dlnaHttpUrl(control)) return@firstNotNullOfOrNull null
            DlnaDevice(device.field("UDN").ifBlank { control }, device.field("friendlyName").ifBlank { "智能电视" },
                control, service.field("serviceType"))
        }
    } catch (_: Exception) { null }
}

internal fun dlnaTime(position: Long): String {
    val seconds = position.coerceAtLeast(0) / 1000
    return "${seconds / 3600}:${((seconds / 60) % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun parseDlnaTime(value: String): Long {
    val parts = value.trim().substringBefore('.').split(':')
    if (parts.size != 3) return 0
    val hours = parts[0].toLongOrNull() ?: return 0
    val minutes = parts[1].toLongOrNull() ?: return 0
    val seconds = parts[2].toLongOrNull() ?: return 0
    if (hours !in 0..100_000 || minutes !in 0..59 || seconds !in 0..59) return 0
    return (hours * 3600 + minutes * 60 + seconds) * 1000
}

/** Executes bounded UPnP requests. The television fetches the URL; no video is proxied or cached. */
internal class DlnaClient(client: HttpClient) {
    private val http = client.config {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 5_000; connectTimeoutMillis = 3_000; socketTimeoutMillis = 5_000 }
    }

    suspend fun device(location: String): DlnaDevice? {
        if (!dlnaHttpUrl(location)) return null
        val response = http.get(location)
        if (!response.status.isSuccess()) return null
        return parseDlnaDevice(response.bodyAsText(), location)
    }

    suspend fun command(device: DlnaDevice, action: String, values: List<Pair<String, String>> = emptyList()): String {
        val arguments = (listOf("InstanceID" to "0") + values).joinToString("") {
            "<${it.first}>${xmlEscape(it.second)}</${it.first}>"
        }
        val response = http.post(device.controlUrl) {
            header("Content-Type", "text/xml; charset=\"utf-8\"")
            header("SOAPAction", "\"${device.serviceType}#$action\"")
            setBody("""<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${device.serviceType}">$arguments</u:$action></s:Body></s:Envelope>""")
        }
        val body = response.bodyAsText()
        val parsed = try { DlnaXml.parse(body) } catch (_: Exception) { null }
        val fault = parsed?.descendants()?.firstOrNull { it.tag == "fault" }
        if (!response.status.isSuccess() || fault != null) {
            val code = parsed?.descendants()?.firstOrNull { it.tag == "errorcode" }?.value
            throw DlnaException(when (code) {
                "701", "710", "711" -> "电视暂不支持此操作"
                "714", "716" -> "电视无法播放此地址，请更换播放线路"
                else -> "电视未接受操作，请确认电视已开启 DLNA 投屏"
            })
        }
        return body
    }

    suspend fun setMedia(device: DlnaDevice, source: MediaSource) {
        if (!dlnaHttpUrl(source.url)) throw DlnaException("当前视频地址不支持投屏")
        val mime = when {
            source.kind == "hls" -> "application/vnd.apple.mpegurl"
            Url(source.url).encodedPath.endsWith(".webm", true) -> "video/webm"
            Url(source.url).encodedPath.endsWith(".mov", true) -> "video/quicktime"
            else -> "video/mp4"
        }
        val label = listOf(source.title, source.episodeLabel).filter(String::isNotBlank).joinToString(" ")
        val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${xmlEscape(label)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:*">${xmlEscape(source.url)}</res></item></DIDL-Lite>"""
        command(device, "SetAVTransportURI", listOf("CurrentURI" to source.url, "CurrentURIMetaData" to metadata))
    }

    suspend fun play(device: DlnaDevice) { command(device, "Play", listOf("Speed" to "1")) }
    suspend fun pause(device: DlnaDevice) { command(device, "Pause") }
    suspend fun stop(device: DlnaDevice) { command(device, "Stop") }
    suspend fun seek(device: DlnaDevice, position: Long) {
        command(device, "Seek", listOf("Unit" to "REL_TIME", "Target" to dlnaTime(position)))
    }
    suspend fun position(device: DlnaDevice): DlnaPosition {
        val xml = DlnaXml.parse(command(device, "GetPositionInfo"))
        fun field(tag: String) = xml.descendants().firstOrNull { it.tag == tag }?.value.orEmpty()
        return DlnaPosition(parseDlnaTime(field("reltime")), parseDlnaTime(field("trackduration")))
    }
    suspend fun playing(device: DlnaDevice): Boolean =
        DlnaXml.parse(command(device, "GetTransportInfo")).descendants()
            .firstOrNull { it.tag == "currenttransportstate" }?.value == "PLAYING"

    fun close() { http.close() }
}
