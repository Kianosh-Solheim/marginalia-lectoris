package com.example.reader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class DaisyIndexingStatus(
    val isIndexing: Boolean = false,
    val filePath: String = "",
    val stageMessage: String = "",
    val indexedBook: DaisyBookIndex? = null,
    val errorMessage: String? = null
)

/**
 * Background Android Service that extracts a DAISY `.zip` archive, parses the
 * NCC (`ncc.html`) / NCX / OPF Navigation Control Center, and indexes all SMIL
 * audio-text synchronization files for DAISY 2.02 and DAISY 3 talking books.
 */
class DaisyIndexingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): DaisyIndexingService = this@DaisyIndexingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        val fallbackTitle = intent?.getStringExtra(EXTRA_FALLBACK_TITLE).orEmpty()
        val fallbackAuthor = intent?.getStringExtra(EXTRA_FALLBACK_AUTHOR).orEmpty()
        if (filePath.isNotBlank()) {
            serviceScope.launch {
                try {
                    val parsed = indexArchiveOrDirectory(
                        fileOrDir = File(filePath),
                        fallbackTitle = fallbackTitle,
                        fallbackAuthor = fallbackAuthor
                    )
                    val srcFile = File(filePath)
                    if (srcFile.isFile && srcFile.name.endsWith(".zip", ignoreCase = true)) {
                        DaisyParserEngine.extractDaisyAudioFilesInBackground(srcFile, File(parsed.extractedDir))
                    }
                } catch (_: Throwable) {
                } finally {
                    stopSelfResult(startId)
                }
            }
        } else {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_daisy_file_path"
        const val EXTRA_FALLBACK_TITLE = "extra_daisy_fallback_title"
        const val EXTRA_FALLBACK_AUTHOR = "extra_daisy_fallback_author"

        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cachedIndices = object : android.util.LruCache<String, DaisyBookIndex>(2) {}
        private val pathLocks = ConcurrentHashMap<String, Mutex>()
        private val _indexingState = MutableStateFlow(DaisyIndexingStatus())
        val indexingState: StateFlow<DaisyIndexingStatus> = _indexingState.asStateFlow()

        fun getCachedIndex(filePath: String): DaisyBookIndex? = synchronized(cachedIndices) {
            cachedIndices.get(filePath)
        } ?: DaisyParserEngine.getCachedDaisyBook(
            File(filePath).let { f -> "${f.absolutePath}:${f.lastModified()}:${f.length()}" }
        )

        fun startBackgroundIndexing(
            context: Context,
            filePath: String,
            fallbackTitle: String = "",
            fallbackAuthor: String = ""
        ) {
            backgroundScope.launch {
                try {
                    val parsed = indexArchiveOrDirectory(File(filePath), fallbackTitle, fallbackAuthor)
                    val srcFile = File(filePath)
                    if (srcFile.isFile && srcFile.name.endsWith(".zip", ignoreCase = true)) {
                        DaisyParserEngine.extractDaisyAudioFilesInBackground(srcFile, File(parsed.extractedDir))
                    }
                } catch (_: Throwable) {}
            }
            try {
                val intent = Intent(context, DaisyIndexingService::class.java).apply {
                    putExtra(EXTRA_FILE_PATH, filePath)
                    putExtra(EXTRA_FALLBACK_TITLE, fallbackTitle)
                    putExtra(EXTRA_FALLBACK_AUTHOR, fallbackAuthor)
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        suspend fun indexArchiveOrDirectory(
            fileOrDir: File,
            fallbackTitle: String = "",
            fallbackAuthor: String = ""
        ): DaisyBookIndex = withContext(Dispatchers.IO) {
            val cacheKey = fileOrDir.absolutePath
            getCachedIndex(cacheKey)?.let { return@withContext it }

            val mutex = pathLocks.computeIfAbsent(cacheKey) { Mutex() }
            mutex.withLock {
                getCachedIndex(cacheKey)?.let { cached ->
                    _indexingState.value = DaisyIndexingStatus(
                        isIndexing = false,
                        filePath = cacheKey,
                        stageMessage = "Indexed ${cached.smilClips.size} SMIL clips",
                        indexedBook = cached
                    )
                    return@withLock cached
                }

                _indexingState.value = DaisyIndexingStatus(
                    isIndexing = true,
                    filePath = cacheKey,
                    stageMessage = "Reading file"
                )

                try {
                    _indexingState.value = DaisyIndexingStatus(
                        isIndexing = true,
                        filePath = cacheKey,
                        stageMessage = "Parsing chapters"
                    )
                    val parsed = DaisyParserEngine.parseDaisyBook(
                        sourceFileOrDir = fileOrDir,
                        fallbackTitle = fallbackTitle,
                        fallbackAuthor = fallbackAuthor
                    )
                    synchronized(cachedIndices) {
                        cachedIndices.put(cacheKey, parsed)
                    }
                    _indexingState.value = DaisyIndexingStatus(
                        isIndexing = false,
                        filePath = cacheKey,
                        stageMessage = "Ready (${parsed.nccNavItems.size} chapters, ${parsed.smilClips.size} SMIL phrases)",
                        indexedBook = parsed
                    )
                    // Trigger non-blocking background extraction for remaining MP3 audio tracks
                    if (fileOrDir.isFile && fileOrDir.name.endsWith(".zip", ignoreCase = true)) {
                        backgroundScope.launch {
                            DaisyParserEngine.extractDaisyAudioFilesInBackground(fileOrDir, File(parsed.extractedDir))
                        }
                    }
                    parsed
                } catch (e: Throwable) {
                    _indexingState.value = DaisyIndexingStatus(
                        isIndexing = false,
                        filePath = cacheKey,
                        stageMessage = "Failed to index DAISY archive",
                        errorMessage = e.localizedMessage ?: "Unknown DAISY error"
                    )
                    throw e
                }
            }
        }
    }
}
