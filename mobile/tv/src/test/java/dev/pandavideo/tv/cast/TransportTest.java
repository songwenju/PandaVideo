package dev.pandavideo.tv.cast;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransportTest {
    static final class FakePlayer implements Transport.Player {
        String uri = "", metadata = "", state = "NO_MEDIA_PRESENT", title = "";
        long position;
        boolean failed;
        public void setMedia(String u, String m, String t) { uri = u; metadata = m; title = t; state = "STOPPED"; position = 0; failed = false; }
        public void play() { state = "PLAYING"; }
        public void pause() { state = "PAUSED_PLAYBACK"; }
        public void stop() { state = "STOPPED"; }
        public void seek(long p) { position = p; }
        public Transport.Snapshot snapshot() { return new Transport.Snapshot(uri, metadata, state, position, 120000, failed); }
    }
    static String body(String action, String fields) {
        return Transport.envelope("<u:" + action + " xmlns:u=\"" + Transport.TYPE + "\"><InstanceID>0</InstanceID>"
            + fields + "</u:" + action + ">");
    }
    static Transport.Result call(FakePlayer player, String action, String fields) {
        return Transport.execute("\"" + Transport.TYPE + "#" + action + "\"", body(action, fields), player);
    }
    @Test public void uriQueryAndNestedTitleSurviveSoapEscaping() {
        FakePlayer p = new FakePlayer();
        String metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"urn:test\">"
            + "<item><dc:title>电影 &amp; 电视</dc:title></item></DIDL-Lite>";
        assertEquals(200, call(p, "SetAVTransportURI", "<CurrentURI>https://video.example/a.m3u8?a=1&amp;b=2</CurrentURI>"
            + "<CurrentURIMetaData>" + Transport.escape(metadata) + "</CurrentURIMetaData>").status());
        assertEquals("https://video.example/a.m3u8?a=1&b=2", p.uri);
        assertEquals("电影 & 电视", p.title);
        assertEquals(metadata, p.metadata);
        assertTrue(call(p, "GetMediaInfo", "").body().contains("a=1&amp;b=2"));
    }
    @Test public void playbackControlAndStatusUseRealSnapshot() {
        FakePlayer p = new FakePlayer();
        call(p, "SetAVTransportURI", "<CurrentURI>http://video.example/video.mp4</CurrentURI><CurrentURIMetaData/>");
        assertEquals(200, call(p, "Play", "<Speed>1</Speed>").status());
        assertEquals(200, call(p, "Seek", "<Unit>REL_TIME</Unit><Target>0:01:12.500</Target>").status());
        assertEquals(72500, p.position);
        call(p, "Pause", "");
        assertTrue(call(p, "GetTransportInfo", "").body().contains("PAUSED_PLAYBACK"));
        assertTrue(call(p, "GetPositionInfo", "").body().contains("<RelTime>0:01:12</RelTime>"));
        assertTrue(call(p, "GetPositionInfo", "").body().contains("<TrackDuration>0:02:00</TrackDuration>"));
        call(p, "Stop", "");
        assertEquals("STOPPED", p.state);
    }
    @Test public void rejectsInvalidInputsBeforeChangingPlayer() {
        FakePlayer p = new FakePlayer();
        assertTrue(call(p, "Play", "<Speed>1</Speed>").body().contains("<errorCode>701"));
        for (String uri : new String[]{"file:///private", "javascript:bad", "http://user:secret@host/video"}) {
            assertTrue(call(p, "SetAVTransportURI", "<CurrentURI>" + uri + "</CurrentURI><CurrentURIMetaData/>")
                .body().contains("<errorCode>714"));
        }
        assertEquals("", p.uri);
        assertEquals(500, Transport.execute(Transport.TYPE + "#Stop", "<!DOCTYPE x><x/>", p).status());
        assertEquals(500, Transport.execute(Transport.TYPE + "#Stop", "x".repeat(Transport.MAX_BODY + 1), p).status());
        assertEquals(500, Transport.execute(Transport.TYPE + "#Play", body("Stop", ""), p).status());
        assertEquals(500, Transport.execute(Transport.TYPE + "#Stop",
            body("Stop", "<InstanceID>1</InstanceID>"), p).status());
    }
    @Test public void rejectsInvalidSeekAndReportsPlaybackFailure() {
        FakePlayer p = new FakePlayer();
        p.uri = "http://video.example/a.mp4";
        assertTrue(call(p, "Seek", "<Unit>FRAME</Unit><Target>12</Target>").body().contains("<errorCode>710"));
        assertTrue(call(p, "Seek", "<Unit>REL_TIME</Unit><Target>0:99:00</Target>").body().contains("<errorCode>711"));
        p.failed = true;
        assertTrue(call(p, "GetTransportInfo", "").body().contains("ERROR_OCCURRED"));
        assertTrue(call(p, "Play", "<Speed>1</Speed>").body().contains("<errorCode>714"));
    }
    @Test public void descriptorAndServiceDocumentAreValidXml() throws Exception {
        assertEquals("root", Transport.xml(Transport.descriptor("uuid:123", "熊猫 & TV")).getLocalName());
        assertEquals("scpd", Transport.xml(ServiceDescription.xml()).getLocalName());
        assertTrue(ServiceDescription.xml().contains("<name>GetPositionInfo</name>"));
    }
}
