package com.sync.app.glass

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

object GlassHost {

    // WebView 는 Activity 가 바뀌어도 살아있으므로(백그라운드 재생 유지) 같은 WebView 에 묶인
    // GlassController 도 재사용한다. 새로 만들면 JS 가 계속 부르는 기존 GlassBridge 와 어긋난다.
    private var cachedController: GlassController? = null
    private var cachedFor: WebView? = null

    /** WebView/엔진이 완전히 해제될 때 호출. */
    @JvmStatic
    fun release() {
        cachedController = null
        cachedFor = null
    }

    /**
     * Moves the existing WebView into a Compose tree (so it can act as the Kyant `layerBackdrop` source)
     * and layers the native Liquid Glass UI above it. Call once per Activity from onCreate (the page may already be loaded when an Activity re-attaches).
     */
    @JvmStatic
    fun install(activity: AppCompatActivity, webView: WebView) {
        val parent = webView.parent as ViewGroup
        val index = parent.indexOfChild(webView)
        parent.removeView(webView)

        // 문서 스크롤(WebView 자체 스크롤)로 승격되면서 WebView 가 '네이티브 스크롤바'를 직접 그리기 시작했다.
        // CSS(::-webkit-scrollbar)로는 이 스크롤바를 숨길 수 없고, 터치 영역을 가로채 스크롤/가사 드래그와 충돌한다.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

        val existing = cachedController
        val controller = if (existing != null && cachedFor === webView) {
            existing
        } else {
            GlassController(webView).also { c ->
                webView.addJavascriptInterface(c, "GlassBridge")
                webView.setOnScrollChangeListener { _, _, y, _, _ -> c.onNativeScroll(y) }
                cachedController = c
                cachedFor = webView
            }
        }

        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { GlassRoot(webView, controller) }
        }
        parent.addView(
            compose, index,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}
