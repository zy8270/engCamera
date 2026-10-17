package com.engcamera.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 全 app 共享的 DataStore（LocationSource 缓存也用它，避免多实例冲突） */
internal val Context.appDataStore by preferencesDataStore(name = "eng_camera")

/**
 * 水印模板持久化：字段、样式、位置、旋转角、重力开关；外加相册列数记忆。
 * 修改即时保存（对应 4.5 保存模板：点击保存或退出编辑器时自动保存）。
 */
class WatermarkRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // v2 键：v0.1.0 的旧默认（顶部居中、重力关闭）与新版语义不同，直接启用新默认
    private val stateKey = stringPreferencesKey("watermark_state_v2")
    private val columnsKey = intPreferencesKey("gallery_columns_v2") // v2：默认 4 列，弃用旧的 3 列默认
    private val historyKey = stringPreferencesKey("field_history")
    private val saveOriginalKey = booleanPreferencesKey("save_original")
    private val projectsKey = stringPreferencesKey("projects_v1")
    private val shutterSoundKey = booleanPreferencesKey("shutter_sound")
    private val volumeShutterKey = booleanPreferencesKey("volume_shutter")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val templatesKey = stringPreferencesKey("templates_v1") // 用户导入的水印模板
    private val deletableMigratedKey = booleanPreferencesKey("deletable_migrated_v1")
    private val createTitleFromNameKey = booleanPreferencesKey("create_title_from_name")
    private val editorBackdropKey = booleanPreferencesKey("editor_backdrop")
    private val languageKey = stringPreferencesKey("app_language")
    private val photoConfirmKey = booleanPreferencesKey("photo_confirm")
    private val annotationToolKey = intPreferencesKey("annotation_tool")
    private val saveTreeUriKey = stringPreferencesKey("save_tree_uri")
    private val saveTreeNameKey = stringPreferencesKey("save_tree_name")

    private val _state = MutableStateFlow(WatermarkState())
    val state: StateFlow<WatermarkState> = _state.asStateFlow()

    private val _galleryColumns = MutableStateFlow(4)
    val galleryColumns: StateFlow<Int> = _galleryColumns.asStateFlow()

    /** 字段填写历史：标签 -> 最近填过的值（新在后，最多 50 条），用于编辑器快速填充 */
    private val _fieldHistory = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val fieldHistory: StateFlow<Map<String, List<String>>> = _fieldHistory.asStateFlow()

    /** 保存原图原视频：开启后拍照/录像同时保存无水印原片与水印成片（默认关） */
    private val _saveOriginal = MutableStateFlow(false)
    val saveOriginal: StateFlow<Boolean> = _saveOriginal.asStateFlow()

    /** 新建工程标题联动：新建工程组输入的工程名自动写入该工程水印标题（默认开） */
    private val _createTitleFromName = MutableStateFlow(true)
    val createTitleFromName: StateFlow<Boolean> = _createTitleFromName.asStateFlow()

    /** 取景器截帧作编辑器预览背景（默认开，用户拍板 2026-10-16） */
    private val _editorBackdrop = MutableStateFlow(true)
    val editorBackdrop: StateFlow<Boolean> = _editorBackdrop.asStateFlow()

    /** 拍照后确认（v193：默认开，拍摄后先预览标注再保存，用户拍板 2026-10-16） */
    private val _photoConfirm = MutableStateFlow(true)
    val photoConfirm: StateFlow<Boolean> = _photoConfirm.asStateFlow()

    /** 上次使用的标注工具（v195：跨照片/重启记忆，用户拍板 2026-10-16） */
    private val _annotationTool = MutableStateFlow(0)
    val annotationTool: StateFlow<Int> = _annotationTool.asStateFlow()

    /** 界面语言（v191：跟随系统/中文/English，用户拍板全量中英） */
    private val _languageMode = MutableStateFlow(com.engcamera.i18n.AppLanguageMode.SYSTEM)
    val languageMode: StateFlow<com.engcamera.i18n.AppLanguageMode> = _languageMode.asStateFlow()

    /** 快门声音：拍照时播放系统快门音（默认关，兼容部分地区法律） */
    private val _shutterSound = MutableStateFlow(false)
    val shutterSound: StateFlow<Boolean> = _shutterSound.asStateFlow()

    /** 音量键拍照：主界面音量键视同快门（默认开） */
    private val _volumeShutter = MutableStateFlow(true)
    val volumeShutter: StateFlow<Boolean> = _volumeShutter.asStateFlow()

    /** 外观主题：跟随系统 / 日间 / 夜间（默认跟随系统） */
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** 自定义文件保存目录（SAF tree）：null = 默认 DCIM/Camera */
    private val _saveTree = MutableStateFlow<Pair<Uri, String>?>(null)
    val saveTree: StateFlow<Pair<Uri, String>?> = _saveTree.asStateFlow()

    /** 用户导入的水印模板（内置模板见 TemplatePresets） */
    private val _userTemplates = MutableStateFlow<List<WatermarkTemplate>>(emptyList())
    val userTemplates: StateFlow<List<WatermarkTemplate>> = _userTemplates.asStateFlow()

    /** 工程组：多份水印配置快速切换；current 的 state 始终等于 state 流 */
    private val _projects = MutableStateFlow(ProjectGroups())
    val projects: StateFlow<ProjectGroups> = _projects.asStateFlow()

    init {
        scope.launch {
            val stored = context.appDataStore.data.first()[stateKey]
            if (stored != null) {
                runCatching { json.decodeFromString<WatermarkState>(stored) }
                    .onSuccess {
                        _state.value = ensureCountField(
                            ensureWeatherField(
                                migrateWidth(
                                    migrateWebScale(
                                        migrateWebOpacity(
                                            migrateShadowAlpha(
                                                migrateLiquidGlass(
                                                    migrateFieldOrder(migrateTitleBg(migrateAccentTitle(migrateDefaultTitle(it)))),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
            }
            context.appDataStore.data.first()[historyKey]?.let { raw ->
                runCatching { json.decodeFromString<Map<String, List<String>>>(raw) }
                    .onSuccess { _fieldHistory.value = it }
            }
            val cols = context.appDataStore.data.first()[columnsKey] ?: 4
            _galleryColumns.value = cols.coerceIn(3, 5)
            _saveOriginal.value = context.appDataStore.data.first()[saveOriginalKey] ?: false
            _createTitleFromName.value = context.appDataStore.data.first()[createTitleFromNameKey] ?: true
            _editorBackdrop.value = context.appDataStore.data.first()[editorBackdropKey] ?: true
            _photoConfirm.value = context.appDataStore.data.first()[photoConfirmKey] ?: true
            _annotationTool.value = (context.appDataStore.data.first()[annotationToolKey] ?: 0).coerceIn(0, 3)
            runCatching {
                com.engcamera.i18n.AppLanguageMode.valueOf(
                    context.appDataStore.data.first()[languageKey] ?: "SYSTEM",
                )
            }.getOrNull()?.let {
                _languageMode.value = it
                com.engcamera.i18n.I18n.mode = it
            }
            _shutterSound.value = context.appDataStore.data.first()[shutterSoundKey] ?: false
            _volumeShutter.value = context.appDataStore.data.first()[volumeShutterKey] ?: true
            _themeMode.value = context.appDataStore.data.first()[themeModeKey]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
            val savedTreeUri = context.appDataStore.data.first()[saveTreeUriKey]
            if (savedTreeUri != null) {
                _saveTree.value = android.net.Uri.parse(savedTreeUri) to
                    (context.appDataStore.data.first()[saveTreeNameKey] ?: "所选目录")
            }
            // 一次性迁移：计数默认关闭（2026-10-11）——已有状态里的计数也关一次，用户可再手动开
            if (context.appDataStore.data.first()[booleanPreferencesKey("count_default_off_migrated")] != true) {
                _state.value = _state.value.copy(
                    fields = _state.value.fields.map {
                        if (it.type == FieldType.COUNT && it.enabled) it.copy(enabled = false) else it
                    },
                )
                scope.launch {
                    context.appDataStore.edit {
                        it[booleanPreferencesKey("count_default_off_migrated")] = true
                        it[stateKey] = json.encodeToString(_state.value)
                    }
                }
            }
            val storedProjects = context.appDataStore.data.first()[projectsKey]
            _projects.value = runCatching { json.decodeFromString<ProjectGroups>(storedProjects!!) }
                .getOrElse {
                    // 首次使用：现有水印状态打包为"默认工程"
                    ProjectGroups(
                        current = "default",
                        projects = listOf(ProjectGroupEntry("default", "默认工程", _state.value)),
                    )
                }
                .let { g ->
                    g.copy(
                        projects = g.projects.map { p ->
                            p.copy(
                                state = migrateFieldOrder(
                                    migrateWebScale(
                                        migrateWebOpacity(
                                            migrateShadowAlpha(
                                                migrateLiquidGlass(migrateTitleBg(migrateAccentTitle(migrateDefaultTitle(p.state)))),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                }
            // 一次性迁移：液态玻璃记录点默认关闭（2026-10-16）——v158 预设默认曾把 recDot=true
            // 带进液态玻璃存量状态，用户拍板该模板记录点默认关（可再手动开）
            if (context.appDataStore.data.first()[booleanPreferencesKey("lg_recdot_off_migrated")] != true) {
                fun lgRecDotOff(s: WatermarkState) =
                    if (s.style.layout == "LIQUID_GLASS" && s.style.recDot) s.copy(style = s.style.copy(recDot = false)) else s
                _state.value = lgRecDotOff(_state.value)
                _projects.value = _projects.value.copy(
                    projects = _projects.value.projects.map { p -> p.copy(state = lgRecDotOff(p.state)) },
                )
                scope.launch {
                    context.appDataStore.edit {
                        it[booleanPreferencesKey("lg_recdot_off_migrated")] = true
                        it[stateKey] = json.encodeToString(_state.value)
                        it[projectsKey] = json.encodeToString(_projects.value)
                    }
                }
            }
            // 用户导入模板
            context.appDataStore.data.first()[templatesKey]?.let { raw ->
                runCatching { json.decodeFromString<List<WatermarkTemplate>>(raw) }
                    .onSuccess { _userTemplates.value = it }
            }
            // 模板解析桥：内置 + 用户导入
            WatermarkTemplates.resolver = { id ->
                TemplatePresets.find(id)
                    ?: _userTemplates.value.firstOrNull { it.id == id }
            }
            // 一次性迁移：deletable 标志（旧数据按类型规则：仅 NOTE/CUSTOM 可删），覆盖所有工程槽位
            if (context.appDataStore.data.first()[deletableMigratedKey] != true) {
                fun migrate(fields: List<WatermarkField>) = fields.map {
                    it.copy(deletable = it.type == FieldType.NOTE || it.type == FieldType.CUSTOM)
                }
                _state.value = _state.value.copy(fields = migrate(_state.value.fields))
                _projects.value = _projects.value.copy(
                    projects = _projects.value.projects.map { p -> p.copy(state = p.state.copy(fields = migrate(p.state.fields))) },
                )
                scope.launch {
                    context.appDataStore.edit {
                        it[deletableMigratedKey] = true
                        it[stateKey] = json.encodeToString(_state.value)
                        it[projectsKey] = json.encodeToString(_projects.value)
                    }
                }
            }
        }
    }

    /** 存量数据补齐计数字段（不可删除项，缺失时自动补默认值 1） */
    private fun ensureCountField(s: WatermarkState): WatermarkState =
        if (s.fields.none { it.type == FieldType.COUNT }) {
            s.copy(fields = s.fields + WatermarkField("f_count", FieldType.COUNT, "计数", "1"))
        } else {
            s
        }

    /** 存量数据迁移：默认标题底色 0xFF1D4E89 → 半透明 0xCC1D4E89（用户拍板 2026-10-13）。
     *  仅恰好等于旧默认值时替换，用户自定义过的颜色不动；替换后特征消失，幂等。 */
    private fun migrateTitleBg(s: WatermarkState): WatermarkState =
        if (s.style.titleBgColor == 0xFF1D4E89L) {
            s.copy(style = s.style.copy(titleBgColor = 0xCC1D4E89L))
        } else {
            s
        }

    /** 存量数据迁移：旧默认字段顺序（v0.6.2 前：备注在时间前）重排为新默认（标题/时间/位置/天气/计数/备注）。
     *  仅当字段 id 序列恰好等于旧默认六件套时触发——用户手动调过顺序或加过自定义字段的不动；重排后特征消失，天然幂等。 */
    private fun migrateFieldOrder(s: WatermarkState): WatermarkState {
        val oldOrder = listOf("f_project", "f_note", "f_time", "f_location", "f_weather", "f_count")
        if (s.fields.map { it.id } != oldOrder) return s
        val byId = s.fields.associateBy { it.id }
        return s.copy(
            fields = listOf("f_project", "f_time", "f_location", "f_weather", "f_count", "f_note")
                .map { byId.getValue(it) },
        )
    }

    /** 存量数据补齐天气字段（固定项不可删除，只可关闭） */
    private fun ensureWeatherField(s: WatermarkState): WatermarkState =
        if (s.fields.none { it.type == FieldType.WEATHER }) {
            // 插在计数之前，与默认字段顺序一致
            val idx = s.fields.indexOfFirst { it.type == FieldType.COUNT }.let { if (it < 0) s.fields.size else it }
            s.copy(fields = s.fields.toMutableList().apply add@{ add(idx, WatermarkField("f_weather", FieldType.WEATHER, "天气", "")) })
        } else {
            s
        }

    /** 存量数据迁移：水印块宽度统一到 0.576（v0.2.7：0.72→0.48 收窄过窄，按用户反馈 ×1.2 加宽） */
    private fun migrateWidth(s: WatermarkState): WatermarkState =
        if (s.style.maxWidthRatio == 0.72f || s.style.maxWidthRatio == 0.48f) {
            s.copy(style = s.style.copy(maxWidthRatio = 0.576f))
        } else {
            s
        }

    /** 存量数据迁移：极简白影/边框卡片标题默认强调色 深蓝 0xFF1D4E89 → 黄 0xFFFFB300（用户拍板 2026-10-14，
     *  深色底深色字可读性差）。仅这两个模板且恰好等于旧默认值时替换，自定义过的不动；替换后特征消失，幂等。 */
    private fun migrateAccentTitle(s: WatermarkState): WatermarkState =
        if (s.templateId in setOf("minimal", "border") && s.style.accentColor == 0xFF1D4E89L) {
            s.copy(style = s.style.copy(accentColor = 0xFFFFB300L))
        } else {
            s
        }

    /** 存量数据迁移：网页模板整体缩放默认 0.8 → 1.5（用户拍板 2026-10-14，默认太小）。
     *  仅恰好等于旧默认值时替换，用户手动调过的不动；替换后特征消失，幂等。 */
    /** 存量迁移：阴影透明度默认改 100%（用户拍板 2026-10-16）——恰好=旧默认 0.71 才替换，幂等 */
    private fun migrateShadowAlpha(s: WatermarkState): WatermarkState =
        if (s.style.shadowAlpha == 0.71f) {
            s.copy(style = s.style.copy(shadowAlpha = 1f))
        } else {
            s
        }

    private fun migrateWebScale(s: WatermarkState): WatermarkState =
        if (s.style.webScale == 0.8f) {
            s.copy(style = s.style.copy(webScale = 1.5f))
        } else {
            s
        }

    /** 存量数据迁移：网页面板不透明度独立化（v156）——旧版网页 {BG_A} 用原生 bgOpacity，
     *  迁到 webBgOpacity 保视觉不变。仅网页后端模板（内置 id 或 u_ 导入）且 webBgOpacity
     *  仍是新字段默认值（从未用新键调过）时执行一次；替换后特征消失，幂等。 */
    private fun migrateWebOpacity(s: WatermarkState): WatermarkState {
        val isWeb = TemplatePresets.find(s.templateId)?.backend == TemplateBackend.WEB ||
            s.templateId.startsWith("u_")
        if (!isWeb || s.style.webBgOpacity != 85) return s
        return s.copy(style = s.style.copy(webBgOpacity = s.style.bgOpacity.coerceIn(0, 100)))
    }

    /** 存量数据迁移：液态玻璃参数体系切换到 AndroidLiquidGlassView 库（v158）——
     *  v156/157 的旧比例参数（glassCornerRadius<1）回库默认值；v159 圆角默认 40→15；
     *  旧 web 模板"玻璃拟态"（glass）已下架，切到液态玻璃（底部居中锚点）。
     *  替换后特征消失，幂等。 */
    private fun migrateLiquidGlass(s: WatermarkState): WatermarkState {
        var st = s
        if (st.templateId == "glass") {
            st = st.copy(
                templateId = "liquid_glass",
                anchorX = 0.5f,
                anchorY = 1f,
                style = st.style.copy(layout = "LIQUID_GLASS"),
            )
        }
        if (st.style.layout != "LIQUID_GLASS") return st
        if (st.style.glassCornerRadius < 1f || st.style.glassCornerRadius == 40f) {
            st = st.copy(
                style = st.style.copy(
                    glassCornerRadius = 15f,
                    glassRefractionHeight = 20f,
                    glassRefractionOffset = 70f,
                    glassTintAlpha = 0f,
                    glassTintRed = 1f,
                    glassTintGreen = 1f,
                    glassTintBlue = 1f,
                    glassDispersion = 0.5f,
                    glassBlurRadius = 0f,
                ),
            )
        }
        return st
    }

    /** 存量数据迁移：默认标题文字 "XX 工程施工记录" → "工程施工记录"（用户拍板 2026-10-14）。
     *  仅恰好等于旧默认值时替换，用户改过标题的不动；替换后特征消失，幂等。 */
    private fun migrateDefaultTitle(s: WatermarkState): WatermarkState =
        if (s.fields.any { it.type == FieldType.PROJECT_NAME && it.value == "XX 工程施工记录" }) {
            s.copy(
                fields = s.fields.map {
                    if (it.type == FieldType.PROJECT_NAME) it.copy(value = "工程施工记录") else it
                },
            )
        } else {
            s
        }

    /** 记录一次字段填写历史（按标签去重、保最新 5 条） */
    fun rememberFieldValue(label: String, value: String) {
        if (value.isBlank()) return
        _fieldHistory.value = _fieldHistory.value.toMutableMap().apply {
            val list = (this[label] ?: emptyList()).filter { it != value } + value
            put(label, list.takeLast(50))
        }
        scope.launch {
            context.appDataStore.edit { it[historyKey] = json.encodeToString(_fieldHistory.value) }
        }
    }

    /**
     * 所有修改走这里：同步更新内存，按需落盘。isTitle 唯一性在这里统一保证（待确认 5）。
     * persist=false 用于高频更新（如拖动水印每帧），调用方需自行 debounce 后 persistState()。
     */
    fun update(persist: Boolean = true, transform: (WatermarkState) -> WatermarkState) {
        val next = enforceTitleUniqueness(transform(_state.value))
        _state.value = next
        syncCurrentProject(next)
        if (persist) {
            scope.launch { persistStateToDisk() }
        }
    }

    /** 立即把当前水印状态与工程组写入 DataStore */
    suspend fun persistStateToDisk() {
        context.appDataStore.edit {
            it[stateKey] = json.encodeToString(_state.value)
            it[projectsKey] = json.encodeToString(_projects.value)
        }
    }

    /** 当前工程的 state 槽位与 state 流保持一致（切换/编辑后都调用） */
    private fun syncCurrentProject(state: WatermarkState) {
        _projects.value = _projects.value.let { g ->
            g.copy(
                projects = g.projects.map { if (it.id == g.current) it.copy(state = state) else it },
            )
        }
    }

    // ---------- 工程组 ----------
    /** 切换工程：水印内容整体换为目标工程的数据 */
    fun selectProject(id: String) {
        val g = _projects.value
        val entry = g.projects.firstOrNull { it.id == id } ?: return
        syncCurrentProject(_state.value)
        _state.value = entry.state
        _projects.value = g.copy(current = id)
        scope.launch {
            context.appDataStore.edit {
                it[stateKey] = json.encodeToString(entry.state)
                it[projectsKey] = json.encodeToString(_projects.value)
            }
        }
    }

    /** 新建工程：复制当前工程的水印（新工地通常与旧工地相近，改名微调即用）。
     *  nameEdited=用户改过预填名：且"新建工程标题联动"开时，工程名写入新工程水印的标题字段。 */
    fun createProject(name: String, nameEdited: Boolean = false): String {
        val id = "p_${System.currentTimeMillis()}"
        val finalName = name.ifBlank { "工程${_projects.value.projects.size + 1}" }
        val base = _state.value
        val newState = if (nameEdited && _createTitleFromName.value && finalName.isNotBlank()) {
            base.copy(
                fields = base.fields.map {
                    if (it.type == FieldType.PROJECT_NAME && it.isTitle) it.copy(value = finalName.trim()) else it
                },
            )
        } else {
            base
        }
        _projects.value = _projects.value.let { g ->
            g.copy(projects = g.projects + ProjectGroupEntry(id, finalName, newState))
        }
        scope.launch {
            context.appDataStore.edit { it[projectsKey] = json.encodeToString(_projects.value) }
        }
        return id
    }

    /** 重命名工程 */
    fun renameProject(id: String, name: String) {
        if (name.isBlank()) return
        _projects.value = _projects.value.let { g ->
            g.copy(projects = g.projects.map { if (it.id == id) it.copy(name = name.trim()) else it })
        }
        scope.launch {
            context.appDataStore.edit { it[projectsKey] = json.encodeToString(_projects.value) }
        }
    }

    /** 批量删除工程；删光则重建默认工程；当前工程被删则切到剩余第一个 */
    fun deleteProjects(ids: Set<String>) {
        if (ids.isEmpty()) return
        var g = _projects.value
        var remaining = g.projects.filter { it.id !in ids }
        if (remaining.isEmpty()) {
            remaining = listOf(ProjectGroupEntry("default", "默认工程", WatermarkState()))
        }
        var nextState = _state.value
        var nextCurrent = g.current
        if (remaining.none { it.id == g.current }) {
            nextCurrent = remaining.first().id
            nextState = remaining.first().state
            _state.value = nextState
        }
        g = g.copy(current = nextCurrent, projects = remaining)
        _projects.value = g
        scope.launch {
            context.appDataStore.edit {
                it[stateKey] = json.encodeToString(nextState)
                it[projectsKey] = json.encodeToString(g)
            }
        }
    }

    fun setVolumeShutter(value: Boolean) {
        _volumeShutter.value = value
        scope.launch {
            context.appDataStore.edit { it[volumeShutterKey] = value }
        }
    }

    /** 新建工程标题联动开关 */
    fun setCreateTitleFromName(value: Boolean) {
        _createTitleFromName.value = value
        scope.launch {
            context.appDataStore.edit { it[createTitleFromNameKey] = value }
        }
    }

    /** 标注工具记忆 */
    fun setAnnotationTool(tool: Int) {
        _annotationTool.value = tool.coerceIn(0, 3)
        scope.launch {
            context.appDataStore.edit { it[annotationToolKey] = tool.coerceIn(0, 3) }
        }
    }

    /** 拍照后确认开关 */
    fun setPhotoConfirm(value: Boolean) {
        _photoConfirm.value = value
        scope.launch {
            context.appDataStore.edit { it[photoConfirmKey] = value }
        }
    }

    /** 界面语言设置（即时生效：同步刷 I18n.mode） */
    fun setLanguageMode(mode: com.engcamera.i18n.AppLanguageMode) {
        _languageMode.value = mode
        com.engcamera.i18n.I18n.mode = mode
        scope.launch {
            context.appDataStore.edit { it[languageKey] = mode.name }
        }
    }

    /** 取景器截帧背景开关 */
    fun setEditorBackdrop(value: Boolean) {
        _editorBackdrop.value = value
        scope.launch {
            context.appDataStore.edit { it[editorBackdropKey] = value }
        }
    }

    // ---------- 水印模板 ----------

    /** 当前工程的模板 id */
    val currentTemplateId: String get() = _state.value.templateId

    /** 应用模板：布局与字段配置随模板走，字段值按类型保留（工程名/备注/计数等不丢），锚点与宽度不动 */
    fun applyTemplate(id: String) {
        val tpl = WatermarkTemplates.find(id) ?: return
        // 全量快照模板（用户导入/带数据导出）：整份状态原样还原（含字段值/样式/位置）
        if (tpl.snapshot != null) {
            update { tpl.snapshot.copy(templateId = tpl.id) }
            return
        }
        val cur = _state.value
        val newFields = tpl.fields.map { def ->
            val existing = cur.fields.firstOrNull { it.type == def.type }
            WatermarkField(
                id = existing?.id ?: "f_${def.type.name.lowercase()}_${System.currentTimeMillis()}",
                type = def.type,
                label = def.label,
                value = existing?.value.orEmpty(),
                // 字段开关跟随用户现状（用户需求 2026-10-17）：切模板不改数据和开关；
                // 目标模板独有的新字段才用模板默认开关（模板特殊情况）
                enabled = existing?.enabled ?: def.enabled,
                isTitle = def.isTitle,
                deletable = def.deletable,
            )
        }
        update {
            it.copy(
                templateId = tpl.id,
                fields = newFields,
                style = tpl.style.copy(maxWidthRatio = cur.style.maxWidthRatio),
                // 模板自带默认锚点（如液态玻璃=底部居中）则落到该位置，否则保留当前位置
                anchorX = tpl.anchorX ?: it.anchorX,
                anchorY = tpl.anchorY ?: it.anchorY,
            )
        }
    }

    /**
     * 导出当前模板为文本（剪贴板/文件共用）：原生=JSON（含布局/样式/字段/选项全部数据），
     * 网页=HTML 源。返回 (建议文件名, 文本)；无当前模板返回 null。
     */
    fun exportTemplateText(): Pair<String, String>? {
        val tpl = WatermarkTemplates.find(_state.value.templateId) ?: return null
        // 全量导出：模板定义 + 当前完整状态（字段值/颜色样式/宽度/锚点），
        // 另一台设备导入后还原为导出时的样子
        val export = TemplateExport(
            name = tpl.name,
            backend = tpl.backend,
            html = tpl.html,
            options = tpl.options,
            state = _state.value,
        )
        val text = json.encodeToString(export)
        return "${tpl.name}.json" to text
    }

    private fun addUserTemplate(tpl: WatermarkTemplate) {
        _userTemplates.value = _userTemplates.value + tpl
        scope.launch {
            context.appDataStore.edit { it[templatesKey] = json.encodeToString(_userTemplates.value) }
        }
    }

    /**
     * 导入模板文本，自动识别两种格式：
     * - JSON（原生模板，exportTemplateText 导出的）
     * - 网页 HTML（占位符契约见 TemplateHtml）
     * @return 成功返回模板名，失败返回 null
     */
    fun importTemplateText(text: String, fallbackName: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // ① 全量导出（v102+：TemplateExport JSON，含字段值/样式/位置）
        if (trimmed.startsWith("{")) {
            val export = runCatching { json.decodeFromString(TemplateExport.serializer(), trimmed) }
                .getOrNull()
            if (export != null && export.state.fields.isNotEmpty()) {
                val id = "u_${System.currentTimeMillis()}"
                addUserTemplate(
                    WatermarkTemplate(
                        id = id,
                        name = export.name.ifBlank { fallbackName },
                        backend = export.backend,
                        html = export.html,
                        options = export.options,
                        snapshot = export.state.copy(templateId = id),
                        builtIn = false,
                    ),
                )
                return export.name.ifBlank { fallbackName }
            }
            // ② 旧版单模板 JSON（v103）：作为快照模板导入
            val tpl = runCatching { json.decodeFromString<WatermarkTemplate>(trimmed) }.getOrNull()
            if (tpl != null && tpl.fields.isNotEmpty()) {
                val id = "u_${System.currentTimeMillis()}"
                addUserTemplate(
                    tpl.copy(
                        id = id,
                        builtIn = false,
                        snapshot = tpl.snapshot ?: WatermarkState(
                            templateId = id,
                            fields = tpl.fields.map {
                                WatermarkField(
                                    id = "f_${it.type.name.lowercase()}",
                                    type = it.type,
                                    label = it.label,
                                    value = "",
                                    enabled = it.enabled,
                                    isTitle = it.isTitle,
                                    deletable = it.deletable,
                                )
                            },
                            style = tpl.style,
                        ),
                    ),
                )
                return tpl.name.ifBlank { fallbackName }
            }
        }
        // 网页 HTML 模板
        val parsed = com.engcamera.watermark.TemplateHtml.parseImport(trimmed, fallbackName) ?: return null
        val (name, defs) = parsed
        addUserTemplate(
            WatermarkTemplate(
                id = "u_${System.currentTimeMillis()}",
                name = name,
                backend = TemplateBackend.WEB,
                html = trimmed,
                fields = defs,
                options = listOf(
                    TemplateOptionDef("webScale", "整体缩放", "slider", 0.5f, 3f),
                    TemplateOptionDef("webBgOpacity", "面板不透明度", "slider", 10f, 100f),
                    TemplateOptionDef("accentColor", "强调色", "color"),
                ),
                builtIn = false,
            ),
        )
        return name
    }

    /** 删除用户导入的模板；当前工程正在用它则先切回经典 */
    fun deleteUserTemplate(id: String) {
        if (_state.value.templateId == id) applyTemplate("classic")
        _userTemplates.value = _userTemplates.value.filterNot { it.id == id }
        scope.launch {
            context.appDataStore.edit { it[templatesKey] = json.encodeToString(_userTemplates.value) }
        }
    }

    /** 批量重置勾选的工程：模板/样式/宽度/字段值全部回默认（保留各自锚点、旋转角与重力设置） */
    fun resetProjects(ids: Set<String>) {
        if (ids.isEmpty()) return
        fun defaults(old: WatermarkState) = WatermarkState(
            anchorX = old.anchorX,
            anchorY = old.anchorY,
            rotationDeg = old.rotationDeg,
            followGravity = old.followGravity,
        )
        val currentId = _projects.value.current
        // 勾选项里有当前工程：走 update()（同步内存 state + 工程槽位 + 持久化）
        if (currentId in ids) {
            update { defaults(it) }
        }
        // 其余勾选工程：直接重置 _projects 里的槽位
        val remaining = ids - currentId
        if (remaining.isNotEmpty()) {
            _projects.value = _projects.value.copy(
                projects = _projects.value.projects.map {
                    if (it.id in remaining) it.copy(state = defaults(it.state)) else it
                },
            )
        }
        scope.launch {
            context.appDataStore.edit {
                it[stateKey] = json.encodeToString(_state.value)
                it[projectsKey] = json.encodeToString(_projects.value)
            }
        }
    }

    /**
     * 编辑器顶栏"重置"：回到当前模板初始状态——不切换模板（工程组里的重置才回默认模板，见 resetProjects）。
     * 非快照模板：字段值恢复应用默认值（与 defaultFields 一致，如标题"工程施工记录"）、
     * 字段结构/样式回模板定义（颜色、滑杆等调整一并还原）；
     * 快照模板（导入含数据）：整份快照还原。位置/旋转/重力等摆放属性两种模板都保留。
     */
    fun resetToTemplate() {
        val cur = _state.value
        val tpl = WatermarkTemplates.find(cur.templateId) ?: return
        val snap = tpl.snapshot
        if (snap != null) {
            update {
                snap.copy(
                    templateId = tpl.id,
                    anchorX = cur.anchorX,
                    anchorY = cur.anchorY,
                    rotationDeg = cur.rotationDeg,
                    followGravity = cur.followGravity,
                )
            }
            return
        }
        val defaultValues = defaultFields().associateBy { it.type }
        val newFields = tpl.fields.map { def ->
            val existing = cur.fields.firstOrNull { it.type == def.type }
            WatermarkField(
                id = existing?.id ?: "f_${def.type.name.lowercase()}_${System.currentTimeMillis()}",
                type = def.type,
                label = def.label,
                value = defaultValues[def.type]?.value.orEmpty(),
                enabled = def.enabled,
                isTitle = def.isTitle,
                deletable = def.deletable,
            )
        }
        update { it.copy(fields = newFields, style = tpl.style) }
    }

    fun setShutterSound(value: Boolean) {
        _shutterSound.value = value
        scope.launch {
            context.appDataStore.edit { it[shutterSoundKey] = value }
        }
    }

    fun setSaveOriginal(value: Boolean) {
        _saveOriginal.value = value
        scope.launch {
            context.appDataStore.edit { it[saveOriginalKey] = value }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        scope.launch {
            context.appDataStore.edit { it[themeModeKey] = mode.name }
        }
    }

    /** 设置自定义文件保存目录（SAF tree）；uri=null 恢复默认 DCIM/Camera */
    fun setSaveTree(uri: Uri?, name: String?) {
        _saveTree.value = if (uri != null) uri to (name ?: "所选目录") else null
        scope.launch {
            context.appDataStore.edit {
                if (uri != null) {
                    it[saveTreeUriKey] = uri.toString()
                    it[saveTreeNameKey] = name ?: "所选目录"
                } else {
                    it.remove(saveTreeUriKey)
                    it.remove(saveTreeNameKey)
                }
            }
        }
    }

    fun setGalleryColumns(columns: Int) {
        val c = columns.coerceIn(3, 6)
        _galleryColumns.value = c
        scope.launch {
            context.appDataStore.edit { it[columnsKey] = c }
        }
    }

    private fun enforceTitleUniqueness(state: WatermarkState): WatermarkState {
        if (state.fields.count { it.isTitle } <= 1) return state
        var titleSeen = false
        return state.copy(
            fields = state.fields.map { f ->
                if (f.isTitle && !titleSeen) {
                    titleSeen = true
                    f
                } else if (f.isTitle) {
                    f.copy(isTitle = false)
                } else {
                    f
                }
            },
        )
    }
}

/** 简单服务定位器，避免引入 DI 框架 */
class AppContainer(context: Context) {
    val repository = WatermarkRepository(context)
    val locationSource = com.engcamera.location.LocationSource(context)

    /** 音量键→快门：由相机页组合期间注册、离开时清空（编辑器/相册页不触发） */
    var volumeKeyHandler: (() -> Boolean)? = null
}
