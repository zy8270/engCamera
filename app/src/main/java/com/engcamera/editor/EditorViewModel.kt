package com.engcamera.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engcamera.EngCameraApp
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkField
import com.engcamera.data.WatermarkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorViewModel(app: Application) : AndroidViewModel(app),
    com.engcamera.projectgroup.ProjectGroupHandler {

    private val container = (app as EngCameraApp).container
    private val repo = container.repository

    val state: StateFlow<WatermarkState> = repo.state

    /** 自动定位文本（相机页共享同一个 LocationSource） */
    val locationText: StateFlow<String> = container.locationSource.text

    /** 自动天气文本（随定位联动获取） */
    val weatherText: StateFlow<String> = container.locationSource.weather

    /** 手动刷新天气（编辑器"刷新天气"按钮用） */
    fun refreshWeather() = container.locationSource.refreshWeather()

    // ---------- 水印模板 ----------

    val userTemplates: StateFlow<List<com.engcamera.data.WatermarkTemplate>> = repo.userTemplates

    fun applyTemplate(id: String) = repo.applyTemplate(id)

    fun importTemplateText(text: String, fallbackName: String): String? =
        repo.importTemplateText(text, fallbackName)

    fun exportTemplateText(): Pair<String, String>? = repo.exportTemplateText()

    fun deleteUserTemplate(id: String) = repo.deleteUserTemplate(id)

    override fun resetCurrentProject() = repo.resetCurrentProject()

    /** 模板选项：颜色类（写入 WatermarkStyle 对应键） */
    fun setTemplateColor(key: String, color: Long) = repo.update { s ->
        val st = when (key) {
            "accentColor" -> s.style.copy(accentColor = color)
            "titleBgColor" -> s.style.copy(titleBgColor = color)
            "bodyBgColor" -> s.style.copy(bodyBgColor = color)
            "bodyTextColor" -> s.style.copy(bodyTextColor = color)
            "borderColor" -> s.style.copy(borderColor = color)
            else -> s.style
        }
        s.copy(style = st)
    }

    /** 模板选项：数值类 */
    fun setTemplateScalar(key: String, value: Float) = repo.update { s ->
        val st = when (key) {
            "bgOpacity" -> s.style.copy(bgOpacity = value.toInt().coerceIn(0, 100))
            "cornerRatio" -> s.style.copy(cornerRatio = value)
            else -> s.style
        }
        s.copy(style = st)
    }

    private val _dynamic = MutableStateFlow(DynamicValues())
    val dynamic: StateFlow<DynamicValues> = _dynamic.asStateFlow()

    /** 编辑器里的旋转角：预览固定角（重力跟随在编辑器中按 0 展示） */
    val rotationDeg: StateFlow<Float> = combine(state) { s ->
        if (s.first().followGravity) 0f else s.first().rotationDeg
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    init {
        viewModelScope.launch {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            while (isActive) {
                // 时间每秒刷新，位置跟随 locationText 实时值（与相机页同一数据源）
                _dynamic.value = _dynamic.value.copy(
                    time = fmt.format(Date()),
                    location = locationText.value,
                    weather = weatherText.value,
                )
                delay(1000)
            }
        }
        viewModelScope.launch {
            weatherText.collect { w ->
                _dynamic.value = _dynamic.value.copy(weather = w)
            }
        }
        viewModelScope.launch {
            locationText.collect { text ->
                // 定位文字变化时立即更新 dynamic（不等下一秒 tick），预览实时同步。
                // 注意：不写字段值——字段只有用户在编辑对话框点"确定"才提交（取消不得产生"自定义"）
                _dynamic.value = _dynamic.value.copy(location = text)
            }
        }
    }

    /** 刷新定位：仅刷新定位数据源（自动值随流更新），不写入字段；提交由对话框"确定"负责 */
    fun refreshLocation() = container.locationSource.refreshNow()

    fun setEnabled(id: String, enabled: Boolean) = repo.update { s ->
        s.copy(fields = s.fields.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) = repo.update { s ->
        s.copy(fields = s.fields.filterNot { it.id == id })
    }

    fun addField(type: FieldType, label: String, value: String) {
        val finalLabel = label.ifBlank { if (type == FieldType.NOTE) "备注" else "自定义" }
        repo.update { s ->
            s.copy(
                fields = s.fields + WatermarkField(
                    id = "c_${System.currentTimeMillis()}",
                    type = type,
                    label = finalLabel,
                    value = value,
                ),
            )
        }
        repo.rememberFieldValue(finalLabel, value)
    }

    fun updateField(id: String, label: String, value: String, isTitle: Boolean) {
        repo.update { s ->
            s.copy(
                fields = s.fields.map {
                    if (it.id == id) {
                        it.copy(label = label, value = value, isTitle = isTitle)
                    } else {
                        it
                    }
                },
            )
        }
        repo.rememberFieldValue(label, value)
    }

    /** 字段填写历史（按标签，新在后，最多 5 条），编辑对话框快速填充用 */
    val fieldHistory: StateFlow<Map<String, List<String>>> = repo.fieldHistory

    /** 工程组 */
    override val projects: StateFlow<com.engcamera.data.ProjectGroups> = repo.projects

    override fun selectProject(id: String) = repo.selectProject(id)

    override fun createProject(name: String) = repo.createProject(name)

    override fun deleteProjects(ids: Set<String>) = repo.deleteProjects(ids)

    override fun renameProject(id: String, name: String) = repo.renameProject(id, name)


    fun move(from: Int, to: Int) = repo.update { s ->
        if (from !in s.fields.indices || to !in s.fields.indices || from == to) {
            s
        } else {
            val list = s.fields.toMutableList()
            val item = list.removeAt(from)
            list.add(to, item)
            s.copy(fields = list)
        }
    }

}
