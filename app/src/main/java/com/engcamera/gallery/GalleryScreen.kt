package com.engcamera.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import coil.compose.AsyncImage
import com.engcamera.util.MediaSaver
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 相册页（计划书 3.3）：
 * - 只显示 DCIM/Camera 下 engCamera_ 前缀的照片与录像，时间倒序
 * - 双指捏合 3-5 列，松手落定并记忆
 * - 长按进入多选，点击切换，按住滑动连续选中
 * - 批量删除走系统确认（Android 11+），单张跳系统查看器
 */
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onGoShoot: () -> Unit,
) {
    val vm: GalleryViewModel = viewModel()
    val columns by vm.columns.collectAsState()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current

    // 存储读取权限（用户确认：相册浏览手机全部照片/视频）
    fun readPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasReadPermission(): Boolean = readPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var readGranted by remember { mutableStateOf(hasReadPermission()) }
    val readPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        readGranted = hasReadPermission()
        if (readGranted) vm.refresh()
    }

    LaunchedEffect(Unit) {
        if (readGranted) vm.refresh()
    }

    // 从系统相册/查看器返回时重拉文件列表（在那里可能删除过照片），
    // 页面未重建 LaunchedEffect(Unit) 不会再触发
    val galleryLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(galleryLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && readGranted) {
                vm.refresh()
            }
        }
        galleryLifecycle.lifecycle.addObserver(observer)
        onDispose { galleryLifecycle.lifecycle.removeObserver(observer) }
    }

    // Toast 提示
    state.toast?.let { toast ->
        LaunchedEffect(toast) {
            android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onSystemDeleteFinished() else vm.clearSelection()
    }
    val writePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.deleteDirect(state.selected) else vm.clearSelection()
    }

    fun requestDelete() {
        val uris = state.selected.toList()
        if (uris.isEmpty()) return
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
            Build.VERSION.SDK_INT == 29 -> vm.deleteDirect(state.selected)
            else -> {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    vm.deleteDirect(state.selected)
                } else {
                    writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    BackHandler(enabled = state.selectionMode) { vm.clearSelection() }    // 捏合缩放状态：张开（zoom>1）→ 缩略图变大 → 列数减少；捏合（zoom<1）→ 列数增多，最多 5 列
    var viewportWidth by remember { mutableIntStateOf(0) }
    var pinchZoom by remember { mutableFloatStateOf(1f) }
    val baseMinSizePx = if (viewportWidth > 0) viewportWidth.toFloat() / columns else 110f * density.density
    // 首次组合时 viewportWidth 尚未测量（为 0），必须跳过钳制，
    // 否则单元格被压成 1px，LazyGrid 会按全屏数量铺格子导致卡死
    val minSizePx = if (viewportWidth > 0) {
        (baseMinSizePx * pinchZoom)
            .coerceIn(viewportWidth / 5.4f, viewportWidth / 2.6f)
            .coerceAtLeast(1f)
    } else {
        baseMinSizePx.coerceAtLeast(1f)
    }
    val minSizeDp = with(density) { minSizePx.toDp() }
    val gridState = rememberLazyGridState()
    val entries = state.items

    fun findEntryUnder(x: Float, y: Float): MediaSaver.MediaEntry? {
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            x >= it.offset.x && x <= it.offset.x + it.size.width &&
                y >= it.offset.y && y <= it.offset.y + it.size.height
        } ?: return null
        return entries.getOrNull(info.index)
    }

    // 应用内全屏查看器已按用户要求移除：点图直接跳系统相册

    Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding(),
        ) {
        // 顶部：返回、标题、多选状态、删除
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.selectionMode) "已选 ${state.selected.size} 项" else "照片与录像",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!state.selectionMode) {
                    Text(
                        "共 ${entries.size} 项",
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.selectionMode) {
                IconButton(onClick = { vm.clearSelection() }) {
                    Icon(Icons.Default.Close, contentDescription = "取消多选", tint = Color.White)
                }
                IconButton(
                    onClick = { requestDelete() },
                    enabled = state.selected.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除选中项",
                        tint = if (state.selected.isEmpty()) Color(0xFF666666) else Color(0xFFFF5252),
                    )
                }
            }
        }

        if (!readGranted) {
            // 未授权：提示并引导授权（浏览全部照片需要）
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "浏览手机相册需要存储权限",
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { readPermLauncher.launch(readPermissions()) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("授予权限")
                }
            }
        } else if (entries.isEmpty()) {
            // 空状态
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.padding(16.dp),
                )
                Text("相册里还没有照片\n去拍一张吧", color = Color(0xFFAAAAAA), textAlign = TextAlign.Center)
                Button(onClick = onGoShoot, modifier = Modifier.padding(top = 16.dp)) {
                    Text("去拍照")
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.FixedSize(minSizeDp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportWidth = it.width }
                    // 双指捏合调整列数（3-5 列），松手落定
                    .pointerInput(columns) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            var zoomed = false
                            do {
                                val event = awaitPointerEvent(pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    zoomed = true
                                    pinchZoom = (pinchZoom * zoom).coerceIn(0.5f, 2.2f)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (zoomed) {
                                // 张开变少列、捏合变多列（3-5 列）
                                val target = (columns / pinchZoom).roundToInt().coerceIn(3, 6)
                                pinchZoom = 1f
                                if (target != columns) vm.setColumns(target)
                            }
                        }
                    }
                    // 多选模式下按住滑动连续选中
                    .pointerInput(state.selectionMode, entries) {
                        if (!state.selectionMode) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos ->
                                findEntryUnder(pos.x, pos.y)?.let { vm.onHoverSelect(it.uri) }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                findEntryUnder(change.position.x, change.position.y)?.let { vm.onHoverSelect(it.uri) }
                            },
                        )
                    },
                // 无边框正方形网格：无间距、无内边距
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(entries.size, key = { entries[it].uri.toString() }) { index ->
                    val entry = entries[index]
                    MediaCell(
                        entry = entry,
                        selectionMode = state.selectionMode,
                        selected = entry.uri in state.selected,
                        onClick = {
                            if (state.selectionMode) {
                                vm.onItemTap(entry.uri)
                            } else {
                                openViewer(context, entry)
                            }
                        },
                        onLongClick = { vm.onItemLongPress(entry.uri) },
                    )
                }
            }
        }
    }
}

private fun openViewer(context: android.content.Context, entry: MediaSaver.MediaEntry) {
    val mime = entry.mimeType.ifBlank { if (entry.isVideo) "video/mp4" else "image/jpeg" }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(entry.uri, mime)
        // 部分系统图库（如 MIUI）依赖 ClipData 定位具体文件
        clipData = android.content.ClipData.newRawUri("", entry.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(context, "未找到可以打开的系统图库应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    entry: MediaSaver.MediaEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = entry.uri,
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (entry.isVideo && entry.durationMs > 0) {
            val seconds = entry.durationMs / 1000
            Text(
                String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (selectionMode) {
            if (selected) {
                // 红底白钩，醒目防误删（用户反馈 2026-10-10）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .background(Color(0xFFFF4444), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已选中",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                // 未选中：白色空心圆
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .border(2.dp, Color.White, CircleShape),
                )
            }
        }
    }
}
