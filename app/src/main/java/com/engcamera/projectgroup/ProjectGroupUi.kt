package com.engcamera.projectgroup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engcamera.data.ProjectGroupEntry
import com.engcamera.data.ProjectGroups
import com.engcamera.i18n.I18n
import com.engcamera.ui.theme.ChromeScrim
import kotlinx.coroutines.flow.StateFlow

/** 工程组操作接口：编辑器与相机页共用同一套管理能力 */
interface ProjectGroupHandler {
    val projects: StateFlow<ProjectGroups>
    fun selectProject(id: String)

    /** nameEdited=用户改过预填名（配合"新建工程标题联动"开关，工程名写入新工程标题） */
    fun createProject(name: String, nameEdited: Boolean = false): String
    fun deleteProjects(ids: Set<String>)
    fun renameProject(id: String, name: String)

    /** 批量重置勾选的工程：模板/样式/宽度/字段值全部回默认（保留各自锚点与重力设置） */
    fun resetProjects(ids: Set<String>)
}

/**
 * 工程组选择器：屏幕居中弹出（遮罩淡入+面板滑入）。
 *
 * 两套布局（v0.6.37 重新梳理，用户反馈 2026-11-18「界面不和谐」）：
 * - **普通模式**：每行=名称+「当前」徽标+重命名；点行切换工程并关闭面板。
 *   头部右侧只有「新建」一个动作，副标题提示「长按多选」。
 * - **多选模式**（长按任意行进入，自动勾上该行）：行首出现勾选框，点行=勾选/取消，
 *   头部右侧换成「取消」退出多选，底部出现「重置 / 删除」操作条。
 *
 * 行高与左右内边距统一（水平 14dp），保证文字不贴卡片边缘；
 * 面板高度改为「随内容自适应 + 上限 62% 屏高」，工程少时不再留大片空白。
 */
@Composable
fun ProjectGroupSelector(
    visible: Boolean,
    handler: ProjectGroupHandler,
    onDismiss: () -> Unit,
) {
    val groups by handler.projects.collectAsState()
    var pendingDelete by remember { mutableStateOf(setOf<String>()) }
    var selectionMode by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // 批量删除二次确认（用户需求 2026-11-18）：与「重置」同规格，删除不可恢复
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectGroupEntry?>(null) }
    // 重命名弹窗里的删除：先弹二次确认（删除不可恢复，与批量删除同规格）
    var showRenameDeleteConfirm by remember { mutableStateOf(false) }

    /** 退出多选：清勾选并收起勾选框 */
    fun exitSelection() {
        selectionMode = false
        pendingDelete = emptySet()
    }

    /**
     * 返回键（用户反馈 2026-11-18）：原来面板没注册 BackHandler，按返回直接冒泡到
     * Activity 退出整个 app。分层语义与「危险操作二次确认」同款：
     * 多选中 → 先退多选（不丢面板）；普通模式 → 关闭面板。
     * 弹窗（重命名/新建/确认）是独立 Dialog 窗口，系统返回由它们自己先消费。
     */
    BackHandler(enabled = visible) {
        if (selectionMode) exitSelection() else onDismiss()
    }

    // 面板关闭时复位多选状态：AnimatedVisibility 只是隐藏，组合还在，
    // 不复位的话下次打开会带着上次的勾选和勾选框
    LaunchedEffect(visible) {
        if (!visible) exitSelection()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 面板最高占 62% 屏高（工程多时可滚动，工程少时随内容收缩）
        val maxPanelHeight = maxHeight * 0.62f

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
                    .heightIn(max = maxPanelHeight),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ProjectGroupHeader(
                        selectionMode = selectionMode,
                        selectedCount = pendingDelete.size,
                        onExitSelection = { exitSelection() },
                        onCreate = { showCreate = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    LazyColumn(
                        // weight(fill=false)：工程少时按内容收缩，不撑满整个面板
                        modifier = Modifier.weight(1f, fill = false),
                        // 水平 6dp + 行内 14dp = 20dp，与头部标题左缘对齐（视觉一条竖线）
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(groups.projects.size, key = { groups.projects[it].id }) { i ->
                            val entry = groups.projects[i]
                            val active = entry.id == groups.current
                            val checked = entry.id in pendingDelete
                            ProjectRow(
                                entry = entry,
                                active = active,
                                selectionMode = selectionMode,
                                checked = checked,
                                onToggleCheck = { on ->
                                    pendingDelete =
                                        if (on) pendingDelete + entry.id else pendingDelete - entry.id
                                },
                                onSelect = {
                                    if (selectionMode) {
                                        // 多选模式里点行=勾选/取消（用户拍板 2026-11-18）
                                        pendingDelete = if (checked) {
                                            pendingDelete - entry.id
                                        } else {
                                            pendingDelete + entry.id
                                        }
                                    } else {
                                        handler.selectProject(entry.id)
                                        onDismiss()
                                    }
                                },
                                onLongPress = {
                                    // 长按=进入多选并勾上长按的这行（默认不显示勾选框）
                                    selectionMode = true
                                    pendingDelete = pendingDelete + entry.id
                                },
                                onRename = {
                                    renameTarget = entry
                                    showRename = true
                                },
                            )
                        }
                    }

                    // 底部操作条：仅多选模式出现（普通模式没有任何批量动作，不占空间）
                    AnimatedVisibility(
                        visible = selectionMode,
                        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                    ) {
                        SelectionActionBar(
                            selectedCount = pendingDelete.size,
                            onReset = { showResetConfirm = true },
                            onDelete = { showDeleteConfirm = true },
                        )
                    }
                }
            }
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text(I18n.t("重置勾选的工程")) },
                text = {
                    Text(
                        I18n.t("是否重置勾选的 {n} 个工程的水印为默认值？它们的模板、样式、宽度与全部字段值将恢复默认（水印位置保留）。")
                            .replace("{n}", "${pendingDelete.size}"),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        handler.resetProjects(pendingDelete)
                        exitSelection()
                        showResetConfirm = false
                    }) { Text(I18n.t("确认重置")) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text(I18n.t("取消")) }
                },
            )
        }

        // 批量删除二次确认（用户需求 2026-11-18）：与重置同规格，写明数量与不可恢复
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(I18n.t("删除工程")) },
                text = {
                    Text(
                        I18n.t("删除所选 {n} 个工程？其水印配置将一并删除，不可恢复。")
                            .replace("{n}", "${pendingDelete.size}"),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        handler.deleteProjects(pendingDelete)
                        exitSelection()
                        showDeleteConfirm = false
                    }) {
                        Text(I18n.t("删除"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text(I18n.t("取消")) }
                },
            )
        }

        if (showRename && renameTarget != null) {
            var name by remember(renameTarget) { mutableStateOf(renameTarget!!.name) }
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text(I18n.t("重命名工程")) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(I18n.t("工程名")) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        handler.renameProject(renameTarget!!.id, name)
                        showRename = false
                    }) { Text(I18n.t("确定")) }
                },
                dismissButton = {
                    // 取消左边放红色「删除」（用户需求 2026-11-18）：重命名面板里顺手删掉这个工程，
                    // 删除不可恢复所以走二次确认（与批量删除同规格）
                    Row {
                        TextButton(onClick = { showRenameDeleteConfirm = true }) {
                            Text(I18n.t("删除"), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { showRename = false }) { Text(I18n.t("取消")) }
                    }
                },
            )
        }

        if (showRenameDeleteConfirm && renameTarget != null) {
            AlertDialog(
                onDismissRequest = { showRenameDeleteConfirm = false },
                title = { Text(I18n.t("删除工程")) },
                text = {
                    Text(
                        I18n.t("删除工程「{name}」？其水印配置将一并删除，不可恢复。")
                            .replace("{name}", renameTarget!!.name),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val deletedId = renameTarget!!.id
                        handler.deleteProjects(setOf(deletedId))
                        // 被删的工程若也在勾选集里，勾选计数要同步清掉（否则底部栏会多算一个）
                        pendingDelete = pendingDelete - deletedId
                        if (pendingDelete.isEmpty()) selectionMode = false
                        showRenameDeleteConfirm = false
                        showRename = false
                        renameTarget = null
                    }) {
                        Text(I18n.t("删除"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDeleteConfirm = false }) {
                        Text(I18n.t("取消"))
                    }
                },
            )
        }

        if (showCreate) {
            val initialName = "工程" + (groups.projects.size + 1)
            var name by remember { mutableStateOf(initialName) }
            AlertDialog(
                onDismissRequest = { showCreate = false },
                title = { Text(I18n.t("新建工程")) },
                text = {
                    Column {
                        Text(I18n.t("将复制当前工程的水印内容，可在切换后修改。"))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(I18n.t("工程名")) },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 用户改过预填名才算"输入了名称"（配合标题联动开关写入新工程标题）
                        val edited = name.trim() != initialName
                        val id = handler.createProject(name, edited)
                        handler.selectProject(id) // 创建后自动切换到新工程
                        showCreate = false
                        onDismiss()
                    }) { Text(I18n.t("创建")) }
                },
                dismissButton = {
                    TextButton(onClick = { showCreate = false }) { Text(I18n.t("取消")) }
                },
            )
        }
    }
}

/**
 * 面板头部：左侧标题+副标题，右侧动作随模式切换。
 * 普通模式=「新建」胶囊；多选模式=「取消」（退出多选）。
 * 不再把取消/重置/新建三个按钮并排挤在一行（用户反馈 2026-11-18 视觉不和谐）。
 */
@Composable
private fun ProjectGroupHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    onExitSelection: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                I18n.t("工程组"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                // 副标题去掉数量前缀（v0.6.37）：带「N 个工程」时英文在窄面板里被截成
                // "long-press to…"，中文也挤。数量不是关键信息，操作提示才是
                if (selectionMode) {
                    I18n.t("已选 {n} 个 · 点行勾选").replace("{n}", "$selectedCount")
                } else {
                    I18n.t("点名称切换工程，长按可多选")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (selectionMode) {
            TextButton(onClick = onExitSelection) { Text(I18n.t("取消")) }
        } else {
            Surface(
                onClick = onCreate,
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
                        I18n.t("新建"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** 多选模式底部操作条：重置（tonal）+ 删除所选（error），未勾选时禁用 */
@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabled = selectedCount > 0
    Row(
        Modifier
            .fillMaxWidth()
            // 左右 6dp 与列表卡片外缘对齐（列表 contentPadding 6dp），底部 12dp 收口
            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onReset,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.weight(1f),
        ) {
            Row(
                Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    I18n.t("重置"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
        }
        Surface(
            onClick = onDelete,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.weight(1f),
        ) {
            Row(
                Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    I18n.t("删除所选 {n} 个").replace("{n}", "$selectedCount"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 单行工程：勾选框（仅多选模式）| 名称 | 「当前」徽标 | 重命名。
 *
 * 布局要点（v0.6.37，用户反馈 2026-11-18「文字左边贴着背景」）：
 * - 水平内边距统一 14dp（原来只有 2dp，没勾选框时文字几乎压在卡片圆角上）
 * - 普通行不铺背景，只有「当前」/「勾选」两态才上色——减少满屏色块更清爽
 * - 行高由垂直 12dp 内边距 + 单行文字撑出，与勾选框（48dp 触摸区）居中对齐
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    entry: ProjectGroupEntry,
    active: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onToggleCheck: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    checked -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                    active -> MaterialTheme.colorScheme.secondaryContainer
                    // 普通行也铺一层卡片底（比面板底色高一档），
                    // 让每一行都看得出是独立条目而不是一片空白（用户反馈「不和谐」）
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            // 长按进入多选（勾选框默认不显示）
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
            .padding(start = if (selectionMode) 4.dp else 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = checked,
                onCheckedChange = onToggleCheck,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = when {
                checked -> MaterialTheme.colorScheme.onErrorContainer
                active -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp),
        )
        if (active) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    I18n.t("当前"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onRename) {
            Icon(
                Icons.Default.Edit,
                contentDescription = I18n.t("重命名") + entry.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
