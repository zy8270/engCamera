package com.engcamera.watermark

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkState
import com.engcamera.data.WatermarkTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 网页水印模板渲染器：单个复用的 WebView，把模板 HTML 渲染成透明位图。
 * - WebView 以 1×1 透明不可触摸的方式 attach 到 Activity 窗口（未 attach 渲染器不工作），
 *   因此 ensureWebView 必须拿到 Activity context（预热都从 UI 层发起）。
 * - 单 WebView 串行渲染队列：连续多个 loadData 会互相顶掉，必须等上一页快照完再下一页。
 * - 缓存键 = html+动态文本+宽度+选项 的哈希；容量 8，LRU 淘汰。
 */
@SuppressLint("SetJavaScriptEnabled")
object WebTemplateRenderer {

    private val _tick = MutableStateFlow(0L)

    /** 渲染完成通知（自增 tick，预览层收集它触发重组取新缓存） */
    val tick: StateFlow<Long> = _tick

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size > 8) {
                eldest.value.recycle()
                return true
            }
            return false
        }
    }
    private val cacheLock = Any()
    private val jobs = ConcurrentHashMap<String, CompletableFuture<Bitmap?>>()

    // 串行渲染队列（仅主线程读写）
    private class QueuedJob(
        val context: Context,
        val key: String,
        val html: String,
        val widthPx: Int,
        val future: CompletableFuture<Bitmap?>,
    )

    private val queue = ArrayDeque<QueuedJob>()
    private var loading = false

    fun cacheKey(
        state: WatermarkState,
        template: WatermarkTemplate,
        dynamic: DynamicValues,
        widthPx: Int,
    ): String {
        val values = FieldType.entries.joinToString("|") {
            TemplateHtml.valueOf(it, it.name, state, dynamic)
        }
        return ((template.html ?: "").hashCode().toString(16) +
            "|" + values.hashCode().toString(16) +
            "|" + widthPx +
            "|" + state.style.bgOpacity +
            "|" + state.style.accentColor)
    }

    /** 预览层专用：时间截断到分钟，避免预览每秒触发一次 WebView 重渲（成片烧录用真实时间，不受影响） */
    fun previewDynamic(dynamic: DynamicValues): DynamicValues {
        val t = dynamic.time
        return if (t.length >= 16) dynamic.copy(time = t.substring(0, 16) + ":00") else dynamic
    }

    /** 同步取缓存（不渲染）；预览层 miss 时调 warmAsync */
    fun current(key: String): Bitmap? = synchronized(cacheLock) { cache[key] }

    /** 阻塞等待渲染完成（烧录线程用）；超时返回当前缓存（可能 null） */
    fun await(key: String, timeoutMs: Long = 3000): Bitmap? {
        current(key)?.let { return it }
        val future = jobs[key] ?: return current(key)
        runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        return current(key)
    }

    /** 入队渲染；同 key 已在飞/在队则挂等既有任务。onDone 在完成线程回调（可能 null=失败） */
    fun warmAsync(
        context: Context,
        key: String,
        template: WatermarkTemplate,
        state: WatermarkState,
        dynamic: DynamicValues,
        widthPx: Int,
        onDone: ((Bitmap?) -> Unit)? = null,
    ) {
        current(key)?.let { onDone?.invoke(it); return }
        val html = TemplateHtml.buildDocument(template, state, dynamic)
        if (html == null) {
            onDone?.invoke(null)
            return
        }
        val existing = jobs[key]
        if (existing != null) {
            existing.whenComplete { _, _ -> onDone?.invoke(current(key)) }
            return
        }
        val future = CompletableFuture<Bitmap?>()
        jobs[key] = future
        future.whenComplete { b, _ ->
            jobs.remove(key)
            onDone?.invoke(b)
        }
        synchronized(queue) {
            queue.removeAll { it.key == key }
            queue.addLast(QueuedJob(context, key, html, widthPx, future))
            drainLocked(context)
        }
    }

    private fun drainLocked(context: Context) {
        if (loading) return
        val job = queue.removeFirstOrNull() ?: return
        loading = true
        mainHandler.post { renderOnMain(job) }
    }

    private fun renderOnMain(job: QueuedJob) {
        try {
            val wv = ensureWebView(job.context)
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    android.util.Log.d("WebTpl", "onPageFinished key=${job.key.take(12)}")
                    view.postDelayed({ measureThenSnapshot(view, job, attempt = 0) }, 60)
                }
            }
            android.util.Log.d("WebTpl", "load key=${job.key.take(12)} len=${job.html.length}")
            wv.loadDataWithBaseURL(null, job.html, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            android.util.Log.w("WebTpl", "load failed", t)
            finishJob(job, null)
        }
    }

    /**
     * 未 attach/自测的 WebView measure 高度不可信（实测返回固定 3344）：
     * 先按目标宽布局，再注入 JS 读 body.scrollHeight（CSS px）按密度换算成设备高，精确快照。
     */
    private fun measureThenSnapshot(view: WebView, job: QueuedJob, attempt: Int = 0) {
        try {
            val w = job.widthPx.coerceAtLeast(1)
            val capH = (w * 4).coerceAtMost(6000)
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(capH, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, capH)
            view.evaluateJavascript(
                "(function(){return Math.ceil(document.body.scrollHeight);})()",
            ) { result ->
                val cssH = result.replace("\"", "").trim().toDoubleOrNull() ?: 0.0
                val density = view.resources.displayMetrics.density
                val h = if (cssH > 0) {
                    (cssH * density).roundToInt().coerceIn(1, capH)
                } else {
                    capH
                }
                android.util.Log.d("WebTpl", "measured h=$h (css=$cssH) key=${job.key.take(12)}")
                mainHandler.post { snapshotAt(view, job, w, h, 0) }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WebTpl", "measure failed attempt=$attempt", t)
            if (attempt < 2) {
                mainHandler.postDelayed({ measureThenSnapshot(view, job, attempt + 1) }, 150)
            } else {
                finishJob(job, null)
            }
        }
    }

    private fun snapshotAt(view: WebView, job: QueuedJob, w: Int, h: Int, attempt: Int) {
        try {
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            val bitmap = cropToContent(full)
            android.util.Log.d("WebTpl", "snapshot ${bitmap.width}x${bitmap.height} key=${job.key.take(12)}")
            synchronized(cacheLock) { cache[job.key] = bitmap }
            _tick.value = _tick.value + 1
            finishJob(job, bitmap)
        } catch (t: Throwable) {
            android.util.Log.w("WebTpl", "snapshot failed attempt=$attempt", t)
            if (attempt < 2) {
                mainHandler.postDelayed({ snapshotAt(view, job, w, h, attempt + 1) }, 120)
            } else {
                finishJob(job, null)
            }
        }
    }

    /** 裁掉透明边到内容紧致尺寸（水印块锚点按位图中心定位，透明边会带偏位置） */
    private fun cropToContent(full: Bitmap): Bitmap {
        val w = full.width
        val h = full.height
        val pixels = IntArray(w * h)
        full.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (pixels[row + x] != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return full // 全透明：原样返回（上层有齿轮兜底）
        val inset = 2
        val l = (minX - inset).coerceAtLeast(0)
        val t = (minY - inset).coerceAtLeast(0)
        val cw = (maxX - minX + 1 + inset * 2).coerceAtMost(w - l)
        val ch = (maxY - minY + 1 + inset * 2).coerceAtMost(h - t)
        if (cw <= 0 || ch <= 0 || (cw == w && ch == h)) return full
        val cropped = Bitmap.createBitmap(full, l, t, cw, ch)
        full.recycle()
        return cropped
    }

    private fun finishJob(job: QueuedJob, bitmap: Bitmap?) {
        job.future.complete(bitmap)
        synchronized(queue) {
            loading = false
            drainLocked(job.context)
        }
    }

    private fun ensureWebView(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        // JS 仅用于本地页面测量内容高度（渲染的是自组 HTML 字符串，禁文件访问、无网络加载）
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.layout(0, 0, 1, 1)
        // 未 attach 到窗口的 WebView 渲染器不工作（onPageFinished 永不触发）：
        // 以 1×1 透明、不可触摸的方式挂到 Activity 窗口上（TYPE_APPLICATION 需要活动 token，
        // 所以调用方必须传 Activity context；拿不到就退回离屏模式，部分机型仍可用）
        val anchor = (context as? Activity) ?: (context as? android.content.ContextWrapper)?.let {
            var c: Context? = it
            while (c is android.content.ContextWrapper) {
                c = c.baseContext
                if (c is Activity) break
            }
            c as? Activity
        }
        if (anchor != null) {
            try {
                anchor.windowManager.addView(
                    wv,
                    android.view.WindowManager.LayoutParams(
                        1,
                        1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT,
                    ),
                )
                android.util.Log.d("WebTpl", "webview attached to window")
            } catch (t: Throwable) {
                android.util.Log.w("WebTpl", "window attach failed, fallback offscreen", t)
            }
        }
        webView = wv
        return wv
    }
}
