package dev.pandavideo.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exercises the production casting protocol and native views on a TV with a 30-second fixture.
 * Avoids app and Media3 symbols so the same probe works against an obfuscated Release APK.
 */
public final class ReceiverRemoteProbe extends Instrumentation {
    private static final String TYPE = "urn:schemas-upnp-org:service:AVTransport:1";
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

    private String command(String action, String fields) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(receiverUrl).openConnection();
        connection.setConnectTimeout(4_000);
        connection.setReadTimeout(4_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        connection.setRequestProperty("SOAPAction", "\"" + TYPE + "#" + action + "\"");
        byte[] body = ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
            + "<u:" + action + " xmlns:u=\"" + TYPE + "\"><InstanceID>0</InstanceID>"
            + fields + "</u:" + action + "></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (var output = connection.getOutputStream()) { output.write(body); }
            check(connection.getResponseCode() == 200, "Casting command rejected: " + action);
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            try (var input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) response.write(buffer, 0, count);
            }
            return response.toString("UTF-8");
        } finally { connection.disconnect(); }
    }

    private long position() throws Exception {
        Matcher match = Pattern.compile("<RelTime>([0-9]+):([0-9]+):([0-9]+)</RelTime>")
            .matcher(command("GetPositionInfo", ""));
        check(match.find(), "Missing receiver position");
        return (Long.parseLong(match.group(1)) * 3600 + Long.parseLong(match.group(2)) * 60
            + Long.parseLong(match.group(3))) * 1000;
    }

    private void seek(String time) throws Exception {
        command("Seek", "<Unit>REL_TIME</Unit><Target>" + time + "</Target>");
    }

    private void key(int code) {
        sendKeyDownUpSync(code);
        waitForIdleSync();
    }

    private static View find(View view, boolean surface) {
        if (surface && view instanceof SurfaceView) return view;
        if (!surface && view instanceof TextView text
            && ("充满屏幕".contentEquals(text.getText()) || "适应屏幕".contentEquals(text.getText()))) return view;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), surface);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        int status = Activity.RESULT_CANCELED;
        try {
            check(mediaUrl != null && receiverUrl != null, "Provide mediaUrl and receiverUrl");
            activity = startActivitySync(new Intent().setClassName("dev.pandavideo.tv",
                "dev.pandavideo.tv.cast.ReceiverActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity receiver = activity;
            // The server starts asynchronously with the Activity's LAN discovery.
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (true) {
                try {
                    command("SetAVTransportURI", "<CurrentURI>" + mediaUrl.replace("&", "&amp;")
                        + "</CurrentURI><CurrentURIMetaData/>");
                    break;
                } catch (java.io.IOException unavailable) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw unavailable;
                    SystemClock.sleep(100);
                }
            }
            command("Play", "<Speed>1</Speed>");
            boolean ready;
            do {
                ready = command("GetTransportInfo", "").contains("<CurrentTransportState>PLAYING</CurrentTransportState>")
                    && command("GetPositionInfo", "").contains("<TrackDuration>0:00:30</TrackDuration>");
                if (ready) break;
                SystemClock.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            check(ready, "Controlled fixture did not become ready");
            command("Pause", "");
            seek("0:00:15");
            View[] size = {null}, videoSurface = {null};
            int[] fitWidth = {0};
            runOnMainSync(() -> {
                View root = ((ViewGroup) receiver.findViewById(android.R.id.content)).getChildAt(0);
                check(((ColorDrawable) root.getBackground()).getColor() == Color.BLACK,
                    "Letterbox background must be black");
                size[0] = find(root, false);
                videoSurface[0] = find(root, true);
                check(size[0] != null && videoSurface[0] != null, "Missing receiver controls or video surface");
                View playerView = (View) ((View) videoSurface[0].getParent()).getParent();
                check(((ColorDrawable) playerView.getBackground()).getColor() == Color.BLACK,
                    "Video background must be black");
                fitWidth[0] = videoSurface[0].getWidth();
            });
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            check(position() == 5_000, "Left must rewind ten seconds");
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(position() == 15_000, "Right must advance ten seconds");
            key(KeyEvent.KEYCODE_DPAD_UP);
            runOnMainSync(() -> check(size[0].hasFocus(), "Up must focus screen sizing"));
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            SystemClock.sleep(200);
            runOnMainSync(() -> {
                check("适应屏幕".contentEquals(((TextView) size[0]).getText()), "Fill label");
                check(videoSurface[0].getWidth() > fitWidth[0], "Fill must crop the 4:3 video to the wider screen");
            });
            check(position() == 15_000 && command("GetTransportInfo", "").contains("PAUSED_PLAYBACK"),
                "Sizing must preserve paused playback and position");
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            SystemClock.sleep(200);
            runOnMainSync(() -> check(videoSurface[0].getWidth() == fitWidth[0], "Fit must restore the complete image"));
            seek("0:00:02");
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            check(position() == 0, "Rewind must clamp at zero");
            seek("0:00:25");
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(position() >= 29_000 && position() <= 30_000, "Forward must clamp at duration");
            check(command("GetTransportInfo", "").contains("PAUSED_PLAYBACK"), "Remote seek must preserve pause");
            result.putString("stream", "PASS: production SOAP, black background, D-pad seek bounds, fill/fit geometry and pause\n");
            status = Activity.RESULT_OK;
        } catch (Throwable error) {
            // Report the assertion or exception type only; network messages may contain addresses.
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName()
                + (error instanceof AssertionError ? ": " + error.getMessage() : "") + "\n");
        } finally {
            try { command("Stop", ""); } catch (Exception ignored) {}
            if (activity != null) {
                Activity receiver = activity;
                runOnMainSync(receiver::finish);
            }
        }
        finish(status, result);
    }
}
