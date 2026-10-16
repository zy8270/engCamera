package com.engcamera.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.engcamera.i18n.I18n

/**
 * MediaStore 读写：成片统一保存到 DCIM/Camera/，文件名前缀 engCamera_
 * （计划书 2.3；相册页按同样规则过滤）。
 */
object MediaSaver {

    const val RELATIVE_DIR = "DCIM/Camera/"
    const val FILE_PREFIX = "engCamera_"

    data class MediaEntry(
        val id: Long,
        val uri: Uri,
        val isVideo: Boolean,
        val name: String,
        val dateAdded: Long,
        val durationMs: Long,
        val mimeType: String,
        /** 拍摄时间（毫秒），部分来源可能为 0 */
        val takenAtMs: Long = 0L,
    )

    /** 排序键：优先拍摄时间，缺失回退入库时间（相册从新到旧） */
    private fun MediaEntry.sortKey(): Long = if (takenAtMs > 0) takenAtMs else dateAdded * 1000

    fun timeStampName(ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$FILE_PREFIX$ts.$ext"
    }

    /** 保存水印成片 JPEG，返回 Uri。takenAtMs 显式写入 DATE_TAKEN（不写则由系统猜，双张保存时排序不稳定） */
    suspend fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        takenAtMs: Long? = null,
        treeUri: Uri? = null,
    ): Uri = withContext(Dispatchers.IO) {
        if (treeUri != null) {
            return@withContext saveToTree(context, treeUri, "image/jpeg", takenAtMs) { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, timeStampName("jpg"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            takenAtMs?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error(I18n.t("MediaStore 插入失败"))
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: error(I18n.t("无法写入照片"))
        if (Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        uri
    }

    /** 把缓存中的录像文件转存进 MediaStore，返回 Uri。takenAtMs 同 saveBitmap */
    suspend fun saveVideoFile(
        context: Context,
        src: File,
        takenAtMs: Long? = null,
        treeUri: Uri? = null,
    ): Uri = withContext(Dispatchers.IO) {
        if (treeUri != null) {
            val doc = saveToTree(context, treeUri, "video/mp4", takenAtMs) { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            src.delete()
            return@withContext doc
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, timeStampName("mp4"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            takenAtMs?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error(I18n.t("MediaStore 插入失败"))
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: error(I18n.t("无法写入视频"))
        if (Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        src.delete()
        uri
    }


    /** 写入用户自选目录（SAF tree），返回 document Uri 并触发媒体扫描；失败抛异常由调用方兜底 */
    private suspend fun saveToTree(
        context: Context,
        treeUri: Uri,
        mime: String,
        takenAtMs: Long?,
        write: (java.io.OutputStream) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val treeDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val doc = DocumentsContract.createDocument(resolver, treeDoc, mime, timeStampName(mime.substringAfterLast('/')))
            ?: error(I18n.t("无法在所选目录创建文件"))
        resolver.openOutputStream(doc)?.use { out -> write(out) } ?: error(I18n.t("无法写入所选目录"))
        // 等媒体扫描完成拿到 MediaStore Uri（缩略图/相册立即可见）；3 秒超时回退 document uri
        val mediaUri = withTimeoutOrNull(3000) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(doc.toString()), arrayOf(mime)) { _, u ->
                    if (cont.isActive) cont.resume(u)
                }
            }
        }
        val finalUri = mediaUri ?: doc
        // 尽力补写拍摄时间（SAF 文件 owner 是系统组件，可能被拒，失败不影响保存）
        if (takenAtMs != null) {
            runCatching {
                resolver.update(
                    finalUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, takenAtMs) },
                    null,
                    null,
                )
            }
        }
        finalUri
    }

    /** 查询本 App 拍摄的照片+录像，按添加时间倒序 */
    fun queryOwnMedia(context: Context): List<MediaEntry> = queryMedia(context, ownOnly = true)

    /** 查询手机全部照片+录像，按添加时间倒序（需存储读取权限） */
    fun queryAllMedia(context: Context): List<MediaEntry> = queryMedia(context, ownOnly = false)

    private fun queryMedia(context: Context, ownOnly: Boolean): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        val resolver = context.contentResolver

        fun queryCollection(collection: Uri, isVideo: Boolean) {
            val projection = mutableListOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_TAKEN,
            )
            if (isVideo) projection.add(MediaStore.Video.VideoColumns.DURATION)
            val selection: String?
            val args: Array<String>?
            if (ownOnly) {
                // 自定义保存目录（SAF）的文件不在 DCIM/Camera：名字前缀 engCamera_ 即算本应用拍摄
                if (Build.VERSION.SDK_INT >= 29) {
                    selection =
                        "(${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)" +
                            " OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                    args = arrayOf(RELATIVE_DIR, "$FILE_PREFIX%", "$FILE_PREFIX%")
                } else {
                    selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                    args = arrayOf("%${RELATIVE_DIR}$FILE_PREFIX%")
                }
            } else {
                selection = null
                args = null
            }
            resolver.query(
                collection,
                projection.toTypedArray(),
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val iDur = if (isVideo) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                val iTaken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                while (c.moveToNext()) {
                    out += MediaEntry(
                        id = c.getLong(iId),
                        uri = ContentUris.withAppendedId(collection, c.getLong(iId)),
                        isVideo = isVideo,
                        name = c.getString(iName) ?: "",
                        dateAdded = c.getLong(iDate),
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                        mimeType = if (iMime >= 0) c.getString(iMime) ?: "" else "",
                        takenAtMs = if (iTaken >= 0) c.getString(iTaken)?.toLongOrNull() ?: 0L else 0L,
                    )
                }
            }
        }

        queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        // 按拍摄时间从新到旧；拍摄时间缺失回退入库时间。
        // 时间相同（同秒双张）时后插入的在前——水印版后插入，因此 水印=最新=第一张、原图第二张
        // （用户确认 2026-10-11：两个相册都以水印图为最新；DATE_TAKEN 可能被 MIUI 按 EXIF 覆盖回同值）
        return out.sortedWith(
            compareByDescending<MediaEntry> { it.sortKey() }
                .thenByDescending { it.dateAdded }
                .thenByDescending { it.id },
        )
    }

    /** EXIF 方向转旋转角（计划书 9：合成前必须旋转） */
    fun exifDegrees(path: String): Int = when (
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        else -> 0
    }
}
