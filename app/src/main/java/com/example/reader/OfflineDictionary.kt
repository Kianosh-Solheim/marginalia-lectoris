package com.example.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup

/** ONYX DictionaryUtil protocol: [keyword, limit, base URL, queryOnlineDict=false]. */
object OfflineDictionary {
    suspend fun lookup(context: Context, word: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            repeat(5) { attempt ->
                var loading = false
                context.contentResolver.query(Uri.parse("content://com.onyx.dict.DictionaryProvider"), null, null,
                    arrayOf(word.trim(), "5", "", "false"), null)?.use { cursor ->
                    check(cursor.moveToFirst()) { "The tablet dictionary returned no result." }
                    when (cursor.getInt(cursor.getColumnIndexOrThrow("state"))) {
                        0 -> {
                            @Suppress("DEPRECATION")
                            val fd = cursor.extras.getParcelable<ParcelFileDescriptor>("resultFileFd")
                                ?: error("The tablet dictionary returned an empty definition.")
                            val raw = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val entries = JSONArray(raw.trimEnd('\u0000'))
                            val answer = (0 until entries.length()).mapNotNull { i ->
                                val entry = entries.getJSONObject(i)
                                val definition = Jsoup.parse(entry.optString("explanation")).wholeText().trim()
                                if (definition.isBlank()) null else entry.optJSONObject("dictionary")?.optString("name", "Tablet dictionary").orEmpty() + "\n" + definition
                            }.joinToString("\n\n")
                            check(answer.isNotBlank()) { "No offline definition found. Try another word or choose an online source." }
                            return@withContext Result.success(answer)
                        }
                        2 -> loading = true
                        3 -> error("No offline dictionary is enabled. Install or enable a dictionary in the ONYX Dictionary app.")
                        1 -> error("No offline definition found. Try another word or choose an online source.")
                        else -> error("The tablet dictionary could not complete this lookup. Open the ONYX Dictionary app and check its dictionaries.")
                    }
                } ?: error("The ONYX offline dictionary is not available on this device.")
                if (loading && attempt < 4) delay(1000)
            }
            Result.failure(IllegalStateException("The offline dictionary is still loading. Try again shortly."))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(IllegalStateException(e.message ?: "Offline dictionary lookup failed.")) }
    }
}
