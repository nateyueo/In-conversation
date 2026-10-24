package com.yanjian.app

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Runs the real packaged data through the app importer, without dumping the dataset into a report. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PackagedContentTest {
    @Test fun generatedProductionAssetImportsAndHasUsableQueues() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        assumeTrue("Production dataset is not ready", context.assets.list("content")?.contains("official-content.json") == true)
        val db = Room.inMemoryDatabaseBuilder(context, YanjianDatabase::class.java).allowMainThreadQueries().build()
        try {
            val count = OfficialContentImporter(db).importPackage {
                java.io.InputStreamReader(context.assets.open("content/official-content.json"), Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT))
            }
            assertTrue(count > 0)
            assertEquals(count, db.entryDao().manifest()!!.entryCount)
            val preferences = StudyPreferences(context)
            preferences.categories = CONTENT_CATEGORIES; preferences.purposes = setOf("daily", "writing")
            preferences.ratio = 60
            val words = RecommendationRepository(db, preferences).queue(StudyMode.WORD)
            val sentences = RecommendationRepository(db, preferences).queue(StudyMode.SENTENCE)
            assertTrue("Production word pool is empty", words.entries.isNotEmpty())
            assertTrue("Production sentence pool is empty: ${sentences.message}", sentences.entries.isNotEmpty())
            assertTrue(words.entries.size <= 100 && sentences.entries.size <= 100)
            println("Production asset accepted: version=${db.entryDao().manifest()!!.contentVersion}, entries=$count, wordQueue=${words.entries.size}, sentenceQueue=${sentences.entries.size}")
        } finally { db.close() }
    }
}
