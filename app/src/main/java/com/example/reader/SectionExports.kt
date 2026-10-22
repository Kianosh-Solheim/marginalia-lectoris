package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single-file exports of one chapter, cited as a book section: the chapter's own PDF, its RIS
 * or BibTeX record, its notes as Markdown, or everything as a .zip. Works for a chapter of an
 * edited volume (by index) and for a chapter of a folder book (its own document).
 */
object SectionExports {
    enum class Kind(val label: String) { PDF("Chapter PDF"), RIS("RIS (.ris)"), BIB("BibTeX (.bib)"), NOTES("Notes (Markdown)"), ZIP("Everything (.zip)") }

    data class Out(val file: File, val mime: String)

    private fun dir(context: Context) = File(context.cacheDir, "exports/sections").apply { mkdirs() }

    fun mime(kind: Kind) = when (kind) {
        Kind.PDF -> "application/pdf"
        Kind.RIS -> "application/x-research-info-systems"
        Kind.BIB -> "application/x-bibtex"
        Kind.NOTES -> "text/markdown"
        Kind.ZIP -> "application/zip"
    }

    /** A chapter of an edited volume. */
    suspend fun chapter(
        context: Context, book: DocumentEntity, volume: EditedVolume, index: Int, kind: Kind,
        highlights: List<HighlightEntity>, settings: SettingsEntity
    ): Out = withContext(Dispatchers.IO) {
        BibStore.init(context)
        val (doc, rec) = EditedVolumes.chapterAsDocument(book, volume, index)
        val base = MarkdownExporter.sanitizeFilename(EditedVolumes.exportFileName(book, volume, index).substringBeforeLast('.')).ifBlank { "chapter" }
        val f = when (kind) {
            Kind.PDF -> File(dir(context), "$base.pdf").also { out ->
                if (book.fileType.equals("PDF", true) || book.filePath.endsWith(".pdf", true)) {
                    ChapterExporter.extractPdfPages(context, File(book.filePath), volume.chapters[index].startPage,
                        EditedVolumes.endPageOf(volume.chapters, index, volume.totalPages), out)
                } else ChapterExporter.renderEpubChapterPdf(context, book, volume, index, out)
            }
            Kind.RIS -> File(dir(context), "$base.ris").also { it.writeText(Ris.export(doc, rec), Charsets.UTF_8) }
            Kind.BIB -> File(dir(context), "${Citations.citeKey(doc, rec).ifBlank { "chapter" }}.bib").also { it.writeText(Citations.bibtex(doc, rec), Charsets.UTF_8) }
            Kind.NOTES -> File(dir(context), MarkdownExporter.sanitizeFilename("${volume.chapters[index].title} – notes") + ".md").also {
                it.writeText(MarkdownExporter.generateUnifiedObsidianBookMarkdown(doc, EditedVolumes.highlightsIn(volume, index, highlights), settings), Charsets.UTF_8)
            }
            Kind.ZIP -> ChapterExporter.export(context, book, volume, index, highlights, settings).file
        }
        Out(f, mime(kind))
    }

    /** A chapter that is its own document (a part of a folder book). */
    suspend fun document(context: Context, doc: DocumentEntity, kind: Kind, highlights: List<HighlightEntity>, settings: SettingsEntity): Out =
        withContext(Dispatchers.IO) {
            BibStore.init(context)
            val rec = BibStore.get(doc.id)
            val source = File(doc.filePath)
            val name = MarkdownExporter.formatExportedSourceFilename(doc).ifBlank { source.name }
            val f = when (kind) {
                Kind.PDF -> {
                    check(source.isFile) { "The chapter file is missing." }
                    File(dir(context), MarkdownExporter.sanitizeFilename(name.substringBeforeLast('.')) + "." + source.extension.ifBlank { "pdf" })
                        .also { source.copyTo(it, overwrite = true) }
                }
                Kind.RIS -> File(dir(context), MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "reference" } + ".ris").also { it.writeText(Ris.export(doc, rec), Charsets.UTF_8) }
                Kind.BIB -> File(dir(context), "${Citations.citeKey(doc, rec).ifBlank { "reference" }}.bib").also { it.writeText(Citations.bibtex(doc, rec), Charsets.UTF_8) }
                Kind.NOTES -> File(dir(context), MarkdownExporter.sanitizeFilename("${doc.title} – notes") + ".md").also {
                    it.writeText(MarkdownExporter.generateUnifiedObsidianBookMarkdown(doc, highlights.filter { h -> h.documentId == doc.id }, settings), Charsets.UTF_8)
                }
                Kind.ZIP -> MarkdownExporter.createCompleteDocumentBundleZip(context, doc, highlights.filter { h -> h.documentId == doc.id }, settings).outputFileOrFolder
            }
            Out(f, if (kind == Kind.PDF && !source.extension.equals("pdf", true)) "application/octet-stream" else mime(kind))
        }
}
