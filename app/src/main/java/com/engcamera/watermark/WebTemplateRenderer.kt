package com.engcamera.watermark

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.max
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

    /**
     * 网页位图固定渲染宽（px）：任何调用方（预览/成片/缩略图）共用同一份缓存，
     * 绘制时按 webScale 等比缩放到目标尺寸——CSS 内容比例在所有场景一致。
     */
    private const val RENDER_WIDTH = 1440

    /** 网页块绘制宽 = 画面宽度 × 该比例（整体缩放滑杆的基准系数，默认 webScale 0.8 → 40% 画面宽） */
    private const val BASE_WIDTH_FRACTION = 0.5f

    /** 网页模板的绘制缩放：块宽目标 = 目标画布宽 × 返回值 */
    fun webDrawFraction(state: WatermarkState): Float =
        BASE_WIDTH_FRACTION * state.style.webScale.coerceIn(0.3f, 2f)

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
    private val debugDump = java.util.concurrent.atomic.AtomicBoolean(false)

    // 串行渲染队列（仅主线程读写）
    private class QueuedJob(
        val context: Context,
        val key: String,
        val html: String,
        val widthPx: Int,
        val future: CompletableFuture<Bitmap?>,
        val diskThumbId: String? = null,
    )

    private val queue = ArrayDeque<QueuedJob>()
    private var loading = false

    // ---------- 缩略图磁盘缓存（模板选择器秒开） ----------
    // 内存 LRU 只在进程内：重启必空、被预览/成片键挤掉也空，选择器打开时网页卡要排队重渲=一片空白。
    // 渲染成功即把缩略图落盘（每模板一个 PNG）；下次打开先读盘秒显，后台刷新完成后自动换新。

    /** 网页模板缩略图磁盘文件（按模板 id 哈希命名，渲染成功即覆盖写） */
    fun thumbFile(context: Context, templateId: String): java.io.File =
        java.io.File(java.io.File(context.filesDir, "webtpl_thumb"), "t_" + Integer.toHexString(templateId.hashCode()) + ".png")

    /** 读磁盘缩略图；无文件或解码失败返回 null（首次使用，走占位+现渲） */
    fun loadThumb(context: Context, templateId: String): Bitmap? = runCatching {
        val f = thumbFile(context, templateId)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    private fun saveThumbAsync(context: Context, templateId: String, src: Bitmap) {
        if (src.width <= 1 || src.height <= 1) return
        val w = 720
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)
        Thread {
            runCatching {
                val scaled = Bitmap.createScaledBitmap(src, w, h, true)
                val f = thumbFile(context, templateId)
                f.parentFile?.mkdirs()
                f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (scaled != src) scaled.recycle()
            }
        }.start()
    }

    /** 最近一次调用方的 context（弱引用防泄漏）：成片烧录线程补渲时复用（WebView 已建则不再需要 Activity） */
    @Volatile
    private var lastContextRef: java.lang.ref.WeakReference<Context>? = null

    fun cacheKey(
        state: WatermarkState,
        template: WatermarkTemplate,
        dynamic: DynamicValues,
        widthPx: Int,
    ): String {
        // 位图固定宽渲染：忽略调用方宽度，预览/成片/缩略图共享同一份缓存
        val values = FieldType.entries.joinToString("|") {
            TemplateHtml.valueOf(it, it.name, state, dynamic)
        }
        // 字段开关状态必须进键：开关变化即使文本值不变也要触发重渲（否则开关"不生效"）
        val flags = state.fields.joinToString(",") { it.type.name + if (it.enabled) "1" else "0" }
        // webScale 绝不能进键：位图固定 1440 宽渲染、HTML 不含 webScale，它只是绘制期缩放系数——
        // 进键会让"整体缩放"滑杆每动一格就触发一次完整 WebView 重渲，串行队列被过期任务淹没（v124 修复）
        return ((template.html ?: "").hashCode().toString(16) +
            "|" + values.hashCode().toString(16) +
            "|" + flags.hashCode().toString(16) +
            "|" + RENDER_WIDTH +
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

    /**
     * 烧录线程专用补渲：预览渲的是预览宽，成片按成片宽取缓存必然 miss——
     * 在此用最近记录的 context 发起渲染并阻塞等待（WebView 渲染跑在主线程，烧录线程可安全等待）。
     * 没有可用 context（预览从未渲过且 Activity 已不在）直接返回，由调用方走兜底。
     */
    fun warmBlocking(
        key: String,
        template: WatermarkTemplate,
        state: WatermarkState,
        dynamic: DynamicValues,
        widthPx: Int,
    ) {
        if (current(key) != null) return
        val ctx = lastContextRef?.get() ?: return
        val latch = java.util.concurrent.CountDownLatch(1)
        warmAsync(ctx, key, template, state, dynamic, widthPx) { latch.countDown() }
        latch.await(6, TimeUnit.SECONDS)
    }

    /** 入队渲染；同 key 已在飞/在队则挂等既有任务。onDone 在完成线程回调（可能 null=失败）。
     *  diskThumbId 非空时（模板选择器缩略图），渲染成功会把缩略图落盘供下次秒开。
     *  注意 onDone 必须是最后一个参数（warmBlocking 用尾随 lambda 调用）。 */
    fun warmAsync(
        context: Context,
        key: String,
        template: WatermarkTemplate,
        state: WatermarkState,
        dynamic: DynamicValues,
        widthPx: Int,
        diskThumbId: String? = null,
        onDone: ((Bitmap?) -> Unit)? = null,
    ) {
        current(key)?.let { onDone?.invoke(it); return }
        lastContextRef = java.lang.ref.WeakReference(context)
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
            queue.addLast(QueuedJob(context, key, html, RENDER_WIDTH, future, diskThumbId))
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
                    // 首绘光栅化需要额外时间（attach 后首个任务尤甚），留足等待
                    view.postDelayed({ measureThenSnapshot(view, job, attempt = 0) }, 300)
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
            val b1 = drawToBitmap(view, w, h)
            mainHandler.postDelayed({
                try {
                    val b2 = drawToBitmap(view, w, h)
                    // 光栅化收敛判定：间隔一帧的两次绘制逐像素一致才算稳定
                    // （attach 后首个任务首绘常缺内容——背景有了文字/渐变未画）
                    // 整页同色（如只有背景色、文字/渐变尚未画上）也视为未完成
                    val stable = java.util.Arrays.equals(pixelsOf(b1), pixelsOf(b2)) &&
                        !isUniform(b2)
                    b1.recycle()
                    if (!stable && attempt < 6) {
                        b2.recycle()
                        snapshotAt(view, job, w, h, attempt + 1)
                        return@postDelayed
                    }
                    val bitmap = cropToContent(b2) ?: b2
                    android.util.Log.d(
                        "WebTpl",
                        "snapshot ${bitmap.width}x${bitmap.height} key=${job.key.take(12)} rounds=$attempt stable=$stable",
                    )
                    synchronized(cacheLock) { cache[job.key] = bitmap }
                    job.diskThumbId?.let { id -> saveThumbAsync(job.context, id, bitmap) }
                    _tick.value = _tick.value + 1
                    finishJob(job, bitmap)
                } catch (t: Throwable) {
                    android.util.Log.w("WebTpl", "snapshot2 failed attempt=$attempt", t)
                    finishJob(job, null)
                }
            }, 250)
        } catch (t: Throwable) {
            android.util.Log.w("WebTpl", "snapshot failed", t)
            finishJob(job, null)
        }
    }

    private fun drawToBitmap(view: WebView, w: Int, h: Int): Bitmap {
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }


    /** 采样检查位图是否整页同色（页面只有背景、内容未绘制） */
    private fun isUniform(b: Bitmap): Boolean {
        val px = pixelsOf(b)
        if (px.isEmpty()) return true
        val stride = max(1, px.size / 400)
        val first = px[0]
        for (i in px.indices step stride) {
            if (px[i] != first) return false
        }
        return true
    }

    private fun pixelsOf(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    /** 裁掉透明边到内容紧致尺寸（水印块锚点按位图中心定位，透明边会带偏位置） */
    /** 裁掉透明边到内容紧致尺寸；全透明或纯白占比过高（页面未光栅化完）返回 null 由调用方重试 */
    private fun cropToContent(full: Bitmap): Bitmap? {
        val w = full.width
        val h = full.height
        val pixels = IntArray(w * h)
        full.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        var white = 0
        var total = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                if (p != 0) {
                    total++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (p == 0xFFFFFFFF.toInt()) white++
                }
            }
        }
        // 未光栅化的 tile 是纯白不透明：内容区里纯白占比过高视为页面还没画完
        if (maxX < 0 || (total > 0 && white > total * 9 / 10)) return null
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
