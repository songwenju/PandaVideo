package dev.pandavideo

import dev.pandavideo.core.player.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DlnaTest {
    private fun descriptor(control: String = "/upnp/control", extra: String = "", type: String = "MediaRenderer") =
        """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0">$extra<device>
            <deviceType>urn:schemas-upnp-org:device:$type:1</deviceType>
            <friendlyName>客厅 &amp; 电视 &#x1F4FA;</friendlyName><UDN>uuid:tv</UDN>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/wrong</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$control</controlURL></service></serviceList>
            </device></root>"""
    private val location = "http://192.168.1.10:1234/device/description.xml"
    private val target = DlnaDevice("uuid:tv", "电视", "http://192.168.1.10:1234/upnp/control",
        "urn:schemas-upnp-org:service:AVTransport:1")

    @Test fun discoversOnlySuccessfulSsdpRepliesAndPreservesPort() {
        assertEquals(location, ssdpLocation("HTTP/1.1 200 OK\r\nLoCaTiOn: $location\r\n\r\n"))
        assertNull(ssdpLocation("NOTIFY * HTTP/1.1\r\nLOCATION: $location"))
        assertNull(ssdpLocation("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd"))
        assertNull(ssdpLocation("HTTP/1.1 200 OK\r\nLOCATION: http://user:password@192.168.1.1/root.xml"))
        assertTrue(ssdpRequest().decodeToString().endsWith("\r\n\r\n"))
    }
    @Test fun descriptorSelectsAvTransportAndDecodesNames() {
        val device = assertNotNull(parseDlnaDevice(descriptor(), location))
        assertEquals("客厅 & 电视 📺", device.name)
        assertEquals(target.controlUrl, device.controlUrl)
        assertEquals(target.serviceType, device.serviceType)
    }
    @Test fun descriptorResolvesRelativeEndpointsAndUrlBase() {
        assertEquals("http://192.168.1.10:1234/device/transport",
            parseDlnaDevice(descriptor("transport"), location)?.controlUrl)
        assertEquals("http://192.168.1.10:4567/upnp/transport",
            parseDlnaDevice(descriptor("transport", "<URLBase>http://192.168.1.10:4567/upnp/</URLBase>"), location)?.controlUrl)
        assertEquals("http://192.168.1.10:4567/control",
            parseDlnaDevice(descriptor("http://192.168.1.10:4567/control"), location)?.controlUrl)
    }
    @Test fun ignoresServersInvalidXmlAndEntityDeclarations() {
        assertNull(parseDlnaDevice(descriptor(type = "MediaServer"), location))
        assertNull(parseDlnaDevice("<root><device></root>", location))
        assertNull(parseDlnaDevice("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///secret'>]>" + descriptor(), location))
        assertNull(parseDlnaDevice(descriptor("javascript:invalid"), location))
        assertNull(parseDlnaDevice("x".repeat(512_001), location))
    }
    @Test fun handlesNamespacedDescriptorsAndCdata() {
        val xml = """<d:root xmlns:d="urn:schemas-upnp-org:device-1-0"><d:device>
            <d:deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</d:deviceType>
            <d:friendlyName><![CDATA[卧室 & TV]]></d:friendlyName>
            <d:serviceList><d:service><d:serviceType>urn:schemas-upnp-org:service:AVTransport:2</d:serviceType>
            <d:controlURL>/control</d:controlURL></d:service></d:serviceList></d:device></d:root>"""
        assertEquals("卧室 & TV", parseDlnaDevice(xml, location)?.name)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", parseDlnaDevice(xml, location)?.serviceType)
    }
    @Test fun validatesDirectedDiscoveryAndTimeValues() {
        assertTrue(dlnaIpv4("192.168.1.10"))
        listOf("256.1.1.1", "192.168.1", "https://192.168.1.1", "239.255.255.250", "0.0.0.0")
            .forEach { assertFalse(dlnaIpv4(it), it) }
        assertEquals("1:02:03", dlnaTime(3_723_000))
        assertEquals(3_723_000, parseDlnaTime("1:02:03.500"))
        assertEquals(0, parseDlnaTime("NOT_IMPLEMENTED"))
        assertEquals(0, parseDlnaTime("0:99:00"))
        assertEquals("0:00:00", dlnaTime(-1))
    }
    @Test fun soapEscapesUriAndNestedMetadataWithoutLosingQueryParameters() = runTest {
        var body = ""
        var action = ""
        val engine = mockEngine { request ->
            body = (request.body as TextContent).text
            action = request.headers["SOAPAction"].orEmpty()
            // Ktor moves Content-Type from request headers into OutgoingContent before the engine.
            val type = assertNotNull(request.body.contentType)
            assertTrue(type.match(ContentType.Text.Xml))
            assertEquals("utf-8", type.parameter("charset").orEmpty().trim('"').lowercase())
            respond("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>")
        }
        val base = HttpClient(engine)
        val client = DlnaClient(base)
        try {
            client.setMedia(target, MediaSource(1, "https://media.example/video.m3u8?a=1&b=2", "hls", "电影 <A&B>"))
            assertEquals("\"${target.serviceType}#SetAVTransportURI\"", action)
            assertContains(body, "<CurrentURI>https://media.example/video.m3u8?a=1&amp;b=2</CurrentURI>")
            assertContains(body, "&lt;dc:title&gt;电影 &amp;lt;A&amp;amp;B&amp;gt;&lt;/dc:title&gt;")
            assertContains(body, "application/vnd.apple.mpegurl")
            assertContains(body, "<InstanceID>0</InstanceID>")
        } finally { client.close(); base.close() }
    }
    @Test fun treatsSoapFaultAsFailureEvenWithHttp200AndDoesNotExposeRemoteText() = runTest {
        val engine = mockEngine {
            respond("""<s:Envelope xmlns:s="urn:test"><s:Body><s:Fault><detail><UPnPError>
                <errorCode>714</errorCode><errorDescription>https://secret.example/token</errorDescription>
                </UPnPError></detail></s:Fault></s:Body></s:Envelope>""", HttpStatusCode.OK)
        }
        val base = HttpClient(engine)
        val client = DlnaClient(base)
        try {
            val failure = assertFailsWith<DlnaException> { client.play(target) }
            assertEquals("电视无法播放此地址，请更换播放线路", failure.message)
        } finally { client.close(); base.close() }
    }
    @Test fun parsesPositionAndIssuesRelativeTimeSeek() = runTest {
        val engine = mockEngine { request ->
            if (request.headers["SOAPAction"].orEmpty().contains("#Seek")) {
                val body = (request.body as TextContent).text
                assertContains(body, "<Unit>REL_TIME</Unit><Target>0:02:03</Target>")
                respond("<root/>")
            } else respond("<root><RelTime>0:02:03</RelTime><TrackDuration>1:30:00</TrackDuration></root>")
        }
        val base = HttpClient(engine)
        val client = DlnaClient(base)
        try {
            assertEquals(DlnaPosition(123_000, 5_400_000), client.position(target))
            client.seek(target, 123_000)
        } finally { client.close(); base.close() }
    }
    @Test fun networkCancellationIsPreserved() = runTest {
        val engine = mockEngine { throw CancellationException("Cancelled") }
        val base = HttpClient(engine)
        val client = DlnaClient(base)
        try { assertFailsWith<CancellationException> { client.device(location) } }
        finally { client.close(); base.close() }
    }
}
