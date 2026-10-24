package com.yanjian.app

import android.content.Context
import androidx.room.Room
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContentPipelineTest {
    private lateinit var db: YanjianDatabase
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val fixture get() = javaClass.getResource("/official-fixture.json")!!.readText()
    private fun entry(number: Int = 1, genre: String = "practical", kind: String = "sentence"): JsonObject {
        val item = JsonParser.parseString(fixture).asJsonObject.getAsJsonArray("entries")[0].asJsonObject.deepCopy()
        item.addProperty("id", "official:$kind:00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
        item.addProperty("kind", kind); item.addProperty("genre", genre)
        if (kind == "word") item.addProperty("shortMeaning", "本项目自制教学解释。")
        return item
    }
    private fun pack(version: Int = 1, vararg items: JsonObject): String {
        val root = JsonParser.parseString(fixture).asJsonObject
        root.addProperty("contentVersion", version)
        root.add("entries", JsonArray().apply { items.forEach { add(it) } })
        return root.toString()
    }
    private suspend fun install(json: String) = OfficialContentImporter(db).importPackage { StringReader(json) }
    private suspend fun rejected(json: String) {
        try { install(json); fail("Expected invalid package to fail") } catch (e: IllegalArgumentException) { /* expected */ }
    }
    @Before fun setup() {
        context.getSharedPreferences("study-preferences", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, YanjianDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun teardown() { db.close() }

    @Test fun importRepeatUpdateAndRetirementPreserveUserState() = runBlocking {
        val original = entry(); val id = original.get("id").asString
        install(pack(1, original, entry(2)))
        val state = UserEntryEntity(id, true, true, "KNOWN", 7, 1234L)
        db.userEntryDao().put(state)
        assertEquals(2, install(pack(1, original, entry(2))))
        assertEquals(state, db.userEntryDao().get(id))
        val revised = original.deepCopy().apply { addProperty("recordVersion", 2); addProperty("usageNote", "已有的真实用法说明") }
        install(pack(2, revised))
        assertEquals(2, db.entryDao().manifest()!!.contentVersion)
        assertEquals("已有的真实用法说明", db.entryDao().get(id)!!.context)
        assertEquals(state, db.userEntryDao().get(id))
        assertNotNull(db.entryDao().get(entry(2).get("id").asString))
        assertEquals(1, db.entryDao().get(entry(2).get("id").asString)!!.contentVersion)
        rejected(pack(1, original))
    }
    @Test fun duplicateAndInvalidLastRowRollBackEarlierWritesAndPointer() = runBlocking {
        val original = entry(); val id = original.get("id").asString
        install(pack(1, original))
        val revised = original.deepCopy().apply { addProperty("recordVersion", 2); addProperty("usageNote", "修订内容") }
        rejected(pack(2, revised, revised))
        assertEquals(1, db.entryDao().get(id)!!.recordVersion)
        val bad = entry(2).apply { addProperty("publicationStatus", "candidate") }
        rejected(pack(2, revised, bad))
        assertNull(db.entryDao().get(bad.get("id").asString))
        assertEquals(1, db.entryDao().manifest()!!.contentVersion)
        assertEquals(1, db.entryDao().get(id)!!.recordVersion)
        rejected(pack(1, revised))
        rejected(pack(2, original.deepCopy().apply { addProperty("recordVersion", 0) }))
    }
    @Test fun malformedAndIllegalSourceAreRejectedWithoutActivation() = runBlocking {
        try { install("{broken"); fail() } catch (_: Exception) { }
        val bad = entry().apply { getAsJsonArray("sources")[0].asJsonObject.addProperty("evidenceStatus", "candidate") }
        rejected(pack(1, bad)); assertNull(db.entryDao().manifest())
        rejected(pack(1, entry(kind = "word").apply { addProperty("shortMeaning", "") }))
        assertNull(db.entryDao().manifest())
    }
    @Test fun sourceLabelsAndEmptySentenceMeaningUseRealSource() {
        val original = parseEntry(entry(), 1)
        assertEquals("", original.meaning)
        assertEquals("自制原创", original.sourceLabel())
        assertFalse(original.backText().contains("影视"))
        assertTrue(original.backText().contains("言间自制教学句"))
        val film = entry().apply { getAsJsonArray("sources")[0].asJsonObject.apply {
            addProperty("type", "film"); addProperty("rightsStatus", "public_domain"); addProperty("locator", "测试定位")
        } }
        assertEquals("影视来源", parseEntry(film, 1).sourceLabel())
        assertFalse(parseEntry(film, 1).sourceDetails().contains("原文已核验"))
        assertEquals("自制原创 · 演示内容", original.copy(isSample = true).sourceLabel())
    }
    @Test fun favoriteOnlyChangesFavoriteIncludingRecollection() = runBlocking {
        install(pack(1, entry()))
        val id = entry().get("id").asString
        val old = UserEntryEntity(id, true, true, "KNOWN", 9, 9876L)
        db.userEntryDao().put(old)
        val repository = UserRepository(db)
        assertEquals(old.copy(isFavorite = false), repository.favorite(id))
        assertEquals(old, repository.favorite(id))
    }
    @Test fun preferencesReopenAndStrictEndpointsFilteringEmptyPools() = runBlocking {
        install(pack(1, entry(), entry(2, "classic"), entry(3, kind = "word")))
        val preferences = StudyPreferences(context)
        preferences.ratio = 0; preferences.categories = setOf("沟通"); preferences.purposes = setOf("writing"); preferences.mode = StudyMode.SENTENCE
        val reopened = StudyPreferences(context)
        assertEquals(0, reopened.ratio); assertEquals(setOf("沟通"), reopened.categories)
        assertEquals(setOf("writing"), reopened.purposes); assertEquals(StudyMode.SENTENCE, reopened.mode)
        var queue = RecommendationRepository(db, reopened).queue(StudyMode.SENTENCE)
        assertTrue(queue.entries.isNotEmpty()); assertTrue(queue.entries.all { it.genre == "classic" })
        reopened.ratio = 100
        queue = RecommendationRepository(db, reopened).queue(StudyMode.SENTENCE)
        assertTrue(queue.entries.isNotEmpty()); assertTrue(queue.entries.all { it.genre == "practical" })
        reopened.categories = setOf("告别")
        assertTrue(RecommendationRepository(db, reopened).queue(StudyMode.SENTENCE).entries.isEmpty())
        reopened.categories = emptySet()
        assertNotNull(RecommendationRepository(db, reopened).queue(StudyMode.WORD).message)
        assertTrue(weightedQueue(emptyList(), listOf(parseEntry(entry(2, "classic"), 1)), 100).entries.isEmpty())
        assertTrue(weightedQueue(listOf(parseEntry(entry(), 1)), emptyList(), 0).entries.isEmpty())
    }
    @Test fun weightedQueueIsBoundedStableAndActuallySixtyForty() {
        val p = (1..100).map { parseEntry(entry(it), 1) }
        val c = (101..200).map { parseEntry(entry(it, "classic"), 1) }
        val queue = weightedQueue(p, c, 60).entries
        assertEquals(100, queue.size); assertEquals(60, queue.count { it.genre == "practical" })
        assertEquals(100, queue.map { it.id }.distinct().size)
        assertEquals(queue, weightedQueue(p, c, 60).entries)
    }
    @Test fun realV1FileMigrationAndRestartKeepLegacyUsersAndContent() = runBlocking {
        val name = "migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, text TEXT NOT NULL, meaning TEXT NOT NULL, workTitle TEXT NOT NULL, quote TEXT NOT NULL, category TEXT NOT NULL, genre TEXT NOT NULL, context TEXT NOT NULL, isSample INTEGER NOT NULL)")
            legacy.execSQL("CREATE INDEX index_entries_kind ON entries(kind)")
            legacy.execSQL("CREATE INDEX index_entries_text ON entries(text)")
            legacy.execSQL("CREATE TABLE user_entries (entryId TEXT NOT NULL PRIMARY KEY, isFavorite INTEGER NOT NULL, isHidden INTEGER NOT NULL, mastery TEXT NOT NULL, reviewCount INTEGER NOT NULL, lastReviewedAt INTEGER)")
            legacy.execSQL("INSERT INTO entries VALUES ('sample-sentence-001','sentence','旧句子','旧释义','原创演示','旧句子','沟通','practical','旧语境',1)")
            legacy.execSQL("INSERT INTO user_entries VALUES ('sample-sentence-001',1,1,'KNOWN',13,1234)")
            legacy.version = 1
        }
        db.close()
        fun open() = Room.databaseBuilder(context, YanjianDatabase::class.java, name).addMigrations(MIGRATION_1_2).allowMainThreadQueries().build()
        db = open()
        val expected = UserEntryEntity("sample-sentence-001", true, true, "KNOWN", 13, 1234L)
        assertEquals(expected, db.userEntryDao().get(expected.entryId))
        assertEquals("旧句子", db.entryDao().get(expected.entryId)!!.text)
        install(pack(1, entry()))
        assertEquals(expected, db.userEntryDao().get(expected.entryId))
        assertTrue(db.entryDao().userContent("sentence", 100, 0).any { it.id == expected.entryId })
        db.close(); db = open()
        assertEquals(expected, db.userEntryDao().get(expected.entryId))
        assertEquals(1, db.entryDao().manifest()!!.contentVersion)
        assertFalse(RecommendationRepository(db, StudyPreferences(context)).queue(StudyMode.SENTENCE).entries.any { it.isSample })
    }
}
