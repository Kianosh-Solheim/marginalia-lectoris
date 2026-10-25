package com.example.server

import android.content.Context
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsEntity
import com.example.reader.AiPreferences
import com.example.reader.AiProvider
import com.example.reader.AiSettings
import com.example.reader.BackupManager
import com.example.reader.CitationStyle
import com.example.reader.Citations
import com.example.reader.FolderSync
import com.example.ui.EinkPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's settings for the web view. The page is drawn from the field list below, so a
 * setting added here shows up in the browser without changing the page. API keys are never
 * sent to the browser: it only learns whether one is saved and its last four characters.
 */
class SettingsApi(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val ai = AiPreferences(context)

    private class Ctx(var s: SettingsEntity, var a: AiSettings, val context: Context) {
        var aiChanged = false
    }

    private class Field(
        val key: String, val group: String, val label: String, val type: String,
        val options: List<Pair<String, String>> = emptyList(),
        val min: Double = 0.0, val max: Double = 0.0, val step: Double = 1.0,
        val help: String = "",
        val get: Ctx.() -> Any,
        val set: Ctx.(Any) -> Unit
    )

    private fun Any.bool() = when (this) { is Boolean -> this; is String -> this.equals("true", true); is Number -> this.toInt() != 0; else -> false }
    private fun Any.num() = when (this) { is Number -> this.toDouble(); is String -> this.toDoubleOrNull() ?: 0.0; else -> 0.0 }
    private fun Any.str() = this.toString()

    private fun choice(f: List<Pair<String, String>>, v: Any, cur: String) = v.str().takeIf { s -> f.any { it.first == s } } ?: cur

    private val AI = "AI"
    private val NOTES = "Notes & export"
    private val CITE = "Citations"
    private val READ = "Reading"
    private val TEXT = "Text & layout"
    private val PDF = "PDF text"
    private val TTS = "Read aloud"
    private val LIB = "Library & screen"

    private val themes = listOf("WHITE" to "White", "CREAM" to "Cream", "DARK" to "Dark")
    private val fonts = listOf("SERIF" to "Serif", "SANS" to "Sans", "MONO" to "Mono")
    private val aligns = listOf("JUSTIFY" to "Justified", "LEFT" to "Left")
    private val paragraphs = listOf("BOTH" to "Indent + space", "INDENT" to "Indent", "SPACED" to "Space between", "NONE" to "None")
    private val views = listOf("PAGINATED" to "Pages", "VERTICAL_SCROLL" to "Scroll")
    private val turns = listOf("PAGE_CURL" to "3D page flip", "SLIDE" to "Slide", "NONE" to "None")
    private val pdfModes = listOf("AUTO" to "Automatic", "ORIGINAL" to "Original text", "OCR" to "OCR", "HYBRID" to "Hybrid")
    private val ocrLangs = listOf("AUTO" to "Automatic", "en" to "English", "no" to "Norwegian", "de" to "German", "fr" to "French", "es" to "Spanish", "la" to "Latin")
    private val ttsLangs = listOf("en-GB" to "English (British)", "nb-NO" to "Norwegian")
    private val providers = AiProvider.values().map { it.name to it.label }
    private val titleLangs = listOf("BOOK" to "Follow book", "ENGLISH" to "English", "NORWEGIAN" to "Norsk")
    private val styles = CitationStyle.values().map { it.name to it.label }
    private val refresh = listOf("0" to "Off", "1" to "Every page", "5" to "Every 5", "10" to "Every 10", "20" to "Every 20")

    private val fields: List<Field> = listOf(
        // AI
        Field("ai.provider", AI, "Provider", "choice", providers, get = { a.provider.name }, set = { v ->
            val p = runCatching { AiProvider.valueOf(v.str()) }.getOrNull()
            if (p != null && p != a.provider) { a = a.copy(provider = p, model = ai.model(p)); aiChanged = true }
        }),
        Field("ai.model", AI, "Model", "text", help = "Leave as it is unless you want a different model from the same provider.",
            get = { a.model }, set = { v -> a = a.copy(model = v.str().trim()); aiChanged = true }),
        Field("ai.endpoint", AI, "Endpoint (Other provider only)", "text", help = "Full HTTPS URL, including /v1/chat/completions.",
            get = { a.endpoint }, set = { v -> a = a.copy(endpoint = v.str().trim()); aiChanged = true }),
        Field("ai.titleLanguage", AI, "Title language", "choice", titleLangs, get = { a.titleLanguage },
            set = { v -> a = a.copy(titleLanguage = choice(titleLangs, v, a.titleLanguage)); aiChanged = true }),
        Field("ai.dictionaryAi", AI, "Explain words in context", "bool", help = "Adds an AI option to the Dictionary.",
            get = { a.dictionaryAi }, set = { v -> a = a.copy(dictionaryAi = v.bool()); aiChanged = true }),
        Field("ai.titlePrompt", AI, "Title prompt", "longtext", get = { a.titlePrompt }, set = { v -> a = a.copy(titlePrompt = v.str()); aiChanged = true }),
        Field("ai.summaryPrompt", AI, "Summary prompt", "longtext", get = { a.summaryPrompt }, set = { v -> a = a.copy(summaryPrompt = v.str()); aiChanged = true }),
        Field("ai.tagsPrompt", AI, "Tags prompt", "longtext", get = { a.tagsPrompt }, set = { v -> a = a.copy(tagsPrompt = v.str()); aiChanged = true }),
        Field("ai.dictionaryPrompt", AI, "Word explanation prompt", "longtext", get = { a.dictionaryPrompt }, set = { v -> a = a.copy(dictionaryPrompt = v.str()); aiChanged = true }),

        // Notes & export
        Field("mocTitleFormat", NOTES, "Overview note title", "text", help = "Use {{title}} for the book title, e.g. Notes on {{title}}.",
            get = { s.mocTitleFormat }, set = { v -> s = s.copy(mocTitleFormat = v.str().ifBlank { "Notes on {{title}}" }) }),
        Field("wikiLinkStyle", NOTES, "Obsidian links [[Note]]", "bool", help = "Off: standard Markdown links [Note](Note.md).",
            get = { s.wikiLinkStyle }, set = { v -> s = s.copy(wikiLinkStyle = v.bool()) }),
        Field("defaultTags", NOTES, "Default tags", "text", help = "Comma separated.", get = { s.defaultTags }, set = { v -> s = s.copy(defaultTags = v.str()) }),
        Field("customFrontmatterYaml", NOTES, "Extra frontmatter lines", "longtext", help = "YAML lines added to every note, e.g. type: literature-note",
            get = { s.customFrontmatterYaml }, set = { v -> s = s.copy(customFrontmatterYaml = v.str()) }),
        Field("includeDateInFrontmatter", NOTES, "Date in frontmatter", "bool", get = { s.includeDateInFrontmatter }, set = { v -> s = s.copy(includeDateInFrontmatter = v.bool()) }),
        Field("includePageInFrontmatter", NOTES, "Page in frontmatter", "bool", get = { s.includePageInFrontmatter }, set = { v -> s = s.copy(includePageInFrontmatter = v.bool()) }),
        Field("includeSourceInFrontmatter", NOTES, "Source in frontmatter", "bool", get = { s.includeSourceInFrontmatter }, set = { v -> s = s.copy(includeSourceInFrontmatter = v.bool()) }),
        Field("includeAuthorInFrontmatter", NOTES, "Author in frontmatter", "bool", get = { s.includeAuthorInFrontmatter }, set = { v -> s = s.copy(includeAuthorInFrontmatter = v.bool()) }),

        // Citations
        Field("citationStyle", CITE, "Default citation style", "choice", styles, help = "Used for “Copy as citation” and the citation in exported notes.",
            get = { Citations.defaultStyle(context).name },
            set = { v -> runCatching { CitationStyle.valueOf(v.str()) }.getOrNull()?.let { Citations.setDefaultStyle(context, it) } }),

        // Reading
        Field("readerViewMode", READ, "View", "choice", views, get = { s.readerViewMode.ifBlank { "PAGINATED" } }, set = { v -> s = s.copy(readerViewMode = choice(views, v, s.readerViewMode)) }),
        Field("readerColumnCount", READ, "Columns", "choice", listOf("1" to "One", "2" to "Two"), get = { s.readerColumnCount.coerceIn(1, 2).toString() },
            set = { v -> s = s.copy(readerColumnCount = v.num().toInt().coerceIn(1, 2)) }),
        Field("pageTurnAnimation", READ, "Page turn animation", "choice", turns, get = { s.pageTurnAnimation.ifBlank { "PAGE_CURL" } }, set = { v -> s = s.copy(pageTurnAnimation = choice(turns, v, s.pageTurnAnimation)) }),
        Field("volumeKeyNavigation", READ, "Turn pages with volume buttons", "bool", get = { s.volumeKeyNavigation }, set = { v -> s = s.copy(volumeKeyNavigation = v.bool()) }),
        Field("volumeKeyAnimation", READ, "Animate volume-button page turns", "bool", get = { s.volumeKeyAnimation }, set = { v -> s = s.copy(volumeKeyAnimation = v.bool()) }),
        Field("showEpubPageEquivalents", READ, "Show printed page numbers in EPUBs", "bool", get = { s.showEpubPageEquivalents },
            set = { v -> val on = v.bool(); s = s.copy(showEpubPageEquivalents = on, paginateByEpubPageEquivalents = s.paginateByEpubPageEquivalents && on) }),
        Field("paginateByEpubPageEquivalents", READ, "Page EPUBs by printed pages", "bool", get = { s.paginateByEpubPageEquivalents },
            set = { v -> s = s.copy(paginateByEpubPageEquivalents = v.bool() && s.showEpubPageEquivalents) }),

        // Text & layout
        Field("readerTheme", TEXT, "Colours", "choice", themes, get = { s.readerTheme.uppercase().let { t -> if (t == "CREAM" || t == "DARK") t else "WHITE" } },
            set = { v -> s = s.copy(readerTheme = choice(themes, v, s.readerTheme)) }),
        Field("readerFontFamily", TEXT, "Font", "choice", fonts, get = { s.readerFontFamily.ifBlank { "SERIF" } }, set = { v -> s = s.copy(readerFontFamily = choice(fonts, v, s.readerFontFamily)) }),
        Field("readerFontSizeSp", TEXT, "Font size (sp)", "number", min = 10.0, max = 40.0, step = 1.0, get = { s.readerFontSizeSp.toDouble() },
            set = { v -> s = s.copy(readerFontSizeSp = v.num().toFloat().coerceIn(10f, 40f)) }),
        Field("readerLineSpacing", TEXT, "Line spacing", "number", min = 1.0, max = 2.6, step = 0.1, get = { s.readerLineSpacing.toDouble() },
            set = { v -> s = s.copy(readerLineSpacing = v.num().toFloat().coerceIn(1f, 2.6f)) }),
        Field("readerTextAlign", TEXT, "Alignment", "choice", aligns, get = { s.readerTextAlign.ifBlank { "JUSTIFY" } }, set = { v -> s = s.copy(readerTextAlign = choice(aligns, v, s.readerTextAlign)) }),
        Field("readerParagraphStyle", TEXT, "Paragraphs", "choice", paragraphs, get = { s.readerParagraphStyle.ifBlank { "BOTH" } }, set = { v -> s = s.copy(readerParagraphStyle = choice(paragraphs, v, s.readerParagraphStyle)) }),
        Field("publisherStylesEnabled", TEXT, "Keep the publisher's styling", "bool", help = "Off: your font, spacing and paragraph settings apply.",
            get = { s.publisherStylesEnabled }, set = { v -> s = s.copy(publisherStylesEnabled = v.bool()) }),
        Field("readerMarginDp", TEXT, "Side margins (dp)", "number", min = 0.0, max = 96.0, step = 4.0, get = { s.readerMarginDp }, set = { v -> s = s.copy(readerMarginDp = v.num().toInt().coerceIn(0, 96)) }),
        Field("readerTopMarginDp", TEXT, "Top margin (dp)", "number", min = 0.0, max = 160.0, step = 4.0, get = { s.readerTopMarginDp }, set = { v -> s = s.copy(readerTopMarginDp = v.num().toInt().coerceIn(0, 160)) }),
        Field("readerBottomMarginDp", TEXT, "Bottom margin (dp)", "number", min = 0.0, max = 160.0, step = 4.0, get = { s.readerBottomMarginDp }, set = { v -> s = s.copy(readerBottomMarginDp = v.num().toInt().coerceIn(0, 160)) }),

        // PDF text
        Field("defaultPdfTextExtractionMode", PDF, "Text from PDFs", "choice", pdfModes, help = "For newly added PDFs; each book can still be changed on its own.",
            get = { s.defaultPdfTextExtractionMode.ifBlank { "AUTO" } }, set = { v -> s = s.copy(defaultPdfTextExtractionMode = choice(pdfModes, v, s.defaultPdfTextExtractionMode)) }),
        Field("defaultPdfOcrLanguage", PDF, "OCR language", "choice", ocrLangs, get = { s.defaultPdfOcrLanguage.ifBlank { "AUTO" } }, set = { v -> s = s.copy(defaultPdfOcrLanguage = choice(ocrLangs, v, s.defaultPdfOcrLanguage)) }),

        // Read aloud
        Field("ttsLanguage", TTS, "Language", "choice", ttsLangs, get = { s.ttsLanguage.ifBlank { "en-GB" } }, set = { v -> s = s.copy(ttsLanguage = choice(ttsLangs, v, s.ttsLanguage)) }),
        Field("ttsSpeechRate", TTS, "Speed", "number", min = 0.5, max = 2.0, step = 0.05, get = { s.ttsSpeechRate.toDouble() }, set = { v -> s = s.copy(ttsSpeechRate = v.num().toFloat().coerceIn(0.5f, 2f)) }),
        Field("ttsPitch", TTS, "Pitch", "number", min = 0.5, max = 2.0, step = 0.05, get = { s.ttsPitch.toDouble() }, set = { v -> s = s.copy(ttsPitch = v.num().toFloat().coerceIn(0.5f, 2f)) }),
        Field("ttsAutoAdvancePage", TTS, "Turn pages while reading aloud", "bool", get = { s.ttsAutoAdvancePage }, set = { v -> s = s.copy(ttsAutoAdvancePage = v.bool()) }),

        // Library & screen
        Field("showContinueReadingCard", LIB, "Show the Continue reading card", "bool", get = { s.showContinueReadingCard }, set = { v -> s = s.copy(showContinueReadingCard = v.bool()) }),
        Field("maxPinnedBooksInWidget", LIB, "Books in the home-screen widget", "number", min = 1.0, max = 12.0, step = 1.0, get = { s.maxPinnedBooksInWidget },
            set = { v -> s = s.copy(maxPinnedBooksInWidget = v.num().toInt().coerceIn(1, 12)) }),
        Field("einkRefresh", LIB, "E-ink clean refresh", "choice", refresh, help = "Briefly flashes the page to clear ghosting.",
            get = { EinkPrefs.refreshEvery(context).toString() }, set = { v -> v.str().toIntOrNull()?.takeIf { n -> refresh.any { it.first == "$n" } }?.let { EinkPrefs.setRefreshEvery(context, it) } })
    )

    private fun keyHint(p: AiProvider): String =
        if (!ai.hasKey(p)) "" else runCatching { ai.readKey(p).trim().takeLast(4) }.getOrDefault("")

    suspend fun get(): JSONObject {
        val ctx = Ctx(db.settingsDao().getSettingsSnapshot() ?: SettingsEntity(), ai.load(), context)
        val groups = JSONArray()
        fields.groupBy { it.group }.forEach { (g, list) ->
            groups.put(JSONObject().put("name", g).put("fields", JSONArray().apply {
                list.forEach { f ->
                    put(JSONObject().put("key", f.key).put("label", f.label).put("type", f.type).put("help", f.help)
                        .put("min", f.min).put("max", f.max).put("step", f.step)
                        .put("options", JSONArray().apply { f.options.forEach { (v, l) -> put(JSONObject().put("value", v).put("label", l)) } })
                        .put("value", f.get(ctx)))
                }
            }))
        }
        val keys = JSONArray()
        AiProvider.values().forEach { p ->
            keys.put(JSONObject().put("provider", p.name).put("label", p.label).put("saved", ai.hasKey(p)).put("last4", keyHint(p)))
        }
        val root = runCatching {
            StorageManager.getRootDirectory(context, ctx.s.customStoragePath, ctx.s.storageLocationType).absolutePath
        }.getOrDefault("")
        return JSONObject().put("groups", groups).put("keys", keys).put("info", JSONObject()
            .put("libraryFolder", root)
            .put("lastFolderCheck", FolderSync.lastSync.value)
            .put("lastBackup", BackupManager.lastBackupTime(context))
            .put("autoBackup", BackupManager.autoTarget(context) != null))
    }

    suspend fun put(b: JSONObject): JSONObject {
        val before = db.settingsDao().getSettingsSnapshot() ?: SettingsEntity()
        val ctx = Ctx(before, ai.load(), context)
        val byKey = fields.associateBy { it.key }
        // Provider first, so a model sent together with it belongs to the new provider.
        val keys = b.keys().asSequence().toList().sortedBy { if (it == "ai.provider") 0 else 1 }
        for (k in keys) {
            val f = byKey[k] ?: throw IllegalArgumentException("Unknown setting: $k")
            f.set(ctx, b.get(k))
        }
        if (ctx.s != before) db.settingsDao().saveSettings(ctx.s)
        if (ctx.aiChanged) ai.save(ctx.a)
        return get()
    }

    /** Saves (or, with a blank key, removes) the API key for one provider. */
    suspend fun setKey(b: JSONObject): JSONObject {
        val p = runCatching { AiProvider.valueOf(b.optString("provider")) }.getOrElse { throw IllegalArgumentException("Unknown provider.") }
        val key = b.optString("key").trim()
        require(key.length <= 400 && key.none { it.isWhitespace() }) { "That doesn't look like an API key." }
        ai.saveKey(p, key)
        return get()
    }

    fun checkFolders(): JSONObject {
        FolderSync.scanSoon()
        return JSONObject().put("ok", true)
    }
}
