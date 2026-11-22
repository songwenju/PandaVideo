package dev.pandavideo.tv.cast;

import dev.pandavideo.tv.R;
import dev.pandavideo.core.platform.AndroidSystemMediaMute;
import dev.pandavideo.core.platform.PlaybackMediaSessionService;

import android.app.AlertDialog;
import android.os.*;
import android.net.*;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.ui.PlayerView;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;

/** TV launcher entry point and main-thread Media3 adapter for LAN casting. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class ReceiverActivity extends androidx.activity.ComponentActivity implements Transport.Player {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService networkWorker = Executors.newSingleThreadExecutor();
    private final Runnable retryNetwork = this::restartNetwork;
    private int networkStartRetries;
    private ExoPlayer player;
    private AndroidSystemMediaMute systemMute;
    private okhttp3.OkHttpClient playbackHttp;
    private ReceiverServer server;
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager connectivity;
    private NsdManager nsd;
    private NsdManager.RegistrationListener bonjour;
    private ReceiverWaitingView waiting;
    private TextView connectedBadge;
    private final Runnable hideConnectedBadge = () -> connectedBadge.setVisibility(View.GONE);
    private PlayerView video;
    private TextView screenMode;
    private boolean fillScreen;
    private String uri = "", metadata = "", title = "", address = "", networkError = "";
    private long lastPosition, lastDuration, nextStartPosition;
    private boolean failed, destroyed, retryOnResume, preparing, castEnded;
    private volatile boolean foreground;
    private AlertDialog errorDialog;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { restartNetwork(); }
        @Override public void onLost(Network network) { restartNetwork(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { restartNetwork(); }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (player == null) { finish(); return; }
                if (waiting.getVisibility() != View.VISIBLE && video.isControllerFullyVisible())
                    video.hideController();
                else stop();
            }
        });
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        video = (PlayerView) getLayoutInflater().inflate(R.layout.receiver_player_view, root, false);
        video.setBackgroundColor(Color.BLACK);
        video.setShutterBackgroundColor(Color.BLACK);
        configureScreenMode();
        configureSeekButton(androidx.media3.ui.R.id.exo_rew_with_amount);
        configureSeekButton(androidx.media3.ui.R.id.exo_ffwd_with_amount);
        video.findViewById(androidx.media3.ui.R.id.exo_play_pause)
            .setBackground(getDrawable(R.drawable.tv_control_background));
        video.setShowPreviousButton(false);
        video.setShowNextButton(false);
        video.setControllerAnimationEnabled(false);
        video.setKeepContentOnPlayerReset(false);
        video.setUseController(false);
        video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        waiting = new ReceiverWaitingView(this, this::restartNetwork);
        root.addView(waiting, new FrameLayout.LayoutParams(-1, -1));
        connectedBadge = new TextView(this);
        connectedBadge.setText("投屏成功");
        // Old TV fonts may not contain the checkmark glyph. A bounded vector draws
        // identically at any screen density and never falls back to a missing glyph.
        android.graphics.drawable.Drawable success = getDrawable(R.drawable.cast_success);
        success.setBounds(0, 0, dp(24), dp(24));
        connectedBadge.setCompoundDrawables(success, null, null, null);
        connectedBadge.setCompoundDrawablePadding(dp(10));
        connectedBadge.setTextSize(17);
        connectedBadge.setTextColor(Color.rgb(94, 227, 146));
        connectedBadge.setGravity(Gravity.CENTER);
        connectedBadge.setPadding(dp(24), dp(14), dp(24), dp(14));
        android.graphics.drawable.GradientDrawable badgeBackground = new android.graphics.drawable.GradientDrawable();
        badgeBackground.setColor(Color.argb(220, 35, 38, 48));
        badgeBackground.setCornerRadius(dp(14));
        connectedBadge.setBackground(badgeBackground);
        connectedBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badgeLayout.topMargin = dp(30);
        root.addView(connectedBadge, badgeLayout);
        setContentView(root);
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            screenMode.setVisibility(visibility);
            // Hiding the controls removes the focused button. Keep focus on the
            // video so the next D-pad/OK event still reaches Media3's controller.
            if (visibility == View.GONE && player != null && waiting.getVisibility() != View.VISIBLE)
                video.requestFocus();
        });
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("pandacast-discovery");
            multicastLock.setReferenceCounted(false); multicastLock.acquire();
        }
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback);
        restartNetwork();
    }

    private void restartNetwork() {
        if (!foreground || networkWorker.isShutdown()) return;
        try {
            networkWorker.execute(() -> {
                if (!foreground) return;
                main.removeCallbacks(retryNetwork);
                Inet4Address ip = null;
                for (Network net : connectivity.getAllNetworks()) {
                    NetworkCapabilities caps = connectivity.getNetworkCapabilities(net);
                    LinkProperties links = connectivity.getLinkProperties(net);
                    if (caps == null || links == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                    for (LinkAddress link : links.getLinkAddresses()) {
                        if (link.getAddress() instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()) { ip = ipv4; break; }
                    }
                    if (ip != null) break;
                }
                String next = ip == null ? "" : ip.getHostAddress();
                if (next.equals(address) && server != null && server.isRunning()) return;
                if (!next.equals(address) || ip == null) networkStartRetries = 0;
                stopBonjour();
                if (server != null) { server.close(); server = null; }
                address = next; networkError = "";
                if (ip != null) {
                    var prefs = getPreferences(MODE_PRIVATE);
                    String uuid = prefs.getString("uuid", null);
                    if (uuid == null) { uuid = "uuid:" + UUID.randomUUID(); prefs.edit().putString("uuid", uuid).apply(); }
                    try {
                        server = new ReceiverServer(ip, uuid, "熊猫投屏 · " + Build.MODEL, this);
                        networkStartRetries = 0;
                        startBonjour();
                    }
                    catch (Exception e) {
                        networkError = "接收服务未启动，请稍后重试";
                        // Old TV firmware can briefly retain a listener during Activity
                        // replacement. Retry only three times, then keep the manual action.
                        // Do not log provider errors, which may contain private data.
                        if (++networkStartRetries <= 3) main.postDelayed(retryNetwork, 2_000);
                    }
                }
                main.post(() -> { if (!destroyed) refreshStatus(); });
            });
        } catch (RejectedExecutionException ignored) { /* Activity teardown raced a network callback. */ }
    }

    /** Advertises the descriptor through system DNS-SD for iOS without multicast entitlement. */
    private void startBonjour() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("PandaCast " + Build.MODEL);
        info.setServiceType("_pandacast._tcp.");
        info.setPort(8899);
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo service) { }
            @Override public void onServiceUnregistered(NsdServiceInfo service) { }
            @Override public void onRegistrationFailed(NsdServiceInfo service, int code) {
                // SSDP and directed IP remain usable if this optional advertisement fails.
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo service, int code) { }
        };
        bonjour = listener;
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); }
        catch (RuntimeException ignored) { bonjour = null; }
    }

    private void stopBonjour() {
        if (bonjour == null) return;
        try { nsd.unregisterService(bonjour); }
        catch (IllegalArgumentException ignored) { /* Registration failed before teardown. */ }
        bonjour = null;
    }

    private void refreshStatus() {
        waiting.update(address, networkError, failed, castEnded);
        if (preparing && !failed && networkError.isEmpty() && !address.isEmpty()) waiting.showReceiving();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void configureScreenMode() {
        screenMode = new TextView(this);
        screenMode.setText("充满屏幕");
        screenMode.setTextColor(Color.WHITE);
        screenMode.setTextSize(16);
        screenMode.setGravity(Gravity.CENTER);
        screenMode.setPadding(dp(18), dp(12), dp(18), dp(12));
        screenMode.setBackground(getDrawable(R.drawable.tv_control_background));
        screenMode.setFocusable(true);
        screenMode.setVisibility(View.GONE);
        screenMode.setOnClickListener(view -> {
            fillScreen = !fillScreen;
            // Zoom preserves the video's aspect ratio and crops the edges instead
            // of stretching faces. Switching size never recreates the decoder.
            video.setResizeMode(fillScreen ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            screenMode.setText(fillScreen ? "适应屏幕" : "充满屏幕");
            video.showController();
        });
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        layout.topMargin = dp(24); layout.setMarginEnd(dp(24));
        video.getOverlayFrameLayout().addView(screenMode, layout);
    }

    private void configureSeekButton(int id) {
        TextView button = video.findViewById(id);
        if (button == null) return;
        // Media3's icon attributes apply to ImageView controls only. Its amount
        // controls are TextViews with a foreground ring and a separate numeral.
        // Replace that foreground directly while keeping Media3's click handler.
        button.setForeground(getDrawable(id == androidx.media3.ui.R.id.exo_rew_with_amount
            ? R.drawable.pandavideo_seek_back : R.drawable.pandavideo_seek_forward));
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, dp(4), 0, 0);
        button.setTextSize(10);
        button.setIncludeFontPadding(false);
        button.setBackground(getDrawable(R.drawable.tv_control_background));
        ViewGroup.LayoutParams layout = button.getLayoutParams();
        layout.width = dp(64); layout.height = dp(64);
        button.setLayoutParams(layout);
    }

    /** A decoder exists only while a cast is prepared or playing. */
    private ExoPlayer ensurePlayer() {
        if (player != null) return player;
        // Controllers can outlive the catalog screen and leave its paused decoder in
        // the media service. Release that owner synchronously before allocating a cast
        // decoder, including direct receiver launches and retries.
        PlaybackMediaSessionService.releaseForCasting();
        if (playbackHttp == null) playbackHttp = PlaybackTls.createClient(this);
        ExoPlayer created = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(
                new DefaultDataSource.Factory(this, new OkHttpDataSource.Factory(playbackHttp))))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build();
        player = created;
        created.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        systemMute = new AndroidSystemMediaMute(this, created);
        video.setUseController(false);
        video.setPlayer(created);
        video.setVisibility(View.VISIBLE);
        created.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                // Queued events from a released player must not reveal the previous cast.
                if (destroyed || player != created) return;
                if (state == androidx.media3.common.Player.STATE_READY && preparing) {
                    preparing = false;
                    video.setUseController(true);
                    waiting.setVisibility(View.GONE);
                    if (!video.isControllerFullyVisible()) video.requestFocus();
                    connectedBadge.setVisibility(View.VISIBLE);
                    main.removeCallbacks(hideConnectedBadge);
                    main.postDelayed(hideConnectedBadge, 2_000);
                }
                if (state == androidx.media3.common.Player.STATE_ENDED) endPlayback();
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (destroyed || player != created) return;
                // Remote URLs and native error messages can contain access tokens.
                preparing = false; failed = true;
                connectedBadge.setVisibility(View.GONE); waiting.setVisibility(View.VISIBLE); refreshStatus();
                showPlaybackError();
            }
        });
        return created;
    }

    private MediaItem mediaItem() {
        MediaItem.Builder item = new MediaItem.Builder().setUri(uri)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        if (metadata.contains("application/vnd.apple.mpegurl") || metadata.contains("application/x-mpegURL"))
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        return item.build();
    }

    private void releasePlayer() {
        ExoPlayer previous = player;
        if (previous != null) {
            lastPosition = Math.max(0, previous.getCurrentPosition());
            lastDuration = Math.max(0, previous.getDuration());
        }
        // Clear identity before release can dispatch any final listener callbacks.
        if (systemMute != null) { systemMute.close(); systemMute = null; }
        player = null;
        video.hideController();
        video.setUseController(false);
        video.setPlayer(null);
        video.setVisibility(View.GONE);
        video.clearFocus();
        if (previous != null) previous.release();
    }

    private void endPlayback() {
        preparing = false; castEnded = true; failed = false; nextStartPosition = 0;
        retryOnResume = false;
        main.removeCallbacks(hideConnectedBadge);
        connectedBadge.setVisibility(View.GONE);
        if (errorDialog != null) { errorDialog.dismiss(); errorDialog = null; }
        releasePlayer();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        waiting.setVisibility(View.VISIBLE);
        refreshStatus();
    }

    private void showPlaybackError() {
        if (isFinishing() || destroyed || !hasWindowFocus()) { retryOnResume = true; return; }
        if (errorDialog != null && errorDialog.isShowing()) return;
        errorDialog = new AlertDialog.Builder(this).setTitle("播放失败")
            .setMessage("无法加载当前视频，请重试，或在手机更换播放线路。")
            .setPositiveButton("重试", (dialog, which) -> {
                if (destroyed || player == null || castEnded) return;
                failed = false; player.prepare(); player.play(); waiting.setVisibility(View.GONE);
            }).setNegativeButton("取消", null).create();
        errorDialog.show();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && retryOnResume) { retryOnResume = false; showPlaybackError(); }
    }

    @Override public void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override public void onResume() {
        super.onResume();
        // PICO may suspend networking while the headset sleeps without changing its IP.
        if (connectivity != null) restartNetwork();
    }

    @Override public void onStop() {
        // The integrated receiver is foreground-only. A Home press or another Activity
        // must stop its audio as well as release the only TV decoder.
        foreground = false;
        if (player != null) endPlayback();
        networkWorker.execute(() -> {
            stopBonjour();
            if (server != null) { server.close(); server = null; }
        });
        super.onStop();
    }

    // HTTP workers never touch Media3 directly. A timed-out request is cancelled before
    // execution, so a queued SetURI cannot unexpectedly replace a later successful cast.
    private <T> T onPlayer(Callable<T> operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (destroyed || !foreground) throw new IllegalStateException("Receiver closed");
            try { return operation.call(); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        FutureTask<T> task = new FutureTask<>(() -> {
            if (destroyed || !foreground) throw new IllegalStateException("Receiver closed");
            return operation.call();
        });
        main.post(task);
        try { return task.get(2500, TimeUnit.MILLISECONDS); }
        catch (Exception e) { task.cancel(false); throw new IllegalStateException("Player unavailable"); }
    }

    @Override public void setMedia(String value, String data, String label) {
        onPlayer(() -> {
            uri = value; metadata = data; title = label; failed = false;
            preparing = true; castEnded = false;
            lastPosition = 0; lastDuration = 0; nextStartPosition = 0;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            main.removeCallbacks(hideConnectedBadge); connectedBadge.setVisibility(View.GONE);
            video.hideController(); video.setUseController(false);
            waiting.showReceiving(); waiting.setVisibility(View.VISIBLE);
            if (errorDialog != null) errorDialog.dismiss();
            retryOnResume = false;
            ExoPlayer current = ensurePlayer();
            current.setPlayWhenReady(false);
            current.setMediaItem(mediaItem()); current.prepare();
            video.requestFocus();
            return null;
        });
    }
    @Override public void play() {
        onPlayer(() -> {
            if (uri.isEmpty()) throw new IllegalStateException("No media");
            failed = false; castEnded = false;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (player == null) {
                preparing = true;
                waiting.showReceiving(); waiting.setVisibility(View.VISIBLE);
                ensurePlayer().setMediaItem(mediaItem(), nextStartPosition);
            }
            player.prepare(); player.play();
            if (!preparing) waiting.setVisibility(View.GONE);
            return null;
        });
    }
    @Override public void pause() { onPlayer(() -> { if (player != null) player.pause(); return null; }); }
    @Override public void stop() {
        onPlayer(() -> {
            endPlayback(); return null;
        });
    }
    @Override public void seek(long milliseconds) {
        onPlayer(() -> {
            long duration = player == null ? lastDuration : player.getDuration();
            long position = duration > 0 ? Math.min(milliseconds, duration) : milliseconds;
            if (player == null) { nextStartPosition = position; lastPosition = position; }
            else player.seekTo(position);
            return null;
        });
    }
    @Override public Transport.Snapshot snapshot() {
        return onPlayer(() -> {
            int state = player == null ? androidx.media3.common.Player.STATE_IDLE : player.getPlaybackState();
            String transport = uri.isEmpty() ? "NO_MEDIA_PRESENT"
                : failed || state == androidx.media3.common.Player.STATE_IDLE || state == androidx.media3.common.Player.STATE_ENDED
                    ? "STOPPED" : !player.getPlayWhenReady() ? "PAUSED_PLAYBACK"
                    : state == androidx.media3.common.Player.STATE_BUFFERING ? "TRANSITIONING" : "PLAYING";
            return new Transport.Snapshot(uri, metadata, transport,
                player == null ? lastPosition : Math.max(0, player.getCurrentPosition()),
                player == null ? lastDuration : Math.max(0, player.getDuration()), failed);
        });
    }

    // Activity exposes this public framework hook. AndroidX Core annotates its
    // compatibility override as library-only, but a TV Activity must override and
    // forward this hook to intercept remote keys before the native PlayerView.
    @android.annotation.SuppressLint("RestrictedApi")
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (player != null && waiting.getVisibility() != View.VISIBLE && !failed) {
            boolean back = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND;
            boolean forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            boolean timelineFocused = video.findViewById(androidx.media3.ui.R.id.exo_progress).hasFocus();
            boolean settingsFocused = video.findViewById(androidx.media3.ui.R.id.exo_settings).hasFocus();
            if ((back || forward) && (!timelineFocused && !settingsFocused
                    || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                // Intercept before PlayerView consumes D-pad input as focus navigation.
                // Media3 clamps each seek and preserves the current pause/play intent.
                if (event.getAction() == KeyEvent.ACTION_DOWN && player.isCurrentMediaItemSeekable()) {
                    if (back) player.seekBack(); else player.seekForward();
                    video.showController();
                }
                return true;
            }
            // Explicit vertical targets keep the native time bar and settings
            // reachable even when the overlay changes Android's focus geometry.
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    video.showController();
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (settingsFocused) video.findViewById(androidx.media3.ui.R.id.exo_progress).requestFocus();
                        else if (timelineFocused) video.findViewById(androidx.media3.ui.R.id.exo_play_pause).requestFocus();
                        else screenMode.requestFocus();
                    } else {
                        if (screenMode.hasFocus()) video.findViewById(androidx.media3.ui.R.id.exo_play_pause).requestFocus();
                        else if (timelineFocused) video.findViewById(androidx.media3.ui.R.id.exo_settings).requestFocus();
                        else video.findViewById(androidx.media3.ui.R.id.exo_progress).requestFocus();
                    }
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (player != null) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                if (event.getRepeatCount() == 0) stop();
                return true;
            }
        }
        if (player != null && waiting.getVisibility() != View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (event.getRepeatCount() == 0) {
                    if (player.getPlayWhenReady()) pause(); else play();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(retryNetwork);
        main.removeCallbacks(hideConnectedBadge);
        connectivity.unregisterNetworkCallback(networkCallback);
        // Queue close behind any in-flight bind, then reject further network work.
        networkWorker.execute(() -> { stopBonjour(); if (server != null) server.close(); });
        networkWorker.shutdown();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (errorDialog != null) errorDialog.dismiss();
        releasePlayer();
        if (playbackHttp != null) {
            playbackHttp.dispatcher().cancelAll();
            playbackHttp.connectionPool().evictAll();
            playbackHttp.dispatcher().executorService().shutdown();
        }
        super.onDestroy();
    }
}
