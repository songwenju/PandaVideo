package dev.pandavideo.tv.cast;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded, namespace-aware AVTransport contract shared by HTTP and host tests. */
final class Transport {
    static final String TYPE = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final int MAX_BODY = 64 * 1024;

    interface Player {
        void setMedia(String uri, String metadata, String title);
        void play();
        void pause();
        void stop();
        void seek(long milliseconds);
        Snapshot snapshot();
    }

    record Snapshot(String uri, String metadata, String state, long position, long duration, boolean failed) {}
    record Result(int status, String body) {}
    static final class Fault extends Exception {
        final int code;
        Fault(int code) { this.code = code; }
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static Element xml(String value) throws Exception {
        if (value.length() > MAX_BODY || value.toUpperCase(java.util.Locale.ROOT).contains("<!DOCTYPE")
                || value.toUpperCase(java.util.Locale.ROOT).contains("<!ENTITY")) throw new Fault(402);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External entity"); });
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new InputSource(new StringReader(value))).getDocumentElement();
    }

    static String field(Element parent, String name) throws Fault {
        String result = null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && name.equals(child.getLocalName())) {
                if (result != null) throw new Fault(402);
                result = child.getTextContent();
            }
        }
        if (result == null) throw new Fault(402);
        return result;
    }

    static long milliseconds(String value) throws Fault {
        if (!value.matches("[0-9]{1,6}:[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,3})?")) throw new Fault(711);
        String[] parts = value.split(":");
        return Long.parseLong(parts[0]) * 3600000 + Long.parseLong(parts[1]) * 60000
            + Math.round(Double.parseDouble(parts[2]) * 1000);
    }

    static String time(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    static String validUri(String value) throws Fault {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null) throw new Fault(714);
            return value;
        } catch (java.net.URISyntaxException e) { throw new Fault(714); }
    }

    static String title(String metadata) {
        try {
            var nodes = xml(metadata).getElementsByTagNameNS("*", "title");
            if (nodes.getLength() > 0) return nodes.item(0).getTextContent();
        } catch (Exception ignored) { /* Optional metadata never prevents URI playback. */ }
        return "正在投屏";
    }

    static Result execute(String soapAction, String body, Player player) {
        try {
            String action = soapAction.replace("\"", "").trim();
            if (!action.startsWith(TYPE + "#")) throw new Fault(401);
            action = action.substring(TYPE.length() + 1);
            Element root = xml(body);
            if (!"Envelope".equals(root.getLocalName())
                || !"http://schemas.xmlsoap.org/soap/envelope/".equals(root.getNamespaceURI())) throw new Fault(402);
            var bodies = root.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body");
            if (bodies.getLength() != 1) throw new Fault(402);
            Element command = null;
            for (Node node = bodies.item(0).getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element) {
                    if (command != null) throw new Fault(402);
                    command = element;
                }
            }
            if (command == null || !action.equals(command.getLocalName())
                || !TYPE.equals(command.getNamespaceURI())) throw new Fault(401);
            if (!"0".equals(field(command, "InstanceID"))) throw new Fault(718);
            Map<String, String> out = new LinkedHashMap<>();
            switch (action) {
                case "SetAVTransportURI" -> {
                    String uri = field(command, "CurrentURI");
                    String metadata = field(command, "CurrentURIMetaData");
                    player.setMedia(validUri(uri), metadata, title(metadata));
                }
                case "Play" -> {
                    if (!"1".equals(field(command, "Speed"))) throw new Fault(717);
                    requireMedia(player);
                    player.play();
                }
                case "Pause" -> { requireMedia(player); player.pause(); }
                case "Stop" -> player.stop();
                case "Seek" -> {
                    requireMedia(player);
                    String unit = field(command, "Unit");
                    if (!unit.equals("REL_TIME") && !unit.equals("ABS_TIME")) throw new Fault(710);
                    player.seek(milliseconds(field(command, "Target")));
                }
                case "GetPositionInfo" -> {
                    Snapshot s = player.snapshot();
                    out.put("Track", s.uri().isEmpty() ? "0" : "1");
                    out.put("TrackDuration", time(s.duration()));
                    out.put("TrackMetaData", s.metadata());
                    out.put("TrackURI", s.uri());
                    out.put("RelTime", time(s.position())); out.put("AbsTime", time(s.position()));
                    out.put("RelCount", "2147483647"); out.put("AbsCount", "2147483647");
                }
                case "GetTransportInfo" -> {
                    Snapshot s = player.snapshot();
                    out.put("CurrentTransportState", s.state());
                    out.put("CurrentTransportStatus", s.failed() ? "ERROR_OCCURRED" : "OK");
                    out.put("CurrentSpeed", "1");
                }
                case "GetMediaInfo" -> {
                    Snapshot s = player.snapshot();
                    out.put("NrTracks", s.uri().isEmpty() ? "0" : "1");
                    out.put("MediaDuration", time(s.duration()));
                    out.put("CurrentURI", s.uri()); out.put("CurrentURIMetaData", s.metadata());
                    out.put("NextURI", ""); out.put("NextURIMetaData", "");
                    out.put("PlayMedium", "NETWORK"); out.put("RecordMedium", "NOT_IMPLEMENTED");
                    out.put("WriteStatus", "NOT_IMPLEMENTED");
                }
                case "GetDeviceCapabilities" -> {
                    out.put("PlayMedia", "NETWORK"); out.put("RecMedia", "NOT_IMPLEMENTED");
                    out.put("RecQualityModes", "NOT_IMPLEMENTED");
                }
                case "GetTransportSettings" -> { out.put("PlayMode", "NORMAL"); out.put("RecQualityMode", "NOT_IMPLEMENTED"); }
                case "GetCurrentTransportActions" -> out.put("Actions", "Play,Pause,Stop,Seek");
                default -> throw new Fault(401);
            }
            StringBuilder fields = new StringBuilder();
            out.forEach((key, value) -> fields.append("<").append(key).append(">").append(escape(value))
                .append("</").append(key).append(">"));
            return new Result(200, envelope("<u:" + action + "Response xmlns:u=\"" + TYPE + "\">"
                + fields + "</u:" + action + "Response>"));
        } catch (Fault fault) { return fault(fault.code); }
        catch (Exception ignored) { return fault(402); }
    }

    private static void requireMedia(Player player) throws Fault {
        if (player.snapshot().uri().isEmpty()) throw new Fault(701);
        if (player.snapshot().failed()) throw new Fault(714);
    }

    static Result fault(int code) {
        return new Result(500, envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
            + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code
            + "</errorCode><errorDescription>Command rejected</errorDescription></UPnPError></detail></s:Fault>"));
    }

    static String envelope(String content) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" + content + "</s:Body></s:Envelope>";
    }

    static String descriptor(String uuid, String name) {
        return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major>"
            + "<minor>0</minor></specVersion><device><deviceType>" + RENDERER + "</deviceType><friendlyName>"
            + escape(name) + "</friendlyName><manufacturer>PandaCast</manufacturer><modelName>PandaCastReceiver</modelName>"
            + "<UDN>" + escape(uuid) + "</UDN><serviceList><service><serviceType>" + TYPE
            + "</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>"
            + "<SCPDURL>/avtransport.xml</SCPDURL><controlURL>/control</controlURL><eventSubURL/>"
            + "</service></serviceList></device></root>";
    }
}
