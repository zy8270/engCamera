package com.engcamera.gallery

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engcamera.EngCameraApp
import com.engcamera.util.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.engcamera.i18n.I18n

/**
 * 多选模式枚举
 */
enum class SelectionMode {
    BROWSE,      // 浏览模式
    MULTI_SELECT // 多选模式
}

/**
 * 绘画模式枚举（选择/取消选择）
 */
enum class PaintMode {
    SELECT,    // 选择模式
    DESELECT   // 取消选择模式
}

/**
 * 自动滚动方向枚举
 */
enum class AutoScrollDirection {
    NONE,  // 不滚动
    UP,    // 向上滚动
    DOWN   // 向下滚动
}

data class GalleryState(
    val items: List<MediaSaver.MediaEntry> = emptyList(),
    val mode: SelectionMode = SelectionMode.BROWSE,
    val selected: Set<Uri> = emptySet(),
    val paintMode: PaintMode = PaintMode.SELECT,
    val autoScroll: AutoScrollDirection = AutoScrollDirection.NONE,
    val longPressItem: Uri? = null,
    val lastPaintedItem: Uri? = null,
    val toast: String? = null,
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as EngCameraApp).container.repository

    /** 网格列数（捏合后记忆，3-5 列） */
    val columns: StateFlow<Int> = repo.galleryColumns

    fun setColumns(n: Int) = repo.setGalleryColumns(n)

    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            // 用户确认（2026-10-10）：相册展示手机全部照片/视频，不只本应用拍摄
            val items = MediaSaver.queryAllMedia(getApplication())
            _state.update { s ->
                s.copy(items = items, selected = s.selected.filter { u -> items.any { it.uri == u } }.toSet())
            }
        }
    }

    /**
     * 长按/滑动刚选中的项与时间。
     * 长按原地松手时，格子会把这次按住当成 click 回调，
     * UI 层可读取这两个值来忽略这次 click，避免刚选中的项被 toggle 掉。
     */
    private var lastSelectUri: Uri? = null
    private var lastSelectElapsed = 0L

    private fun markSelect(uri: Uri) {
        lastSelectUri = uri
        lastSelectElapsed = SystemClock.elapsedRealtime()
    }

    /** 供 UI 层判断某个 click 是否应当被忽略（因为在触摸手势里已经处理过它） */
    fun shouldIgnoreClick(uri: Uri, windowMs: Long = 500L): Boolean {
        val u = lastSelectUri ?: return false
        return u == uri && (SystemClock.elapsedRealtime() - lastSelectElapsed) < windowMs
    }

    /**
     * 长按处理：进入多选模式。
     * 起点默认选中，paintMode 固定为 SELECT；
     * 同时把起点写成 lastPaintedItem，避免同一手势里后续滑回起点被重复处理。
     */
    fun onLongPress(uri: Uri) {
        _state.update { s ->
            if (s.mode != SelectionMode.BROWSE) return@update s
            s.copy(
                mode = SelectionMode.MULTI_SELECT,
                selected = s.selected + uri,
                paintMode = PaintMode.SELECT,
                longPressItem = uri,
                lastPaintedItem = uri,
            )
        }
        markSelect(uri)
    }

    /**
     * 触摸按下处理。
     * - BROWSE：交给 UI 处理长按计时器，这里不改状态（也不能改，否则会污染 selected）
     * - MULTI_SELECT：根据起点是否已选中决定画笔模式，然后对起点应用一次画笔
     */
    fun onTouchDown(uri: Uri) {
        val s = _state.value
        if (s.mode != SelectionMode.MULTI_SELECT) return
        val newPaintMode = if (uri in s.selected) PaintMode.DESELECT else PaintMode.SELECT
        _state.update {
            it.copy(paintMode = newPaintMode, lastPaintedItem = null)
        }
        applyPaint(uri)
    }

    /**
     * 触摸移动处理。
     * - BROWSE：交给 UI 处理滚动
     * - MULTI_SELECT：对经过的项应用当前画笔模式
     */
    fun onTouchMove(uri: Uri) {
        if (_state.value.mode != SelectionMode.MULTI_SELECT) return
        applyPaint(uri)
    }

    /**
     * 应用画笔：
     * 把 uri 强制设成 paintMode 对应的状态（不是 toggle）；
     * 同一手势内对同一个 uri 只处理一次。
     */
    private fun applyPaint(uri: Uri) {
        _state.update { s ->
            if (uri == s.lastPaintedItem) return@update s
            val newSelected = when (s.paintMode) {
                PaintMode.SELECT -> s.selected + uri
                PaintMode.DESELECT -> s.selected - uri
            }
            s.copy(selected = newSelected, lastPaintedItem = uri)
        }
        markSelect(uri)
    }

    /**
     * 触摸抬起处理：结束当前笔画，清掉自动滚动和去重标记。
     * selected / paintMode 保留，允许后续继续点击或再次涂抹。
     */
    fun onTouchUp() {
        _state.update { s ->
            s.copy(
                autoScroll = AutoScrollDirection.NONE,
                lastPaintedItem = null,
            )
        }
    }

    /**
     * 更新自动滚动状态（由 UI 层根据手指位置调用）。
     */
    fun updateAutoScroll(direction: AutoScrollDirection) {
        _state.update { s ->
            s.copy(autoScroll = direction)
        }
    }

    /**
     * 清除选择（退出多选）。
     */
    fun clearSelection() {
        _state.update { s ->
            s.copy(
                mode = SelectionMode.BROWSE,
                selected = emptySet(),
                paintMode = PaintMode.SELECT,
                autoScroll = AutoScrollDirection.NONE,
                longPressItem = null,
                lastPaintedItem = null,
            )
        }
    }

    /** Android 10 及以下：直接删除（本 App 自己的文件） */
    fun deleteDirect(uris: Set<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var failed = 0
            uris.forEach { u ->
                runCatching { resolver.delete(u, null, null) }.onFailure { failed++ }
            }
            _state.update { it.copy(mode = SelectionMode.BROWSE, selected = emptySet()) }
            refresh()
            _state.update {
                it.copy(toast = if (failed == 0) I18n.t("已删除 {n} 项").replace("{n}", "${uris.size}") else I18n.t("有 {n} 项删除失败").replace("{n}", "$failed"))
            }
        }
    }

    /** Android 11+ 系统删除确认完成后调用（无论确认与否） */
    fun onSystemDeleteFinished() {
        _state.update { it.copy(mode = SelectionMode.BROWSE, selected = emptySet()) }
        refresh()
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }
}