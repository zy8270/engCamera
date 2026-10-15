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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 网页水印模板渲染器：把模板 HTML 渲染成透明位图。
 * - WebView 池（≤3 个）并行渲染：单个 WebView 连续 loadData 会互相顶掉，多实例互不干扰；
 *   每个 1×1 透明不可触摸 attach 到 Activity 窗口（未 attach 渲染器不工作），
 *   创建时必须拿到 Activity context（预热都从 UI 层发起）。
 * - 软件层渲染（LAYER_TYPE_SOFTWARE）：draw() 同步栅格化整份文档，无合成器竞争/半成品帧。
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

    /** 网页模板的绘制缩放：块宽目标 = 目标画布宽 × 返回值（默认 webScale 1.5 → 75% 画面宽） */
    fun webDrawFraction(state: WatermarkState): Float =
        BASE_WIDTH_FRACTION * state.style.webScale.coerceIn(0.5f, 3f)

    private val _tick = MutableStateFlow(0L)

    /** 渲染完成通知（自增 tick，预览层收集它触发重组取新缓存） */
    val tick: StateFlow<Long> = _tick

    private val mainHandler = Handler(Looper.getMainLooper())

    // WebView 池（仅主线程读写）：单 WebView 连续 loadData 会互相顶掉才串行，多实例可并行——
    // 选择器一次暖 3 张网页模板时不再是 3×单张耗时。池满时任务回队等释放。
    private val idleViews = ArrayDeque<WebView>()
    private var createdCount = 0

    private class QueuedJob(
        val context: Context,
        val key: String,
        val html: String,
        val widthPx: Int,
        var future: CompletableFuture<Bitmap?>,
        val diskThumbId: String? = null,
        val enqueuedAt: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        // 渲染中占用的池内 WebView（finishJob 归还）；loadedAt 用于耗时统计；
        // retriedLoad=自愈重排标记（首载缩放定格时同一视图重 load 一次，红米实测必好）
        var view: WebView? = null
        var loadedAt: Long = 0
        var retriedLoad = false
    }

    private val queue = ArrayDeque<QueuedJob>()
    private var activeLoads = 0

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

    // ---------- 缩略图磁盘缓存（模板选择器秒开） ----------
    // 内存 LRU 只在进程内：重启必空、被预览/成片键挤掉也空，选择器打开时网页卡要排队重渲=一片空白。
    // 渲染成功即把缩略图落盘（每模板一个 PNG）；下次打开先读盘秒显，后台刷新完成后自动换新。

    /** 网页模板缩略图磁盘文件（按模板 id 哈希命名，渲染成功即覆盖写）。
     *  t3_ 前缀 v3：t2_ 是"画错缩放整帧被接受"年代写入的坏图，改名即全部作废（用户反馈 2026-10-14） */
    fun thumbFile(context: Context, templateId: String): java.io.File =
        java.io.File(java.io.File(context.filesDir, "webtpl_thumb"), "t3_" + Integer.toHexString(templateId.hashCode()) + ".png")

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
            "|" + state.style.webBgOpacity +
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
     *  注意 onDone 必须是最后一个参数（warmBlocking 用尾随 lambda 调用）。
     *  失败自动重试最多 2 次（延迟 800ms）：调用方（编辑器预览 LaunchedEffect）只暖一次、
     *  不会自己重试，渲染一失败预览就永远占位（用户复现 2026-10-14"一直载不出来"） */
    fun warmAsync(
        context: Context,
        key: String,
        template: WatermarkTemplate,
        state: WatermarkState,
        dynamic: DynamicValues,
        widthPx: Int,
        diskThumbId: String? = null,
        onDone: ((Bitmap?) -> Unit)? = null,
    ) = warmWithRetry(context, key, template, state, dynamic, widthPx, diskThumbId, retriesLeft = 2, onDone)

    private fun warmWithRetry(
        context: Context,
        key: String,
        template: WatermarkTemplate,
        state: WatermarkState,
        dynamic: DynamicValues,
        widthPx: Int,
        diskThumbId: String?,
        retriesLeft: Int,
        onDone: ((Bitmap?) -> Unit)? = null,
    ) {
        current(key)?.let { onDone?.invoke(it); return }
        lastContextRef = java.lang.ref.WeakReference(context)
        contextRefForDump = context.applicationContext
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
            if (b == null && retriesLeft > 0) {
                // 延迟重暖：同 key 期间被别的路径渲好则直接取缓存；jobs 去重防重复渲染
                mainHandler.postDelayed({
                    if (current(key) != null) {
                        onDone?.invoke(current(key))
                    } else {
                        warmWithRetry(context, key, template, state, dynamic, widthPx, diskThumbId, retriesLeft - 1, onDone)
                    }
                }, WARM_RETRY_DELAY_MS)
            } else {
                onDone?.invoke(b)
            }
        }
        synchronized(queue) {
            queue.removeAll { it.key == key }
            queue.addLast(QueuedJob(context, key, html, RENDER_WIDTH, future, diskThumbId))
            drainLocked(context)
        }
    }

    private fun drainLocked(context: Context) {
        // WebView 池并行：有空闲视图（或还能新建）就取任务，池满则等 finishJob 归还后再 drain
        while (activeLoads < MAX_CONCURRENT_RENDERS && queue.isNotEmpty()) {
            val job = queue.removeFirst()
            activeLoads++
            mainHandler.post { renderOnMain(job) }
        }
    }

    private fun renderOnMain(job: QueuedJob) {
        try {
            val wv = acquireWebView(job.context)
            job.view = wv
            job.loadedAt = android.os.SystemClock.elapsedRealtime()
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // 软件层渲染 draw() 同步栅格化整份文档，无合成器竞争，无需长等待；
                    // 留 100ms 给首帧布局收敛（evaluateJavascript 本身会强制同步布局）
                    view.postDelayed({ measureThenSnapshot(view, job, attempt = 0) }, 100)
                }
            }
            wv.loadDataWithBaseURL(null, job.html, "text/html", "utf-8", null)
            // 看门狗：onPageFinished 有概率永不回调（WebView 渲染进程死亡等），管线无超时
            // 会永久挂死——future 不完成、视图不归还、jobs 表死键挡住后续重暖（用户复现 2026-10-14）。
            // job.view 在 finishJob 归还时清空，此处仍持有即未完成；合法渲染全链路 <3s，8s 必是挂死
            mainHandler.postDelayed({
                if (job.view === wv && !job.future.isDone) {
                    android.util.Log.w("WebTpl", "watchdog: wedged render, destroy webview key=${job.key.take(12)}")
                    job.view = null
                    runCatching { destroyWebView(job.context, wv) }
                    synchronized(queue) {
                        activeLoads--
                        drainLocked(job.context)
                    }
                    job.future.complete(null)
                }
            }, WATCHDOG_MS)
        } catch (t: Throwable) {
            android.util.Log.w("WebTpl", "load failed", t)
            finishJob(job, null)
        }
    }

    private const val WATCHDOG_MS = 8000L
    private const val WARM_RETRY_DELAY_MS = 800L

    /** 销毁疑似挂死的 WebView（渲染进程已死，复用只会继续挂）：先摘窗再 destroy */
    private fun destroyWebView(context: Context, wv: WebView) {
        val anchor = (context as? Activity) ?: (context as? android.content.ContextWrapper)?.let {
            var c: Context? = it
            while (c is android.content.ContextWrapper) {
                c = c.baseContext
                if (c is Activity) break
            }
            c as? Activity
        }
        runCatching { anchor?.windowManager?.removeView(wv) }
        runCatching { wv.destroy() }
    }

    /**
     * 未 attach/自测的 WebView measure 高度不可信（实测返回固定 3344）：
     * 先按目标宽布局，再注入 JS 读 body.scrollHeight（CSS px）按密度换算成设备高，精确快照。
     * 同时读 body 直接子元素的 union boundingRect 作为"内容真实外沿"：
     * scrollWidth 会被视口宽钳住（窄卡片也报视口宽），拿它当期望宽会把
     * "画错缩放、内容铺满画布"的坏帧误判为合格（红米实测 2026-10-14）。
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
            // 布局基准（高 + 内容盒宽/高）：JS 读到的布局尺寸是渲染完整性的硬校验依据——
            // getBoundingClientRect 含 transform 后的真实外沿；阴影/发光等绘制溢出盒外，
            // 由校验容差吸收
            view.evaluateJavascript(
                "(function(){var d=document.body,l=1e9,t=1e9,r=-1e9,b=-1e9;" +
                    "for(var i=0;i<d.children.length;i++){var q=d.children[i].getBoundingClientRect();" +
                    "if(q.width===0&&q.height===0)continue;" +
                    "if(q.left<l)l=q.left;if(q.top<t)t=q.top;if(q.right>r)r=q.right;if(q.bottom>b)b=q.bottom;}" +
                    "return Math.ceil(d.scrollHeight)+','+Math.ceil(r>=0?r-l:0)+','+Math.ceil(b>=0?b-t:0);})()",
            ) { result ->
                val parts = result.replace("\"", "").trim().split(",")
                val cssH = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val cssBoxW = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val cssBoxH = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                val density = view.resources.displayMetrics.density
                // 画布高取 scrollHeight 与内容盒高的较大者：阴影溢出 body 布局高时也装得下
                val h = listOf(cssH, cssBoxH).maxOrNull()
                    ?.takeIf { it > 0 }?.let { (it * density).roundToInt().coerceIn(1, capH) } ?: capH
                // 内容盒宽高（CSS px → 设备 px）：窄于视口的卡片是合法的，期望值必须是卡片自己
                val expW = if (cssBoxW > 0) (cssBoxW * density).roundToInt() else 0
                val expH = if (cssBoxH > 0) (cssBoxH * density).roundToInt() else 0
                mainHandler.post { snapshotAt(view, job, w, h, 0, expW, expH) }
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

    /** 渲染诊断转储开关（置 true 时把渲染帧落到 Download/engTplDbg/ 供 adb 拉取分析；
     *  注意 PNG 压缩跑在主线程会拖慢渲染，平时必须关，需要取证时再开。
     *  2026-10-14 临时开启：v152 仍有概率出"只渲染一个角"，需要抓复发现场 */
    private const val TPL_DEBUG_DUMP = false

    /** TODO 调试转储：位图缩到 ≤720 宽写入公共下载目录 Download/engTplDbg/ */
    private fun debugDump(tag: String, bmp: Bitmap) {
        if (!TPL_DEBUG_DUMP) return
        runCatching {
            val scaled = if (bmp.width > 720) {
                Bitmap.createScaledBitmap(bmp, 720, (bmp.height.toLong() * 720 / bmp.width).toInt().coerceAtLeast(1), true)
            } else {
                bmp
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$tag.png")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/engTplDbg")
            }
            contextRefForDump?.let { ctx ->
                ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?.let { uri ->
                        ctx.contentResolver.openOutputStream(uri)?.use {
                            scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
            }
            if (scaled != bmp) scaled.recycle()
        }
    }

    @Volatile
    private var contextRefForDump: android.content.Context? = null

    /**
     * 完整性容差：裁剪去透明边 ±2px/抗锯齿边缘/布局取整，外加阴影发光等绘制溢出
     * （玻璃拟态 box-shadow 24CSS 模糊双沿 ≈ 内容宽 11%）——取内容盒 1/7 封顶，
     * 仍远严于旧 scrollWidth 方案：红米实测 2026-10-14，"画错缩放只画出左上角"的坏帧
     * 裁剪宽=画布宽 1440 vs scrollWidth 期望 1441 差 1px 被放行；改内容盒期望后
     * 该帧差 258px 直接拦死。
     */
    private fun sizeTolerance(expected: Int): Int = maxOf(32, expected / 7)

    private fun snapshotAt(
        view: WebView,
        job: QueuedJob,
        w: Int,
        h: Int,
        attempt: Int,
        expW: Int,
        expH: Int,
    ) {
        try {
            val b1 = drawToBitmap(view, w, h)
            // 间隔随轮次拉长：首绘光栅化慢的机型/排队渲染时给足收敛时间
            val gap = 80L + attempt * 40L
            mainHandler.postDelayed({
                try {
                    val b2 = drawToBitmap(view, w, h)
                    // 光栅化收敛判定：间隔一帧的两次绘制逐像素一致才算稳定
                    // 整页同色（如只有背景色、文字/渐变尚未画上）也视为未完成
                    val stable = java.util.Arrays.equals(pixelsOf(b1), pixelsOf(b2)) &&
                        !isUniform(b2)
                    b1.recycle()
                    debugDump("raw_${job.key.take(6)}_a$attempt", b2)
                    val cropped = if (stable) cropToContent(b2) else null
                    // 完整性硬校验：内容尺寸须与布局尺寸吻合。两帧定格在同一半成品状态
                    // （只画出左上角）时逐像素稳定判定会误放行，只有尺寸校验能拦住（用户复现 2026-10-14）
                    val sizeOk = cropped != null && expW > 0 && expH > 0 &&
                        abs(cropped.width - expW) <= sizeTolerance(expW) &&
                        abs(cropped.height - expH) <= sizeTolerance(expH)
                    if (cropped != null && sizeOk) {
                        debugDump("crop_${job.key.take(6)}_a$attempt", cropped)
                        synchronized(cacheLock) { cache[job.key] = cropped }
                        job.diskThumbId?.let { id -> saveThumbAsync(job.context, id, cropped) }
                        _tick.value = _tick.value + 1
                        finishJob(job, cropped)
                    } else if (attempt < MAX_SNAPSHOT_ATTEMPTS) {
                        b2.recycle()
                        snapshotAt(view, job, w, h, attempt + 1, expW, expH)
                    } else if (!job.retriedLoad) {
                        // 自愈重排（红米实测 2026-10-14）：首载在 1×1 提交状态下定格于错误缩放时，
                        // 同一 WebView 第二次 load（此时已是 1440 宽）立即正常——不空等 UI 再触发
                        android.util.Log.w(
                            "WebTpl",
                            "render failed attempt=$attempt stable=$stable sizeOk=$sizeOk key=${job.key.take(12)} -> self-heal reload",
                        )
                        b2.recycle()
                        job.retriedLoad = true
                        job.future.complete(null)
                        val retryFuture = CompletableFuture<Bitmap?>()
                        retryFuture.whenComplete { _, _ -> jobs.remove(job.key) }
                        jobs[job.key] = retryFuture
                        job.future = retryFuture
                        synchronized(queue) {
                            job.view?.let { v ->
                                job.view = null
                                idleViews.addLast(v)
                            }
                            activeLoads--
                            queue.removeAll { it.key == job.key }
                            queue.addLast(job)
                            drainLocked(job.context)
                        }
                    } else {
                        // 重 load 后仍失败：判渲染失败——不缓存、不落盘、不通知，
                        // 宁可这次没有水印（选择器显示旧缩略图/占位符）也不让残缺块进预览和成片；
                        // 下次打开选择器会自动重新渲染补上
                        android.util.Log.w(
                            "WebTpl",
                            "render failed after reload attempt=$attempt stable=$stable sizeOk=$sizeOk key=${job.key.take(12)}",
                        )
                        b2.recycle()
                        finishJob(job, null)
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("WebTpl", "snapshot2 failed attempt=$attempt", t)
                    finishJob(job, null)
                }
            }, gap)
        } catch (t: Throwable) {
            android.util.Log.w("WebTpl", "snapshot failed", t)
            finishJob(job, null)
        }
    }

    // 软渲染下帧是确定性的，3 轮足够判死；真死（首载缩放定格）走自愈重 load 而不是傻等
    private const val MAX_SNAPSHOT_ATTEMPTS = 3

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
        // 未光栅化的 tile 是横贯全宽的纯白不透明：纯白占比过高且内容区宽≈位图全宽视为页面还没画完；
        // 正常的纯白内容块（如玻璃拟态拉满底色）只在全宽里占一窄条，不误判
        if (maxX < 0 ||
            (total > 0 && white > total * 9 / 10 && (maxX - minX + 1) >= w * 19 / 20)
        ) return null
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
        // 幂等：看门狗已强制收尾的 job，迟到的回调只补 future（complete 二次无害），
        // 不再重复归还视图/减计数（job.view 已清空即说明收尾过）
        synchronized(queue) {
            val v = job.view
            job.view = null
            if (v != null) {
                idleViews.addLast(v)
                activeLoads--
            }
            drainLocked(job.context)
        }
    }

    private const val MAX_CONCURRENT_RENDERS = 3

    /** 池取视图：优先复用空闲；否则新建（≤MAX_CONCURRENT_RENDERERS 个）。
     *  仅主线程调用（renderOnMain），drain 已保证不会超额。 */
    private fun acquireWebView(context: Context): WebView {
        idleViews.removeFirstOrNull()?.let { return it }
        return createWebView(context)
    }

    private var slowDrawEnabled = false

    private fun createWebView(context: Context): WebView {
        // 捕获专用模式（须在创建任何 WebView 前设置）：draw() 同步栅格化整份文档，
        // 杜绝"顶部条纹画完、下方字段还是空白"的半成品快照（瓦片异步光栅化跟不上取帧，
        // 逐像素稳定判定对这种两帧同相的半成品无效）。模板页面都很小，开销可忽略（用户反馈 2026-10-14）
        if (!slowDrawEnabled) {
            WebView.enableSlowWholeDocumentDraw()
            slowDrawEnabled = true
        }
        val wv = WebView(context)
        createdCount++
        // 软件层渲染：draw() 同步把整份文档画进画布，与硬件合成器完全解耦——
        // 1×1 挂窗 + 手动 measure/layout 的组合下，合成器的光栅化范围/缩放跟着
        // ViewRootImpl 的窗口重排走，竞争失败时 draw() 取到按错误缩放绘制的
        // "只画出左上角"整帧（红米实测 2026-10-14：卡片 430CSS 被放大铺满 1440 画布，
        // 右/底全切），且帧间逐像素稳定、裁剪尺寸≈画布尺寸，两道校验都拦不住。
        // 软件路径不经过合成器，无此竞争；页面很小开销可忽略。
        wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        // JS 仅用于本地页面测量内容高度（渲染的是自组 HTML 字符串，禁文件访问、无网络加载）
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 首帧缩放根治（红米实测 2026-10-14）：新建 WebView 若在 1×1 状态下提交第一页，
        // Chromium 按提交时的视口宽推导页面缩放，画出来是放大铺满画布的左上角切片，
        // 且定格稳定、重试 8 轮全废；同一视图第二次 load（此时已是 1440 宽）立即正常。
        // 所以创建时先 layout 到渲染宽再首载，绝不给 1×1 提交的机会
        wv.layout(0, 0, RENDER_WIDTH, (RENDER_WIDTH * 2).coerceAtMost(6000))
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
                // 挂窗尺寸=渲染尺寸 + alpha=0（完全透明、不可触摸、不可聚焦）：
                // v152 曾用 1×1 挂窗 + 创建时预 layout 1440，但 attach 后 ViewRootImpl 首次遍历
                // 会按窗口参数把视图重排回 1×1——页面提交晚于该遍历时又以 1×1 视口提交，
                // 错误缩放（只画左上角）概率复发（用户复现 2026-10-14 v152）。
                // 窗口本身就是 1440 宽后，任何时序下提交视口都正确；alpha=0 保证用户看不见。
                anchor.windowManager.addView(
                    wv,
                    android.view.WindowManager.LayoutParams(
                        RENDER_WIDTH,
                        (RENDER_WIDTH * 2).coerceAtMost(6000),
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT,
                    ).apply { alpha = 0f },
                )
            } catch (t: Throwable) {
                android.util.Log.w("WebTpl", "window attach failed, fallback offscreen", t)
            }
        }
        return wv
    }
}
