package com.engcamera.gallery

import android.app.Application
import android.net.Uri
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

data class GalleryState(
    val items: List<MediaSaver.MediaEntry> = emptyList(),
    val selectionMode: Boolean = false,
    val selected: Set<Uri> = emptySet(),
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

    fun onItemLongPress(uri: Uri) {
        _state.update { s ->
            if (s.selectionMode) s else s.copy(selectionMode = true, selected = s.selected + uri)
        }
    }

    fun onItemTap(uri: Uri) {
        _state.update { s ->
            if (!s.selectionMode) {
                s
            } else {
                val newSelected = if (uri in s.selected) s.selected - uri else s.selected + uri
                s.copy(selected = newSelected, selectionMode = newSelected.isNotEmpty())
            }
        }
    }

    /** 滑动多选：划过的项加入选中 */
    fun onHoverSelect(uri: Uri) {
        _state.update { s ->
            if (!s.selectionMode) s else s.copy(selected = s.selected + uri)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectionMode = false, selected = emptySet()) }
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
            _state.update { it.copy(selectionMode = false, selected = emptySet()) }
            refresh()
            _state.update {
                it.copy(toast = if (failed == 0) "已删除 ${uris.size} 项" else "有 $failed 项删除失败")
            }
        }
    }

    /** Android 11+ 系统删除确认完成后调用（无论确认与否） */
    fun onSystemDeleteFinished() {
        _state.update { it.copy(selectionMode = false, selected = emptySet()) }
        refresh()
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }
}
