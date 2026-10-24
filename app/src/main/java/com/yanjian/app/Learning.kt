package com.yanjian.app

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

val appJson = Gson()
fun newId(): String = UUID.randomUUID().toString()
fun newEvent(entry: EntryEntity, type: String, id: String = newId(), session: String = "", before: String = "", after: String = ""): ActivityEvent {
    val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
    return ActivityEvent(id, entry.id, entry.kind, type, now, Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(), zone.id, session, before, after)
}
fun scored(old: UserEntryEntity, grade: Mastery): UserEntryEntity {
    require(grade != Mastery.NEW)
    val now = System.currentTimeMillis()
    val days = when (grade) { Mastery.UNFAMILIAR -> 1; Mastery.FAMILIAR -> 3; else -> 7 }
    return old.copy(mastery = grade.name, reviewCount = old.reviewCount + 1, lastReviewedAt = now,
        dueAt = now + days * 86400000L, schedulerVersion = "demo-1", revision = old.revision + 1)
}
fun EntryEntity.reviewable() = !id.startsWith("personal:") || meaning.isNotBlank()

data class ReviewCard(val entryId: String, val presentationId: String = newId(), val revealed: Boolean = false,
    val flipped: Boolean = false, val skipped: Boolean = false, val scored: Boolean = false, val initial: Boolean = false)
data class ReviewSession(val id: String = newId(), val kind: String, val cards: List<ReviewCard>,
    val position: Int = 0, val filter: String? = null, val practice: Boolean = false)
data class BrowseDraft(val ids: List<String>, val index: Int, val flipped: Boolean, val complete: Boolean = false, val preferenceKey: String = "")
data class ModeDraft(val page: String = "DISCOVER", val search: String = "", val status: String = "ALL",
    val dueOnly: Boolean = false, val sort: String = "recent", val period: Int = 1)
data class RelationUndo(val before: UserEntryEntity?, val after: UserEntryEntity, val eventId: String? = null)

class LearningRepository(private val db: YanjianDatabase) {
    suspend fun save(session: ReviewSession) = db.stateDao().put(AppRecord("session:${session.kind}", "session", appJson.toJson(session)))
    suspend fun restore(kind: String): ReviewSession? = db.stateDao().get("session:$kind")?.let { appJson.fromJson(it.json, ReviewSession::class.java) }
    suspend fun eligible(kind: String, practice: Boolean = false): List<EntryEntity> {
        val sql = """SELECT e.* FROM entries e JOIN user_entries u ON e.id=u.entryId
            WHERE e.kind=? AND u.isFavorite=1 ${if (practice) "" else "AND (u.reviewCount=0 OR (u.dueAt IS NOT NULL AND u.dueAt<=?))"}
            AND (e.id NOT LIKE 'personal:%' OR trim(e.meaning)<>'')
            ORDER BY CASE WHEN u.reviewCount>0 THEN 0 ELSE 1 END,u.dueAt, u.collectedAt IS NULL,u.collectedAt,e.id"""
        return db.entryDao().query(SimpleSQLiteQuery(sql, if (practice) arrayOf<Any>(kind) else arrayOf<Any>(kind, System.currentTimeMillis())))
    }
    suspend fun start(kind: String, practice: Boolean, items: List<EntryEntity>? = null): ReviewSession {
        val users = db.userEntryDao().inMode(kind).associateBy { it.entryId }
        val cards = (items ?: eligible(kind, practice)).filter { it.reviewable() }.map { ReviewCard(it.id, initial = (users[it.id]?.reviewCount ?: 0) == 0) }
        return ReviewSession(kind = kind, cards = cards, practice = practice).also { save(it) }
    }
    suspend fun rate(session: ReviewSession, card: ReviewCard, grade: Mastery, next: ReviewSession): Boolean = db.withTransaction {
        require(!session.practice && card.revealed && !card.scored)
        if (db.stateDao().event(card.presentationId) != null) return@withTransaction false
        val entry = db.entryDao().get(card.entryId) ?: error("内容已不可用")
        val old = db.userEntryDao().get(card.entryId) ?: error("收藏已不存在")
        require(old.isFavorite && entry.reviewable()) { "此内容已不在可学习收藏中" }
        val after = scored(old, grade)
        db.stateDao().event(newEvent(entry, "review", card.presentationId, session.id, appJson.toJson(old), appJson.toJson(after)))
        db.userEntryDao().put(after); save(next); true
    }
    suspend fun undo(session: ReviewSession): ReviewSession = db.withTransaction {
        val event = db.stateDao().events().filter { it.type == "review" && it.sessionId == session.id && it.undoneAt == null }.maxByOrNull { it.occurredAt }
            ?: error("本会话暂无可撤销评分")
        val after = appJson.fromJson(event.nextState, UserEntryEntity::class.java)
        require(db.userEntryDao().get(event.entryId) == after) { "内容后来已变更，无法覆盖新状态" }
        val before = appJson.fromJson(event.previousState, UserEntryEntity::class.java)
        db.userEntryDao().put(before.copy(revision = after.revision + 1))
        db.stateDao().update(event.copy(undoneAt = System.currentTimeMillis()))
        val index = session.cards.indexOfFirst { it.presentationId == event.eventId }; require(index >= 0)
        session.copy(position = index, filter = null, cards = session.cards.mapIndexed { i, c -> if (i == index) c.copy(presentationId = newId(), scored = false, skipped = false, revealed = true, flipped = true) else c }).also { save(it) }
    }
    suspend fun collection(kind: String, draft: ModeDraft, offset: Int = 0, limit: Int = 100, scope: String = "favorite"): List<EntryEntity> {
        val args = mutableListOf<Any>(kind)
        val predicates = mutableListOf("e.kind=?")
        predicates += when (scope) { "personal" -> "e.id LIKE 'personal:%' AND e.isSample=0"; "hidden" -> "u.isHidden=1"; else -> "u.isFavorite=1" }
        if (draft.search.isNotBlank()) {
            predicates += "(e.text LIKE ? ESCAPE '\\' OR e.meaning LIKE ? ESCAPE '\\' OR e.workTitle LIKE ? ESCAPE '\\' OR e.sourcesJson LIKE ? ESCAPE '\\' OR e.tagsJson LIKE ? ESCAPE '\\')"
            val escaped = draft.search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            repeat(5) { args += "%$escaped%" }
        }
        if (scope == "favorite" && draft.status != "ALL") { predicates += "u.mastery=?"; args += draft.status }
        if (scope == "favorite" && draft.dueOnly) { predicates += "u.reviewCount>0 AND u.dueAt IS NOT NULL AND u.dueAt<=?"; args += System.currentTimeMillis() }
        val order = when (draft.sort) { "due" -> "u.dueAt IS NULL,u.dueAt"; "mastery" -> "u.mastery"; else -> "u.collectedAt IS NULL,u.collectedAt DESC" }
        args += limit; args += offset
        return db.entryDao().query(SimpleSQLiteQuery("SELECT e.* FROM entries e LEFT JOIN user_entries u ON e.id=u.entryId WHERE ${predicates.joinToString(" AND ")} ORDER BY $order,e.id LIMIT ? OFFSET ?", args.toTypedArray()))
    }
}
