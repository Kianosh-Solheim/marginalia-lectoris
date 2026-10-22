package com.example.reader

import android.content.Context
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SampleContentProvider {

    /**
     * Initializes app settings if not present, and scans the persistent Readmark/ directory
     * so user files are always preserved and discovered across app restarts.
     */
    suspend fun populateInitialDataIfEmpty(context: Context, database: AppDatabase) = withContext(Dispatchers.IO) {
        val settingsDao = database.settingsDao()

        // Ensure default settings exist with "Literature" tag and e-ink defaults
        val existingSettings = settingsDao.getSettingsSnapshot()
        if (existingSettings == null) {
            settingsDao.saveSettings(
                SettingsEntity(
                    id = 1,
                    defaultTags = "Literature",
                    mocTitleFormat = "Notes on {{title}}",
                    wikiLinkStyle = true,
                    includeDateInFrontmatter = true,
                    includePageInFrontmatter = true,
                    includeSourceInFrontmatter = true,
                    includeAuthorInFrontmatter = true,
                    customFrontmatterYaml = "",
                    readerFontSizeSp = 18f,
                    readerTheme = "EINK",
                    volumeKeyNavigation = true,
                    readerTextAlign = "JUSTIFY",
                    readerMarginDp = 24,
                    readerLineSpacing = 1.6f,
                    readerFontFamily = "SERIF",
                    storageLocationType = "DOCUMENTS",
                    customStoragePath = ""
                )
            )
        }

        // Clean up any previously seeded example files as requested by the user
        try {
            val currentSettings = settingsDao.getSettingsSnapshot() ?: SettingsEntity()
            removeExampleFiles(context, database, currentSettings.customStoragePath, currentSettings.storageLocationType)
            StorageManager.scanAndSyncLibrary(context, database, currentSettings.customStoragePath, currentSettings.storageLocationType)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun removeExampleFiles(
        context: Context,
        database: AppDatabase,
        customStoragePath: String = "",
        storageType: String = "DOCUMENTS"
    ) {
        val documentDao = database.documentDao()
        val exampleTitles = listOf(
            "The Future of AI",
            "Thinking for Yourself",
            "Democracy in the Digital Age",
            "The Man Who Stopped Aging",
            "The Factory"
        )
        val allDocs = documentDao.getAllDocumentsSnapshot()
        for (doc in allDocs) {
            if (exampleTitles.any { it.equals(doc.title.trim(), ignoreCase = true) }) {
                documentDao.deleteDocument(doc)
            }
        }
        val root = StorageManager.getRootDirectory(context, customStoragePath, storageType)
        for (category in listOf("Articles", "Books")) {
            val catDir = java.io.File(root, category)
            if (catDir.exists()) {
                for (name in exampleTitles) {
                    val folder = java.io.File(catDir, name)
                    if (folder.exists()) {
                        folder.deleteRecursively()
                    }
                }
            }
        }
    }
}
