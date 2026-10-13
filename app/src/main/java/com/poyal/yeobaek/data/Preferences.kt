package com.poyal.yeobaek.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.appStore by preferencesDataStore("app_preferences")
private val Context.txtStore by preferencesDataStore("txt_preferences")

enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class TxtPaper { FOLLOW_APP, LIGHT, SEPIA, DARK }
data class AppPreferences(val theme: AppTheme = AppTheme.SYSTEM, val tree: String = "", val label: String = "")
data class TxtPreferences(val serif: Boolean = true, val size: Float = 18f, val line: Float = 1.85f, val margin: Float = 24f, val paper: TxtPaper = TxtPaper.FOLLOW_APP)

class Preferences(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val tree = stringPreferencesKey("start_folder_uri")
    private val label = stringPreferencesKey("start_folder_label")
    private val serif = booleanPreferencesKey("serif")
    private val size = floatPreferencesKey("size")
    private val line = floatPreferencesKey("line")
    private val margin = floatPreferencesKey("margin")
    private val paper = stringPreferencesKey("paper")
    val app = context.appStore.data.map { p -> AppPreferences(
        runCatching { AppTheme.valueOf(p[theme] ?: "SYSTEM") }.getOrDefault(AppTheme.SYSTEM), p[tree] ?: "", p[label] ?: "") }
    val txt = context.txtStore.data.map { p -> TxtPreferences(
        p[serif] ?: true, (p[size] ?: 18f).coerceIn(14f, 32f), (p[line] ?: 1.85f).coerceIn(1.3f, 2.1f),
        (p[margin] ?: 24f).coerceIn(16f, 40f), runCatching { TxtPaper.valueOf(p[paper] ?: "FOLLOW_APP") }.getOrDefault(TxtPaper.FOLLOW_APP)) }
    suspend fun setTheme(value: AppTheme) { context.appStore.edit { it[theme] = value.name } }
    suspend fun setFolder(uri: String, name: String) { context.appStore.edit { it[tree] = uri; it[label] = name } }
    suspend fun setFont(value: Boolean) { context.txtStore.edit { it[serif] = value } }
    suspend fun setSize(value: Float) { context.txtStore.edit { it[size] = value.coerceIn(14f, 32f) } }
    suspend fun setLine(value: Float) { context.txtStore.edit { it[line] = value.coerceIn(1.3f, 2.1f) } }
    suspend fun setMargin(value: Float) { context.txtStore.edit { it[margin] = value.coerceIn(16f, 40f) } }
    suspend fun setPaper(value: TxtPaper) { context.txtStore.edit { it[paper] = value.name } }
    suspend fun resetTxt() { context.txtStore.edit { it.clear() } }
}
