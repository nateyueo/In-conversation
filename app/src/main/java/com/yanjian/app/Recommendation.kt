package com.yanjian.app

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery

class StudyPreferences(context: Context) {
    private val store = context.getSharedPreferences("study-preferences", Context.MODE_PRIVATE)
    fun exportValues(): String = appJson.toJson(store.all.mapValues { (_, v) -> if (v is Set<*>) v.toList() else v })
    fun restoreValues(json: String) {
        val values = com.google.gson.JsonParser.parseString(json).asJsonObject
        val edit = store.edit().clear()
        values.entrySet().forEach { (k, v) -> when {
            v.isJsonArray -> edit.putStringSet(k, v.asJsonArray.map { it.asString }.toSet())
            v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> edit.putInt(k, v.asInt)
            v.isJsonPrimitive && v.asJsonPrimitive.isString -> edit.putString(k, v.asString)
        } }
        check(edit.commit()) { "偏好未能保存" }
    }
    var ratio: Int
        get() = store.getInt("practicalRatio", 60)
        set(value) { store.edit().putInt("practicalRatio", value.coerceIn(0, 100)).apply() }
    var categories: Set<String>
        get() = store.getStringSet("categories:${mode.name}", store.getStringSet("categories", CONTENT_CATEGORIES))!!.toSet()
        set(value) { store.edit().putStringSet("categories:${mode.name}", value.toSet()).apply() }
    var purposes: Set<String>
        get() = store.getStringSet("purposes:${mode.name}", store.getStringSet("purposes", setOf("daily", "writing")))!!.toSet()
        set(value) { store.edit().putStringSet("purposes:${mode.name}", value.toSet()).apply() }
    var mode: StudyMode
        get() = StudyMode.valueOf(store.getString("mode", "WORD")!!)
        set(value) { store.edit().putString("mode", value.name).apply() }
    fun cursor(kind: String, genre: String) = store.getString("cursor:$kind:$genre", "")!!
    fun shown(entry: EntryEntity) { store.edit().putString("cursor:${entry.kind}:${if (entry.kind == "word") "all" else entry.genre}", entry.id).apply() }
}

data class Recommendation(val entries: List<EntryEntity>, val message: String? = null)

// Stable weighted sequence, built only when filters change or the bounded queue is exhausted.
fun weightedQueue(practical: List<EntryEntity>, classic: List<EntryEntity>, ratio: Int, limit: Int = 100): Recommendation {
    require(ratio in 0..100)
    if ((ratio > 0 && practical.isEmpty()) || (ratio < 100 && classic.isEmpty()))
        return Recommendation(emptyList(), "当前偏好所需的${if (practical.isEmpty() && ratio > 0) "实用" else "经典"}内容为空，请调整领域、用途或比例。")
    val result = mutableListOf<EntryEntity>()
    var p = 0; var c = 0
    for (index in 0 until limit) {
        val usePractical = ((index + 1) * ratio + 50) / 100 > (index * ratio + 50) / 100
        if (usePractical) {
            if (p == practical.size) break
            result += practical[p++]
        } else {
            if (c == classic.size) break
            result += classic[c++]
        }
    }
    return Recommendation(result.distinctBy { it.id }, if (result.isEmpty()) "当前范围暂无内容" else null)
}

class RecommendationRepository(private val db: YanjianDatabase, private val preferences: StudyPreferences) {
    suspend fun queue(mode: StudyMode): Recommendation {
        if (preferences.categories.isEmpty() || preferences.purposes.isEmpty())
            return Recommendation(emptyList(), "请选择至少一个领域和一种用途；全不选时不推荐内容。")
        val version = db.entryDao().manifest()?.contentVersion
            ?: return Recommendation(emptyList(), "正式内容包未就绪，请等待内容数据发布。")
        val kind = if (mode == StudyMode.WORD) "word" else "sentence"
        suspend fun pool(genre: String): List<EntryEntity> {
            val cats = preferences.categories.sorted()
            val purposes = preferences.purposes.sorted()
            val query = """SELECT e.* FROM entries e LEFT JOIN user_entries u ON e.id=u.entryId
                WHERE e.isSample=0 AND e.contentVersion=? AND e.kind=? ${if (genre == "all") "" else "AND e.genre=?"}
                AND (u.isHidden IS NULL OR u.isHidden=0)
                AND e.category IN (${cats.joinToString(",") { "?" }})
                AND (${purposes.joinToString(" OR ") { "e.tagsJson LIKE ?" }})
                ORDER BY CASE WHEN e.id>? THEN 0 ELSE 1 END, e.id LIMIT 100""".trimIndent()
            val args = mutableListOf<Any>(version, kind)
            if (genre != "all") args.add(genre)
            args.addAll(cats); args.addAll(purposes.map { "%\"$it\"%" }); args.add(preferences.cursor(kind, genre))
            return db.entryDao().query(SimpleSQLiteQuery(query, args.toTypedArray()))
        }
        if (mode == StudyMode.WORD) {
            // The 60:40 setting applies only to sentences. Words use both genres without weighting.
            val words = pool("all")
            return Recommendation(words, if (words.isEmpty()) "当前领域和用途暂无可用词语，请调整偏好。" else null)
        }
        val ratio = preferences.ratio
        return weightedQueue(if (ratio > 0) pool("practical") else emptyList(), if (ratio < 100) pool("classic") else emptyList(), ratio)
    }
}
