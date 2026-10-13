package com.engcamera.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engcamera.ui.theme.LocalAppDarkTheme
import com.engcamera.util.MediaSaver
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 相册页（计划书 3.3）：
 * - 只显示 DCIM/Camera 下 engCamera_ 前缀的照片与录像，时间倒序
 * - 双指捏合 3-5 列，松手落定并记忆
 * - 长按进入多选，滑动涂抹连续选择/取消，边缘自动滚动
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
    val viewConfiguration = LocalViewConfiguration.current

    val darkTheme = LocalAppDarkTheme.current
    DisposableEffect(darkTheme) {
        val style =
            if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }

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

    BackHandler(enabled = state.mode == SelectionMode.MULTI_SELECT) { vm.clearSelection() }

    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var pinchZoom by remember { mutableFloatStateOf(1f) }

    val baseMinSizePx = if (viewportWidth > 0) viewportWidth.toFloat() / columns else 110f * density.density
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
    // pointerInput 的 PointerInputScope 是受限挂起作用域，不能直接 launch 或
    // 调用 gridState.scrollBy；这里取一个外部 CoroutineScope 供手势里使用。
    val gestureScope = rememberCoroutineScope()

    // 圆圈热区：MediaCell 右上角 align(TopEnd).padding(4dp).size(24dp)。
    // 用 36dp 触摸目标居中于圆圈，方便手指点中。
    val checkPadPx = with(density) { 4.dp.toPx() }
    val checkSizePx = with(density) { 24.dp.toPx() }
    val checkTouchPx = with(density) { 36.dp.toPx() }

    /**
     * 把屏幕坐标映射到当前可见项。使用 gridState.layoutInfo，不依赖视图实例，
     * 因此列表滚动、视图复用都不影响命中结果。
     */
    fun hitTest(pos: Offset): MediaSaver.MediaEntry? {
        val items = state.items
        val layoutInfo = gridState.layoutInfo
        for (item in layoutInfo.visibleItemsInfo) {
            val x = item.offset.x.toFloat()
            val y = item.offset.y.toFloat()
            val w = item.size.width.toFloat()
            val h = item.size.height.toFloat()
            if (pos.x >= x && pos.x < x + w && pos.y >= y && pos.y < y + h) {
                val idx = item.index
                if (idx in items.indices) return items[idx]
            }
        }
        return null
    }

    /**
     * 判断屏幕坐标是否落在某项的圆圈热区。
     * 圆圈位置：格子右上角 align(TopEnd).padding(4dp).size(24dp)；
     * 热区以圆圈中心为中心、36dp 边长的正方形，便于手指点中。
     * 多选态下只有按下圆圈才开始涂抹，按下图片本体则交给列表滚动。
     */
    fun checkHitAt(pos: Offset): MediaSaver.MediaEntry? {
        val items = state.items
        val layoutInfo = gridState.layoutInfo
        val half = checkTouchPx / 2f
        for (item in layoutInfo.visibleItemsInfo) {
            val x = item.offset.x.toFloat()
            val y = item.offset.y.toFloat()
            val w = item.size.width.toFloat()
            val h = item.size.height.toFloat()
            if (pos.x >= x && pos.x < x + w && pos.y >= y && pos.y < y + h) {
                val idx = item.index
                if (idx in items.indices) {
                    val cx = x + w - checkPadPx - checkSizePx / 2f
                    val cy = y + checkPadPx + checkSizePx / 2f
                    if (pos.x >= cx - half && pos.x <= cx + half &&
                        pos.y >= cy - half && pos.y <= cy + half
                    ) {
                        return items[idx]
                    }
                }
            }
        }
        return null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.mode == SelectionMode.MULTI_SELECT) "已选 ${state.selected.size} 项" else "照片与录像",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.mode == SelectionMode.BROWSE) {
                    Text(
                        "共 ${entries.size} 项",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.mode == SelectionMode.MULTI_SELECT) {
                IconButton(onClick = { vm.clearSelection() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "取消多选",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = { requestDelete() },
                    enabled = state.selected.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除选中项",
                        tint = if (state.selected.isEmpty()) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        if (!readGranted) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "浏览手机相册需要存储权限",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    "相册里还没有照片\n去拍一张吧",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
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
                    .onSizeChanged {
                        viewportWidth = it.width
                        viewportHeightPx = it.height
                    }
                    // 双指捏合调整列数（3-5 列），松手落定
                    .pointerInput(columns) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var zoomed = false
                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    zoomed = true
                                    pinchZoom = (pinchZoom * zoom).coerceIn(0.5f, 2.2f)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (zoomed) {
                                val target = (columns / pinchZoom).roundToInt().coerceIn(3, 6)
                                pinchZoom = 1f
                                if (target != columns) vm.setColumns(target)
                            }
                        }
                    }
                    // 长按进入多选 + 滑动涂抹 + 边缘自动滚动
                    // 单指手势全部由这一层处理：浏览态判断“点击 / 长按 / 拖动滚动”，
                    // 多选态直接进入涂抹。长按后同一手势继续拖动，不会断。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val downPos = down.position
                            val downItem = hitTest(downPos)
                            val modeAtStart = state.mode

                            if (modeAtStart == SelectionMode.MULTI_SELECT) {
                                // —— 多选态：点圆圈涂抹，点图片/空白滚动 ——
                                // 只有按下在圆圈热区上才开始涂抹（按下瞬间决定 select/deselect）；
                                // 按下图片本体或空白处则不消费事件，交给 LazyGrid 正常上下滚动。
                                val checkItem = checkHitAt(downPos)
                                if (checkItem != null) {
                                    // 按下圆圈 → 进入涂抹
                                    vm.onTouchDown(checkItem.uri)
                                    var lastUri: Uri? = checkItem.uri
                                    var lastAutoScrollAt = 0L
                                    val edgeZonePx = 80f * density.density

                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break

                                        // 涂抹滑动时手指经过的整个项都算命中（不限圆圈），
                                        // 因为按下圆圈已经决定 paintMode，沿途整项涂抹才顺手。
                                        val item = hitTest(change.position)
                                        if (item != null && item.uri != lastUri) {
                                            vm.onTouchMove(item.uri)
                                            lastUri = item.uri
                                        }

                                        // 边缘自动滚动：手指进入顶部/底部热区后，
                                        // 按节流步长滚动列表，新进入手指下的项继续被涂抹。
                                        if (viewportHeightPx > 0) {
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastAutoScrollAt > 16L) {
                                                val delta = when {
                                                    change.position.y < edgeZonePx -> -12f
                                                    change.position.y > viewportHeightPx - edgeZonePx -> 12f
                                                    else -> 0f
                                                }
                                                if (delta != 0f) {
                                                    gestureScope.launch { gridState.scrollBy(delta) }
                                                    lastAutoScrollAt = now
                                                }
                                            }
                                        }
                                        change.consume()
                                    }
                                    vm.updateAutoScroll(AutoScrollDirection.NONE)
                                    vm.onTouchUp()
                                } else {
                                    // 按下图片本体或空白 → 不消费，让 LazyGrid 正常滚动
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                    }
                                }
                            } else {
                                // —— 浏览态：判断点击 / 长按 / 拖动 ——
                                var longPressed = false
                                var scrolled = false
                                var lastUri: Uri? = null
                                var longPressJob: Job? = null

                                if (downItem != null) {
                                    longPressJob = gestureScope.launch {
                                        delay(viewConfiguration.longPressTimeoutMillis)
                                        if (!scrolled && !longPressed) {
                                            longPressed = true
                                            vm.onLongPress(downItem.uri)
                                            lastUri = downItem.uri
                                        }
                                    }
                                }

                                var lastAutoScrollAt = 0L
                                val edgeZonePx = 80f * density.density

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                    if (!change.pressed) {
                                        longPressJob?.cancel()
                                        if (!longPressed && !scrolled && downItem != null) {
                                            openViewer(context, downItem)
                                        }
                                        if (longPressed) {
                                            vm.updateAutoScroll(AutoScrollDirection.NONE)
                                            vm.onTouchUp()
                                        }
                                        break
                                    }

                                    if (!longPressed && !scrolled) {
                                        if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                                            // 用户拖动 → 交给 LazyGrid 滚动，取消长按
                                            scrolled = true
                                            longPressJob?.cancel()
                                        }
                                    }

                                    if (longPressed) {
                                        // 长按已触发：后续拖动作为涂抹
                                        if (state.mode != SelectionMode.MULTI_SELECT) break
                                        val item = hitTest(change.position)
                                        if (item != null && item.uri != lastUri) {
                                            vm.onTouchMove(item.uri)
                                            lastUri = item.uri
                                        }
                                        if (viewportHeightPx > 0) {
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastAutoScrollAt > 16L) {
                                                val delta = when {
                                                    change.position.y < edgeZonePx -> -12f
                                                    change.position.y > viewportHeightPx - edgeZonePx -> 12f
                                                    else -> 0f
                                                }
                                                if (delta != 0f) {
                                                    gestureScope.launch { gridState.scrollBy(delta) }
                                                    lastAutoScrollAt = now
                                                }
                                            }
                                        }
                                        change.consume()
                                    }
                                    // 浏览态且未长按：不消费，让 LazyGrid 正常滚动
                                }
                            }
                        }
                    },
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(entries.size, key = { entries[it].uri.toString() }) { index ->
                    val entry = entries[index]
                    MediaCell(
                        entry = entry,
                        mode = state.mode,
                        selected = entry.uri in state.selected,
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
        clipData = android.content.ClipData.newRawUri("", entry.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(context, "未找到可以打开的系统图库应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MediaCell(
    entry: MediaSaver.MediaEntry,
    mode: SelectionMode,
    selected: Boolean,
) {
    Box(
        Modifier
            .aspectRatio(1f),
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
        if (mode == SelectionMode.MULTI_SELECT) {
            if (selected) {
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
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .shadow(2.dp, CircleShape)
                        .border(2.dp, Color.White, CircleShape),
                )
            }
        }
    }
}