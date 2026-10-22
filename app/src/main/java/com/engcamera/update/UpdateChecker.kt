package com.engcamera.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.engcamera.data.appDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 远程最新版本信息 */
data class UpdateInfo(val version: String, val url: String)

/**
 * 检查 GitHub Release 最新版本（用户需求 2026-10-22）：
 * 每天最多联网核对一次，结果缓存在 DataStore——同一天再次启动直接用缓存，
 * 不重复请求；联网失败不写入"已检查"标记，下次启动会自动重试。
 */
object UpdateChecker {
    private const val RELEASE_API = "https://api.github.com/repos/zy8270/engCamera/releases/latest"
    private val lastCheckDayKey = stringPreferencesKey("update_last_check_day")
    private val latestVersionKey = stringPreferencesKey("update_latest_version")
    private val latestUrlKey = stringPreferencesKey("update_latest_url")

    /**
     * 读取缓存的更新信息；当缓存的版本不高于当前版本时返回 null（视为已更新）。
     * 只读本地，不联网。
     */
    suspend fun cached(context: Context, currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = context.appDataStore.data.first()
        val version = prefs[latestVersionKey] ?: return@withContext null
        val url = prefs[latestUrlKey] ?: return@withContext null
        if (isNewer(version, currentVersion)) UpdateInfo(version, url) else null
    }

    /**
     * 启动时调用：今天已检查过则直接返回缓存；否则联网查询最新 Release，
     * 成功才记录检查日期。返回需要提示的更新信息（无更新/失败为 null）。
     */
    suspend fun checkIfNeeded(context: Context, currentVersion: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val prefs = context.appDataStore.data.first()
            if (prefs[lastCheckDayKey] == today) return@withContext cached(context, currentVersion)

            val info = fetchLatest() ?: return@withContext cached(context, currentVersion)
            context.appDataStore.edit {
                it[lastCheckDayKey] = today
                it[latestVersionKey] = info.version
                it[latestUrlKey] = info.url
            }
            if (isNewer(info.version, currentVersion)) info else null
        }

    /** 请求 GitHub Release API，取 tag_name / html_url；任何失败返回 null（静默降级） */
    private fun fetchLatest(): UpdateInfo? {
        return runCatching {
            val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "engCamera-update-check")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val tag = json.optString("tag_name").trim()
                val url = json.optString("html_url").trim()
                if (tag.isBlank()) {
                    null
                } else {
                    UpdateInfo(
                        version = tag.removePrefix("v").removePrefix("V"),
                        url = url.ifBlank { "https://github.com/zy8270/engCamera/releases" },
                    )
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 版本号比较（按数字段逐位比，忽略前缀 v 与非数字后缀）：latest 是否比 current 新 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size, 1)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Long> =
        Regex("\\d+").findAll(v).mapNotNull { it.value.toLongOrNull() }.toList()
}
