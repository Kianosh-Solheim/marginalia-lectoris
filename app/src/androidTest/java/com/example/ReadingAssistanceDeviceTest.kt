package com.example

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.reader.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingAssistanceDeviceTest {
    @Test fun keysAreEncryptedAndCanBeReplacedAndRemoved() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(app.cacheDir, "ai-test-credentials").apply { mkdirs() }
        }
        val prefs = AiPreferences(isolated)
        try {
            prefs.saveKey(AiProvider.GEMINI, "test-key-not-a-real-credential")
            assertEquals("test-key-not-a-real-credential", AiPreferences(isolated).readKey(AiProvider.GEMINI))
            assertFalse(File(isolated.noBackupFilesDir, "ai_credentials.json").readText().contains("test-key-not-a-real-credential"))
            prefs.saveKey(AiProvider.GEMINI, "replacement-test-key")
            assertEquals("replacement-test-key", prefs.readKey(AiProvider.GEMINI))
            prefs.saveKey(AiProvider.GEMINI, "")
            assertFalse(prefs.hasKey(AiProvider.GEMINI))
        } finally { File(isolated.noBackupFilesDir, "ai_credentials.json").delete() }
    }
    @Test fun tabletSpeechActivityIsAvailable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(app.packageManager))
    }
    @Test fun onyxDictionaryReturnsAnOfflineDefinition() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val result = OfflineDictionary.lookup(app, "hello")
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        assertTrue(result.getOrThrow().isNotBlank())
    }
}
