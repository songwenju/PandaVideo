package dev.pandavideo.tv.cast;

import dev.pandavideo.tv.R;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** Minimal, overscan-safe landing screen. Playback remains in the sibling PlayerView. */
@SuppressLint("ViewConstructor") // Activity-created view; no XML inflation or layout preview constructor.
final class ReceiverWaitingView extends FrameLayout {
    private static final int WHITE = Color.rgb(243, 246, 250);
    private static final int MUTED = Color.rgb(146, 157, 172);
    private final TextView heading, hint, network;
    private final Button retry;

    ReceiverWaitingView(Context context, Runnable reconnect) {
        super(context);
        addView(new AmbientBackground(context), new LayoutParams(-1, -1));
        TextView brand = text("熊猫投屏", 18, MUTED);
        brand.setTypeface(null, Typeface.BOLD);
        LayoutParams brandLayout = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        brandLayout.setMargins(dp(48), dp(30), dp(48), 0);
        addView(brand, brandLayout);

        LinearLayout center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(36), dp(48), dp(36), dp(48));
        LayoutParams centerLayout = new LayoutParams(-1, -2, Gravity.CENTER);
        addView(center, centerLayout);
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.panda_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("熊猫视频 Logo");
        center.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        heading = text("等待投屏", 28, WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams headingLayout = new LinearLayout.LayoutParams(-2, -2);
        headingLayout.topMargin = dp(20);
        center.addView(heading, headingLayout);
        TextView device = text("熊猫投屏 · " + Build.MODEL, 16, Color.rgb(174, 196, 255));
        LinearLayout.LayoutParams deviceLayout = new LinearLayout.LayoutParams(-2, -2);
        deviceLayout.topMargin = dp(12);
        center.addView(device, deviceLayout);
        hint = text("在手机熊猫视频中点击投屏，选择这台设备", 14, MUTED);
        LinearLayout.LayoutParams hintLayout = new LinearLayout.LayoutParams(-2, -2);
        hintLayout.topMargin = dp(18);
        center.addView(hint, hintLayout);
        retry = new Button(context);
        retry.setText("重新连接网络");
        retry.setTextSize(14);
        retry.setTextColor(WHITE);
        GradientDrawable button = new GradientDrawable();
        button.setColor(Color.rgb(35, 44, 59));
        button.setCornerRadius(dp(12));
        button.setStroke(dp(1), Color.rgb(69, 82, 101));
        retry.setBackground(button);
        retry.setPadding(dp(20), 0, dp(20), 0);
        retry.setOnClickListener(v -> reconnect.run());
        retry.setVisibility(GONE);
        LinearLayout.LayoutParams retryLayout = new LinearLayout.LayoutParams(-2, dp(48));
        retryLayout.topMargin = dp(16);
        center.addView(retry, retryLayout);

        network = text("正在连接网络…", 12, MUTED);
        LayoutParams footer = new LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        footer.setMargins(dp(40), 0, dp(40), dp(26));
        addView(network, footer);
    }

    /** Updates network and playback feedback without exposing the media URL. */
    void update(String address, String error, boolean failed, boolean ended) {
        boolean missingNetwork = address.isEmpty();
        boolean networkFailed = !error.isEmpty();
        heading.setText(networkFailed ? "连接暂时不可用" : missingNetwork ? "等待网络连接"
            : failed ? "视频暂时无法播放" : ended ? "投屏已结束" : "等待投屏");
        hint.setText(networkFailed ? error : missingNetwork ? "请连接 Wi-Fi 或有线网络"
            : failed ? "请在手机重试，或更换播放线路"
            : ended ? "在手机选择设备，即可再次开始投屏" : "在手机熊猫视频中选择这台设备，开始大屏观影");
        network.setText(missingNetwork ? "手机和接收端需连接同一局域网"
            : address + "   ·   手机和接收端需连接同一局域网");
        retry.setText(missingNetwork ? "重新连接网络" : "重试接收服务");
        retry.setVisibility(missingNetwork || networkFailed ? VISIBLE : GONE);
    }

    void showReceiving() {
        heading.setText("正在接收来自手机的投屏");
        hint.setText("正在加载视频，请稍候…");
        retry.setVisibility(GONE);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Small vector television mark, drawn locally at the display's density. */
    private static final class Television extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        Television(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            triangle.moveTo(44, 22); triangle.lineTo(60, 32); triangle.lineTo(44, 42); triangle.close();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 100f, getHeight() / 76f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(70, 116, 148, 218));
            canvas.drawRoundRect(2, 2, 98, 62, 12, 12, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(Color.rgb(174, 196, 255));
            canvas.drawRoundRect(10, 9, 90, 54, 5, 5, paint);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(50, 63, 50, 71, paint);
            canvas.drawLine(35, 72, 65, 72, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(174, 196, 255));
            canvas.drawPath(triangle, paint);
            canvas.restore();
        }
    }

    /** Blue evening backdrop with restrained mountain silhouettes; no bitmap dependency. */
    private static final class AmbientBackground extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ridge = new Path(), foreground = new Path(), glow = new Path();
        private Shader sky;
        AmbientBackground(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            sky = new LinearGradient(0, 0, w, h, new int[] {
                Color.rgb(8, 23, 48), Color.rgb(29, 52, 91), Color.rgb(75, 74, 105)
            }, new float[] {0, .6f, 1}, Shader.TileMode.CLAMP);
            ridge.reset(); ridge.moveTo(0, h * .82f);
            ridge.lineTo(w * .1f, h * .72f); ridge.lineTo(w * .19f, h * .8f);
            ridge.lineTo(w * .29f, h * .74f); ridge.lineTo(w * .43f, h * .86f);
            ridge.lineTo(w * .6f, h * .79f); ridge.lineTo(w * .75f, h * .87f);
            ridge.lineTo(w * .89f, h * .77f); ridge.lineTo(w, h * .82f);
            ridge.lineTo(w, h); ridge.lineTo(0, h); ridge.close();
            foreground.reset(); foreground.moveTo(0, h * .9f);
            foreground.cubicTo(w * .3f, h * .84f, w * .43f, h * .99f, w * .62f, h * .91f);
            foreground.cubicTo(w * .82f, h * .83f, w * .87f, h * .92f, w, h * .86f);
            foreground.lineTo(w, h); foreground.lineTo(0, h); foreground.close();
            glow.reset(); glow.moveTo(0, h * .32f);
            glow.cubicTo(w * .3f, h * .8f, w * .7f, h * .75f, w, h * .28f);
        }
        @Override protected void onDraw(Canvas canvas) {
            paint.setShader(sky); paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null); paint.setColor(Color.argb(16, 126, 167, 255));
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(getHeight() * .035f);
            canvas.drawPath(glow, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(150, 12, 30, 56));
            canvas.drawPath(ridge, paint);
            paint.setColor(Color.argb(180, 8, 22, 42)); canvas.drawPath(foreground, paint);
        }
    }
}
