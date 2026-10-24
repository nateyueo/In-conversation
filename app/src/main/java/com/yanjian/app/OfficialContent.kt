package com.yanjian.app

import androidx.room.withTransaction
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val CONTENT_CATEGORIES = linkedSetOf(
    "日常交流", "古典诗词", "时间与安排", "学习与工作", "家庭与朋友", "情感与心情", "请求与建议", "出行与地点", "饮食与生活", "健康与身体", "问候与礼貌", "天气与环境", "写景状物", "购物与金钱",
    "评价", "人际", "送别思乡", "表达", "情感", "行动", "思维", "抒情言志", "人生感怀", "生活", "时间", "咏史怀古", "自然", "学习", "边塞征战", "状态", "态度", "工作", "品格", "批评", "方法", "行为", "比喻", "性格", "经历", "道理", "祝愿",
    "日常实用", "经典表达", "情感表达", "人生感悟", "期待", "情绪", "沟通", "安慰", "拒绝", "告别", "思念", "鼓励", "克制"
)
private val gson = GsonBuilder().serializeNulls().create()
private val sourceTypes = mapOf("dictionary" to "词典来源", "community_sentence" to "社区例句", "public_domain_text" to "公共领域文本", "original" to "自制原创", "film" to "影视来源")

private fun JsonObject.string(name: String): String {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "字段 $name 必须为字符串" }
    return value.asString
}
private fun JsonObject.nullable(name: String): String? {
    require(has(name)) { "缺少字段 $name" }
    return if (get(name).isJsonNull) null else string(name).also { require(it.isNotBlank()) { "$name 未知应为 null" } }
}
private fun JsonObject.integer(name: String): Int {
    val v = get(name)
    require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isNumber && v.toString().matches(Regex("[1-9][0-9]*"))) { "$name 必须为正整数" }
    return v.asString.toInt()
}
private fun reader(open: () -> Reader) = JsonReader(open()).apply { strictness = Strictness.STRICT }

data class PackageHeader(val contentVersion: Int)

class OfficialContentImporter(private val db: YanjianDatabase) {
    // Two streaming passes allow arbitrary JSON property order without retaining the entries array.
    suspend fun importPackage(open: () -> Reader): Int = withContext(Dispatchers.IO) {
        val header = reader(open).use { r ->
            val seen = hashSetOf<String>()
            var version = 0
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName(); require(seen.add(name)) { "重复包字段 $name" }
                when (name) {
                    "format" -> { require(r.peek() == JsonToken.STRING); require(r.nextString() == "yanjian-official-content") { "不支持的内容格式" } }
                    "schemaVersion" -> { require(r.peek() == JsonToken.NUMBER); require(r.nextString() == "1") { "不支持的结构版本" } }
                    "datasetId" -> { require(r.peek() == JsonToken.STRING); require(r.nextString() == "yanjian-text") { "不支持的数据集" } }
                    "contentVersion" -> { require(r.peek() == JsonToken.NUMBER); val value = r.nextString(); require(value.matches(Regex("[1-9][0-9]*"))); version = value.toInt() }
                    "entries" -> r.skipValue()
                    else -> error("未知包字段 $name")
                }
            }
            r.endObject(); require(r.peek() == JsonToken.END_DOCUMENT)
            require(seen == setOf("format", "schemaVersion", "datasetId", "contentVersion", "entries")) { "包字段不完整" }
            PackageHeader(version)
        }
        db.withTransaction {
            val dao = db.entryDao()
            val active = dao.manifest()
            require(header.contentVersion >= (active?.contentVersion ?: 0)) { "内容包版本低于已安装版本" }
            val sameVersion = active?.contentVersion == header.contentVersion
            val ids = hashSetOf<String>()
            reader(open).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "entries") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        val entry = parseEntry(JsonParser.parseReader(r).asJsonObject, header.contentVersion)
                        require(ids.add(entry.id)) { "重复条目 ID：${entry.id}" }
                        val old = dao.get(entry.id)
                        if (old != null) {
                            if (sameVersion) require(old.contentVersion == header.contentVersion) { "相同版本条目集合变化" }
                            require(entry.recordVersion >= old.recordVersion) { "条目修订号倒退：${entry.id}" }
                            if (sameVersion || entry.recordVersion == old.recordVersion) {
                                require(entry.copy(contentVersion = old.contentVersion) == old) { "相同版本内容冲突：${entry.id}" }
                            }
                        } else require(!sameVersion) { "相同版本新增条目" }
                        if (!sameVersion) {
                            if (old == null) dao.insert(entry) else dao.update(entry)
                        }
                    }
                    r.endArray()
                }
                r.endObject(); require(r.peek() == JsonToken.END_DOCUMENT)
            }
            require(ids.isNotEmpty()) { "正式包不能为空" }
            if (sameVersion) require(ids.size == active!!.entryCount) { "相同版本条目集合变化" }
            else dao.activate(ContentManifest("yanjian-text", header.contentVersion, ids.size))
            ids.size
        }
    }
}

internal fun parseEntry(o: JsonObject, version: Int): EntryEntity {
    val kind = o.string("kind"); require(kind in setOf("word", "sentence"))
    val id = o.string("id")
    require(id.matches(Regex("official:$kind:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "非法官方 ID" }
    val revision = o.integer("recordVersion")
    val text = o.string("text"); require(text.isNotBlank())
    val meaning = o.string("shortMeaning")
    require(kind != "word" || (meaning.isNotBlank() && meaning.any { it in '\u3400'..'\u9fff' })) { "词语必须有中文短释义" }
    val genre = o.string("genre"); require(genre in setOf("practical", "classic"))
    val category = o.string("category"); require(category in CONTENT_CATEGORIES) { "未知领域分类 $category" }
    require(o.string("publicationStatus") == "published") { "候选或未发布状态不能打包" }
    require(o.has("usageNote")) { "缺少 usageNote" }
    val usage = if (o.get("usageNote").isJsonNull) null else o.string("usageNote")
    val tags = o.getAsJsonArray("tags").map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString.also { t -> require(t.isNotBlank()) } }.distinct().sorted()
    val sources = o.getAsJsonArray("sources"); require(sources.size() > 0) { "缺少来源" }
    val sourceIds = hashSetOf<String>()
    val canonical = JsonArray()
    var traceable = false
    sources.forEach { element ->
        val s = element.asJsonObject
        val sourceId = s.string("id"); require(sourceId.isNotBlank() && sourceIds.add(sourceId))
        val type = s.string("type"); require(type in sourceTypes)
        val title = s.nullable("title"); val quote = s.nullable("quote"); val locator = s.nullable("locator")
        val url = s.nullable("url"); val edition = s.nullable("edition")
        val license = s.nullable("licenseName"); val licenseUrl = s.nullable("licenseUrl")
        val attribution = s.nullable("attribution")
        val evidence = s.string("evidenceStatus"); require(evidence in setOf("source_checked", "verified"))
        val rights = s.string("rightsStatus"); require(rights in setOf("open_license", "public_domain", "original"))
        require((type == "original") == (rights == "original"))
        if (type == "original") require(attribution != null && (attribution.contains("自制") || attribution.contains("原创")))
        else require(!url.isNullOrBlank() || !locator.isNullOrBlank()) { "来源不可追溯：$id / $sourceId" }
        if (rights == "open_license") require(!license.isNullOrBlank() && !licenseUrl.isNullOrBlank() && !attribution.isNullOrBlank()) { "缺少开放许可或署名说明" }
        if (quote?.contains(text) == true || !locator.isNullOrBlank()) traceable = true
        canonical.add(JsonObject().apply {
            addProperty("id", sourceId); addProperty("type", type); addProperty("title", title); addProperty("quote", quote)
            addProperty("locator", locator); addProperty("url", url); addProperty("edition", edition)
            addProperty("licenseName", license); addProperty("licenseUrl", licenseUrl); addProperty("attribution", attribution)
            addProperty("evidenceStatus", evidence); addProperty("rightsStatus", rights)
        })
    }
    require(traceable) { "主正文没有可定位来源" }
    val first = canonical[0].asJsonObject
    return EntryEntity(id, kind, text, meaning, first.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        first.get("quote")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), category, genre, usage.orEmpty(), false,
        revision, version, gson.toJson(tags), gson.toJson(canonical))
}

fun EntryEntity.sourceLabel(): String {
    if (id.startsWith("personal:")) return "个人内容 · 用户提供"
    if (isSample) return "自制原创 · 演示内容"
    return sourceObjects().map { sourceTypes[it.get("type").asString] ?: "来源记录" }.distinct().joinToString(" / ")
}
private fun EntryEntity.sourceObjects(): List<JsonObject> = JsonParser.parseString(sourcesJson).asJsonArray.map { it.asJsonObject }
fun EntryEntity.sourceDetails(): String {
    if (id.startsWith("personal:")) return listOf(workTitle, quote, context).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "个人内容 · 来源未填写" }
    if (isSample) return "言间自制原创演示；非影视原句。"
    return sourceObjects().joinToString("\n\n") { s ->
        listOf("${sourceTypes[s.get("type").asString]} · ${if (s.get("evidenceStatus").asString == "verified") "原文已核验" else "来源记录已检查"}",
            *listOf("title", "quote", "locator", "edition", "url", "attribution", "licenseName", "licenseUrl", "rightsStatus")
                .mapNotNull { key -> s.get(key)?.takeUnless { it.isJsonNull }?.asString }.toTypedArray()).joinToString("\n")
    }
}
fun EntryEntity.backText(): String = meaning.ifBlank { "这条内容暂未补充释义" }

// Keep repository identifiers and licensing out of the reading surface.
fun EntryEntity.readingCredit(): String {
    val title = workTitle.trim()
    return title.takeIf { it.startsWith("《") && it.endsWith("》") && it.length < 60 }.orEmpty()
}
