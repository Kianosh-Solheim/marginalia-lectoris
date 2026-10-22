package com.example.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadingAssistanceTest {
    @Test fun metadataOverridesSelectionLanguage() {
        assertEquals("Norwegian", NoteAiAssistant.language("nn-NO", "a word"))
        assertEquals("Norwegian", NoteAiAssistant.language("nb", "the book"))
        assertEquals("English", NoteAiAssistant.language("en-GB", "det er ikke sÃ¥ lett"))
    }
    @Test fun missingMetadataUsesPassage() {
        assertEquals("Norwegian", NoteAiAssistant.language("", "Dette er ikke det som stÃ¥r i boken"))
        assertEquals("English", NoteAiAssistant.language("", "The idea is that knowledge depends on evidence"))
    }
    @Test fun explanatoryTitlesAreNotTruncated() {
        val title = "Evidence changes what we believe because it reveals the limits of our previous explanations"
        assertEquals(title, NoteAiAssistant.cleanNoteFilenameTitle(title))
        assertEquals("Evidence changes beliefs", NoteAiAssistant.cleanNoteFilenameTitle("Evidence: changes / beliefs.md"))
    }
    @Test fun promptsProviderAndLanguagePersistWithoutCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AiPreferences(context)
        val s = AiSettings(provider = AiProvider.CLAUDE, model = "my-model", titleLanguage = "NORWEGIAN",
            titlePrompt = "Explain the central argument", summaryPrompt = "Short summary", tagsPrompt = "Reuse tags", dictionaryAi = true)
        prefs.save(s)
        assertEquals(s, AiPreferences(context).load())
        assertFalse(prefs.hasKey(AiProvider.CLAUDE))
        assertEquals("", prefs.readKey(AiProvider.CLAUDE))
    }
    @Test fun missingKeyProducesAnActionableErrorNotFabricatedSuggestions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AiPreferences(context).save(AiSettings())
        val result = NoteAiAssistant.suggestNoteTitle(context, "A passage", "", "", "Book", 1, "en")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Settings"))
    }
    @Test fun contextualAiIsDisabledByDefault() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AiPreferences(context).save(AiSettings())
        val result = NoteAiAssistant.explain(context, "word", "A word in context", "en")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Enable"))
    }
}
