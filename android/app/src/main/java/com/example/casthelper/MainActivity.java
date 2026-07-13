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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

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

        web.loadUrl("https://www.google.com");
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
        toast("已发送到电视 ✅");
    }

    // 连接/恢复电视会话后自动投出待投地址;其余回调无需处理。
    private class CastSessionListener implements SessionManagerListener<CastSession> {
        @Override public void onSessionStarted(CastSession session, String sessionId) { castPending(session); }
        @Override public void onSessionResumed(CastSession session, boolean wasSuspended) { castPending(session); }
        @Override public void onSessionStartFailed(CastSession session, int error) { pendingUrl = null; }
        @Override public void onSessionStarting(CastSession session) {}
        @Override public void onSessionEnding(CastSession session) {}
        @Override public void onSessionEnded(CastSession session, int error) {}
        @Override public void onSessionSuspended(CastSession session, int reason) {}
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
    }

    @Override
    protected void onPause() {
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
