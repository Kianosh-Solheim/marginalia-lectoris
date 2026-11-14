package com.example.reader

/**
 * A quotation that runs on to the next page: the note dialog is put aside (with what was written
 * in it), the reader turns the page, and what is selected there is joined on.
 */
data class NoteContinuation(
    val title: String,
    val quote: String,
    val notes: String,
    val startPage: Int,          // 1-based PDF page the quotation starts on
    val lastPage: Int,           // the last page it reaches so far
    val printedLabel: String,    // as stored (may already be a span)
    val chapter: String,
    val colorHex: String,
    val tags: String
) {
    companion object {
        /** Joins the two halves. A word hyphenated across the page break is put together again. */
        fun join(first: String, second: String): String {
            val a = first.trimEnd(); val b = second.trimStart()
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val broken = a.length >= 2 && (a.last() == '-' || a.last() == '­') && a[a.length - 2].isLetter() && b.first().isLowerCase()
            return if (broken) a.dropLast(1) + b else "$a $b"
        }

        /** The page label of the whole quotation: from where it started to the page of the new part. */
        fun span(c: NoteContinuation, newPage: Int, newPrintedLabel: String): String {
            val (startRange, startPrinted) = PdfToEbookParser.parseStoredPageLabels(c.startPage, c.printedLabel)
            val (endRange, endPrinted) = PdfToEbookParser.parseStoredPageLabels(newPage, newPrintedLabel)
            val endPdf = maxOf(newPage, c.lastPage, endRange.substringAfterLast('–').substringAfterLast('-').trim().toIntOrNull() ?: newPage,
                startRange.substringAfterLast('–').substringAfterLast('-').trim().toIntOrNull() ?: c.startPage)
            val s = startPrinted.substringBefore('–').trim()
            val e = endPrinted.substringAfterLast('–').trim()
            return PdfQuotePageSpan(c.startPage, endPdf, s, e).toStoredPrintedPageLabel()
        }
    }
}
