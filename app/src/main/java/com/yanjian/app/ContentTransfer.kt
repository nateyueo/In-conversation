package com.yanjian.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer

private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun normalized(text: String) = Normalizer.normalize(text.trim().replace("\r\n", "\n"), Normalizer.Form.NFC)
private fun JsonObject.string(key: String, default: String = ""): String = get(key)?.takeUnless { it.isJsonNull }?.asString ?: default

data class ImportRow(val line: Int, val fields: Map<String, String>)
data class ImportFile(val format: String, val hash: String, val packageId: String, val rows: List<ImportRow>, val headers: List<String>, val mapping: Map<String, String>)
data class ImportCandidate(val line: Int, val entry: EntryEntity, val sourceKey: String?, val old: EntryEntity?, val duplicate: Boolean)
data class ImportPreview(val file: ImportFile, val candidates: List<ImportCandidate>, val errors: List<String>, val defaultKind: String, val rowKinds: Map<Int,String>)
data class ImportChange(val before: EntryEntity?, val after: EntryEntity, val beforeUser: UserEntryEntity?, val afterUser: UserEntryEntity?)
data class ImportJournal(val id: String, val hash: String, val changes: List<ImportChange>, val skipped: Int, val undone: Boolean = false)

class ContentTransfer(private val context: Context, private val db: YanjianDatabase) {
    private val aliases = linkedMapOf("kind" to listOf("kind", "类型"), "text" to listOf("text", "内容", "正文"),
        "shortMeaning" to listOf("shortMeaning", "释义"), "category" to listOf("category", "类别"), "tags" to listOf("tags", "标签"),
        "sourceTitle" to listOf("sourceTitle", "作品名"), "sourceQuote" to listOf("sourceQuote", "原句"),
        "sourceUrl" to listOf("sourceUrl", "来源链接"), "externalId" to listOf("externalId", "外部编号"))
    val fields get() = aliases.keys.toList()
    suspend fun read(uri: Uri, maxBytes: Int = 10 * 1024 * 1024): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer,0,minOf(buffer.size,maxBytes + 1 - output.size())); if (n < 0) break; output.write(buffer,0,n); if (output.size() > maxBytes) break }
            output.toByteArray()
        } ?: error("无法读取所选文件")
        require(bytes.size <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MiB" }
        try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (e: java.nio.charset.CharacterCodingException) { error("文件不是有效 UTF-8，请另存为 UTF-8 后重试") }
    }
    suspend fun parse(uri: Uri, format: String): ImportFile = withContext(Dispatchers.IO) {
        val text = read(uri); val rows: List<ImportRow>; val headers: List<String>; var packageId = ""
        when (format) {
            "json" -> {
                val root = JsonParser.parseString(text).asJsonObject
                require(root.string("format") == "yanjian-content" && root.get("schemaVersion")?.asInt == 1) { "不支持的内容文件格式或版本" }
                packageId = root.string("packageId")
                rows = root.getAsJsonArray("entries").mapIndexed { index, element ->
                    val obj = element.asJsonObject
                    val map = aliases.keys.associateWith { key -> when (key) {
                        "tags" -> obj.getAsJsonArray("tags")?.map { it.asString }?.joinToString("|") ?: ""
                        "sourceTitle" -> obj.get("source")?.takeUnless { it.isJsonNull }?.asJsonObject?.string("title") ?: ""
                        "sourceQuote" -> obj.get("source")?.takeUnless { it.isJsonNull }?.asJsonObject?.string("quote") ?: ""
                        "sourceUrl" -> obj.get("source")?.takeUnless { it.isJsonNull }?.asJsonObject?.string("url") ?: ""
                        else -> obj.string(key)
                    } }
                    ImportRow(index + 1, map)
                }
                headers = aliases.keys.toList()
            }
            "csv" -> {
                val cells = parseCsv(text); require(cells.isNotEmpty()) { "文件为空" }
                headers = cells.first().second.map { it.trim() }; require(headers.distinct().size == headers.size) { "表头重复" }
                rows = cells.drop(1).filter { it.second.any(String::isNotBlank) }.map { (line, values) ->
                    ImportRow(line, if (values.size == headers.size) headers.zip(values).toMap() else mapOf("__error" to "列数与表头不符"))
                }
            }
            "txt" -> {
                headers = listOf("text"); rows = text.lineSequence().mapIndexedNotNull { i, s -> s.trim().takeIf { it.isNotEmpty() }?.let { ImportRow(i + 1, mapOf("text" to it)) } }.toList()
            }
            else -> error("首版支持 CSV、JSON、TXT")
        }
        require(rows.size <= 10000) { "超过 10,000 条，请拆分文件" }
        require(rows.isNotEmpty()) { "没有可导入内容" }
        ImportFile(format, digest(text), packageId, rows, headers, aliases.mapValues { (_, names) -> headers.firstOrNull { h -> names.any { it.equals(h, true) } } ?: "" })
    }
    suspend fun preview(file: ImportFile, mapping: Map<String, String>, defaultKind: String, rowKinds: Map<Int,String>): ImportPreview = withContext(Dispatchers.IO) {
        require(!mapping["text"].isNullOrBlank()) { "请映射内容列" }
        val existing = db.entryDao().personal().filterNot { it.isSample }; val errors = mutableListOf<String>(); val result = mutableListOf<ImportCandidate>(); val keys = mutableSetOf<String>()
        file.rows.forEach { row ->
            try {
                require(!row.fields.containsKey("__error")) { row.fields["__error"] ?: "列错误" }
                fun value(key: String) = row.fields[mapping[key]].orEmpty().trim()
                val kind = rowKinds[row.line] ?: when (val raw = value("kind")) { "词语", "词", "word" -> "word"; "句子", "句", "sentence" -> "sentence"; "" -> defaultKind; else -> error("未知类型 $raw") }
                require(kind in listOf("word","sentence")) { "未知类型" }
                val text = row.fields[mapping["text"]].orEmpty(); require(text.isNotBlank()) { "缺少内容" }; require(text.codePointCount(0, text.length) <= 4000) { "正文超过 4,000 码点" }
                val tags = value("tags").split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct(); require(tags.size <= 20) { "标签超过 20 个" }
                val external = value("externalId")
                val key = if (file.packageId.isNotBlank() && external.isNotBlank()) "import-key:${digest(file.packageId + "\u0000" + external)}" else null
                if (key != null) require(keys.add(key)) { "包内外部编号重复" }
                val oldId = key?.let { db.stateDao().get(it)?.json }
                val old = oldId?.let { db.entryDao().get(it) }
                require(old == null || old.id.startsWith("personal:")) { "导入映射不能指向官方内容" }
                require(old == null || old.kind == kind) { "同一外部编号不能更换词句类型，请使用新编号" }
                val entry = EntryEntity(old?.id ?: "personal:${newId()}", kind, text, value("shortMeaning"), value("sourceTitle"), value("sourceQuote"),
                    value("category").ifBlank { "个人内容" }, if (value("category") == "classic") "classic" else "practical", value("sourceUrl"),
                    isSample = false, recordVersion = (old?.recordVersion ?: 0) + 1, tagsJson = appJson.toJson(tags))
                fun same(a: EntryEntity, b: EntryEntity) = a.copy(id = "", recordVersion = 0) == b.copy(id = "", recordVersion = 0)
                val duplicate = if (old != null) same(old, entry) else (existing + result.map { it.entry }).any { normalized(it.text) == normalized(text) && same(it.copy(text = text), entry) }
                result += ImportCandidate(row.line, entry, key, old, duplicate)
            } catch (e: Exception) { errors += "第 ${row.line} 行：${e.message ?: "字段无法解析"}" }
        }
        ImportPreview(file.copy(mapping = mapping), result, errors, defaultKind, rowKinds)
    }
    suspend fun commit(preview: ImportPreview, favorite: Boolean, update: Boolean): ImportJournal = db.withTransaction {
        require(db.stateDao().records().none { it.type == "import" && appJson.fromJson(it.json, ImportJournal::class.java).let { j -> j.hash == preview.file.hash && !j.undone } }) { "该文件已导入，未重复写入" }
        val batchId = newId(); val changes = mutableListOf<ImportChange>()
        preview.candidates.filter { !it.duplicate && (it.old == null || update) }.forEach { c ->
            require(db.entryDao().get(c.entry.id) == c.old) { "预览后内容已变更，请重新预览" }
            val entry = c.entry
            val beforeUser = db.userEntryDao().get(entry.id)
            if (c.old == null) db.entryDao().insert(entry) else db.entryDao().update(entry)
            val afterUser = if (favorite && beforeUser?.isFavorite != true) (beforeUser ?: UserEntryEntity(entry.id)).copy(isFavorite = true, collectedAt = System.currentTimeMillis(), revision = (beforeUser?.revision ?: 0) + 1) else if (c.old != null) beforeUser?.copy(revision = beforeUser.revision + 1) else beforeUser
            if (afterUser != null) db.userEntryDao().put(afterUser)
            if (afterUser?.isFavorite == true && beforeUser?.isFavorite != true) db.stateDao().event(newEvent(entry, "favorite", session = batchId))
            c.sourceKey?.let { db.stateDao().put(AppRecord(it, "import-key", entry.id)) }
            changes += ImportChange(c.old, entry, beforeUser, afterUser)
        }
        ImportJournal(batchId, preview.file.hash, changes, preview.file.rows.size - changes.size).also { db.stateDao().put(AppRecord("import:${it.id}", "import", appJson.toJson(it))) }
    }
    suspend fun undo(journal: ImportJournal) = db.withTransaction {
        val live = db.stateDao().get("import:${journal.id}") ?: error("批次不存在")
        require(!appJson.fromJson(live.json, ImportJournal::class.java).undone) { "本批已撤销" }
        journal.changes.forEach { c ->
            require(db.entryDao().get(c.after.id) == c.after && db.userEntryDao().get(c.after.id) == c.afterUser) { "导入后内容或学习已变更，撤销有冲突" }
            require(db.stateDao().events().none { it.entryId == c.after.id && it.type == "review" }) { "内容已有学习记录，不能撤销导入" }
        }
        journal.changes.forEach { c ->
            if (c.before == null) db.entryDao().deletePersonal(c.after.id) else db.entryDao().update(c.before)
            if (c.beforeUser == null) db.userEntryDao().delete(c.after.id) else db.userEntryDao().put(c.beforeUser)
            db.stateDao().records().filter { it.type == "import-key" && it.json == c.after.id && c.before == null }.forEach { db.stateDao().delete(it.key) }
            // Retired snapshots keep batch and event references valid after undo.
            if (c.before == null) db.entryDao().insert(c.after.copy(isSample = true))
        }
        db.stateDao().events().filter { it.sessionId == journal.id && it.type == "favorite" && it.undoneAt == null }.forEach { db.stateDao().update(it.copy(undoneAt = System.currentTimeMillis())) }
        db.stateDao().put(live.copy(json = appJson.toJson(journal.copy(undone = true))))
    }
    suspend fun export(format: String): String = withContext(Dispatchers.IO) {
        val entries = db.entryDao().personal().filterNot { it.isSample }
        if (format == "json") appJson.toJson(mapOf("format" to "yanjian-content", "schemaVersion" to 1, "packageId" to "yanjian-personal", "entries" to entries.map { e -> mapOf("externalId" to e.id, "kind" to e.kind, "text" to e.text, "shortMeaning" to e.meaning, "category" to e.category, "tags" to JsonParser.parseString(e.tagsJson), "source" to mapOf("kind" to "personal", "title" to e.workTitle, "quote" to e.quote, "url" to e.context)) }))
        else {
            fun cell(s: String): String { val safe = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$s" else s; return "\"${safe.replace("\"", "\"\"")}\"" }
            "\uFEFFkind,text,shortMeaning,category,tags,sourceTitle,sourceQuote,sourceUrl,externalId\r\n" + entries.joinToString("\r\n") { e -> listOf(e.kind,e.text,e.meaning,e.category,JsonParser.parseString(e.tagsJson).asJsonArray.joinToString("|") { it.asString },e.workTitle,e.quote,e.context,e.id).joinToString(",", transform = ::cell) }
        }
    }
    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() } ?: error("无法写入所选文件")
    }
}

// RFC-style CSV fields: quoted newlines and doubled quotation marks remain text.
private fun parseCsv(text: String): List<Pair<Int, List<String>>> {
    val result = mutableListOf<Pair<Int, List<String>>>(); var row = mutableListOf<String>(); val field = StringBuilder()
    var quoted = false; var closed = false; var i = 0; var line = 1; var start = 1
    fun endField() { row += field.toString(); field.setLength(0); closed = false }
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' -> if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else { quoted = false; closed = true }
            quoted -> { field.append(c); if (c == '\n') line++ }
            c == '"' -> { require(field.isEmpty() && !closed) { "第 $line 行引号位置错误" }; quoted = true }
            c == ',' -> endField()
            c == '\r' || c == '\n' -> { endField(); result += start to row; row = mutableListOf(); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++; line++; start = line }
            else -> { require(!closed) { "第 $line 行引号后有多余字符" }; field.append(c) }
        }; i++
    }
    require(!quoted) { "第 $start 行引号未闭合" }
    if (field.isNotEmpty() || row.isNotEmpty() || closed) { endField(); result += start to row }
    return result
}

data class BackupPayload(val databaseVersion: Int = 3, val contentVersion: Int, val entries: List<EntryEntity>, val users: List<UserEntryEntity>, val events: List<ActivityEvent>, val records: List<AppRecord>, val preferences: String)
data class BackupFile(val format: String = "yanjian-backup", val schemaVersion: Int = 1, val payload: String, val sha256: String)
class BackupRepository(private val db: YanjianDatabase, private val preferences: StudyPreferences) {
    suspend fun snapshot(): BackupPayload = db.withTransaction {
        val users = db.userEntryDao().all(); val events = db.stateDao().events(); val records = db.stateDao().records().filter { it.type != "recovery" && it.key != "restored-preferences" }
        val journalEntries = records.filter { it.type == "import" }.flatMap { appJson.fromJson(it.json, ImportJournal::class.java).changes.map { c -> c.after } }
        val refs = (users.map { it.entryId } + events.map { it.entryId } + journalEntries.map { it.id } + records.filter { it.type == "session" }.flatMap { appJson.fromJson(it.json, ReviewSession::class.java).cards.map { c -> c.entryId } } + records.filter { it.type == "browse" }.flatMap { appJson.fromJson(it.json, BrowseDraft::class.java).ids }).toSet()
        val entries = (db.entryDao().personal() + refs.mapNotNull { id -> db.entryDao().get(id) ?: journalEntries.firstOrNull { it.id == id }?.copy(isSample = true) }).distinctBy { it.id }
        BackupPayload(contentVersion = db.entryDao().manifest()?.contentVersion ?: 0, entries = entries, users = users, events = events, records = records, preferences = preferences.exportValues())
    }
    fun encode(payload: BackupPayload): String { val body = appJson.toJson(payload); return appJson.toJson(BackupFile(payload = body, sha256 = digest(body))) }
    fun decode(text: String): BackupPayload {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.string("format") == "yanjian-backup" && root.get("schemaVersion")?.asInt == 1) { "不支持的备份格式或版本" }
        val body = root.string("payload"); require(digest(body) == root.string("sha256")) { "备份校验失败" }
        val p = appJson.fromJson(body, BackupPayload::class.java)
        validate(p); return p
    }
    fun validate(p: BackupPayload) {
        require(p.databaseVersion == 3 && p.contentVersion >= 0) { "不支持的数据库版本" }
        require(p.entries.map { it.id }.distinct().size == p.entries.size && p.users.map { it.entryId }.distinct().size == p.users.size && p.events.map { it.eventId }.distinct().size == p.events.size && p.records.map { it.key }.distinct().size == p.records.size) { "备份含重复标识" }
        val entries = p.entries.associateBy { it.id }; val refs = entries.keys
        fun checkEntry(e: EntryEntity) {
            require((e.id.startsWith("personal:") || e.id.startsWith("official:") || e.isSample) && e.kind in listOf("word", "sentence") && e.text.isNotBlank() && e.text.codePointCount(0,e.text.length) <= 4000 && e.recordVersion >= 0) { "内容快照非法" }
            val tags = JsonParser.parseString(e.tagsJson)
            require(tags.isJsonArray && tags.asJsonArray.size() <= 20 && tags.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } && JsonParser.parseString(e.sourcesJson).isJsonArray) { "内容标签或来源非法" }
        }
        fun checkUser(u: UserEntryEntity) {
            require(u.entryId in refs && u.reviewCount >= 0 && u.revision >= 0 && u.mastery in Mastery.entries.map { it.name } && (u.reviewCount != 0 || u.mastery == "NEW") && u.schedulerVersion == "demo-1" && (u.collectedAt == null || u.collectedAt >= 0) && (u.lastReviewedAt == null || u.lastReviewedAt >= 0) && (u.dueAt == null || u.dueAt >= 0)) { "学习状态或引用非法" }
        }
        p.entries.forEach(::checkEntry); p.users.forEach(::checkUser)
        val presentations = mutableSetOf<String>()
        p.events.forEach { e ->
            require(e.eventId.isNotBlank() && e.entryId in refs && e.type in listOf("review", "exposure", "favorite") && e.kind == entries[e.entryId]?.kind && e.occurredAt >= 0 && (e.undoneAt == null || e.undoneAt >= e.occurredAt)) { "事件引用非法" }
            val date = java.time.LocalDate.parse(e.localDate); val zone = java.time.ZoneId.of(e.zoneId)
            require(java.time.Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDate() == date) { "事件日期与时区不一致" }
            if (e.type == "review") {
                val a = appJson.fromJson(e.previousState, UserEntryEntity::class.java); val b = appJson.fromJson(e.nextState, UserEntryEntity::class.java)
                checkUser(a); checkUser(b)
                require(e.sessionId.isNotBlank() && a.entryId == e.entryId && b.entryId == e.entryId && b.reviewCount == a.reviewCount + 1 && b.revision == a.revision + 1 && b.mastery != "NEW") { "评分快照非法" }
            }
        }
        p.records.forEach { r -> when (r.type) {
            "session" -> {
                val s = appJson.fromJson(r.json, ReviewSession::class.java)
                require(r.key == "session:${s.kind}" && s.id.isNotBlank() && s.kind in listOf("word", "sentence") && s.position in 0..s.cards.size && s.cards.map { it.entryId }.distinct().size == s.cards.size && (s.filter == null || s.filter in Mastery.entries.filter { it != Mastery.NEW }.map { it.name })) { "会话状态非法" }
                s.cards.forEach { c -> require(c.entryId in refs && entries[c.entryId]?.kind == s.kind && c.presentationId.isNotBlank() && presentations.add(c.presentationId) && (!c.scored || (!s.practice && p.events.any { it.eventId == c.presentationId && it.sessionId == s.id && it.entryId == c.entryId && it.type == "review" && it.undoneAt == null }))) { "会话卡片或评分引用非法" } }
            }
            "browse" -> { val b = appJson.fromJson(r.json, BrowseDraft::class.java); val kind = r.key.removePrefix("browse:"); require(r.key in listOf("browse:word","browse:sentence") && b.ids.all { entries[it]?.kind == kind } && b.index in 0..b.ids.size && b.ids.distinct().size == b.ids.size) { "浏览状态非法" } }
            "mode" -> { val m = appJson.fromJson(r.json, ModeDraft::class.java); require(r.key in listOf("mode:word","mode:sentence") && m.page in Page.entries.map { it.name } && m.period in listOf(1,7,30,0) && m.status in listOf("ALL") + Mastery.entries.map { it.name } && m.sort in listOf("recent","due","mastery")) { "模式状态非法" } }
            "import-key" -> require(r.key.startsWith("import-key:") && r.json in refs && r.json.startsWith("personal:")) { "导入引用非法" }
            "import" -> {
                val j = appJson.fromJson(r.json, ImportJournal::class.java)
                require(r.key == "import:${j.id}" && j.id.isNotBlank() && j.skipped >= 0 && j.changes.map { it.after.id }.distinct().size == j.changes.size) { "导入批次非法" }
                j.changes.forEach { c ->
                    require(c.after.id.startsWith("personal:") && c.after.id in refs && (c.before == null || c.before.id == c.after.id) && (c.beforeUser == null || c.beforeUser.entryId == c.after.id) && (c.afterUser == null || c.afterUser.entryId == c.after.id)) { "导入批次引用非法" }
                    checkEntry(c.after); c.before?.let(::checkEntry); c.beforeUser?.let(::checkUser); c.afterUser?.let(::checkUser)
                }
            }
            else -> error("备份含不支持的用户记录")
        } }
        val prefs = JsonParser.parseString(p.preferences).asJsonObject
        prefs.entrySet().forEach { (key, v) -> when {
            key == "mode" -> require(v.asString in StudyMode.entries.map { it.name })
            key == "practicalRatio" -> require(v.asInt in 0..100)
            key in listOf("categories","categories:WORD","categories:SENTENCE","purposes","purposes:WORD","purposes:SENTENCE") -> require(v.isJsonArray && v.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
            key in listOf("cursor:word:all","cursor:sentence:practical","cursor:sentence:classic") -> require(v.isJsonPrimitive && v.asJsonPrimitive.isString)
            else -> error("未知偏好字段")
        } }
    }
    suspend fun restore(p: BackupPayload) {
        validate(p)
        db.withTransaction {
            val recovery = encode(snapshot())
            db.userEntryDao().clear(); db.stateDao().clearEvents(); db.stateDao().clearRecords(); db.entryDao().deletePersonal()
            p.entries.forEach { e -> if (e.id.startsWith("personal:")) db.entryDao().insert(e) else if (db.entryDao().get(e.id) == null) db.entryDao().insert(e.copy(isSample = true, contentVersion = 0)) }
            p.users.forEach { db.userEntryDao().put(it) }; p.events.forEach { db.stateDao().event(it) }; p.records.forEach { db.stateDao().put(it) }
            db.stateDao().put(AppRecord("recovery-point", "recovery", recovery))
            // Durable handoff makes preference restoration recoverable after a process death.
            db.stateDao().put(AppRecord("restored-preferences", "preferences", p.preferences))
        }
        applyRestoredPreferences()
    }
    suspend fun applyRestoredPreferences() { db.stateDao().get("restored-preferences")?.let { preferences.restoreValues(it.json); db.stateDao().delete(it.key) } }
}
