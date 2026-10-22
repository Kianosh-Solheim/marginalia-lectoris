package com.example.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * The app's EPUB reader (Readium) counts places as "positions". So that notes made in the browser
 * land in the same place in the app, the server works out the same positions for each chapter file
 * here, with its own copy of the publication (the reader's open book is never touched).
 */
object EpubIndex {
    data class Resource(val href: String, val first: Int, val count: Int, val title: String)
    data class Index(val resources: List<Resource>, val total: Int, val toc: List<Pair<String, String>>)

    private val cache = HashMap<String, Index>()

    suspend fun of(context: Context, file: File): Index? {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(cache) { cache[key]?.let { return it } }
        val built = build(context, file) ?: return null
        synchronized(cache) {
            if (cache.size > 6) cache.clear()
            cache[key] = built
        }
        return built
    }

    private suspend fun build(context: Context, file: File): Index? {
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val parser = DefaultPublicationParser(context = context, httpClient = http, assetRetriever = retriever, pdfFactory = null)
        val asset = retriever.retrieve(file).getOrNull() ?: return null
        val pub = PublicationOpener(publicationParser = parser).open(asset = asset, allowUserInteraction = false).getOrNull() ?: return null
        try {
            val positions = runCatching { pub.positions() }.getOrDefault(emptyList())
            val toc = mutableListOf<Pair<String, String>>()
            fun walk(links: List<Link>) { links.forEach { l -> toc += l.href.toString() to l.title.orEmpty(); walk(l.children) } }
            walk(pub.tableOfContents)
            val resources = mutableListOf<Resource>()
            pub.readingOrder.forEach { link ->
                val href = link.href.toString()
                val mine = positions.filter { it.href.toString() == href }
                val first = mine.minOfOrNull { it.locations.position ?: Int.MAX_VALUE }?.takeIf { it != Int.MAX_VALUE }
                    ?: (resources.lastOrNull()?.let { it.first + it.count } ?: 1)
                val title = toc.lastOrNull { it.first.substringBefore('#') == href }?.second
                    ?: toc.firstOrNull { it.first.substringBefore('#') == href }?.second
                    ?: link.title.orEmpty()
                resources += Resource(href, first, mine.size.coerceAtLeast(1), title)
            }
            return Index(resources, positions.size.coerceAtLeast(resources.size), toc)
        } finally {
            runCatching { pub.close() }
        }
    }

    /** Finds the resource whose href is [webHref] (epub.js gives hrefs relative to the package file). */
    fun resourceFor(index: Index, webHref: String): Resource? {
        val clean = webHref.substringBefore('#').trimStart('/')
        if (clean.isBlank()) return null
        return index.resources.firstOrNull { it.href == clean }
            ?: index.resources.firstOrNull { it.href.endsWith("/$clean") }
            ?: index.resources.firstOrNull { clean.endsWith("/${it.href}") }
            ?: index.resources.firstOrNull { it.href.substringAfterLast('/') == clean.substringAfterLast('/') }
    }

    /** The app's position for a place [progression] (0..1) through [webHref]. */
    fun positionFor(index: Index, webHref: String, progression: Double): Int {
        val r = resourceFor(index, webHref) ?: return 1
        val offset = (progression.coerceIn(0.0, 0.9999) * r.count).toInt()
        return r.first + offset
    }

    /** The resource holding the app's position [position] and the progression in it. */
    fun locate(index: Index, position: Int): Pair<Resource, Double>? {
        val r = index.resources.lastOrNull { it.first <= position } ?: index.resources.firstOrNull() ?: return null
        val p = ((position - r.first).toDouble() / r.count.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return r to p
    }

    fun toJson(index: Index): JSONObject = JSONObject().apply {
        put("total", index.total)
        put("resources", JSONArray().apply {
            index.resources.forEach { r -> put(JSONObject().put("href", r.href).put("first", r.first).put("count", r.count).put("title", r.title)) }
        })
    }
}
