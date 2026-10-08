package app.parkedvideo.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.parkedvideo.R
import app.parkedvideo.Sites

/**
 * Draws a WebView onto the car screen.
 *
 * Android Auto hands navigation apps a raw Surface to draw their map on. We
 * attach a VirtualDisplay to that surface, show a Presentation (a window on
 * that display) containing a WebView, and forward the car's touch gestures to
 * it. While [setBlocked] is true an opaque overlay covers the page and all
 * media on it is kept paused.
 */
class SurfaceRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
) : DefaultLifecycleObserver {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var overlay: TextView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var density = 1f
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var stableArea: Rect? = null

    private var currentUrl = Sites.HOME
    private var blocked = true
    private var blockedMessage = ""

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = attach(surfaceContainer)

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

        override fun onStableAreaChanged(stableArea: Rect) {
            this@SurfaceRenderer.stableArea = Rect(stableArea)
            applyStableArea()
        }

        override fun onClick(x: Float, y: Float) = tap(x, y)

        // Distances follow GestureDetector: positive means the finger moved up/left.
        override fun onScroll(distanceX: Float, distanceY: Float) =
            scrollCss(distanceX / density, distanceY / density, smooth = false)

        override fun onFling(velocityX: Float, velocityY: Float) =
            scrollCss(-velocityX * FLING_FACTOR / density, -velocityY * FLING_FACTOR / density, smooth = true)

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            if (!blocked) webView?.zoomBy(scaleFactor.coerceIn(0.5f, 2f))
        }
    }

    private val chromeClient = object : WebChromeClient() {
        // Sites call this when the user taps a video's fullscreen button.
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            val parent = root ?: return callback.onCustomViewHidden()
            removeCustomView()
            customView = view
            customViewCallback = callback
            // Insert below the blocking overlay so it still covers fullscreen video.
            parent.addView(view, parent.indexOfChild(overlay), matchParent())
        }

        override fun onHideCustomView() = removeCustomView()
    }

    private val webClient = object : WebViewClient() {
        // Keep http(s) in the WebView; drop app links (intent://, vnd.youtube:, …)
        // since there's nothing on the car screen to hand them to.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            return scheme != "http" && scheme != "https"
        }

        override fun onPageFinished(view: WebView, url: String?) = applyMediaGuard()
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // The host may already be gone by now; nothing to clean up on its side then.
        runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) }
        release()
    }

    fun loadUrl(url: String) {
        currentUrl = url
        exitFullscreen()
        webView?.loadUrl(url)
    }

    fun goBack() {
        val web = webView ?: return
        if (customView != null) exitFullscreen() else if (web.canGoBack()) web.goBack()
    }

    fun togglePlayPause() {
        if (blocked) return
        webView?.evaluateJavascript(TOGGLE_PLAY_JS, null)
    }

    /** Scrolls most of a screen up (-1) or down (1). */
    fun scrollPage(direction: Int) {
        val height = stableArea?.height() ?: surfaceHeight
        scrollCss(0f, direction * height * 0.7f / density, smooth = true)
    }

    fun setBlocked(blocked: Boolean, message: String) {
        this.blocked = blocked
        blockedMessage = message
        updateOverlay()
        applyMediaGuard()
    }

    private fun attach(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        density = container.dpi / 160f
        surfaceWidth = container.width
        surfaceHeight = container.height
        try {
            val displayManager = carContext.getSystemService(DisplayManager::class.java)
            val display = displayManager.createVirtualDisplay(
                "ParkedVideo", container.width, container.height, container.dpi, surface, 0,
            )
            virtualDisplay = display
            presentation = Presentation(carContext, display.display, R.style.Theme_ParkedVideo_Car).apply {
                setContentView(buildContent(context))
                show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't draw on the car screen", e)
            release()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildContent(context: Context): View {
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = webClient
            webChromeClient = chromeClient
            loadUrl(currentUrl)
        }
        val text = TextView(context).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            val pad = (32 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        frame.addView(web, matchParent())
        frame.addView(text, matchParent())
        root = frame
        webView = web
        overlay = text
        applyStableArea()
        updateOverlay()
        return frame
    }

    /** Keeps the page out from under the car's own buttons. Fullscreen video still fills the screen. */
    private fun applyStableArea() {
        val web = webView ?: return
        val area = stableArea ?: return
        val params = web.layoutParams as FrameLayout.LayoutParams
        params.setMargins(area.left, area.top, (surfaceWidth - area.right).coerceAtLeast(0), (surfaceHeight - area.bottom).coerceAtLeast(0))
        web.layoutParams = params
    }

    private fun updateOverlay() {
        val text = overlay ?: return
        text.text = blockedMessage
        text.visibility = if (blocked) View.VISIBLE else View.GONE
    }

    private fun applyMediaGuard() {
        webView?.evaluateJavascript(MEDIA_GUARD_JS.replace("__BLOCKED__", blocked.toString()), null)
    }

    private fun tap(x: Float, y: Float) {
        val target = root ?: return
        if (blocked) return
        val downTime = SystemClock.uptimeMillis()
        sendTouch(target, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        sendTouch(target, downTime, downTime + TAP_MS, MotionEvent.ACTION_UP, x, y)
    }

    private fun sendTouch(target: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun scrollCss(dx: Float, dy: Float, smooth: Boolean) {
        if (blocked || customView != null) return
        val behavior = if (smooth) "smooth" else "auto"
        webView?.evaluateJavascript("window.scrollBy({left: $dx, top: $dy, behavior: '$behavior'});", null)
    }

    private fun exitFullscreen() {
        customViewCallback?.onCustomViewHidden()
        removeCustomView()
    }

    private fun removeCustomView() {
        customView?.let { root?.removeView(it) }
        customView = null
        customViewCallback = null
    }

    private fun release() {
        webView?.let { web ->
            web.url?.takeIf { it.startsWith("http") }?.let { currentUrl = it }
            exitFullscreen()
            root?.removeAllViews()
            web.destroy()
        }
        webView = null
        overlay = null
        root = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private companion object {
        const val TAG = "SurfaceRenderer"
        const val TAP_MS = 50L
        const val FLING_FACTOR = 0.25f

        const val TOGGLE_PLAY_JS = """
            (function() {
              var v = document.querySelector('video');
              if (!v) return;
              if (v.paused) v.play(); else v.pause();
            })();
        """

        // Installs (once per page) a listener and a 1s timer that pause any media
        // that starts while blocked, then pauses everything right now if blocked.
        const val MEDIA_GUARD_JS = """
            (function() {
              window.__parkedVideoBlocked = __BLOCKED__;
              function pauseAll() {
                document.querySelectorAll('video, audio').forEach(function(m) { m.pause(); });
              }
              if (!window.__parkedVideoGuard) {
                window.__parkedVideoGuard = true;
                document.addEventListener('play', function(e) {
                  if (window.__parkedVideoBlocked) e.target.pause();
                }, true);
                setInterval(function() { if (window.__parkedVideoBlocked) pauseAll(); }, 1000);
              }
              if (window.__parkedVideoBlocked) pauseAll();
            })();
        """
    }
}
