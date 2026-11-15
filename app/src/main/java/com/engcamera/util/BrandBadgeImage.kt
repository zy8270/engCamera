package com.engcamera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 右下角品牌角标的**外部导入图片**存储（v0.6.31，Issue #5 用户需求 2026-11-15）。
 *
 * 与 [BrandLogo]（4 款工程模板标题栏左侧的 logo）是两个独立东西：
 * - 目录独立 `filesDir/brand_badge/`、前缀独立 `badge_`
 *   原因：`BrandLogo.deleteOld` 按 `logo_` 前缀清理，若共用一个目录会互删对方的图。
 * - 缓存独立，互不影响。
 *
 * 契约与 BrandLogo 一致：渲染器是纯函数、拿不到 Context，
 * 所以位图必须在渲染期无参可取（[current] 只吃文件名）。
 * `init` 由 Application.onCreate 调用。
 *
 * 文件名为空 = 使用内置默认文字角标（代码绘制，不打包图片）。
 */
object BrandBadgeImage {

    private const val DIR = "brand_badge"
    private const val PREFIX = "badge_"
    private const val MAX_SIDE = 512

    private var appContext: Context? = null
    private val cache = HashMap<String, Bitmap>()

    /** 启动时记录 context（Application.onCreate 调用） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 当前生效的角标图片：文件名空/文件丢失/解码失败都返回 null
     * （调用方据此回退到内置默认文字角标，不崩）。
     */
    fun current(fileName: String): Bitmap? {
        val ctx = appContext ?: return null
        if (fileName.isBlank()) return null
        cache[fileName]?.let { if (!it.isRecycled) return it }
        val bmp = runCatching { decode(fileOf(ctx, fileName)) }.getOrNull() ?: return null
        cache[fileName] = bmp
        return bmp
    }

    /**
     * 保存用户选的图并返回文件名。解码是 IO 活（调用方须放后台协程）。
     * 旧文件由调用方随后用 [deleteOld] 清理。
     */
    fun save(context: Context, uri: Uri): String? = runCatching {
        val bmp = decodeUri(context, uri) ?: return null
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = "$PREFIX${System.currentTimeMillis()}.png"
        val out = File(dir, name)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache[name] = bmp
        name
    }.getOrNull()

    /** 删除不再引用的旧角标文件（keep 之外的 badge_* 全清） */
    fun deleteOld(context: Context, keep: String) {
        runCatching {
            val dir = File(context.filesDir, DIR)
            dir.listFiles()?.forEach { f ->
                if (f.name != keep && f.name.startsWith(PREFIX)) {
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
}
