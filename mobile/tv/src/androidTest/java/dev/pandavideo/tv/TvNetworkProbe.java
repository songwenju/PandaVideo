package dev.pandavideo.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/**
 * Probes platform trust without a test framework or dependencies on obfuscated app classes.
 * Only public source hosts are reported; playback URLs and authentication data are excluded.
 */
public class TvNetworkProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        // Let the installed application's normal initialization configure its native HTTPS
        // factory. This also works against a minified release without reflection into it.
        startActivitySync(new Intent(Intent.ACTION_MAIN)
            .setClassName("dev.pandavideo.tv", "dev.pandavideo.tv.TvActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        StringBuilder failures = new StringBuilder();
        StringBuilder report = new StringBuilder();
        for (String host : new String[] {"pomo.mom", "japi.zxfmj.com", "img9.doubanio.com",
                "badssl.com", "wrong.host.badssl.com", "expired.badssl.com", "self-signed.badssl.com"}) {
            boolean expectFailure = host.startsWith("wrong.host.") || host.startsWith("expired.")
                || host.startsWith("self-signed.");
            HttpsURLConnection connection = null;
            try {
                connection = (HttpsURLConnection) new URL("https://" + host + "/").openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                report.append("host=").append(host).append(" status=").append(status).append("\n");
                if (expectFailure) failures.append("Unexpectedly trusted ").append(host).append("\n");
            } catch (Exception error) {
                StringBuilder reason = new StringBuilder("host=" + host);
                Throwable cause = error;
                for (int count = 0; cause != null && count < 4; count++, cause = cause.getCause()) {
                    reason.append(" -> ").append(cause.getClass().getSimpleName()).append(": ")
                        .append(String.valueOf(cause.getMessage()).replaceAll("https?://\\S+", "[address]"));
                }
                if (!expectFailure) failures.append(reason).append("\n");
                report.append(reason).append("\n");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", report.toString());
        result.putString("failures", failures.toString());
        finish(failures.length() == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
