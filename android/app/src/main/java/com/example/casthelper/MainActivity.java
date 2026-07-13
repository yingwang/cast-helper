package com.example.casthelper;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.api.ResultCallback;

import java.util.Locale;
import java.util.regex.Matcher;
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
    private volatile int detectedPriority = 0; // m3u8/mpd=2,mp4 等=1:防止贴片广告盖掉正片
    private volatile String altStreamUrl = null; // 被“master 优先”压下的最近一条固定码率分支,备用
    private String lastPageUrl = null; // 当前页面地址(忽略 #),检测 SPA 站内换集用
    private String episodeHint = "";   // 从页面选中集读到的“第N集”,标题不含集数时补上
    // 用户点了「投屏到电视」但还没连接电视时,先把要投的地址记在这里,选好电视后自动投。
    private String pendingUrl = null;
    private long pendingPosMs = 0;  // 记地址时本地已看到的进度
    private long pendingSetAt = 0;  // 记地址的时刻,太久没投就作废
    private CastContext castContext;
    private SessionManagerListener<CastSession> sessionListener;

    // 投屏后的遥控条
    private LinearLayout controls;
    private Button btnPlayPause;
    private SeekBar seek;
    private TextView timeLabel;
    private TextView tvStatus; // 「📺 电视名 · 播放中/缓冲中…」
    private boolean userSeeking = false;
    private RemoteMediaClient remoteClient;
    private RemoteMediaClient.Callback mediaCallback;
    private RemoteMediaClient.ProgressListener progressListener;

    // 音量(调的是 Chromecast 输出电平,和电视遥控器的音量叠加生效)
    private SeekBar volSeek;
    private Button btnMute;
    private boolean userVolDragging = false;
    private CastSession castSession;
    private boolean playErrorToasted = false; // 每次投出后,拉流失败只提示一次
    private volatile boolean errorAwaitingVariant = false; // master 投失败、正等固定分支出现好自动换线
    // 电视遥控器/其他手机改了 Chromecast 音量时,同步刷新音量条。
    private final Cast.Listener castListener = new Cast.Listener() {
        @Override public void onVolumeChanged() { refreshControls(); }
    };

    // 自动连播(实验):电视播完 → 让网页播放器跳到片尾触发网站自带连播
    // → 嗅探到不同于当前集的新直链 → 自动投出。
    private CheckBox chkAutoNext;
    private String lastCastUrl = null;
    private volatile boolean awaitingNext = false;
    // 电视端最近的播放位置/时长(随进度监听每 3 秒更新),停止投屏时回写给网页播放器。
    private long lastTvPosMs = 0;
    private long lastTvDurMs = 0;
    // master 总表在部分站会拉不动(分支相对路径丢 token)或反复缓冲:盯着开播后的
    // 播放状态,出问题就自动切回网页选中的固定码率分支。
    // 默认投网页选中的固定码率线路(即“后到的赢”):master 自适应在真实站点上反而频繁
    // 缓冲(总表里分支相对路径丢 token / 自适应用力过猛),所以不再默认投 master。
    private boolean preferVariant = true;
    private boolean fallbackTried = false; // 本次投放已切换过,防循环
    private long castStartAt = 0;
    private boolean playbackStarted = false;
    private long bufferingSince = 0;
    private long bufferedAccumMs = 0;
    private int bufferSpells = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable nextTimeout = new Runnable() {
        @Override public void run() {
            if (awaitingNext) {
                stopAutoNextWait();
                toast("没能自动接上下一集,请回 App 手动点下一集");
            }
        }
    };
    // 第一招(跳到片尾播完)12 秒内没接上时的兜底:直接给播放器发“播放结束”事件,
    // 免得片尾那几秒因直链过期拉不动而卡死。只在播放头确实停在片尾附近时才发,
    // 避免网站其实刚自己换上下一集(还没请求直链)时被误发 ended 而跳集。
    private final Runnable nextFallback = new Runnable() {
        @Override public void run() {
            if (!awaitingNext || web == null) return;
            web.evaluateJavascript("(function(){" + jsCollectMedia("video") + JS_PICK_LONGEST
                    + "if(!_v)return;"
                    + "if(!(isFinite(_v.duration)&&_v.duration>1&&_v.currentTime>_v.duration-30))return;"
                    + "try{_v.pause();}catch(e){}_v.muted=true;"
                    + "try{_v.dispatchEvent(new Event('ended'));}catch(e){}})();", null);
        }
    };
    // 页面开播十几秒还嗅探不到直链时,探测是不是 blob/加密流(部分 B 站视频等 MSE/DRM 形态),
    // 是的话明确提示换方案,免得用户干等。
    private final Runnable blobProbe = new Runnable() {
        @Override public void run() {
            if (detectedUrl != null || web == null) return;
            web.evaluateJavascript(jsProbeMedia(), new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (detectedUrl == null && "2".equals(v)) {
                        status.setText("此站视频走加密或内存流(blob),App 嗅探不到直链;请改用电脑 Chrome 的「投放标签页」投屏");
                    }
                }
            });
        }
    };
    // master 投失败后等固定分支出现的兜底:12 秒还没等到就给终态提示。
    private final Runnable errorGiveUp = new Runnable() {
        @Override public void run() {
            if (!errorAwaitingVariant) return;
            errorAwaitingVariant = false;
            if (!playErrorToasted) {
                playErrorToasted = true;
                toast("电视拉流失败:该站直链可能有防盗链/跨域限制,建议改用电脑 Chrome 的「投放标签页」");
            }
        }
    };

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
            // 常驻注册(不随 onPause 注销):息屏/切后台时也要能收到“播放结束”,
            // 自动连播才有机会接上下一集。onDestroy 里再注销。
            castContext.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
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
                // 只放行 http(s)。视频站常用 intent:// 等链接把人拽去装它家 App,
                // WebView 加载不了这类 scheme,会用 ERR_UNKNOWN_URL_SCHEME 错误页顶掉当前页面。
                return !isHttp(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) { // API 21–23 走这个重载
                return !isHttp(url);
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                final String u = request.getUrl().toString();
                final int pri = priorityOf(u);
                if (pri > 0) {
                    // 同一地址反复请求(播放列表刷新)不用重复刷 UI;但正等下一集时要放行:
                    // 下一集可能早被网站预取过一次,再请求时地址相同,也得进自动连播判断。
                    final boolean dup = u.equals(detectedUrl);
                    if (dup && !awaitingNext) return null;
                    // 取舍规则:同级(m3u8 之间 / mp4 之间)「后到的赢」——网页播放器最后
                    // 请求的那条就是它选中的固定码率线路 / 正片,投它电视最稳(preferVariant
                    // 默认 true)。低级别(mp4)不覆盖高级别(m3u8)。
                    final boolean better = pri > detectedPriority
                            || (pri == detectedPriority && (pri == 1 || preferVariant));
                    if (better) { detectedUrl = u; detectedPriority = pri; if (pri == 2) altStreamUrl = null; }
                    else if (pri == 2 && detectedPriority == 2 && altStreamUrl == null) altStreamUrl = u; // 只记 master 之后第一条固定分支当备用
                    // 之前投 master 失败、正等固定分支出现的话,拿到就自动换线重试
                    if (altStreamUrl != null && errorAwaitingVariant) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (!errorAwaitingVariant) return;
                                errorAwaitingVariant = false;
                                uiHandler.removeCallbacks(errorGiveUp);
                                tryVariantFallback("电视拉流失败,自动换固定码率线路重试…");
                            }
                        });
                    }
                    if (!better && !awaitingNext) return null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (better && !dup) {
                                castBtn.setEnabled(true);
                                status.setText("已发现视频,点下面「投屏到电视」\n" + shorten(u));
                            }
                            // 自动连播:正等下一集时,嗅探到不同于当前集、级别不低于当前集的
                            // 新直链就自动投出(级别判断挡住集间贴片广告)。
                            if (awaitingNext && pri >= priorityOf(lastCastUrl)
                                    && !baseOf(u).equals(baseOf(lastCastUrl))) {
                                stopAutoNextWait();
                                CastSession s = castContext == null ? null
                                        : castContext.getSessionManager().getCurrentCastSession();
                                if (s != null && s.isConnected()) {
                                    toast("自动连播:正在投下一集…");
                                    episodeHint = ""; // 换集了,旧集数作废
                                    loadMedia(s, u, 0);
                                }
                            }
                        }
                    });
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                urlBar.setText(url);
                lastPageUrl = url;
                uiHandler.removeCallbacks(blobProbe);
                // 换了页面,旧直链多半已失效或不属于本页:重置,免得一键把上一部投出去。
                detectedUrl = null;
                detectedPriority = 0;
                altStreamUrl = null;
                castBtn.setEnabled(false);
                if (!awaitingNext) {
                    status.setText("加载中…登录并播放视频后,这里会提示可投屏");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面开播一阵还嗅探不到直链的话,探测是否 blob/加密流并提示。
                uiHandler.removeCallbacks(blobProbe);
                uiHandler.postDelayed(blobProbe, 12000);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // SPA 站内换集走 pushState,不触发 onPageStarted:URL(忽略 #)变了同样重置嗅探,
                // 否则一键投出去的还是上一集。
                if (!noFragment(url).equals(noFragment(lastPageUrl))) {
                    lastPageUrl = url;
                    detectedUrl = null;
                    detectedPriority = 0;
                    altStreamUrl = null;
                    castBtn.setEnabled(false);
                }
            }
        });

        findViewById(R.id.goBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                // 软键盘「前往」只回调一次;物理回车按下/抬起各回调一次,只认按下,免得加载两遍。
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                    load();
                }
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
        tvStatus = findViewById(R.id.tvStatus);

        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (remoteClient != null) remoteClient.togglePlayback(); }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(-10000); }
        });
        findViewById(R.id.btnFwd).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(30000); }
        });
        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { manualNext(); }
        });
        findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (remoteClient == null) return;
                setLocalPauseGuard(false); // 先解除守卫,回手机能正常播放
                syncBackToPage(); // 把电视看到的进度写回网页播放器,回手机能接着看
                remoteClient.stop();
            }
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

        volSeek = findViewById(R.id.volSeek);
        btnMute = findViewById(R.id.btnMute);
        chkAutoNext = findViewById(R.id.chkAutoNext);

        volSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar sb) { userVolDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userVolDragging = false;
                if (castSession == null) return;
                try {
                    castSession.setVolume(sb.getProgress() / 100.0);
                } catch (Exception e) {
                    toast("音量设置失败:" + e.getMessage());
                }
            }
        });
        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (castSession == null) return;
                try {
                    castSession.setMute(!castSession.isMute());
                    refreshControls();
                } catch (Exception e) {
                    toast("静音切换失败:" + e.getMessage());
                }
            }
        });

        // 每 3 秒更新一次进度,对墨水屏更友好(避免每秒刷新造成的闪烁/耗电)。
        progressListener = new RemoteMediaClient.ProgressListener() {
            @Override public void onProgressUpdated(long progressMs, long durationMs) {
                if (durationMs > 0) { lastTvPosMs = progressMs; lastTvDurMs = durationMs; }
                trackBufferingAndMaybeFallback(); // 每 3 秒一跳,卡在缓冲时也有心跳做判断
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
            @Override public void onStatusUpdated() {
                refreshControls();
                trackBufferingAndMaybeFallback();
                notifyIfPlaybackError();
                maybeAutoNext();
            }
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
        detectedPriority = 0;
        altStreamUrl = null;
        castBtn.setEnabled(false);
        status.setText("加载中…登录并播放视频后,这里会提示可投屏");
        web.loadUrl(u);
        hideKeyboard();
    }

    private void cast() {
        if (castContext == null) {
            toast("投屏组件不可用(缺 Google Play 服务)");
            return;
        }
        // 只读:选中集“第N集”(补标题)、当前 <video> 直链(仅在完全没嗅到直链时兜底)。
        // 投屏目标 = 嗅探到的直链(网页当前在放的那条),且不做续播 seek——与最早不卡的
        // 版本保持一致(续播 seek、改用 currentSrc 都是后来加的,先撤掉排查卡顿)。
        web.evaluateJavascript(jsProbeActive(),
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String ep = "", src = "";
                        try {
                            org.json.JSONArray a = new org.json.JSONArray(value);
                            ep = a.optString(1, "");
                            src = a.optString(2, "");
                        } catch (Exception ignored) {}
                        String target = detectedUrl;
                        if (target == null) {
                            String bar = urlBar.getText().toString().trim();
                            if (STREAM.matcher(bar).find()) target = bar;
                        }
                        if (target == null && !TextUtils.isEmpty(src) && STREAM.matcher(src).find()) target = src;
                        if (target == null) {
                            web.evaluateJavascript(jsProbeMedia(), new ValueCallback<String>() {
                                @Override public void onReceiveValue(String v) {
                                    toast("2".equals(v)
                                            ? "此站是加密/内存流(blob),抓不到直链;请用电脑 Chrome 的「投放标签页」"
                                            : "还没发现视频:先让视频播放几秒再点");
                                }
                            });
                            return;
                        }
                        episodeHint = ep;
                        startCast(target, 0); // 不做续播 seek,从头播(老行为)
                    }
                });
    }

    private void startCast(String target, long positionMs) {
        CastSession session = castContext.getSessionManager().getCurrentCastSession();
        if (session != null && session.isConnected()) {
            loadMedia(session, target, positionMs);
            return;
        }
        // 还没连接电视:记下要投的地址,弹出「选择电视」对话框;选好后 sessionListener 会自动开投。
        pendingUrl = target;
        pendingPosMs = positionMs;
        pendingSetAt = SystemClock.elapsedRealtime();
        toast("请选择要投屏的电视…");
        routeButton.performClick();
    }

    // 选好电视、会话就绪后,把之前记下的地址自动投出去。
    private void castPending(CastSession session) {
        if (pendingUrl == null) return;
        String target = pendingUrl;
        pendingUrl = null;
        // 记下太久(比如上次弹框被取消、这次是手动连的电视)就作废,免得误投旧片。
        if (SystemClock.elapsedRealtime() - pendingSetAt > 120000) return;
        loadMedia(session, target, pendingPosMs);
    }

    private void loadMedia(CastSession session, String target, long positionMs) {
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

        // 标题取当前网页标题(一般含剧名和集数),电视和通知栏上都会显示。
        String title = castTitle();
        MediaMetadata meta = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        meta.putString(MediaMetadata.KEY_TITLE, title);

        MediaInfo info = new MediaInfo.Builder(target)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(type)
                .setMetadata(meta)
                .build();

        MediaLoadRequestData.Builder reqBuilder = new MediaLoadRequestData.Builder()
                .setMediaInfo(info);
        if (positionMs > 5000) reqBuilder.setCurrentTime(positionMs); // 本地已看几分钟就从那里接着放

        playErrorToasted = false;
        lastTvPosMs = 0;
        lastTvDurMs = 0;
        castStartAt = SystemClock.elapsedRealtime();
        playbackStarted = false;
        bufferingSince = 0;
        bufferedAccumMs = 0;
        bufferSpells = 0;
        fallbackTried = false;
        errorAwaitingVariant = false;
        uiHandler.removeCallbacks(errorGiveUp);
        // 先把本地按停(并挂上“保持暂停”守卫),再发投放请求——顺序反过来的话,
        // 万一发送环节抛异常,本地就会一直白放。
        pauseLocalPlayback();
        client.load(reqBuilder.build()).setResultCallback(
                new ResultCallback<RemoteMediaClient.MediaChannelResult>() {
                    @Override public void onResult(RemoteMediaClient.MediaChannelResult r) {
                        if (!r.getStatus().isSuccess()) {
                            setLocalPauseGuard(false); // 投放被拒:解除守卫,别把本地播放也一起锁死
                            toast("投屏请求没成功(" + r.getStatus().getStatusCode() + "),请重试");
                        }
                    }
                });
        lastCastUrl = target;
        stopAutoNextWait();
        bindMediaClient();
        toast("已投到电视 ✅ " + title);
    }

    // 电视报告"播放正常结束"时,让网页播放器跳到片尾正常播完,触发网站自带的自动连播;
    // 12 秒没动静就直接给播放器发 ended 事件兜底;网站加载下一集后由嗅探回调接手自动投出。
    // 90 秒还没等到就提示手动。息屏/后台时靠投屏媒体通知(前台服务)保住进程。
    private void maybeAutoNext() {
        if (remoteClient == null || chkAutoNext == null || !chkAutoNext.isChecked()) return;
        if (awaitingNext || lastCastUrl == null) return;
        if (remoteClient.getPlayerState() != MediaStatus.PLAYER_STATE_IDLE
                || remoteClient.getIdleReason() != MediaStatus.IDLE_REASON_FINISHED) return;
        if (tryCastPreloadedNext()) return;
        awaitingNext = true;
        toast("这一集放完了,尝试自动接下一集…");
        triggerPageNext(12000, 90000);
    }

    // 手动「下一集」:随时可点,不依赖“自动连播”开关,也不用等这一集放完。
    private void manualNext() {
        if (web == null || lastCastUrl == null) return;
        if (tryCastPreloadedNext()) return;
        stopAutoNextWait();
        awaitingNext = true;
        toast("正在让网页跳下一集…");
        triggerPageNext(8000, 60000);
    }

    // 有些站会提前预取下一集:嗅探记录里已有不同于当前集的正片直链,直接投,不用等。
    private boolean tryCastPreloadedNext() {
        String preloaded = detectedUrl;
        if (preloaded == null || priorityOf(preloaded) < priorityOf(lastCastUrl)
                || baseOf(preloaded).equals(baseOf(lastCastUrl))) return false;
        CastSession s = castContext == null ? null
                : castContext.getSessionManager().getCurrentCastSession();
        if (s == null || !s.isConnected()) return false;
        toast("自动连播:正在投下一集…");
        episodeHint = ""; // 换集了,旧集数作废,交给新页面标题
        loadMedia(s, preloaded, 0);
        return true;
    }

    // 让网页播放器跳到片尾正常播完,触发网站自带连播;接不上时的兜底与超时见
    // nextFallback / nextTimeout。
    private void triggerPageNext(long fallbackDelayMs, long timeoutMs) {
        web.evaluateJavascript(jsSetPauseGuard(false) // 要让页面播到片尾,先解除暂停守卫
                + "(function(){" + jsCollectMedia("video") + JS_PICK_LONGEST
                + "if(!_v)return;_v.muted=true;"
                + "if(isFinite(_v.duration)&&_v.duration>1){try{_v.currentTime=Math.max(0,_v.duration-0.6);}catch(e){}}"
                + "try{_v.play();}catch(e){}})();", null);
        uiHandler.postDelayed(nextFallback, fallbackDelayMs);
        uiHandler.postDelayed(nextTimeout, timeoutMs);
    }

    // 结束“等下一集”状态,撤掉兜底与超时任务。
    private void stopAutoNextWait() {
        awaitingNext = false;
        uiHandler.removeCallbacks(nextTimeout);
        uiHandler.removeCallbacks(nextFallback);
    }

    // 电视端拉流失败(常见原因:直链带防盗链、HLS/DASH 缺 CORS 头)时提示一次,给出替代方案。
    private void notifyIfPlaybackError() {
        if (remoteClient == null || playErrorToasted) return;
        if (remoteClient.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE
                && remoteClient.getIdleReason() == MediaStatus.IDLE_REASON_ERROR) {
            // 先试自动换线:部分站的 master 总表里分支是相对路径、token 只在网页选中的
            // 那条分支上,电视按 master 去拿会被拒;换回固定分支往往就能播。
            if (tryVariantFallback("电视拉流失败,自动换固定码率线路重试…")) return;
            if (errorAwaitingVariant) return; // 已在等固定分支出现,别重复提示
            // master 失败但固定分支可能还没被网页请求到:先等一会儿,拿到再自动换线,
            // 12 秒没等到才给终态提示(见 errorGiveUp 与 shouldInterceptRequest 的重试)。
            if (!preferVariant && !fallbackTried && altStreamUrl == null) {
                errorAwaitingVariant = true;
                uiHandler.postDelayed(errorGiveUp, 12000);
                return;
            }
            playErrorToasted = true;
            toast("电视拉流失败:该站直链可能有防盗链/跨域限制,建议改用电脑 Chrome 的「投放标签页」");
        }
    }

    // 把备用的固定码率分支投出去(从电视当前位置续播);没有备用或已试过则返回 false。
    private boolean tryVariantFallback(String why) {
        String target = altStreamUrl;
        if (fallbackTried || preferVariant || target == null || target.equals(lastCastUrl)) return false;
        CastSession s = castContext == null ? null
                : castContext.getSessionManager().getCurrentCastSession();
        if (s == null || !s.isConnected()) return false;
        fallbackTried = true;
        preferVariant = true; // 这次运行里之后的集数直接用固定分支,不再拿 master 试错
        toast(why);
        loadMedia(s, target, Math.max(0, lastTvPosMs));
        // 换线后把“当前直链”指向固定分支,别让失败的 master 之后被误当成预取的下一集投出
        detectedUrl = target;
        detectedPriority = priorityOf(target);
        altStreamUrl = null;
        return true;
    }

    // 开播后头 2 分半盯着缓冲情况:开播即卡、反复缓冲、或一次卡太久,就自动切固定码率线路。
    private void trackBufferingAndMaybeFallback() {
        if (remoteClient == null || castStartAt == 0) return;
        long now = SystemClock.elapsedRealtime();
        int ps = remoteClient.getPlayerState();
        if (ps == MediaStatus.PLAYER_STATE_PLAYING) {
            playbackStarted = true;
            if (bufferingSince > 0) { bufferedAccumMs += now - bufferingSince; bufferingSince = 0; }
        } else if (ps == MediaStatus.PLAYER_STATE_BUFFERING && playbackStarted) {
            if (bufferingSince == 0) { bufferingSince = now; bufferSpells++; }
        }
        if (preferVariant || now - castStartAt > 150000) return;
        long stuckMs = bufferingSince > 0 ? now - bufferingSince : 0;
        boolean neverStarted = !playbackStarted && now - castStartAt > 25000
                && (ps == MediaStatus.PLAYER_STATE_BUFFERING || ps == MediaStatus.PLAYER_STATE_LOADING);
        if (neverStarted || bufferedAccumMs + stuckMs > 15000 || bufferSpells >= 4 || stuckMs > 10000) {
            tryVariantFallback("缓冲频繁,自动切到固定码率线路…");
        }
    }

    // 直链优先级:m3u8/mpd(正片播放列表)=2,mp4 等单文件=1,非直链=0。
    private static int priorityOf(String u) {
        if (u == null) return 0;
        Matcher m = STREAM.matcher(u);
        if (!m.find()) return 0;
        String ext = m.group(1).toLowerCase(Locale.US);
        return ("m3u8".equals(ext) || "mpd".equals(ext)) ? 2 : 1;
    }

    // 只放行 http(s) 的页面导航。
    private static boolean isHttp(String u) {
        if (u == null) return false;
        String s = u.toLowerCase(Locale.US);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String noFragment(String u) {
        return u == null ? "" : u.split("#")[0];
    }

    // 探测页面媒体形态:2=blob/加密流(嗅探不到直链),1=有普通视频,0=没有视频。
    private static String jsProbeMedia() {
        return "(function(){" + jsCollectMedia("video")
                + "var blob=0;for(var i=0;i<_vs.length;i++){var s=(_vs[i].currentSrc||_vs[i].src||'');"
                + "if(s.indexOf('blob:')===0||_vs[i].mediaKeys){blob=1;break;}}"
                + "return blob?2:(_vs.length?1:0);})();";
    }

    // 停止投屏/会话断开时,把电视端进度写回网页播放器(仅当网页里还是同一部片:时长差 5 秒内)。
    private void syncBackToPage() {
        if (web == null || lastTvPosMs <= 0 || lastTvDurMs <= 0) return;
        double pos = lastTvPosMs / 1000.0;
        double dur = lastTvDurMs / 1000.0;
        web.evaluateJavascript("(function(){" + jsCollectMedia("video") + JS_PICK_LONGEST
                + "if(!_v||!isFinite(_v.duration))return;"
                + "if(Math.abs(_v.duration-" + dur + ")>5)return;"
                + "try{_v.currentTime=" + pos + ";}catch(e){}})();", null);
    }

    private static String baseOf(String u) {
        return u == null ? "" : u.split("[?#]")[0];
    }

    // JS 片段:遍历主页面和所有同源 iframe,把匹配 selector 的媒体元素收进 _vs
    //(很多站的播放器在 iframe 里;跨域 iframe 访问不到,只能跳过)。
    private static String jsCollectMedia(String selector) {
        return "var _vs=[];(function _c(w){try{var l=w.document.querySelectorAll('" + selector + "');"
                + "for(var i=0;i<l.length;i++)_vs.push(l[i]);}catch(e){}"
                + "try{for(var j=0;j<w.frames.length;j++)_c(w.frames[j]);}catch(e){}})(window);";
    }

    // JS 片段:从 _vs 里挑时长最长的当正片(广告/预告一般更短),存到 _v。
    private static final String JS_PICK_LONGEST =
            "var _v=null,_d=-1;for(var i=0;i<_vs.length;i++){var x=_vs[i],"
                    + "dd=isFinite(x.duration)?x.duration:0;if(dd>_d){_d=dd;_v=x;}}";

    // 打开/关闭“保持暂停”守卫(Java 侧薄封装,供停止投屏 / 投放被拒 / 会话结束等路径调用)。
    private void setLocalPauseGuard(boolean on) {
        if (web != null) web.evaluateJavascript(jsSetPauseGuard(on), null);
    }

    // 投屏成功后,把 App 内网页里正在放的音视频暂停并静音(含同源 iframe),并挂上
    // “保持暂停”守卫:不少站的播放器过几秒会自己恢复播放,单次暂停压不住,守卫监听
    // play 事件立刻再按停。守卫在停止投屏 / 触发连播时解除。
    private void pauseLocalPlayback() {
        if (web == null) return;
        web.evaluateJavascript("(function(){" + jsCollectMedia("video,audio")
                + "for(var i=0;i<_vs.length;i++){try{_vs[i].pause();_vs[i].muted=true;}catch(e){}}})();"
                + jsSetPauseGuard(true), null);
    }

    // 打开/关闭“保持暂停”守卫(含同源 iframe):每个文档只装一次 play 捕获监听,
    // 用 _chGuard 开关控制;play 事件不冒泡但会经过捕获阶段,document 上能截到。
    private static String jsSetPauseGuard(boolean on) {
        return "(function _g(w){try{w._chGuard=" + (on ? "1" : "0") + ";"
                + "if(!w._chGuardInit){w._chGuardInit=1;"
                + "w.document.addEventListener('play',function(ev){"
                + "if(w._chGuard){try{ev.target.pause();ev.target.muted=true;}catch(e){}}"
                + "},true);}}catch(e){}"
                + "try{for(var j=0;j<w.frames.length;j++)_g(w.frames[j]);}catch(e){}})(window);";
    }

    private static final Pattern EPISODE = Pattern.compile(
            "第\\s*[0-9一二三四五六七八九十百零]+\\s*[集话話期]|[Ee][Pp]?\\s*\\d{1,4}|\\d{1,3}\\s*[集话話期]");

    // 投屏标题:用当前网页标题,截掉“站名/在线观看”等后缀,尽量留下剧名 + 集数。
    // 分隔符含无空格连字符(爱壹帆「剧名-免费在线观看-爱壹帆」)、下划线、竖线、破折号。
    // 标题里没有集数时,用从页面选中集读到的 episodeHint 补上。
    private String castTitle() {
        String t = web == null ? null : web.getTitle();
        if (t != null) t = t.trim();
        if (TextUtils.isEmpty(t) || t.startsWith("http")) return "投屏";
        String[] parts = t.split("\\s*[_|·]\\s*|\\s*[-–—]\\s*");
        String out = parts[0].trim();
        if (out.length() < 2) out = t; // 首段太短(没按预期分隔),退回整串
        if (!EPISODE.matcher(out).find()) {
            String ep = null;
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].replaceAll("(在线观看|免费观看|高清|完整版|无删减).*$", "").trim();
                // “全30集 / 更新至10集 / 共24集”是总集数,不是当前集数,跳过
                if (p.isEmpty() || p.contains("全") || p.contains("更新") || p.contains("共")) continue;
                if (p.length() <= 20 && EPISODE.matcher(p).find()) { ep = p; break; }
            }
            if (ep == null && !TextUtils.isEmpty(episodeHint) && EPISODE.matcher(episodeHint).find()) {
                ep = episodeHint; // 标题不含集数(如爱壹帆),用页面里读到的选中集
            }
            if (ep != null) out = out + " " + ep;
        }
        if (out.length() > 60) out = out.substring(0, 60) + "…";
        return out;
    }

    // 采集当前正片:进度(秒)+ 页面“选中集”的第N集文案 + 该 <video> 自己暴露的 http(s) 直链。
    // 集数从带 active/current/playing/selected/on 等类名、且文本很短又形如“第N集”的元素里取,
    // 尽量避免误抓到导航等无关元素;直链取 currentSrc(blob/MSE 则为空,回退到嗅探直链)。
    // 返回 JSON 数组 [pos, ep, src]。
    private static String jsProbeActive() {
        return "(function(){" + jsCollectMedia("video") + JS_PICK_LONGEST
                + "var pos=(_v&&isFinite(_v.duration)&&_v.duration>1)?Math.floor(_v.currentTime||0):0;"
                + "var ep='';try{var ds=[document];(function gd(w){try{for(var j=0;j<w.frames.length;j++){"
                + "var fw=w.frames[j],d=fw.document;if(d){ds.push(d);gd(fw);}}}catch(e){}})(window);"
                + "var re=/第\\s*[0-9一二三四五六七八九十百零]+\\s*[集话話期]|[Ee][Pp]\\s*\\d{1,4}/;"
                + "var sel='[class*=\"active\"],[class*=\"cur\"],[class*=\"playing\"],[class*=\"selected\"],.on,.act';"
                + "for(var di=0;di<ds.length&&!ep;di++){var els=ds[di].querySelectorAll(sel);"
                + "for(var i=0;i<els.length;i++){var tx=(els[i].textContent||'').replace(/\\s+/g,'');"
                + "if(tx.length<=10){var m=tx.match(re);if(m){ep=m[0];break;}}}}"
                + "}catch(e){}"
                + "var src='';try{if(_v){var ss=(_v.currentSrc||_v.src||'');if(/^https?:/i.test(ss))src=ss;}}catch(e){}"
                + "return [pos,ep,src];})();";
    }

    // 把遥控条挂到当前会话的 RemoteMediaClient 上(注册状态回调与进度监听)。
    private void bindMediaClient() {
        RemoteMediaClient client = null;
        CastSession session = null;
        if (castContext != null) {
            session = castContext.getSessionManager().getCurrentCastSession();
            if (session != null) client = session.getRemoteMediaClient();
        }
        if (session != castSession) {
            if (castSession != null) {
                try { castSession.removeCastListener(castListener); } catch (Exception ignored) {}
            }
            if (session != null) session.addCastListener(castListener);
            castSession = session;
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
        if (castSession != null) {
            try { castSession.removeCastListener(castListener); } catch (Exception ignored) {}
        }
        castSession = null;
        stopAutoNextWait();
        errorAwaitingVariant = false;
        uiHandler.removeCallbacks(errorGiveUp);
        setLocalPauseGuard(false); // 会话没了,解除本地暂停守卫
    }

    private void refreshControls() {
        if (controls == null) return;
        boolean has = remoteClient != null && remoteClient.hasMediaSession();
        controls.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) return;
        btnPlayPause.setText(remoteClient.isPaused() ? "▶ 播放" : "⏸ 暂停");
        if (tvStatus != null) {
            String name = null;
            try {
                if (castSession != null && castSession.getCastDevice() != null) {
                    name = castSession.getCastDevice().getFriendlyName();
                }
            } catch (Exception ignored) {}
            int ps = remoteClient.getPlayerState();
            String st = ps == MediaStatus.PLAYER_STATE_BUFFERING ? "缓冲中…"
                    : ps == MediaStatus.PLAYER_STATE_LOADING ? "加载中…"
                    : ps == MediaStatus.PLAYER_STATE_PAUSED ? "已暂停" : "播放中";
            tvStatus.setText(name == null ? "📺 " + st : "📺 " + name + " · " + st);
        }
        if (castSession != null) {
            try {
                if (!userVolDragging) volSeek.setProgress((int) Math.round(castSession.getVolume() * 100));
                btnMute.setText(castSession.isMute() ? "🔊 取消静音" : "🔇 静音");
            } catch (Exception ignored) {}
        }
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
        @Override public void onSessionEnded(CastSession session, int error) { syncBackToPage(); unbindMediaClient(); refreshControls(); }
        @Override public void onSessionSuspended(CastSession session, int reason) { unbindMediaClient(); refreshControls(); }
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
        if (web != null) web.onResume();
        bindMediaClient();
    }

    @Override
    protected void onPause() {
        // 电视真在放(或正等着接下一集)时保持 WebView 运行:自动连播靠它在后台加载下一集;
        // 其余情况(没投、已停止、连着电视但没投片)暂停页面计时器和播放,免得后台白放耗电。
        boolean casting = remoteClient != null && remoteClient.hasMediaSession()
                && remoteClient.getPlayerState() != MediaStatus.PLAYER_STATE_IDLE;
        if (web != null && !casting && !awaitingNext) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (castContext != null && sessionListener != null) {
            castContext.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class);
        }
        unbindMediaClient();
        uiHandler.removeCallbacksAndMessages(null);
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
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
