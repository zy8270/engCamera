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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
}

/**
 * 工程组选择器：屏幕居中弹出（遮罩淡入+面板底部滑入）；
 * 点工程名切换，勾选后批量删除，笔重命名。编辑器与相机页共用。
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
            Column(
                Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.5f)
                    .shadow(10.dp, RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                    .padding(12.dp),
            ) {
                // 头部：工程组 | 删除工程 | 新建工程
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "工程组",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "删除工程",
                        color = if (pendingDelete.isEmpty()) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = pendingDelete.isNotEmpty()) {
                                handler.deleteProjects(pendingDelete)
                                pendingDelete = emptySet()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "新建工程",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showCreate = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "点工程名切换；勾选后可批量删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                LazyColumn(Modifier.weight(1f)) {
                    items(groups.projects.size, key = { groups.projects[it].id }) { i ->
                        val entry = groups.projects[i]
                        val active = entry.id == groups.current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = entry.id in pendingDelete,
                                onCheckedChange = { on ->
                                    pendingDelete =
                                        if (on) pendingDelete + entry.id else pendingDelete - entry.id
                                },
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        handler.selectProject(entry.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    entry.name + if (active) "（当前）" else "",
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                            IconButton(onClick = {
                                renameTarget = entry
                                showRename = true
                            }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "重命名" + entry.name,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
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
