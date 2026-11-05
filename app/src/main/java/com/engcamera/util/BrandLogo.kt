package com.engcamera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.core.content.ContextCompat
import com.engcamera.R
import java.io.File

/**
 * 品牌 logo（v0.6.16，用户需求 2026-11-04）：4 款工程模板标题栏左侧的白底方形容器。
 *
 * - 用户选图走系统选择器（PickVisualMedia 的授权是临时的），必须**复制**进
 *   filesDir/brand_logo/ 存 PNG（保留透明），state 里只存文件名。
 * - 空文件名 = 默认 app 图标（把 drawable 铺在品牌蓝上光栅化，与 launcher 观感一致）。
 * - 位图内存缓存按文件名；解码上限 512px（标题栏容器最大也就水印块半宽，够用）。
 *
 * 持 Application context（Application.onCreate 里 init）：渲染器是纯函数、
 * 拿不到 Context，logo 位图必须在渲染期无参可取。
 */
object BrandLogo {

    private const val DIR = "brand_logo"
    private const val MAX_SIDE = 512

    private var appContext: Context? = null
    private var defaultLogo: Bitmap? = null
    private val cache = HashMap<String, Bitmap>()

    /** 启动时预热默认图标（Application.onCreate 调用） */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (defaultLogo == null) defaultLogo = rasterizeDefault(context)
    }

    /** 当前生效的 logo：自定义命中则用它，否则 app 图标 */
    fun current(logoFile: String): Bitmap? {
        val ctx = appContext ?: return null
        if (logoFile.isNotBlank()) {
            cache[logoFile]?.let { if (!it.isRecycled) return it }
            val bmp = runCatching { decode(fileOf(ctx, logoFile)) }.getOrNull()
            if (bmp != null) {
                cache[logoFile] = bmp
                return bmp
            }
            // 文件丢了（清理/换机）→ 退回默认图标，不崩
        }
        return defaultLogo ?: rasterizeDefault(ctx).also { defaultLogo = it }
    }

    /**
     * 保存用户选的图并返回文件名。解码是 IO 活（调用方须放后台协程）。
     * 旧文件由调用方随后用 [deleteOld] 清理。
     */
    fun save(context: Context, uri: Uri): String? = runCatching {
        val bmp = decodeUri(context, uri) ?: return null
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = "logo_${System.currentTimeMillis()}.png"
        val out = File(dir, name)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache[name] = bmp
        name
    }.getOrNull()

    /** 删除不再引用的旧 logo 文件（keep 之外的 logo_* 全清） */
    fun deleteOld(context: Context, keep: String) {
        runCatching {
            val dir = File(context.filesDir, DIR)
            dir.listFiles()?.forEach { f ->
                if (f.name != keep && f.name.startsWith("logo_")) {
                    cache.remove(f.name)
                    f.delete()
                }
            }
        }
    }

    private fun fileOf(context: Context, name: String): File =
        File(File(context.filesDir, DIR), name)

    private fun decode(f: File): Bitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** 默认 logo = app 图标前景铺在品牌蓝底上（与 launcher 自适应图标同观感） */
    private fun rasterizeDefault(context: Context): Bitmap? = runCatching {
        val size = MAX_SIDE
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(ContextCompat.getColor(context, R.color.ic_launcher_background))
        val fg = ContextCompat.getDrawable(context, R.drawable.ic_launcher_fg)
        if (fg != null) {
            fg.setBounds(0, 0, size, size)
            fg.draw(canvas)
        } else {
            canvas.drawColor(Color.WHITE)
        }
        out
    }.getOrNull()
}
