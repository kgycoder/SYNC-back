package com.sync.app;

import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 프로세스 단위로 살아있는 "재생 엔진".
 *
 * 기존에는 WebView(= YouTube IFrame + app.js 세션) 와 JS 브리지가 MainActivity 에 묶여 있어서
 * 최근 앱 목록에서 앱을 닫으면(Activity 파괴) WebView 가 함께 파괴되고, 재생·큐·세션이 모두 사라졌다.
 *
 * 이제 WebView / 브리지 / MediaSession / 네트워크 로직은 이 엔진이 소유한다.
 *  - Activity 는 엔진의 WebView 를 "빌려 쓰는" 화면(host)일 뿐이다.
 *  - Activity 가 파괴되어도 WebView 는 파괴되지 않고, 포그라운드 서비스(MusicService)가 프로세스를 유지한다.
 *  - 앱을 다시 열면 새 Activity 가 같은 WebView 를 다시 붙인다 → 재생/큐/진행 위치가 그대로 이어진다.
 *
 * 모든 public/package 메서드는 별도 표기가 없으면 메인 스레드에서 호출한다.
 */
public final class SyncEngine {

    private static final String TAG = "SYNC";
    private static final String START_URL =
            "https://appassets.androidplatform.net/assets/www/index.html";

    /** 재생이 멈춘 채 화면도 없는 상태로 이 시간이 지나면 서비스/엔진을 정리한다. */
    private static final long IDLE_STOP_MS = 10L * 60L * 1000L;
    /** WakeLock 은 타임아웃을 두고 재생 중 계속 갱신한다(무한 보유 방지). */
    private static final long WAKELOCK_MS = 30L * 60L * 1000L;

    /**
     * 백그라운드/화면 꺼짐에서도 YouTube IFrame 이 "숨김" 상태로 인식되어 스스로 일시정지하지 않도록
     * 모든 프레임(YouTube iframe 포함)에서 가시성 API 를 항상 visible 로 고정한다.
     */
    private static final String KEEP_VISIBLE_JS =
            "(function(){try{" +
            "var d=document;" +
            "function def(o,k,v){try{Object.defineProperty(o,k,{configurable:true,get:function(){return v;}});}catch(e){}}" +
            "def(d,'hidden',false);def(d,'visibilityState','visible');" +
            "def(d,'webkitHidden',false);def(d,'webkitVisibilityState','visible');" +
            "var stop=function(e){e.stopImmediatePropagation();};" +
            "d.addEventListener('visibilitychange',stop,true);" +
            "d.addEventListener('webkitvisibilitychange',stop,true);" +
            "window.addEventListener('visibilitychange',stop,true);" +
            "window.addEventListener('pagehide',stop,true);" +
            "}catch(e){}})();";

    // ── singleton ────────────────────────────────────────────────────────────
    private static volatile SyncEngine instance;

    /** 엔진을 가져온다(없으면 생성). 반드시 메인 스레드에서 호출. */
    static synchronized SyncEngine get(Context context) {
        if (instance == null) instance = new SyncEngine(context.getApplicationContext());
        return instance;
    }

    /** 이미 살아있는 엔진만 반환(없으면 null). 어느 스레드에서든 호출 가능. */
    static SyncEngine peek() { return instance; }

    // ── members ──────────────────────────────────────────────────────────────
    private final Context appCtx;
    private final MutableContextWrapper webCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WebView webView;
    private final WebViewAssetLoader assetLoader;
    private final LocalMediaStore localMediaStore;
    private final MediaSessionHelper mediaSessionHelper;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(true)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // 현재 화면(없을 수 있음) — 메인 스레드에서만 접근
    private MainActivity activity;
    private boolean activityResumed = false;
    private String lastOrientation = null;

    // 재생 상태(JS 가 보고한 값)
    private volatile boolean isPlaying = false;
    private String lastTitle    = "SYNC";
    private String lastArtist   = "";
    private String lastVideoId  = "";
    private double lastPosition = 0.0;
    private final java.util.concurrent.ConcurrentHashMap<String, String> streamUrlCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    // 잠금/서비스
    private PowerManager.WakeLock wakeLock;
    private long wakeLockAcquiredAt = 0;
    private WifiManager.WifiLock wifiLock;
    private long lastServiceStartRequest = 0;
    private boolean nativeFallbackActive = false;
    private boolean released = false;

    private final Runnable idleStopTask = () -> shutdown(false);

    @android.annotation.SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private SyncEngine(Context app) {
        appCtx = app;
        webCtx = new MutableContextWrapper(app);
        webView = new WebView(webCtx);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        localMediaStore = new LocalMediaStore(app.getFilesDir());

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                        new WebViewAssetLoader.AssetsPathHandler(app))
                .addPathHandler("/res/",
                        new WebViewAssetLoader.ResourcesPathHandler(app))
                .build();

        executor.submit(() -> {
            try {
                LocalMediaDownloader.ensureInitialized(getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "yt-dlp pre-init failed (will retry on download)", e);
            }
        });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setDefaultTextEncodingName("UTF-8");
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36");

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(Color.parseColor("#08080D"));

        // Keep WebView renderer at high priority even when not visible.
        webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false  // do NOT waive priority when the WebView is not visible
        );

        // 모든 프레임에서 "항상 visible" 로 보이게 한다(YouTube iframe 의 자동 일시정지 방지).
        // 미지원 WebView 에서는 조용히 건너뛰고, 기존 JS 자동 재개 로직이 보조한다.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                        webView, KEEP_VISIBLE_JS, new HashSet<>(Arrays.asList("*")));
            }
        } catch (Throwable t) {
            Log.w(TAG, "visibility keep-alive script not installed", t);
        }

        mediaSessionHelper = new MediaSessionHelper(app, cmd ->
                main.post(() -> dispatchMediaCommand(cmd)));

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path != null && path.startsWith("/local/")) {
                        String videoId = path.substring("/local/".length());
                        if (!videoId.isEmpty() && !videoId.contains("..")) {
                            File file = localMediaStore.findMediaFile(videoId);
                            if (file != null) {
                                try {
                                    return LocalMediaStore.openWithRange(file, request);
                                } catch (IOException e) {
                                    Log.e(TAG, "local media range failed", e);
                                }
                            }
                        }
                    }
                }

                WebResourceResponse response =
                        assetLoader.shouldInterceptRequest(uri);
                if (response == null) return null;

                // AssetLoader 응답의 MIME만 가져오고
                // encoding을 UTF-8로 명시한 2인수 생성자 사용 (NPE 없음)
                String mime = response.getMimeType();
                if (mime == null) mime = "text/plain";
                // charset이 이미 붙어 있으면 제거 후 재지정
                if (mime.contains(";")) mime = mime.substring(0, mime.indexOf(";")).trim();

                String encoding = (mime.startsWith("text/") || mime.contains("javascript")
                        || mime.contains("json") || mime.contains("xml"))
                        ? "UTF-8" : null;
                return new WebResourceResponse(mime, encoding, response.getData());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return false;
            }

            /**
             * WebView 렌더러 프로세스가 시스템에 의해 종료된 경우.
             * 페이지(JS 세션)는 사라지지만, 재생 중이었다면 MusicService 의 네이티브
             * MediaPlayer 가 같은 위치에서 이어서 재생해 소리가 끊기지 않게 한다.
             */
            @Override
            public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "WebView renderer killed — reloading page, native fallback if playing.");
                try {
                    startNativeFallbackIfPlaying();
                } catch (Exception e) {
                    Log.e(TAG, "native fallback failed", e);
                }
                try {
                    view.loadUrl(START_URL);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to reload after renderer death", e);
                }
                return true; // we handled it — don't crash the host activity
            }
        });

        webView.loadUrl(START_URL);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Activity(화면) 연결
    // ═════════════════════════════════════════════════════════════════════════

    WebView getWebView() { return webView; }

    String getLastOrientation() { return lastOrientation; }

    /** 새 Activity 가 만들어질 때: WebView 를 이전 부모에서 떼어 이 Activity 에 넘길 준비를 한다. */
    void attachActivity(MainActivity a) {
        activity = a;
        activityResumed = false;
        webCtx.setBaseContext(a);
        ViewParent p = webView.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(webView);
        main.removeCallbacks(idleStopTask);
    }

    /** Activity 가 파괴될 때: WebView 는 파괴하지 않고 떼어 두기만 한다(재생 유지). */
    void detachActivity(MainActivity a) {
        if (activity != a) return;   // 이미 다른 Activity 가 인계받은 경우
        activity = null;
        activityResumed = false;
        ViewParent p = webView.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(webView);
        webCtx.setBaseContext(appCtx);   // Activity 누수 방지
        webView.evaluateJavascript("window.__syncInBg=true;", null);
        if (isPlaying) {
            ensureService();
            acquireLocks();
        } else {
            scheduleIdleStop();
        }
    }

    void onActivityResumed(MainActivity a) {
        if (activity != a) return;
        activityResumed = true;
        main.removeCallbacks(idleStopTask);
        webView.onResume();
        // Tell JS we're back in foreground so auto-resume stops triggering
        webView.evaluateJavascript(
                "window.__syncInBg=false;window.__userBgPause=false;", null);
    }

    void onActivityPaused(MainActivity a) {
        if (activity != a) return;
        activityResumed = false;
        // NEVER call webView.onPause(): JS 와 YouTube IFrame 이 즉시 멈춘다.
        webView.evaluateJavascript("window.__syncInBg=true;", null);
        if (isPlaying) {
            ensureService();
            acquireLocks();
        } else {
            scheduleIdleStop();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  JS 브리지 (Activity 와 무관하게 동작)
    // ═════════════════════════════════════════════════════════════════════════

    public class AndroidBridge {
        @JavascriptInterface
        public boolean hasLocalMedia(String videoId) {
            return localMediaStore.findMediaFile(videoId) != null;
        }

        @JavascriptInterface
        public void postMessage(String json) {
            try {
                JSONObject msg = new JSONObject(json);
                String type = msg.optString("type");
                switch (type) {
                    case "search":
                        executor.submit(() -> doSearch(msg)); break;
                    case "suggest":
                        executor.submit(() -> doSuggest(msg)); break;
                    case "fetchLyrics":
                        executor.submit(() -> doFetchLyrics(msg)); break;
                    case "downloadVideo":
                        executor.submit(() -> doDownloadVideo(msg)); break;
                    case "mediaState":
                        main.post(() -> onMediaState(msg));
                        break;
                    case "orientation":
                        final String orient = msg.optString("value", "sensor");
                        main.post(() -> {
                            lastOrientation = orient;
                            if (activity != null) activity.setOrientation(orient);
                        });
                        break;
                    default: break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "postMessage parse error", e);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                Log.w(TAG, "engine released; message dropped");
            }
        }
    }

    private void sendToJs(JSONObject payload) {
        String b64 = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        // atob()는 Latin-1만 처리 → TextDecoder로 UTF-8 명시 디코딩
        String js =
            "(function(){" +
            "  var b=atob('" + b64 + "');" +
            "  var bytes=new Uint8Array(b.length);" +
            "  for(var i=0;i<b.length;i++) bytes[i]=b.charCodeAt(i);" +
            "  var s=new TextDecoder('utf-8').decode(bytes);" +
            "  window.__sync && window.__sync(s);" +
            "})();";
        main.post(() -> { if (!released) webView.evaluateJavascript(js, null); });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  재생 상태 / 백그라운드 유지
    // ═════════════════════════════════════════════════════════════════════════

    private void onMediaState(JSONObject msg) {
        if (released) return;
        if (mediaSessionHelper != null) mediaSessionHelper.update(msg);
        boolean nowPlaying = msg.optBoolean("playing", false);
        lastTitle    = msg.optString("title",    lastTitle);
        lastArtist   = msg.optString("artist",   lastArtist);
        lastPosition = msg.optDouble("position", lastPosition);
        String vid   = msg.optString("videoId",  "");
        if (!vid.isEmpty() && !vid.equals(lastVideoId)) {
            // New track — evict old URL and start pre-fetching
            lastVideoId = vid;
            streamUrlCache.remove(vid);
            prefetchStreamUrl(vid);
        } else if (!vid.isEmpty() && !streamUrlCache.containsKey(vid)) {
            prefetchStreamUrl(vid); // retry if previous fetch failed
        }
        isPlaying = nowPlaying;

        if (nowPlaying) {
            // 새 페이지(WebView)가 재생을 보고했다 → 네이티브 대체 재생은 더 이상 필요 없음(이중 재생 방지)
            stopNativeFallback();
            ensureService();
            acquireLocks();
            main.removeCallbacks(idleStopTask);
        } else {
            releaseLocks();
            if (!activityResumed) scheduleIdleStop();
        }
        refreshServiceNotification();
    }

    /** 알림/잠금화면/이어폰의 play·pause·next·prev 명령 → JS 로 전달. */
    void dispatchMediaCommand(String cmd) {
        if (released || cmd == null) return;
        if (!("play".equals(cmd) || "pause".equals(cmd)
                || "next".equals(cmd) || "prev".equals(cmd))) return;

        if (nativeFallbackActive) {
            // 렌더러가 죽어 네이티브 플레이어만 재생 중인 상태: 일시정지만 지원
            if ("pause".equals(cmd)) {
                stopNativeFallback();
                isPlaying = false;
                refreshServiceNotification();
            }
            return;
        }
        // Tell JS whether this was a user-initiated pause/play so that the background
        // auto-resume logic can distinguish involuntary YouTube auto-pauses from explicit user commands.
        if ("pause".equals(cmd)) {
            webView.evaluateJavascript("window.__userBgPause=true;", null);
        } else {
            webView.evaluateJavascript("window.__userBgPause=false;", null);
        }
        webView.evaluateJavascript(
                "window.__mediaCmd&&window.__mediaCmd('" + cmd + "')", null);
    }

    // ── MusicService 연동 ────────────────────────────────────────────────────

    String getLastTitle()  { return lastTitle; }
    String getLastArtist() { return lastArtist; }
    boolean isPlayingNow() { return isPlaying; }
    android.support.v4.media.session.MediaSessionCompat.Token getSessionToken() {
        return mediaSessionHelper != null ? mediaSessionHelper.getSessionToken() : null;
    }

    private void ensureService() {
        if (released || MusicService.instance != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastServiceStartRequest < 3000) return;   // 시작 요청 중복 방지
        lastServiceStartRequest = now;
        try {
            ContextCompat.startForegroundService(appCtx, new Intent(appCtx, MusicService.class));
        } catch (RuntimeException e) {
            // 백그라운드에서 시작이 거부된 경우 등 — 앱은 계속 동작해야 한다
            Log.w(TAG, "startForegroundService refused", e);
        }
    }

    private void refreshServiceNotification() {
        MusicService s = MusicService.instance;
        if (s == null) return;
        s.updateNotification(lastTitle, lastArtist, isPlaying, getSessionToken());
    }

    /** 알림의 닫기(삭제) 동작 — 일시정지 상태에서만 알림을 지울 수 있다. */
    void onStopRequested() {
        shutdown(true);
    }

    private void scheduleIdleStop() {
        main.removeCallbacks(idleStopTask);
        main.postDelayed(idleStopTask, IDLE_STOP_MS);
    }

    /**
     * 서비스·잠금을 정리한다. 화면(Activity)이 없으면 WebView 까지 완전히 해제한다.
     * 재생 중이거나 화면이 보이는 상태에서는(force 가 아니면) 아무 것도 하지 않는다.
     */
    private void shutdown(boolean force) {
        if (released) return;
        if (!force && (isPlaying || activityResumed)) return;
        stopNativeFallback();
        releaseLocks();
        try {
            appCtx.stopService(new Intent(appCtx, MusicService.class));
        } catch (RuntimeException ignored) { }
        if (activity == null) release();
    }

    private void release() {
        if (released) return;
        released = true;
        synchronized (SyncEngine.class) {
            if (instance == this) instance = null;
        }
        main.removeCallbacksAndMessages(null);
        releaseLocks();
        executor.shutdown();
        try { mediaSessionHelper.release(); } catch (Exception ignored) { }
        try { com.sync.app.glass.GlassHost.release(); } catch (Throwable ignored) { }
        try {
            ViewParent p = webView.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(webView);
            webView.destroy();
        } catch (Exception ignored) { }
    }

    // ── WakeLock / WifiLock ──────────────────────────────────────────────────

    private void acquireLocks() {
        try {
            long now = SystemClock.elapsedRealtime();
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SYNC::MusicWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && (!wakeLock.isHeld()
                    || now - wakeLockAcquiredAt > WAKELOCK_MS / 2)) {
                wakeLock.acquire(WAKELOCK_MS);   // 재획득 시 타임아웃이 갱신된다
                wakeLockAcquiredAt = now;
            }
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    //noinspection deprecation
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SYNC::WifiLock");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "acquireLocks failed", e);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) { }
    }

    // ── 렌더러 사망 시 네이티브 대체 재생 ─────────────────────────────────────

    private void startNativeFallbackIfPlaying() {
        if (!isPlaying || lastVideoId.isEmpty()) return;
        final String vid = lastVideoId;
        final double pos = lastPosition;
        ensureService();
        String cached = streamUrlCache.get(vid);
        if (cached != null) {
            startNativeFallback(cached, pos);
        } else {
            executor.submit(() -> {
                try {
                    String url = fetchStreamUrlInnerTube(vid);
                    if (url != null && !url.isEmpty()) {
                        streamUrlCache.put(vid, url);
                        main.post(() -> { if (isPlaying) startNativeFallback(url, pos); });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "On-demand stream URL fetch failed for: " + vid, e);
                }
            });
        }
    }

    private void startNativeFallback(String url, double pos) {
        MusicService s = MusicService.instance;
        if (s == null || released) return;
        s.startBgAudio(url, pos);
        nativeFallbackActive = true;
    }

    private void stopNativeFallback() {
        if (!nativeFallbackActive) return;
        nativeFallbackActive = false;
        MusicService s = MusicService.instance;
        if (s != null) s.stopBgAudio();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  네트워크 로직 (MainActivity 에서 그대로 이동 — 내용 변경 없음)
    // ═════════════════════════════════════════════════════════════════════════

    private Context getApplicationContext() { return appCtx; }

    private void doSearch(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
            String URL = "https://www.youtube.com/youtubei/v1/search?key="
                       + KEY + "&prettyPrint=false";

            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20240101.00.00");
            client.put("hl", "ko");
            client.put("gl", "KR");
            JSONObject context = new JSONObject();
            context.put("client", client);
            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("query", query);
            // EgIQAQ== = 영상 검색 필터 (URL인코딩 제거)
            body.put("params", "EgIQAQ==");

            Request req = new Request.Builder()
                    .url(URL)
                    .post(RequestBody.create(
                            body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20240101.00.00")
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/")
                    .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IOException("HTTP " + resp.code());
                JSONArray tracks = parseSearchResults(readUtf8(resp));
                JSONObject result = new JSONObject();
                result.put("type", "searchResult");
                result.put("id", id);
                result.put("success", true);
                result.put("tracks", tracks);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "searchResult");
                err.put("id", id);
                err.put("success", false);
                err.put("error", e.getMessage());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private JSONArray parseSearchResults(String json) throws JSONException {
        JSONArray list = new JSONArray();
        JSONObject doc = new JSONObject(json);
        if (!doc.has("contents")) return list;

        JSONArray sections;
        try {
            sections = doc
                    .getJSONObject("contents")
                    .getJSONObject("twoColumnSearchResultsRenderer")
                    .getJSONObject("primaryContents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
        } catch (JSONException e) { return list; }

        for (int s = 0; s < sections.length() && list.length() < 20; s++) {
            JSONObject sec = sections.getJSONObject(s);
            if (!sec.has("itemSectionRenderer")) continue;
            JSONArray items = sec.getJSONObject("itemSectionRenderer")
                               .getJSONArray("contents");

            for (int k = 0; k < items.length() && list.length() < 20; k++) {
                JSONObject item = items.getJSONObject(k);
                if (!item.has("videoRenderer")) continue;
                JSONObject vr = item.getJSONObject("videoRenderer");
                String vid = vr.optString("videoId", "");
                if (vid.isEmpty()) continue;

                String title = extractText(vr, "title");
                String ch = extractText(vr, "ownerText");
                if (ch.isEmpty()) ch = extractText(vr, "shortBylineText");

                String durStr = "";
                try {
                    if (vr.has("lengthText"))
                        durStr = vr.getJSONObject("lengthText").optString("simpleText", "");
                } catch (JSONException ignored2) {}

                int dur = parseDur(durStr);
                // 과거에는 자체 키워드 휴리스틱(isMusicVideo)으로 결과를
                // 한 번 더 걸러냈지만, 이는 실제 유튜브에는 존재하는
                // 음악 영상이 SYNC 검색에는 나오지 않는 원인이었다.
                // params=EgIQAQ== 로 이미 "동영상" 타입으로 필터링된,
                // 즉 실제 유튜브 검색(동영상 필터)과 동일한 응답이므로
                // 유튜브가 반환한 순서/구성 그대로 전달한다.

                JSONObject t = new JSONObject();
                t.put("id", vid);
                t.put("title", title);
                t.put("channel", ch);
                t.put("dur", dur);
                t.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
                list.put(t);
            }
        }
        return list;
    }

    private String extractText(JSONObject vr, String key) {
        try {
            if (!vr.has(key)) return "";
            JSONObject obj = vr.getJSONObject(key);
            if (obj.has("runs"))
                return obj.getJSONArray("runs").getJSONObject(0).optString("text", "");
            return obj.optString("simpleText", "");
        } catch (JSONException e) { return ""; }
    }

    private int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600
                    + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60
                    + Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private void doSuggest(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String url = "https://suggestqueries.google.com/complete/search"
                    + "?client=firefox&ds=yt&q="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&hl=ko";
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 Firefox/124.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) throw new IOException("empty");
                String json = readUtf8(resp);
                if (json.startsWith("window."))
                    json = json.replaceFirst("^[^(]+\\(", "")
                               .replaceFirst("\\)\\s*$", "");
                JSONArray arr  = new JSONArray(json);
                JSONArray sugs = new JSONArray();
                if (arr.length() > 1) {
                    JSONArray inner = arr.getJSONArray(1);
                    for (int i = 0; i < inner.length() && sugs.length() < 8; i++) {
                        Object o = inner.get(i);
                        String sv = (o instanceof JSONArray)
                                ? ((JSONArray) o).optString(0, "") : o.toString();
                        if (!sv.isEmpty()) sugs.put(sv);
                    }
                }
                JSONObject result = new JSONObject();
                result.put("type", "suggestResult");
                result.put("id", id);
                result.put("success", true);
                result.put("suggestions", sugs);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "suggestResult");
                err.put("id", id);
                err.put("success", false);
                err.put("suggestions", new JSONArray());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private void doDownloadVideo(JSONObject msg) {
        String videoId = msg.optString("videoId");
        String id = msg.optString("id", "0");
        try {
            if (videoId.isEmpty()) throw new IOException("videoId 없음");

            File existing = localMediaStore.findMediaFile(videoId);
            if (existing != null) {
                JSONObject result = new JSONObject();
                result.put("type", "downloadResult");
                result.put("id", id);
                result.put("success", true);
                result.put("videoId", videoId);
                result.put("fileName", existing.getName());
                result.put("size", existing.length());
                sendToJs(result);
                return;
            }

            LocalMediaDownloader.download(
                    getApplicationContext(),
                    localMediaStore,
                    videoId,
                    percent -> {
                        try {
                            JSONObject prog = new JSONObject();
                            prog.put("type", "downloadProgress");
                            prog.put("videoId", videoId);
                            prog.put("percent", percent);
                            sendToJs(prog);
                        } catch (JSONException ignored) {}
                    });

            File saved = localMediaStore.findMediaFile(videoId);
            if (saved == null) throw new IOException("저장 파일 없음");

            JSONObject result = new JSONObject();
            result.put("type", "downloadResult");
            result.put("id", id);
            result.put("success", true);
            result.put("videoId", videoId);
            result.put("fileName", saved.getName());
            result.put("size", saved.length());
            sendToJs(result);
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + videoId, e);
            try {
                JSONObject err = new JSONObject();
                err.put("type", "downloadResult");
                err.put("id", id);
                err.put("success", false);
                err.put("videoId", videoId);
                err.put("error", e.getMessage() != null ? e.getMessage() : "다운로드 실패");
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private void doFetchLyrics(JSONObject msg) {
        String rawTitle = msg.optString("title");
        String channel  = msg.optString("channel");
        double dur      = msg.optDouble("duration", 0);
        String id       = msg.optString("id", "0");

        JSONArray lines = tryLrclib(rawTitle, channel, dur);
        if (lines == null) lines = tryNetEase(rawTitle, channel, dur);

        try {
            JSONObject result = new JSONObject();
            result.put("type", "lyricsResult");
            result.put("id", id);
            if (lines != null) {
                result.put("success", true);
                result.put("lines", lines);
            } else {
                result.put("success", false);
                result.put("lines", new JSONArray());
            }
            sendToJs(result);
        } catch (JSONException ignored) {}
    }

    // ════════════════════════════════════════════════════
    //  1차: lrclib.net — PC 버전 C# 동일 알고리즘 포팅
    // ════════════════════════════════════════════════════
    private JSONArray tryLrclib(String rawTitle, String channel, double ytDur) {
        try {
            String ct = cleanTitle(rawTitle);
            String ca = cleanArtist(channel);

            // ── 검색 쿼리 변형 목록 (PC 버전과 동일) ──
            String stripped = stripBrackets(ct);
            List<String> queries = new ArrayList<>();
            queries.add(ct + " " + ca);
            queries.add(ct);
            if (!stripped.equals(ct)) queries.add(stripped);
            if (!ca.isEmpty()) queries.add(ca + " " + ct);
            if (!ca.isEmpty() && !stripped.equals(ct)) queries.add(stripped + " " + ca);

            // synced 결과가 나올 때까지 순서대로 시도
            JSONArray results = new JSONArray();
            for (String q : queries) {
                JSONArray r = searchLrclib(q);
                if (hasSyncedResults(r)) { results = r; break; }
                // synced 없어도 결과가 있으면 후보로 보관
                if (results.length() == 0 && r.length() > 0) results = r;
            }

            if (results.length() == 0) return null;

            // ── 후보 채점 (PC 버전 동일 가중치) ──
            String bestLrc   = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);

                // syncedLyrics 우선, 없으면 plainLyrics도 시도
                String lrcText = item.optString("syncedLyrics", "");
                if (lrcText.isEmpty()) lrcText = item.optString("plainLyrics", "");
                if (lrcText.isEmpty()) continue;

                double lrcDur = getLrcLastTimestamp(lrcText);
                if (lrcDur <= 0) lrcDur = item.optDouble("duration", 0);

                double score = 0;
                if (ytDur > 0 && lrcDur > 0) {
                    double diff = Math.abs(lrcDur - ytDur);
                    if      (diff <= 3)  score += 50;
                    else if (diff <= 10) score += 35;
                    else if (diff <= 30) score += 15;
                    else if (diff <= 60) score += 5;
                    else                 score -= 25;
                }
                score += titleSim(ct, item.optString("trackName",  "")) * 30;
                score += titleSim(ca, item.optString("artistName", "")) * 20;
                // syncedLyrics 있으면 보너스
                if (!item.optString("syncedLyrics", "").isEmpty()) score += 10;

                if (score > bestScore) { bestScore = score; bestLrc = lrcText; }
            }
            return bestLrc != null ? parseLrc(bestLrc) : null;
        } catch (Exception e) { return null; }
    }

    private JSONArray searchLrclib(String query) {
        try {
            String url = "https://lrclib.net/api/search?q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "SYNCApp/1.0 (https://github.com/sync)")
                    .addHeader("Accept", "application/json")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return new JSONArray();
                Object parsed = new org.json.JSONTokener(readUtf8(resp)).nextValue();
                return parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
            }
        } catch (Exception e) { return new JSONArray(); }
    }

    private boolean hasSyncedResults(JSONArray arr) {
        if (arr == null) return false;
        try {
            for (int i = 0; i < arr.length(); i++)
                if (!arr.getJSONObject(i).optString("syncedLyrics", "").isEmpty())
                    return true;
        } catch (JSONException ignored) {}
        return false;
    }

    // ════════════════════════════════════════════════════
    //  2차: NetEase Cloud Music — 점수 하한 완화
    // ════════════════════════════════════════════════════
    private JSONArray tryNetEase(String rawTitle, String channel, double ytDur) {
        try {
            String ct = cleanTitle(rawTitle);
            String ca = cleanArtist(channel);

            // PC 버전과 동일한 쿼리 변형
            String stripped = stripBrackets(ct);
            List<String> queryList = new ArrayList<>();
            queryList.add(ct + " " + ca);
            queryList.add(ct);
            if (!stripped.equals(ct)) queryList.add(stripped);
            if (!ca.isEmpty()) queryList.add(ca + " " + ct);

            List<long[]>  ids    = new ArrayList<>();
            List<Double>  scores = new ArrayList<>();

            for (String q : queryList) {
                List<long[]>  tmpIds    = new ArrayList<>();
                List<Double>  tmpScores = new ArrayList<>();
                searchNetEase(q, ct, ca, ytDur, tmpIds, tmpScores);
                if (!tmpIds.isEmpty()) { ids = tmpIds; scores = tmpScores; break; }
            }
            if (ids.isEmpty()) return null;

            // 점수 내림차순 정렬
            Integer[] idx = new Integer[ids.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            final List<Double> fs = scores;
            Arrays.sort(idx, (a, b) -> Double.compare(fs.get(b), fs.get(a)));

            // 점수 하한 20으로 완화 (PC 버전 C#은 40이었으나 Android는 더 넉넉하게)
            for (int i = 0; i < Math.min(5, idx.length); i++) {
                if (fs.get(idx[i]) < 20) break;
                JSONArray lines = fetchNetEaseLrc(ids.get(idx[i])[0]);
                if (lines != null && lines.length() > 0) return lines;
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private void searchNetEase(String query, String ct, String ca,
            double ytDur, List<long[]> ids, List<Double> scores) {
        try {
            String url = "https://music.163.com/api/search/get?s="
                    + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&type=1&limit=10";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return;
                JSONObject doc = new JSONObject(readUtf8(resp));
                if (!doc.has("result")) return;
                JSONArray songs = doc.getJSONObject("result").optJSONArray("songs");
                if (songs == null) return;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    long   sid      = song.optLong("id");
                    String st       = song.optString("name", "");
                    double duration = song.optDouble("duration", 0) / 1000.0;
                    StringBuilder ab = new StringBuilder();
                    JSONArray art = song.optJSONArray("artists");
                    if (art != null)
                        for (int j = 0; j < art.length(); j++)
                            ab.append(art.getJSONObject(j).optString("name",""))
                              .append(" ");
                    double score = titleSim(ct, st) * 40
                                 + titleSim(ca, ab.toString().trim()) * 25;
                    if (ytDur > 0 && duration > 0) {
                        double diff = Math.abs(duration - ytDur);
                        if      (diff <= 3)  score += 30;
                        else if (diff <= 10) score += 18;
                        else if (diff <= 30) score += 8;
                        else                 score -= 15;
                    }
                    ids.add(new long[]{sid});
                    scores.add(score);
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONArray fetchNetEaseLrc(long songId) {
        try {
            String url = "https://music.163.com/api/song/lyric?id="
                    + songId + "&lv=1&kv=1&tv=-1";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return null;
                JSONObject doc = new JSONObject(readUtf8(resp));
                String lrc = null;
                if (doc.has("klyric"))
                    lrc = doc.getJSONObject("klyric").optString("lyric", null);
                if (lrc == null || lrc.isEmpty())
                    if (doc.has("lrc"))
                        lrc = doc.getJSONObject("lrc").optString("lyric", null);
                return (lrc != null && !lrc.isEmpty()) ? parseLrc(lrc) : null;
            }
        } catch (Exception e) { return null; }
    }

    // ══════════════════════════════════════════════════
    //  LRC 파서 — [mm:ss.xx] / [mm:ss.xxx] 모두 지원
    // ══════════════════════════════════════════════════
    private static final Pattern CREDIT_RX = Pattern.compile(
            "^\\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带" +
            "|OP|SP|厂牌|发行|监制|制作|ISRC|专辑|歌手)\\s*[：:].{0,80}$");

    // 2~3자리 소수 모두 허용: [mm:ss.xx] 또는 [mm:ss.xxx]
    private static final Pattern TS_RX = Pattern.compile(
            "\\[(\\d+):(\\d{2})[.:](\\d{2,3})\\]");

    private JSONArray parseLrc(String lrc) throws JSONException {
        List<double[]> times = new ArrayList<>();
        List<String>   texts = new ArrayList<>();

        for (String line : lrc.split("\n")) {
            String trimmed = line.trim();
            // 타임스탬프 전체 제거 후 텍스트 추출
            String textPart = trimmed
                    .replaceAll("\\[\\d+:\\d{2}[.:]\\d{2,3}\\]", "").trim();
            if (textPart.isEmpty() || CREDIT_RX.matcher(textPart).matches())
                continue;

            // 한 줄에 타임스탬프 여러 개 처리
            // 예: [00:12.34][00:45.67]가사텍스트
            Matcher scanner = TS_RX.matcher(trimmed);
            while (scanner.find()) {
                String msStr = scanner.group(3);
                // 2자리 = 1/100초, 3자리 = 1/1000초
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(scanner.group(1)) * 60.0
                         + Integer.parseInt(scanner.group(2)) + ms;
                times.add(new double[]{t});
                texts.add(textPart);
            }
        }

        // 시간순 정렬
        Integer[] idx = new Integer[times.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) ->
                Double.compare(times.get(a)[0], times.get(b)[0]));

        JSONArray result = new JSONArray();
        for (int i = 0; i < idx.length; i++) {
            int    ci    = idx[i];
            double start = times.get(ci)[0];
            double end   = (i + 1 < idx.length)
                    ? times.get(idx[i + 1])[0] : start + 5.0;
            if (end - start < 0.1) end = start + 0.5; // 최소 간격 보장
            JSONObject obj = new JSONObject();
            obj.put("start", start);
            obj.put("end",   end);
            obj.put("text",  texts.get(ci));
            result.put(obj);
        }
        return result;
    }

    // ══════════════════════════════════════════════════
    //  헬퍼
    // ══════════════════════════════════════════════════
    private double titleSim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        a = a.toLowerCase(); b = b.toLowerCase();
        if (a.equals(b)) return 1.0;
        if (b.contains(a) || a.contains(b)) return 0.85;
        String[] wa = a.split("[^\\w]+"), wb = b.split("[^\\w]+");
        Set<String> sa = new HashSet<>(), sb2 = new HashSet<>();
        for (String w : wa) if (w.length() > 1) sa.add(w);
        for (String w : wb) if (w.length() > 1) sb2.add(w);
        if (sa.isEmpty() || sb2.isEmpty()) return 0;
        long inter = sa.stream().filter(sb2::contains).count();
        return (double) inter / Math.max(sa.size(), sb2.size());
    }

    private String stripBrackets(String t) {
        return t.replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private double getLrcLastTimestamp(String lrc) {
        double last = 0;
        for (String line : lrc.split("\n")) {
            Matcher m = TS_RX.matcher(line.trim());
            while (m.find()) {
                String msStr = m.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(m.group(1)) * 60.0
                         + Integer.parseInt(m.group(2)) + ms;
                if (t > last) last = t;
            }
        }
        return last;
    }

    /** 제목 정제 — 영문 태그만 제거 (한글 제목 파괴 방지) */
    private String cleanTitle(String t) {
        final String TAG_RX =
            "(?i)official\\s*(?:music\\s*)?(?:video|audio|mv|lyric(?:s)?|visualizer)?" +
            "|\\bm/?v\\b" +
            "|\\bmusic\\s*video\\b" +
            "|\\baudio(?:\\s*only)?\\b" +
            "|\\blyrics?(?:\\s*(?:video|ver(?:sion)?))?\\b" +
            "|\\bvisualizer\\b" +
            "|\\blive(?:\\s+(?:performance|version|session))?\\b" +
            "|\\bperformance(?:\\s+video)?\\b" +
            "|\\b(?:hd|4k|1080p|720p)\\b" +
            "|\\bremaster(?:ed)?(?:\\s+version)?\\b" +
            "|\\bre-?upload\\b" +
            "|\\beng(?:lish)?\\s*(?:ver\\.?|version|sub(?:title)?s?)?\\b" +
            "|\\bkor(?:ean)?\\s*(?:ver\\.?|version)?\\b" +
            "|\\bjp(?:n)?\\s*(?:ver\\.?|version)?\\b";

        t = t.replaceAll("\\(\\s*(?:" + TAG_RX + ")[^)]*\\)", "").trim();
        t = t.replaceAll("\\[\\s*(?:" + TAG_RX + ")[^\\]]*\\]", "").trim();
        t = t.replaceAll("(?i)\\s*[-|]\\s*(?:" + TAG_RX + ")\\s*$", "").trim();
        t = t.replaceAll("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+.+$", "").trim();
        t = t.replaceAll("[\\u2013\\u2014]+", "-").trim();
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    private String cleanArtist(String c) {
        c = c.replaceAll("(?i)\\s*[-–]\\s*Topic\\s*$", "").trim();
        c = c.replaceAll("(?i)VEVO$", "").trim();
        c = c.replaceAll(
                "(?i)\\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\\s*$",
                "").trim();
        return c.replaceAll("\\s{2,}", " ").trim();
    }


    // ── YouTube audio stream URL fetching ────────────────────────────────────
    // Uses YouTube's InnerTube API with the iOS client, which returns
    // pre-signed direct URLs (no signatureCipher decryption needed).
    // These URLs work directly with Android's MediaPlayer for ~6 hours.

    /**
     * Pre-fetch the audio stream URL for a videoId in the background.
     * Result is stored in streamUrlCache so onPause() can use it immediately.
     */
    private void prefetchStreamUrl(String videoId) {
        if (videoId == null || videoId.isEmpty()) return;
        if (streamUrlCache.containsKey(videoId)) return;
        executor.submit(() -> {
            try {
                String url = fetchStreamUrlInnerTube(videoId);
                if (url != null && !url.isEmpty()) {
                    streamUrlCache.put(videoId, url);
                    Log.d(TAG, "Stream URL pre-fetched for: " + videoId);
                }
            } catch (Exception e) {
                Log.w(TAG, "Stream URL pre-fetch failed for: " + videoId, e);
            }
        });
    }

    /**
     * Fetch a direct audio stream URL from YouTube's InnerTube API.
     *
     * Uses the iOS client (clientName=IOS) which returns pre-signed URLs
     * that do not require signature cipher decryption. Typical latency: ~300ms.
     *
     * @param  videoId YouTube video ID
     * @return Direct HTTPS audio URL, or null on failure
     */
    private String fetchStreamUrlInnerTube(String videoId) throws Exception {
        // Build InnerTube player request body
        JSONObject client = new JSONObject();
        client.put("clientName",    "IOS");
        client.put("clientVersion", "19.45.4");
        client.put("deviceModel",   "iPhone16,2");
        client.put("hl", "en");
        client.put("gl", "US");

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject body = new JSONObject();
        body.put("context",       context);
        body.put("videoId",       videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk",    true);

        String apiUrl = "https://www.youtube.com/youtubei/v1/player" +
                "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";

        Request req = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(
                        body.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .addHeader("X-YouTube-Client-Name",    "5")
                .addHeader("X-YouTube-Client-Version", "19.45.4")
                .addHeader("User-Agent",
                        "com.google.ios.youtube/19.45.4 " +
                        "(iPhone; CPU iPhone OS 17_0 like Mac OS X)")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JSONObject doc = new JSONObject(readUtf8(resp));

            JSONObject sd = doc.optJSONObject("streamingData");
            if (sd == null) return null;

            // Prefer audio-only adaptive format (m4a → best MediaPlayer compat)
            JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
            if (adaptive != null) {
                String bestUrl     = null;
                int    bestBitrate = 0;
                for (int i = 0; i < adaptive.length(); i++) {
                    JSONObject f    = adaptive.getJSONObject(i);
                    String     mime = f.optString("mimeType", "");
                    String     url  = f.optString("url", "");
                    if (url.isEmpty()) continue; // skip if signatureCipher needed
                    if (!mime.startsWith("audio/")) continue;
                    int bitrate = f.optInt("bitrate", 0);
                    // Prefer m4a (mp4a codec) over webm for MediaPlayer compatibility
                    boolean isM4a    = mime.startsWith("audio/mp4");
                    boolean bestIsM4a = bestUrl != null &&
                            (bestUrl.contains("audio/mp4") || bestBitrate > 128000);
                    if (bestUrl == null || (isM4a && !bestIsM4a) ||
                            (isM4a == bestIsM4a && bitrate > bestBitrate)) {
                        bestBitrate = bitrate;
                        bestUrl     = url;
                    }
                }
                if (bestUrl != null) return bestUrl;
            }

            // Fallback: muxed format (video+audio, larger but widely supported)
            JSONArray formats = sd.optJSONArray("formats");
            if (formats != null) {
                for (int i = 0; i < formats.length(); i++) {
                    String url = formats.getJSONObject(i).optString("url", "");
                    if (!url.isEmpty()) return url;
                }
            }
        }
        return null;
    }

    /**
     * OkHttp는 Content-Type에 charset이 없으면 Latin-1로 읽음.
     * 한글 깨짐 방지를 위해 항상 명시적 UTF-8로 바이트를 읽는다.
     */
    private String readUtf8(Response resp) throws IOException {
        byte[] bytes = resp.body().bytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }


}
