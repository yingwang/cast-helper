package com.example.casthelper;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.util.Locale;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // Full playlists / files worth casting (ignore individual HLS segments like .ts/.m4s).
    private static final Pattern STREAM =
            Pattern.compile("\\.(m3u8|mpd|mp4|m4v|mov|webm)([?#]|$)", Pattern.CASE_INSENSITIVE);

    // Quick-launch shortcuts for common video sites: {显示名称, 网址}. Prefer mobile pages
    // where they exist so they render well in the WebView.
    private static final String[][] SITES = {
            {"哔哩哔哩", "https://m.bilibili.com"},
            {"芒果TV", "https://www.mgtv.com"},
            {"优酷", "https://www.youku.com"},
            {"腾讯视频", "https://v.qq.com"},
            {"爱奇艺", "https://www.iqiyi.com"},
            {"西瓜视频", "https://www.ixigua.com"},
            {"YouTube", "https://m.youtube.com"},
            {"爱壹帆", "https://m.iyf.tv/"},
            {"欧乐影院", "https://www.olevod.com/"},
    };

    // 倍速档位(Chromecast 默认接收器支持 0.5–2 倍)。
    private static final double[] SPEEDS = {0.5, 1.0, 1.25, 1.5, 2.0};

    private WebView web;
    private EditText urlBar;
    private TextView status;
    private Button castBtn;
    private MediaRouteButton routeButton;
    private volatile String detectedUrl = null;
    // 用户点了「投屏到电视」但还没连接电视时,先把要投的地址记在这里,选好电视后自动投。
    private String pendingUrl = null;
    private CastContext castContext;
    private SessionManagerListener<CastSession> sessionListener;

    // 投屏后的遥控条
    private LinearLayout controls;
    private Button btnPlayPause;
    private SeekBar seek;
    private TextView timeLabel;
    private boolean userSeeking = false;
    private RemoteMediaClient remoteClient;
    private RemoteMediaClient.Callback mediaCallback;
    private RemoteMediaClient.ProgressListener progressListener;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlBar = findViewById(R.id.urlBar);
        status = findViewById(R.id.status);
        castBtn = findViewById(R.id.castBtn);
        web = findViewById(R.id.web);
        routeButton = findViewById(R.id.routeButton);

        try {
            castContext = CastContext.getSharedInstance(getApplicationContext());
            CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), routeButton);
            sessionListener = new CastSessionListener();
        } catch (Exception e) {
            status.setText("投屏组件不可用:请确认设备装了 Google Play 服务");
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // keep navigation inside the app
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                final String u = request.getUrl().toString();
                if (STREAM.matcher(u).find()) {
                    detectedUrl = u;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            castBtn.setEnabled(true);
                            status.setText("已发现视频,点下面「投屏到电视」\n" + shorten(u));
                        }
                    });
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                urlBar.setText(url);
            }
        });

        findViewById(R.id.goBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                load();
                return true;
            }
        });
        castBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cast(); }
        });
        castBtn.setEnabled(false);

        setupShortcuts();
        setupControls();

        web.loadUrl("https://www.google.com");
    }

    private void setupControls() {
        controls = findViewById(R.id.controls);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        seek = findViewById(R.id.seek);
        timeLabel = findViewById(R.id.timeLabel);

        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (remoteClient != null) remoteClient.togglePlayback(); }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(-10000); }
        });
        findViewById(R.id.btnFwd).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(30000); }
        });
        findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (remoteClient != null) remoteClient.stop(); }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) timeLabel.setText(fmt(progress) + " / " + fmt(sb.getMax()));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                seekTo(sb.getProgress());
            }
        });

        LinearLayout speedRow = findViewById(R.id.speedRow);
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);
        for (final double rate : SPEEDS) {
            Button b = new Button(this);
            b.setText(rate == 1.0 ? "1x" : (rate + "x"));
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(gap * 2, gap, gap * 2, gap);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap, 0, gap, 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (remoteClient == null) return;
                    remoteClient.setPlaybackRate(rate);
                    toast("已设为 " + (rate == 1.0 ? "1" : String.valueOf(rate)) + " 倍速");
                }
            });
            speedRow.addView(b);
        }

        // 每 3 秒更新一次进度,对墨水屏更友好(避免每秒刷新造成的闪烁/耗电)。
        progressListener = new RemoteMediaClient.ProgressListener() {
            @Override public void onProgressUpdated(long progressMs, long durationMs) {
                if (userSeeking) return;
                if (durationMs > 0) {
                    seek.setEnabled(true);
                    seek.setMax((int) durationMs);
                    seek.setProgress((int) Math.min(progressMs, durationMs));
                    timeLabel.setText(fmt(progressMs) + " / " + fmt(durationMs));
                } else {
                    seek.setEnabled(false);
                    timeLabel.setText(fmt(progressMs) + " / 直播");
                }
            }
        };
        mediaCallback = new RemoteMediaClient.Callback() {
            @Override public void onStatusUpdated() { refreshControls(); }
            @Override public void onMetadataUpdated() { refreshControls(); }
        };
    }

    private void setupShortcuts() {
        LinearLayout bar = findViewById(R.id.shortcuts);
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);
        for (final String[] site : SITES) {
            Button b = new Button(this);
            b.setText(site[0]);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(gap * 3, gap, gap * 3, gap);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap, gap, gap, gap);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    urlBar.setText(site[1]);
                    load();
                }
            });
            bar.addView(b);
        }
    }

    private void load() {
        String u = urlBar.getText().toString().trim();
        if (TextUtils.isEmpty(u)) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            if (u.contains(".") && !u.contains(" ")) {
                u = "https://" + u;
            } else {
                u = "https://www.google.com/search?q=" + Uri.encode(u);
            }
        }
        detectedUrl = null;
        castBtn.setEnabled(false);
        status.setText("加载中…登录并播放视频后,这里会提示可投屏");
        web.loadUrl(u);
        hideKeyboard();
    }

    private void cast() {
        String target = detectedUrl;
        if (target == null) {
            String bar = urlBar.getText().toString().trim();
            if (STREAM.matcher(bar).find()) target = bar;
        }
        if (target == null) {
            toast("还没发现视频:先让视频播放几秒再点");
            return;
        }
        if (castContext == null) {
            toast("投屏组件不可用(缺 Google Play 服务)");
            return;
        }
        CastSession session = castContext.getSessionManager().getCurrentCastSession();
        if (session != null && session.isConnected()) {
            loadMedia(session, target);
            return;
        }
        // 还没连接电视:记下要投的地址,弹出「选择电视」对话框;选好后 sessionListener 会自动开投。
        pendingUrl = target;
        toast("请选择要投屏的电视…");
        routeButton.performClick();
    }

    // 选好电视、会话就绪后,把之前记下的地址自动投出去。
    private void castPending(CastSession session) {
        if (pendingUrl == null) return;
        String target = pendingUrl;
        pendingUrl = null;
        loadMedia(session, target);
    }

    private void loadMedia(CastSession session, String target) {
        RemoteMediaClient client = session.getRemoteMediaClient();
        if (client == null) {
            toast("投屏会话异常,请重连电视");
            return;
        }

        String type = "video/mp4";
        String lower = target.toLowerCase();
        if (lower.contains(".m3u8")) type = "application/x-mpegurl";
        else if (lower.contains(".mpd")) type = "application/dash+xml";
        else if (lower.contains(".webm")) type = "video/webm";

        MediaMetadata meta = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        meta.putString(MediaMetadata.KEY_TITLE, "投屏");

        MediaInfo info = new MediaInfo.Builder(target)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(type)
                .setMetadata(meta)
                .build();

        MediaLoadRequestData req = new MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .build();

        client.load(req);
        pauseLocalPlayback();
        bindMediaClient();
        toast("已发送到电视 ✅ 下方可控制播放");
    }

    // 投屏成功后,把 App 内网页里正在放的视频暂停并静音,省得 Boox 本地白播、费电。
    private void pauseLocalPlayback() {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('video,audio');" +
                        "for(var i=0;i<m.length;i++){try{m[i].pause();m[i].muted=true;}catch(e){}}}catch(e){}})();",
                null);
    }

    // 把遥控条挂到当前会话的 RemoteMediaClient 上(注册状态回调与进度监听)。
    private void bindMediaClient() {
        RemoteMediaClient client = null;
        if (castContext != null) {
            CastSession session = castContext.getSessionManager().getCurrentCastSession();
            if (session != null) client = session.getRemoteMediaClient();
        }
        if (client == remoteClient) { refreshControls(); return; }
        if (remoteClient != null) {
            remoteClient.unregisterCallback(mediaCallback);
            remoteClient.removeProgressListener(progressListener);
        }
        remoteClient = client;
        if (remoteClient != null) {
            remoteClient.registerCallback(mediaCallback);
            remoteClient.addProgressListener(progressListener, 3000);
        }
        refreshControls();
    }

    private void unbindMediaClient() {
        if (remoteClient != null) {
            remoteClient.unregisterCallback(mediaCallback);
            remoteClient.removeProgressListener(progressListener);
            remoteClient = null;
        }
    }

    private void refreshControls() {
        if (controls == null) return;
        boolean has = remoteClient != null && remoteClient.hasMediaSession();
        controls.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) btnPlayPause.setText(remoteClient.isPaused() ? "▶ 播放" : "⏸ 暂停");
    }

    private void seekTo(long ms) {
        if (remoteClient == null) return;
        remoteClient.seek(new MediaSeekOptions.Builder().setPosition(Math.max(0, ms)).build());
    }

    private void seekBy(long deltaMs) {
        if (remoteClient == null) return;
        seekTo(remoteClient.getApproximateStreamPosition() + deltaMs);
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long t = ms / 1000;
        long h = t / 3600, m = (t % 3600) / 60, s = t % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    // 连接/恢复电视会话后自动投出待投地址;其余回调无需处理。
    private class CastSessionListener implements SessionManagerListener<CastSession> {
        @Override public void onSessionStarted(CastSession session, String sessionId) { castPending(session); bindMediaClient(); }
        @Override public void onSessionResumed(CastSession session, boolean wasSuspended) { castPending(session); bindMediaClient(); }
        @Override public void onSessionStartFailed(CastSession session, int error) { pendingUrl = null; }
        @Override public void onSessionStarting(CastSession session) {}
        @Override public void onSessionEnding(CastSession session) {}
        @Override public void onSessionEnded(CastSession session, int error) { unbindMediaClient(); remoteClient = null; refreshControls(); }
        @Override public void onSessionSuspended(CastSession session, int reason) { unbindMediaClient(); remoteClient = null; refreshControls(); }
        @Override public void onSessionResuming(CastSession session, String sessionId) {}
        @Override public void onSessionResumeFailed(CastSession session, int error) {}
    }

    private String shorten(String u) {
        return u.length() > 64 ? u.substring(0, 64) + "…" : u;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (castContext != null && sessionListener != null) {
            castContext.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
        }
        bindMediaClient();
    }

    @Override
    protected void onPause() {
        unbindMediaClient();
        if (castContext != null && sessionListener != null) {
            castContext.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class);
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
