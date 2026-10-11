package com.engcamera.data

import android.content.Context
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

private val Context.dataStore by preferencesDataStore(name = "eng_camera")

/**
 * 水印模板持久化：字段、样式、位置、旋转角、重力开关；外加相册列数记忆。
 * 修改即时保存（对应 4.5 保存模板：点击保存或退出编辑器时自动保存）。
 */
class WatermarkRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // v2 键：v0.1.0 的旧默认（顶部居中、重力关闭）与新版语义不同，直接启用新默认
    private val stateKey = stringPreferencesKey("watermark_state_v2")
    private val columnsKey = intPreferencesKey("gallery_columns")
    private val historyKey = stringPreferencesKey("field_history")
    private val saveOriginalKey = booleanPreferencesKey("save_original")
    private val shutterSoundKey = booleanPreferencesKey("shutter_sound")

    private val _state = MutableStateFlow(WatermarkState())
    val state: StateFlow<WatermarkState> = _state.asStateFlow()

    private val _galleryColumns = MutableStateFlow(3)
    val galleryColumns: StateFlow<Int> = _galleryColumns.asStateFlow()

    /** 字段填写历史：标签 -> 最近填过的值（新在后，最多 5 条），用于编辑器快速填充 */
    private val _fieldHistory = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val fieldHistory: StateFlow<Map<String, List<String>>> = _fieldHistory.asStateFlow()

    /** 保存原图原视频：开启后拍照/录像同时保存无水印原片与水印成片（默认关） */
    private val _saveOriginal = MutableStateFlow(false)
    val saveOriginal: StateFlow<Boolean> = _saveOriginal.asStateFlow()

    /** 快门声音：拍照时播放系统快门音（默认关，兼容部分地区法律） */
    private val _shutterSound = MutableStateFlow(false)
    val shutterSound: StateFlow<Boolean> = _shutterSound.asStateFlow()

    init {
        scope.launch {
            val stored = context.dataStore.data.first()[stateKey]
            if (stored != null) {
                runCatching { json.decodeFromString<WatermarkState>(stored) }
                    .onSuccess { _state.value = ensureCountField(migrateWidth(it)) }
            }
            context.dataStore.data.first()[historyKey]?.let { raw ->
                runCatching { json.decodeFromString<Map<String, List<String>>>(raw) }
                    .onSuccess { _fieldHistory.value = it }
            }
            val cols = context.dataStore.data.first()[columnsKey] ?: 3
            _galleryColumns.value = cols.coerceIn(3, 5)
            _saveOriginal.value = context.dataStore.data.first()[saveOriginalKey] ?: false
            _shutterSound.value = context.dataStore.data.first()[shutterSoundKey] ?: false
        }
    }

    /** 存量数据补齐计数字段（不可删除项，缺失时自动补默认值 1） */
    private fun ensureCountField(s: WatermarkState): WatermarkState =
        if (s.fields.none { it.type == FieldType.COUNT }) {
            s.copy(fields = s.fields + WatermarkField("f_count", FieldType.COUNT, "计数", "1"))
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

    /** 记录一次字段填写历史（按标签去重、保最新 5 条） */
    fun rememberFieldValue(label: String, value: String) {
        if (value.isBlank()) return
        _fieldHistory.value = _fieldHistory.value.toMutableMap().apply {
            val list = (this[label] ?: emptyList()).filter { it != value } + value
            put(label, list.takeLast(5))
        }
        scope.launch {
            context.dataStore.edit { it[historyKey] = json.encodeToString(_fieldHistory.value) }
        }
    }

    /** 所有修改走这里：同步更新内存，异步落盘。isTitle 唯一性在这里统一保证（待确认 5）。 */
    fun update(transform: (WatermarkState) -> WatermarkState) {
        val next = enforceTitleUniqueness(transform(_state.value))
        _state.value = next
        scope.launch {
            context.dataStore.edit { it[stateKey] = json.encodeToString(next) }
        }
    }

    fun setShutterSound(value: Boolean) {
        _shutterSound.value = value
        scope.launch {
            context.dataStore.edit { it[shutterSoundKey] = value }
        }
    }

    fun setSaveOriginal(value: Boolean) {
        _saveOriginal.value = value
        scope.launch {
            context.dataStore.edit { it[saveOriginalKey] = value }
        }
    }

    fun setGalleryColumns(columns: Int) {
        val c = columns.coerceIn(3, 5)
        _galleryColumns.value = c
        scope.launch {
            context.dataStore.edit { it[columnsKey] = c }
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
}
