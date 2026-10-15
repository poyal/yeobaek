package com.poyal.yeobaek.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.poyal.yeobaek.image.*

private val Context.appStore by preferencesDataStore("app_preferences")
private val Context.txtStore by preferencesDataStore("txt_preferences")
private val Context.pdfStore by preferencesDataStore("pdf_preferences")
private val Context.imageStore by preferencesDataStore("image_preferences")

enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class TxtPaper { FOLLOW_APP, LIGHT, SEPIA, DARK }
@kotlinx.serialization.Serializable
data class AppPreferences(val theme: AppTheme = AppTheme.SYSTEM, val tree: String = "", val label: String = "")
@kotlinx.serialization.Serializable
data class TxtPreferences(val font: TxtFont = TxtFont.NOTO_SERIF, val size: Float = 18f, val line: Float = 1.85f, val margin: Float = 24f, val paper: TxtPaper = TxtPaper.FOLLOW_APP)
enum class PdfFit { WIDTH, PAGE }
enum class PdfCanvas { AUTO, LIGHT, DARK }
@kotlinx.serialization.Serializable
data class PdfPreferences(val fit: PdfFit = PdfFit.WIDTH, val spacing: Float = 8f, val inverted: Boolean = false, val canvas: PdfCanvas = PdfCanvas.AUTO)

class Preferences(private val context: Context) {
    suspend fun explicitReaderFields(): Map<String, Map<String, String>> {
        val t = context.txtStore.data.first().asMap().keys.map { it.name }.toSet()
        val p = context.pdfStore.data.first().asMap().keys.map { it.name }.toSet()
        val i = context.imageStore.data.first().asMap().keys.map { when (it.name) { "vertical_fit" -> "verticalFit"; "paged_fit" -> "pagedFit"; "cover" -> "coverAlone"; else -> it.name } }.toSet()
        return mapOf("common:TXT" to ReaderSettingValues.txt(txt.first()).filterKeys { it in t || it == "font" && "serif" in t },
            "common:PDF" to ReaderSettingValues.pdf(pdf.first()).filterKeys { it in p }, "common:IMAGE" to ReaderSettingValues.image(image.first()).filterKeys { it in i })
    }
    suspend fun restoreThemeIfMissing(value: AppTheme) { context.appStore.edit { if (it[theme] == null) it[theme] = value.name } }

    val image = context.imageStore.data.map { p -> ImagePreferences(
        mode = enumValue(p, "mode", ImageMode.VERTICAL), direction = enumValue(p, "direction", ImageDirection.LTR),
        spread = enumValue(p, "spread", ImageSpread.SINGLE), coverAlone = p[booleanPreferencesKey("cover")] ?: true,
        verticalFit = enumValue(p, "vertical_fit", ImageFit.WIDTH), pagedFit = enumValue(p, "paged_fit", ImageFit.PAGE),
        spacing = (p[floatPreferencesKey("spacing")] ?: 0f).coerceIn(0f, 24f),
        inverted = p[booleanPreferencesKey("inverted")] ?: false, canvas = enumValue(p, "canvas", ImageCanvas.AUTO)) }
    suspend fun setImage(value: ImagePreferences) { context.imageStore.edit { p ->
        p[stringPreferencesKey("mode")] = value.mode.name; p[stringPreferencesKey("direction")] = value.direction.name
        p[stringPreferencesKey("spread")] = value.spread.name; p[booleanPreferencesKey("cover")] = value.coverAlone
        p[stringPreferencesKey("vertical_fit")] = value.verticalFit.name; p[stringPreferencesKey("paged_fit")] = value.pagedFit.name
        p[floatPreferencesKey("spacing")] = value.spacing.coerceIn(0f, 24f); p[booleanPreferencesKey("inverted")] = value.inverted
        p[stringPreferencesKey("canvas")] = value.canvas.name
    } }
    suspend fun setImageInverted(value: Boolean) { context.imageStore.edit { it[booleanPreferencesKey("inverted")] = value } }
    suspend fun resetImage() { context.imageStore.edit { it.clear() } }
    private inline fun <reified T : Enum<T>> enumValue(p: androidx.datastore.preferences.core.Preferences, key: String, default: T): T =
        runCatching { enumValueOf<T>(p[stringPreferencesKey(key)] ?: default.name) }.getOrDefault(default)
    private val theme = stringPreferencesKey("theme")
    private val tree = stringPreferencesKey("start_folder_uri")
    private val label = stringPreferencesKey("start_folder_label")
    private val serif = booleanPreferencesKey("serif")
    private val font = stringPreferencesKey("font")
    private val size = floatPreferencesKey("size")
    private val line = floatPreferencesKey("line")
    private val margin = floatPreferencesKey("margin")
    private val paper = stringPreferencesKey("paper")
    private val pdfFit = stringPreferencesKey("fit")
    private val pdfSpacing = floatPreferencesKey("spacing")
    private val pdfInverted = booleanPreferencesKey("inverted")
    private val pdfCanvas = stringPreferencesKey("canvas")
    val pdf = context.pdfStore.data.map { p -> PdfPreferences(
        runCatching { PdfFit.valueOf(p[pdfFit] ?: "WIDTH") }.getOrDefault(PdfFit.WIDTH),
        (p[pdfSpacing] ?: 8f).coerceIn(0f, 24f), p[pdfInverted] ?: false,
        runCatching { PdfCanvas.valueOf(p[pdfCanvas] ?: "AUTO") }.getOrDefault(PdfCanvas.AUTO)) }
    suspend fun setPdfFit(value: PdfFit) { context.pdfStore.edit { it[pdfFit] = value.name } }
    suspend fun setPdfSpacing(value: Float) { context.pdfStore.edit { it[pdfSpacing] = value.coerceIn(0f, 24f) } }
    suspend fun setPdfInverted(value: Boolean) { context.pdfStore.edit { it[pdfInverted] = value } }
    suspend fun setPdfCanvas(value: PdfCanvas) { context.pdfStore.edit { it[pdfCanvas] = value.name } }
    suspend fun resetPdf() { context.pdfStore.edit { it.clear() } }
    val app = context.appStore.data.map { p -> AppPreferences(
        runCatching { AppTheme.valueOf(p[theme] ?: "SYSTEM") }.getOrDefault(AppTheme.SYSTEM), p[tree] ?: "", p[label] ?: "") }
    val txt = context.txtStore.data.map { p -> TxtPreferences(
        TxtFont.fromPreferences(p[font], p[serif]), (p[size] ?: 18f).coerceIn(14f, 32f), (p[line] ?: 1.85f).coerceIn(1.3f, 2.1f),
        (p[margin] ?: 24f).coerceIn(16f, 40f), runCatching { TxtPaper.valueOf(p[paper] ?: "FOLLOW_APP") }.getOrDefault(TxtPaper.FOLLOW_APP)) }
    suspend fun setTheme(value: AppTheme) { context.appStore.edit { it[theme] = value.name } }
    suspend fun setFolder(uri: String, name: String) { context.appStore.edit { it[tree] = uri; it[label] = name } }
    suspend fun setFont(value: TxtFont) { context.txtStore.edit { it[font] = value.name } }
    suspend fun setSize(value: Float) { context.txtStore.edit { it[size] = value.coerceIn(14f, 32f) } }
    suspend fun setLine(value: Float) { context.txtStore.edit { it[line] = value.coerceIn(1.3f, 2.1f) } }
    suspend fun setMargin(value: Float) { context.txtStore.edit { it[margin] = value.coerceIn(16f, 40f) } }
    suspend fun setPaper(value: TxtPaper) { context.txtStore.edit { it[paper] = value.name } }
    suspend fun resetTxt() { context.txtStore.edit { it.clear() } }
}
