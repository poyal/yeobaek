package com.poyal.yeobaek.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.poyal.yeobaek.image.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.readerStore by preferencesDataStore("reader_settings", produceMigrations = { listOf(RemoveFolderFavorites()) })
enum class SettingsScope { COMMON, FOLDER, BOOK }
enum class FolderSort { NAME, MODIFIED, SIZE }
@Serializable data class ScreenSettings(val brightness: Float = -1f, val rotation: String = "SYSTEM", val awake: Boolean = false)
@Serializable data class ReaderSettings(
    val overrides: Map<String, Map<String, String>> = emptyMap(),
    val folders: Map<String, String> = emptyMap(),
    val screen: ScreenSettings = ScreenSettings(),
    val sort: FolderSort = FolderSort.NAME,
    val crops: Map<String, CropRect> = emptyMap(),
) {
    fun fields(format: String, bookId: String?): Map<String, String> =
        overrides["common:$format"].orEmpty() +
            overrides["folder:${folders[bookId]}:$format"].orEmpty() +
            overrides["book:$bookId:$format"].orEmpty()
    fun editingFields(format: String, bookId: String?, scope: SettingsScope): Map<String, String> =
        overrides["common:$format"].orEmpty() +
            (if (scope != SettingsScope.COMMON) overrides["folder:${folders[bookId]}:$format"].orEmpty() else emptyMap()) +
            (if (scope == SettingsScope.BOOK) overrides["book:$bookId:$format"].orEmpty() else emptyMap())
    fun scopeKey(scope: SettingsScope, format: String, bookId: String?): String = when (scope) {
        SettingsScope.COMMON -> "common:$format"
        SettingsScope.FOLDER -> "folder:${requireNotNull(folders[bookId])}:$format"
        SettingsScope.BOOK -> "book:${requireNotNull(bookId)}:$format"
    }
    fun effectiveFieldScope(format: String, bookId: String?, field: String, fallback: SettingsScope): String {
        val candidates = listOfNotNull(bookId?.let { "book:$it:$format" }, folders[bookId]?.let { "folder:$it:$format" })
        return candidates.firstOrNull { overrides[it]?.containsKey(field) == true } ?: scopeKey(fallback, format, bookId)
    }
}

class ReaderSettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val data = context.readerStore.data.map { decode(it[key]) }
    private fun decode(value: String?) = value?.let { json.decodeFromString<ReaderSettings>(it) } ?: ReaderSettings()
    suspend fun update(change: (ReaderSettings) -> ReaderSettings) {
        context.readerStore.edit { it[key] = json.encodeToString(change(decode(it[key]))) }
    }
    suspend fun fields(scope: String, changes: Map<String, String>) = update {
        it.copy(overrides = it.overrides + (scope to (it.overrides[scope].orEmpty() + changes)))
    }
    suspend fun reset(scope: String) = update { it.copy(overrides = it.overrides - scope) }
}

/** Only changed fields are written. A size override never freezes the common font or paper. */
object ReaderSettingValues {
    fun epub(p: EpubReaderPreferences) = mapOf("mode" to p.mode.name, "publisherStyles" to "${p.publisherStyles}", "font" to p.font.name,
        "size" to "${p.size}", "line" to "${p.line}", "margin" to "${p.margin}", "paper" to p.paper.name, "canvas" to p.canvas.name, "menuVisibility" to p.menuVisibility.name)
    fun epub(p: EpubReaderPreferences, f: Map<String, String>) = p.copy(mode = f.enum("mode", p.mode), publisherStyles = f.bool("publisherStyles", p.publisherStyles),
        font = f.enum("font", p.font), size = f.number("size", p.size, .75f, 2.5f), line = f.number("line", p.line, 1.2f, 2.2f),
        margin = f.number("margin", p.margin, .5f, 2f), paper = f.enum("paper", p.paper), canvas = f.enum("canvas", p.canvas), menuVisibility = f.enum("menuVisibility", p.menuVisibility))
    fun txt(p: TxtPreferences) = mapOf("font" to p.font.name, "size" to "${p.size}", "line" to "${p.line}", "margin" to "${p.margin}", "paper" to p.paper.name, "menuVisibility" to p.menuVisibility.name)
    fun pdf(p: PdfPreferences) = mapOf("doubleTapZoom" to "${p.doubleTapZoom}", "pinchZoom" to "${p.pinchZoom}", "fit" to p.fit.name, "spacing" to "${p.spacing}", "inverted" to "${p.inverted}", "canvas" to p.canvas.name, "menuVisibility" to p.menuVisibility.name)
    fun image(p: ImagePreferences) = mapOf("doubleTapZoom" to "${p.doubleTapZoom}", "pinchZoom" to "${p.pinchZoom}",
        "tapToTurn" to "${p.tapToTurn}", "menuVisibility" to p.menuVisibility.name, "mode" to p.mode.name, "direction" to p.direction.name, "spread" to p.spread.name,
        "coverAlone" to "${p.coverAlone}", "verticalFit" to p.verticalFit.name, "pagedFit" to p.pagedFit.name,
        "spacing" to "${p.spacing}", "inverted" to "${p.inverted}", "canvas" to p.canvas.name, "splitWide" to "${p.splitWide}",
        "cropMode" to p.cropMode.name, "cropLeft" to "${p.crop.left}", "cropTop" to "${p.crop.top}", "cropRight" to "${p.crop.right}", "cropBottom" to "${p.crop.bottom}")
    private inline fun <reified E : Enum<E>> Map<String, String>.enum(key: String, fallback: E): E =
        this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback
    private fun Map<String, String>.number(key: String, fallback: Float, min: Float, max: Float) =
        (this[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback).coerceIn(min, max)
    private fun Map<String, String>.bool(key: String, fallback: Boolean) = this[key]?.toBooleanStrictOrNull() ?: fallback
    fun txt(p: TxtPreferences, f: Map<String, String>) = p.copy(font = f.enum("font", p.font), size = f.number("size", p.size, 14f, 32f),
        line = f.number("line", p.line, 1.3f, 2.1f), margin = f.number("margin", p.margin, 16f, 40f), paper = f.enum("paper", p.paper), menuVisibility = f.enum("menuVisibility", p.menuVisibility))
    fun pdf(p: PdfPreferences, f: Map<String, String>) = p.copy(doubleTapZoom = f.bool("doubleTapZoom", p.doubleTapZoom), pinchZoom = f.bool("pinchZoom", p.pinchZoom), fit = f.enum("fit", p.fit), spacing = f.number("spacing", p.spacing, 0f, 24f),
        inverted = f.bool("inverted", p.inverted), canvas = f.enum("canvas", p.canvas), menuVisibility = f.enum("menuVisibility", p.menuVisibility))
    fun image(p: ImagePreferences, f: Map<String, String>) = p.copy(doubleTapZoom = f.bool("doubleTapZoom", p.doubleTapZoom),
        pinchZoom = f.bool("pinchZoom", p.pinchZoom), tapToTurn = f.bool("tapToTurn", p.tapToTurn), menuVisibility = f.enum("menuVisibility", p.menuVisibility), mode = f.enum("mode", p.mode), direction = f.enum("direction", p.direction),
        spread = f.enum("spread", p.spread), coverAlone = f.bool("coverAlone", p.coverAlone), verticalFit = f.enum("verticalFit", p.verticalFit),
        pagedFit = f.enum("pagedFit", p.pagedFit), spacing = f.number("spacing", p.spacing, 0f, 24f), inverted = f.bool("inverted", p.inverted),
        canvas = f.enum("canvas", p.canvas), splitWide = f.bool("splitWide", p.splitWide), cropMode = f.enum("cropMode", p.cropMode),
        crop = CropRect(f.number("cropLeft", p.crop.left, 0f, .4f), f.number("cropTop", p.crop.top, 0f, .4f),
            f.number("cropRight", p.crop.right, .6f, 1f), f.number("cropBottom", p.crop.bottom, .6f, 1f)))
}
