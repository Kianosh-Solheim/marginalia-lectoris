package com.example.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DaisyParserEngineTest {

    @Test
    fun parseSmilClockMs_parsesAllDaisyClockFormats() {
        assertEquals(0L, DaisyParserEngine.parseSmilClockMs("npt=0.000s"))
        assertEquals(12345L, DaisyParserEngine.parseSmilClockMs("npt=12.345s"))
        assertEquals(83500L, DaisyParserEngine.parseSmilClockMs("npt=00:01:23.50"))
        assertEquals(4500L, DaisyParserEngine.parseSmilClockMs("4500ms"))
        assertEquals(3661000L, DaisyParserEngine.parseSmilClockMs("01:01:01"))
    }

    @Test
    fun parseDaisy202ZipArchive_extractsNccSmilAndFullTextSynchronization() {
        val tempDir = Files.createTempDirectory("daisy_test").toFile()
        try {
            val zipFile = File(tempDir, "sample_daisy_202.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                fun addEntry(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                addEntry(
                    "ncc.html",
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="dc:title" content="Nordic Folk Tales" />
                        <meta name="dc:creator" content="Peter Christen Asbjørnsen" />
                        <meta name="dc:format" content="Daisy 2.02" />
                    </head>
                    <body>
                        <h1 id="ncc_h1"><a href="smil01.smil#par_1">Chapter 1: East of the Sun</a></h1>
                        <span class="page-normal" id="pg_1"><a href="smil01.smil#par_2">1</a></span>
                        <h2 id="ncc_h2"><a href="smil01.smil#par_3">The White Bear</a></h2>
                    </body>
                    </html>
                    """.trimIndent()
                )

                addEntry(
                    "content01.html",
                    """
                    <html>
                    <body>
                        <h1 id="txt_1">Chapter 1: East of the Sun</h1>
                        <p id="txt_2">Once upon a time there was a poor husbandman who had so many children that he had not much food or clothing to give them.</p>
                        <p id="txt_3">Pretty children they all were, but the prettiest was the youngest daughter, who was so lovely there was no end to her loveliness.</p>
                    </body>
                    </html>
                    """.trimIndent()
                )

                addEntry(
                    "smil01.smil",
                    """
                    <smil>
                    <body>
                        <seq dur="28.500s">
                            <par endsync="last" id="par_1">
                                <text src="content01.html#txt_1" id="t1" />
                                <audio src="track01.mp3" clip-begin="npt=0.000s" clip-end="npt=4.200s" id="a1" />
                            </par>
                            <par endsync="last" id="par_2">
                                <text src="content01.html#txt_2" id="t2" />
                                <audio src="track01.mp3" clip-begin="npt=4.200s" clip-end="npt=16.000s" id="a2" />
                            </par>
                            <par endsync="last" id="par_3">
                                <text src="content01.html#txt_3" id="t3" />
                                <audio src="track01.mp3" clip-begin="npt=16.000s" clip-end="npt=28.500s" id="a3" />
                            </par>
                        </seq>
                    </body>
                    </smil>
                    """.trimIndent()
                )

                // Add dummy MP3 bytes
                zos.putNextEntry(ZipEntry("track01.mp3"))
                zos.write(ByteArray(128) { 0x42 })
                zos.closeEntry()
            }

            assertTrue(DaisyParserEngine.isDaisyZipArchive(zipFile))
            val index = DaisyParserEngine.parseDaisyBook(zipFile)

            assertEquals("DAISY 2.02", index.formatVersion)
            assertEquals("Nordic Folk Tales", index.title)
            assertEquals("Peter Christen Asbjørnsen", index.author)
            assertTrue(index.hasRecordedAudio)
            assertEquals(2, index.nccNavItems.size)
            assertEquals("Chapter 1: East of the Sun", index.nccNavItems[0].title)
            assertEquals(1, index.nccNavItems[0].level)
            assertEquals("The White Bear", index.nccNavItems[1].title)
            assertEquals(2, index.nccNavItems[1].level)
            assertEquals(1, index.pageTargets.size)
            assertEquals("1", index.pageTargets[0].pageLabel)
            assertEquals(3, index.smilClips.size)
            assertEquals(0L, index.smilClips[0].clipBeginMs)
            assertEquals(4200L, index.smilClips[0].clipEndMs)
            assertEquals(16000L, index.smilClips[2].clipBeginMs)
            assertEquals(28500L, index.smilClips[2].clipEndMs)
            assertTrue(index.smilClips[1].textSnippet.contains("Once upon a time"))
            assertFalse(index.epubBook.chapters.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
