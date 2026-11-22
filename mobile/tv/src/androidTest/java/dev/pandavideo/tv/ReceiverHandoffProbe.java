package dev.pandavideo.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Verifies that a paused catalog decoder is released before the receiver prepares video. */
public final class ReceiverHandoffProbe extends Instrumentation {
    private String mediaUrl, receiverUrl;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mediaUrl = arguments.getString("mediaUrl");
        receiverUrl = arguments.getString("receiverUrl");
        start();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private Object localSession() throws Exception {
        // This debug-only probe inspects the owner, rather than assuming that closing
        // a controller or changing Activity visibility released a native decoder.
        Class<?> service = Class.forName(
            "dev.pandavideo.core.platform.PlaybackMediaSessionService");
        Field field = service.getDeclaredField("activeSession");
        field.setAccessible(true);
        return field.get(null);
    }

    private String command(String action, String fields) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(receiverUrl).openConnection();
        connection.setConnectTimeout(3_000);
        connection.setReadTimeout(3_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        String type = "urn:schemas-upnp-org:service:AVTransport:1";
        connection.setRequestProperty("SOAPAction", "\"" + type + "#" + action + "\"");
        byte[] body = ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
            + "<u:" + action + " xmlns:u=\"" + type + "\"><InstanceID>0</InstanceID>"
            + fields + "</u:" + action + "></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
        try {
            try (var output = connection.getOutputStream()) { output.write(body); }
            check(connection.getResponseCode() == 200, "Receiver rejected " + action);
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            try (var input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) response.write(buffer, 0, count);
            }
            return response.toString("UTF-8");
        } finally { connection.disconnect(); }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity receiver = null;
        int status = Activity.RESULT_CANCELED;
        try {
            check(mediaUrl != null && receiverUrl != null, "Provide fixture and receiver addresses");
            getTargetContext().startService(new Intent().setClassName("dev.pandavideo.tv",
                "dev.pandavideo.core.platform.PlaybackMediaSessionService"));
            Player[] local = {null};
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (local[0] == null && SystemClock.elapsedRealtime() < deadline) {
                runOnMainSync(() -> {
                    try {
                        Object session = localSession();
                        if (session != null)
                            local[0] = (Player) session.getClass().getMethod("getPlayer").invoke(session);
                    } catch (Exception error) { throw new IllegalStateException(error); }
                });
                SystemClock.sleep(100);
            }
            check(local[0] != null, "Local media service did not start");
            runOnMainSync(() -> {
                local[0].setMediaItem(MediaItem.fromUri(mediaUrl));
                local[0].setPlayWhenReady(false);
                local[0].prepare();
            });
            boolean[] ready = {false};
            while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
                runOnMainSync(() -> ready[0] = local[0].getPlaybackState() == Player.STATE_READY);
                SystemClock.sleep(100);
            }
            check(ready[0], "Paused local fixture did not acquire a decoder");
            receiver = startActivitySync(new Intent().setClassName("dev.pandavideo.tv",
                "dev.pandavideo.tv.cast.ReceiverActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            deadline = SystemClock.elapsedRealtime() + 20_000;
            boolean playing = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    command("SetAVTransportURI", "<CurrentURI>" + mediaUrl.replace("&", "&amp;")
                        + "</CurrentURI><CurrentURIMetaData/>");
                    command("Play", "<Speed>1</Speed>");
                    break;
                } catch (java.io.IOException unavailable) { SystemClock.sleep(100); }
            }
            while (SystemClock.elapsedRealtime() < deadline) {
                String state = command("GetTransportInfo", "");
                check(!state.contains("ERROR_OCCURRED"), "Receiver failed after local decoder handoff");
                if (state.contains("<CurrentTransportState>PLAYING</CurrentTransportState>")) {
                    playing = true;
                    break;
                }
                SystemClock.sleep(100);
            }
            check(playing, "Receiver did not play after local decoder handoff");
            runOnMainSync(() -> {
                try {
                    check(localSession() == null, "Local media session still owns its paused decoder");
                    check(local[0].getPlaybackState() == Player.STATE_IDLE, "Local player was not released");
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            // A second cast must also succeed after the already released service is gone.
            command("Stop", "");
            command("SetAVTransportURI", "<CurrentURI>" + mediaUrl.replace("&", "&amp;")
                + "</CurrentURI><CurrentURIMetaData/>");
            command("Play", "<Speed>1</Speed>");
            result.putString("stream", "PASS: paused local decoder released before receiver playback; repeated handoff accepted\n");
            status = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName()
                + (error instanceof AssertionError ? ": " + error.getMessage() : "") + "\n");
        } finally {
            try { command("Stop", ""); } catch (Exception ignored) {}
            if (receiver != null) {
                Activity activity = receiver;
                runOnMainSync(activity::finish);
            }
        }
        finish(status, result);
    }
}
