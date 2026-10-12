package com.engcamera.projectgroup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engcamera.data.ProjectGroupEntry
import com.engcamera.data.ProjectGroups
import com.engcamera.ui.theme.ChromeScrim
import kotlinx.coroutines.flow.StateFlow

/** 工程组操作接口：编辑器与相机页共用同一套管理能力 */
interface ProjectGroupHandler {
    val projects: StateFlow<ProjectGroups>
    fun selectProject(id: String)
    fun createProject(name: String): String
    fun deleteProjects(ids: Set<String>)
    fun renameProject(id: String, name: String)

    /** 重置当前工程：模板/样式/宽度/字段值全部回默认（保留锚点与重力设置） */
    fun resetCurrentProject()
}

/**
 * 工程组选择器：屏幕居中弹出（遮罩淡入+面板底部滑入）。
 * 全部颜色走主题 token（0.6.0 日夜间随主题）。
 */
@Composable
fun ProjectGroupSelector(
    visible: Boolean,
    handler: ProjectGroupHandler,
    onDismiss: () -> Unit,
) {
    val groups by handler.projects.collectAsState()
    var pendingDelete by remember { mutableStateOf(setOf<String>()) }
    var showCreate by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectGroupEntry?>(null) }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：独立淡入淡出
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(ChromeScrim)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        // 面板：居中圆角+阴影，从底部滑入
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it } +
                fadeIn(tween(240)),
            exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it } +
                fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.55f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(20.dp)) {
                    // 头部：标题+统计 | 新建
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "工程组",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${groups.projects.size} 个工程 · 点名称切换",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 重置当前工程：进入管理模式（勾选任意工程）后出现，确认弹窗防误触
                        if (pendingDelete.isNotEmpty()) {
                            TextButton(onClick = { showResetConfirm = true }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("重置")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        // 新建：tonal 胶囊按钮
                        Surface(
                            onClick = { showCreate = true },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "新建",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))

                    LazyColumn(Modifier.weight(1f)) {
                        items(groups.projects.size, key = { groups.projects[it].id }) { i ->
                            val entry = groups.projects[i]
                            val active = entry.id == groups.current
                            val checked = entry.id in pendingDelete
                            ProjectRow(
                                entry = entry,
                                active = active,
                                checked = checked,
                                onToggleCheck = { on ->
                                    pendingDelete =
                                        if (on) pendingDelete + entry.id else pendingDelete - entry.id
                                },
                                onSelect = {
                                    handler.selectProject(entry.id)
                                    onDismiss()
                                },
                                onRename = {
                                    renameTarget = entry
                                    showRename = true
                                },
                            )
                        }
                    }

                    // 底部删除栏：勾选后出现（替代原头部"删除工程"文字）
                    AnimatedVisibility(visible = pendingDelete.isNotEmpty()) {
                        Surface(
                            onClick = {
                                handler.deleteProjects(pendingDelete)
                                pendingDelete = emptySet()
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(
                                "删除所选 ${pendingDelete.size} 个工程",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showResetConfirm) {
            val currentName = groups.projects.firstOrNull { it.id == groups.current }?.name ?: "当前工程"
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("重置当前工程") },
                text = {
                    Text("是否重置当前工程水印为默认值？「$currentName」的模板、样式、宽度与全部字段值将恢复默认（水印位置保留）。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        handler.resetCurrentProject()
                        showResetConfirm = false
                    }) { Text("确认重置") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
                },
            )
        }

        if (showRename && renameTarget != null) {
            var name by remember(renameTarget) { mutableStateOf(renameTarget!!.name) }
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text("重命名工程") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("工程名") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        handler.renameProject(renameTarget!!.id, name)
                        showRename = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showRename = false }) { Text("取消") }
                },
            )
        }

        if (showCreate) {
            var name by remember { mutableStateOf("工程" + (groups.projects.size + 1)) }
            AlertDialog(
                onDismissRequest = { showCreate = false },
                title = { Text("新建工程") },
                text = {
                    Column {
                        Text("将复制当前工程的水印内容，可在切换后修改。")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("工程名") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val id = handler.createProject(name)
                        handler.selectProject(id) // 创建后自动切换到新工程
                        showCreate = false
                        onDismiss()
                    }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreate = false }) { Text("取消") }
                },
            )
        }
    }
}

/** 单行工程：勾选框 | 名称（+当前徽标）| 重命名；行整体圆角卡片，当前行高亮 */
@Composable
private fun ProjectRow(
    entry: ProjectGroupEntry,
    active: Boolean,
    checked: Boolean,
    onToggleCheck: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    checked -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                    active -> MaterialTheme.colorScheme.secondaryContainer
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onSelect)
            .padding(start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggleCheck,
        )
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (checked) {
                MaterialTheme.colorScheme.onErrorContainer
            } else if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        )
        if (active) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    "当前",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onRename) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "重命名" + entry.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
