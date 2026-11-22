package dev.pandavideo.tv.cast;

import java.util.*;

/** Advertises the implemented polling-based AVTransport action arguments. */
final class ServiceDescription {
    static String xml() {
        Map<String, String[]> actions = new LinkedHashMap<>();
        actions.put("SetAVTransportURI", new String[]{"InstanceID", "CurrentURI", "CurrentURIMetaData"});
        actions.put("Play", new String[]{"InstanceID", "Speed"});
        actions.put("Pause", new String[]{"InstanceID"});
        actions.put("Stop", new String[]{"InstanceID"});
        actions.put("Seek", new String[]{"InstanceID", "Unit", "Target"});
        actions.put("GetPositionInfo", new String[]{"InstanceID", ">Track", ">TrackDuration", ">TrackMetaData",
            ">TrackURI", ">RelTime", ">AbsTime", ">RelCount", ">AbsCount"});
        actions.put("GetTransportInfo", new String[]{"InstanceID", ">CurrentTransportState", ">CurrentTransportStatus", ">CurrentSpeed"});
        actions.put("GetMediaInfo", new String[]{"InstanceID", ">NrTracks", ">MediaDuration", ">CurrentURI",
            ">CurrentURIMetaData", ">NextURI", ">NextURIMetaData", ">PlayMedium", ">RecordMedium", ">WriteStatus"});
        actions.put("GetDeviceCapabilities", new String[]{"InstanceID", ">PlayMedia", ">RecMedia", ">RecQualityModes"});
        actions.put("GetTransportSettings", new String[]{"InstanceID", ">PlayMode", ">RecQualityMode"});
        actions.put("GetCurrentTransportActions", new String[]{"InstanceID", ">Actions"});
        Set<String> variables = new LinkedHashSet<>();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        actions.forEach((name, args) -> {
            xml.append("<action><name>").append(name).append("</name><argumentList>");
            for (String arg : args) {
                boolean output = arg.startsWith(">");
                String field = output ? arg.substring(1) : arg;
                variables.add(field);
                xml.append("<argument><name>").append(field).append("</name><direction>")
                    .append(output ? "out" : "in").append("</direction><relatedStateVariable>A_ARG_TYPE_")
                    .append(field).append("</relatedStateVariable></argument>");
            }
            xml.append("</argumentList></action>");
        });
        xml.append("</actionList><serviceStateTable>");
        for (String field : variables) {
            xml.append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_").append(field)
                .append("</name><dataType>").append(field.equals("InstanceID") || field.equals("Track") || field.equals("NrTracks")
                    ? "ui4" : "string").append("</dataType></stateVariable>");
        }
        return xml.append("</serviceStateTable></scpd>").toString();
    }
}
