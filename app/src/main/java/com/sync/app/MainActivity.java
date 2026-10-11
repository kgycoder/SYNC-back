package com.sync.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 화면(host) 전용 Activity.
 *
 * WebView(YouTube IFrame + app.js 세션), JS 브리지, MediaSession, 네트워크 로직은 모두
 * {@link SyncEngine} 이 소유한다. 이 Activity 는 엔진의 WebView 를 화면에 붙였다 떼기만 하므로
 * Activity 가 파괴(앱 닫기/최근 앱에서 제거/구성 변경)되어도 재생과 세션이 끊기지 않는다.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_POST_NOTIFICATIONS = 7001;

    private WebView webView;
    private SyncEngine engine;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        setContentView(R.layout.activity_main);

        // 이미 재생 중인 엔진이 있으면 그대로 이어받고, 없으면 새로 만든다.
        engine = SyncEngine.get(this);
        engine.attachActivity(this);
        webView = engine.getWebView();

        FrameLayout root = findViewById(R.id.root);
        root.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Liquid Glass: WebView(frontend)를 Compose backdrop 소스로 옮기고 네이티브 글래스 레이어를 얹는다.
        com.sync.app.glass.GlassHost.install(this, webView);

        // 엔진이 기억하고 있는 화면 방향 상태 복원(앱을 다시 열었을 때 JS 상태와 일치시킴)
        String orient = engine.getLastOrientation();
        if (orient != null) setOrientation(orient);

        requestNotificationPermissionIfNeeded();
    }

    /** Android 13+: 미디어 알림(재생 컨트롤)이 보이려면 알림 권한이 필요하다. 재생 자체에는 영향 없음. */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    void setOrientation(String mode) {
        if ("landscape".equals(mode)) {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUI();
        } else {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            showSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        // 상태바 숨김 + 화면 회전이 겹치는 시점에 WebView가 옛 창 크기를
        // 그대로 들고 있는 경우가 있어, 한 프레임 뒤에 인셋 재적용을
        // 강제로 요청해 WebView의 100vh/100dvh 계산이 새 창 크기를
        // 정확히 반영하도록 한다.
        getWindow().getDecorView().post(() ->
                getWindow().getDecorView().requestApplyInsets());
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null)
                c.show(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) engine.onActivityPaused(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) engine.onActivityResumed(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        // Handle notification action buttons — set __userBgPause BEFORE calling
        // __mediaCmd so the onYtSt(PAUSED) handler knows the pause was user-initiated
        final String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case MusicService.ACTION_PLAY:
                webView.evaluateJavascript("window.__userBgPause=false;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('play')", null);
                break;
            case MusicService.ACTION_PAUSE:
                webView.evaluateJavascript("window.__userBgPause=true;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('pause')", null);
                break;
            case MusicService.ACTION_NEXT:
                webView.evaluateJavascript("window.__userBgPause=false;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('next')", null);
                break;
            case MusicService.ACTION_PREV:
                webView.evaluateJavascript("window.__userBgPause=false;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('prev')", null);
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // WebView 는 엔진이 계속 소유한다(destroy 하지 않음) — 화면에서만 분리.
        if (engine != null) engine.detachActivity(this);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "window.__onAndroidBack && window.__onAndroidBack()", null);
    }
}