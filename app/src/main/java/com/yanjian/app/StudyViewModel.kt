package com.yanjian.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class StudyViewModel : ViewModel() {
    var mode by mutableStateOf(StudyMode.WORD); private set
    var page by mutableStateOf(Page.DISCOVER); private set
    var settingsOpen by mutableStateOf(false); private set
    var users by mutableStateOf<Map<String, UserEntryEntity>>(emptyMap()); private set
    private var saved by mutableStateOf<List<EntryEntity>>(emptyList())
    private var queue by mutableStateOf<List<EntryEntity>>(emptyList())
    private var reviewContent by mutableStateOf<Map<String, EntryEntity>>(emptyMap())
    val entries: List<EntryEntity> get() = (queue + saved + reviewContent.values).distinctBy { it.id }
    var currentIndex by mutableIntStateOf(0); private set
    var flipped by mutableStateOf(false); private set
    var roundComplete by mutableStateOf(false); private set
    var detailOpen by mutableStateOf(false); private set
    private var detail by mutableStateOf<EntryEntity?>(null)
    var session by mutableStateOf<ReviewSession?>(null); private set
    val filter: Mastery? get() = session?.filter?.let { Mastery.valueOf(it) }
    var draft by mutableStateOf(ModeDraft()); private set
    var scope by mutableStateOf("favorite"); private set
    var sentenceRatio by mutableIntStateOf(60); private set
    var selectedCategories by mutableStateOf(CONTENT_CATEGORIES.toSet()); private set
    var selectedPurposes by mutableStateOf(setOf("daily", "writing")); private set
    var toast by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var contentMessage by mutableStateOf<String?>("正在加载本地内容…"); private set
    var hasMoreSaved by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var events by mutableStateOf<List<ActivityEvent>>(emptyList()); private set
    var dueCount by mutableIntStateOf(0); private set
    var initialCount by mutableIntStateOf(0); private set
    var relationUndo by mutableStateOf<RelationUndo?>(null); private set
    var importFile by mutableStateOf<ImportFile?>(null); private set
    var importPreview by mutableStateOf<ImportPreview?>(null); private set
    var importResult by mutableStateOf<ImportJournal?>(null); private set
    var restorePreview by mutableStateOf<BackupPayload?>(null); private set
    var editing by mutableStateOf<EntryEntity?>(null); private set
    var managerOpen by mutableStateOf(false); private set
    var batches by mutableStateOf<List<ImportJournal>>(emptyList()); private set
    private lateinit var db: YanjianDatabase
    private lateinit var preferences: StudyPreferences
    private lateinit var learning: LearningRepository
    private lateinit var transfer: ContentTransfer
    private lateinit var backup: BackupRepository
    private val mutex = Mutex()
    private var searchJob: Job? = null
    private var preferencesDirty = false
    private var browsePreferences = ""
    private fun preferenceKey() = appJson.toJson(listOf(sentenceRatio, selectedCategories.sorted(), selectedPurposes.sorted()))
    private var importWarning: String? = null
    val kind get() = if (mode == StudyMode.WORD) "word" else "sentence"

    fun initialize(context: Context) {
        if (::db.isInitialized) return
        db = YanjianDatabase.get(context); preferences = StudyPreferences(context.applicationContext)
        learning = LearningRepository(db); transfer = ContentTransfer(context.applicationContext, db); backup = BackupRepository(db, preferences)
        act(requireReady = false) {
            backup.applyRestoredPreferences()
            try {
                OfficialContentImporter(db).importPackage { java.io.InputStreamReader(context.applicationContext.assets.open("content/official-content.json"), Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { importWarning = "内容包暂不可用，已保留原内容和学习记录。" }
            mode = preferences.mode; readPreferences(); restoreMode(); refreshUsers(); ready = true
        }
    }
    private fun readPreferences() { sentenceRatio = preferences.ratio; selectedCategories = preferences.categories; selectedPurposes = preferences.purposes }
    private suspend fun restoreMode() {
        draft = db.stateDao().get("mode:$kind")?.let { appJson.fromJson(it.json, ModeDraft::class.java) } ?: ModeDraft()
        page = runCatching { Page.valueOf(draft.page) }.getOrDefault(Page.DISCOVER)
        session = learning.restore(kind); loadSessionEntries()
        val browse = db.stateDao().get("browse:$kind")?.let { appJson.fromJson(it.json, BrowseDraft::class.java) }
        if (browse != null) {
            queue = browse.ids.mapNotNull { db.entryDao().get(it) }.filter { it.kind == kind }
            currentIndex = browse.index.coerceIn(0, queue.size); flipped = browse.flipped; roundComplete = browse.complete
            browsePreferences = browse.preferenceKey.orEmpty(); preferencesDirty = browsePreferences != preferenceKey()
            contentMessage = importWarning
        } else loadQueue()
        if (page == Page.REVIEW && session == null) page = Page.SAVED
    }
    private suspend fun loadSessionEntries() { reviewContent = session?.cards?.mapNotNull { db.entryDao().get(it.entryId) }?.associateBy { it.id } ?: emptyMap() }
    private suspend fun saveMode() {
        draft = draft.copy(page = page.name)
        db.withTransaction {
            db.stateDao().put(AppRecord("mode:$kind", "mode", appJson.toJson(draft)))
            db.stateDao().put(AppRecord("browse:$kind", "browse", appJson.toJson(BrowseDraft(queue.map { it.id }, currentIndex, flipped, roundComplete, browsePreferences))))
            session?.let { learning.save(it) }
        }
    }
    private suspend fun refreshUsers() {
        users = db.userEntryDao().inMode(kind).associateBy { it.entryId }
        saved = learning.collection(kind, draft, limit = maxOf(100, saved.size), scope = scope)
        hasMoreSaved = saved.size >= 100 && saved.size % 100 == 0
        val eligible = learning.eligible(kind); initialCount = eligible.count { users[it.id]?.reviewCount == 0 }; dueCount = eligible.size - initialCount
        events = db.stateDao().events().filter { it.kind == kind }
        batches = db.stateDao().records().filter { it.type == "import" }.map { appJson.fromJson(it.json, ImportJournal::class.java) }.reversed()
    }
    fun loadMoreSaved() = act { val more = learning.collection(kind, draft, saved.size, scope = scope); saved = saved + more; hasMoreSaved = more.size == 100 }
    private suspend fun loadQueue() {
        val result = RecommendationRepository(db, preferences).queue(mode)
        queue = result.entries; currentIndex = 0; flipped = false; roundComplete = false
        contentMessage = importWarning ?: result.message; preferencesDirty = false; browsePreferences = preferenceKey(); saveMode()
    }
    fun nextRound() = act { loadQueue() }
    fun toggleMode() = act {
        val sourcePage = page
        saveMode(); mode = if (mode == StudyMode.WORD) StudyMode.SENTENCE else StudyMode.WORD
        preferences.mode = mode; readPreferences(); detailOpen = false; scope = "favorite"; saved = emptyList()
        restoreMode(); page = if (sourcePage == Page.REVIEW && session == null) Page.SAVED else sourcePage
        refreshUsers(); saveMode()
    }
    fun navigate(target: Page) = act {
        detailOpen = false; scope = "favorite"; page = target
        if (target == Page.REVIEW) {
            if (session == null || session!!.cards.all { it.scored }) session = learning.start(kind, false)
            loadSessionEntries()
        }
        refreshUsers(); saveMode()
    }
    fun startReview(practice: Boolean = false, filtered: Boolean = false, restart: Boolean = false) = act {
        val old = session
        if (restart || old == null || old.practice != practice || old.cards.all { it.scored } || filtered) {
            val items = if (filtered) learning.collection(kind, draft, limit = Int.MAX_VALUE) else null
            session = learning.start(kind, practice, items)
        } else {
            val known = old.cards.map { it.entryId }.toSet()
            val additions = learning.eligible(kind, practice).filterNot { it.id in known }.map { ReviewCard(it.id, initial = (users[it.id]?.reviewCount ?: 0) == 0) }
            session = old.copy(cards = old.cards + additions)
        }
        loadSessionEntries()
        session?.let { s -> if (s.position >= s.cards.size || reviewCard() == null) session = s.copy(position = nextPosition(s,-1)) }
        page = Page.REVIEW; saveMode()
    }
    fun toggleSettings() { if (!ready || busy) return; settingsOpen = !settingsOpen; detailOpen = false }
    fun back() { when {
        editing != null -> editing = null
        restorePreview != null -> restorePreview = null
        importFile != null || importResult != null -> clearImport()
        detailOpen -> closeDetail()
        managerOpen -> { managerOpen = false; setScope("favorite") }
        settingsOpen -> toggleSettings()
        page == Page.REVIEW -> navigate(Page.SAVED)
        else -> navigate(Page.DISCOVER)
    } }
    fun updateFilter(value: Mastery) = act {
        val s = session ?: return@act
        session = s.copy(filter = if (s.filter == value.name) null else value.name, position = 0)
        session = session!!.copy(position = nextPosition(session!!, -1)); learning.save(session!!)
    }
    fun setRatio(value: Int) { sentenceRatio = value.coerceIn(0,100); if (::preferences.isInitialized) preferences.ratio = sentenceRatio; preferencesDirty = true }
    fun toggleCategory(value: String) { selectedCategories = if (value in selectedCategories) selectedCategories - value else selectedCategories + value; preferences.categories = selectedCategories; preferencesDirty = true }
    fun togglePurpose(value: String) { selectedPurposes = if (value in selectedPurposes) selectedPurposes - value else selectedPurposes + value; preferences.purposes = selectedPurposes; preferencesDirty = true }
    @JvmName("updateCollectionDraft")
    fun setDraft(value: ModeDraft) {
        draft = value; val snapshot = value; val modeKind = kind
        searchJob?.cancel(); searchJob = viewModelScope.launch {
            delay(180); mutex.withLock { if (kind == modeKind && draft == snapshot) { saved = emptyList(); refreshUsers(); saveMode() } }
        }
    }
    @JvmName("chooseCollectionScope")
    fun setScope(value: String) = act { scope = value; saved = emptyList(); refreshUsers() }
    fun openManager() { managerOpen = true; setScope("personal") }
    fun showDetail(entry: EntryEntity? = if (page == Page.REVIEW) reviewEntry() else current()) = act {
        if (entry == null) return@act
        if (page == Page.REVIEW && reviewCard()?.entryId == entry.id) updateCard { it.copy(revealed = true) }
        if (managerOpen) managerOpen = false
        detail = entry; detailOpen = true
    }
    fun detailEntry() = detail
    fun closeDetail() { detailOpen = false; detail = null }
    private suspend fun updateCard(change: (ReviewCard) -> ReviewCard) {
        val s = session ?: return
        session = s.copy(cards = s.cards.mapIndexed { i,c -> if (i == s.position) change(c) else c }); learning.save(session!!)
    }
    fun flip() = act {
        if (page == Page.REVIEW) updateCard { it.copy(flipped = !it.flipped, revealed = true) }
        else { flipped = !flipped; saveMode() }
    }
    fun next(delta: Int = 1) = act {
        if (preferencesDirty && delta > 0) { loadQueue(); return@act }
        detailOpen = false; flipped = false
        if (delta < 0) { currentIndex = (currentIndex - 1).coerceAtLeast(0); roundComplete = false }
        else if (currentIndex + 1 < queue.size) currentIndex++ else { roundComplete = true; contentMessage = "本轮已看完" }
        current()?.let { preferences.shown(it) }; saveMode()
    }
    fun discoverEntries() = queue
    fun current(): EntryEntity? = if (roundComplete) null else queue.getOrNull(currentIndex)
    fun isFavorite(id: String) = users[id]?.isFavorite == true
    fun favorites() = saved
    private fun act(requireReady: Boolean = true, action: suspend () -> Unit) {
        if (busy || !::db.isInitialized || (requireReady && !ready)) return
        busy = true; error = null
        viewModelScope.launch {
            try { mutex.withLock { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e.message?.takeIf { it.length < 180 } ?: "操作未完成，请重试。"); toast = "操作未完成" }
            finally { busy = false }
        }
    }
    fun visible(entry: EntryEntity) {
        if (!ready) return
        viewModelScope.launch { mutex.withLock {
            try {
                val today = LocalDate.now().toString()
                if (db.stateDao().events().none { it.type == "exposure" && it.entryId == entry.id && it.localDate == today }) db.stateDao().event(newEvent(entry,"exposure"))
                preferences.shown(entry); events = db.stateDao().events().filter { it.kind == kind }
            } catch (e: Exception) { error = "浏览记录未保存，请重试。" }
        } }
    }
    fun toggleFavorite(entry: EntryEntity) = act {
        db.withTransaction {
            val before = db.userEntryDao().get(entry.id)
            val old = before ?: UserEntryEntity(entry.id)
            val after = old.copy(isFavorite = !old.isFavorite, collectedAt = if (!old.isFavorite) System.currentTimeMillis() else old.collectedAt, revision = old.revision + 1)
            val event = if (after.isFavorite) newEvent(entry,"favorite") else null
            db.userEntryDao().put(after); event?.let { db.stateDao().event(it) }; relationUndo = RelationUndo(before,after,event?.eventId)
        }; refreshUsers(); toast = if (isFavorite(entry.id)) "已收藏" else "已取消收藏"
    }
    fun hide(entry: EntryEntity) = act {
        val before = db.userEntryDao().get(entry.id); val old = before ?: UserEntryEntity(entry.id)
        val after = old.copy(isHidden = true, revision = old.revision + 1)
        db.userEntryDao().put(after); relationUndo = RelationUndo(before, after)
        queue = queue.filterNot { it.id == entry.id }; currentIndex = currentIndex.coerceAtMost((queue.size - 1).coerceAtLeast(0)); flipped = false
        refreshUsers(); saveMode(); toast = "已加入不再推荐"
    }
    fun restoreHidden(entry: EntryEntity) = act { val old = db.userEntryDao().get(entry.id) ?: return@act; db.userEntryDao().put(old.copy(isHidden = false, revision = old.revision + 1)); refreshUsers(); toast = "已恢复推荐" }
    fun undoRelation() = act {
        val undo = relationUndo ?: return@act
        db.withTransaction {
            require(db.userEntryDao().get(undo.after.entryId) == undo.after) { "后续状态已变更，无法撤销" }
            if (undo.before == null) db.userEntryDao().delete(undo.after.entryId) else db.userEntryDao().put(undo.before.copy(revision = undo.after.revision + 1))
            undo.eventId?.let { db.stateDao().event(it)?.let { e -> db.stateDao().update(e.copy(undoneAt = System.currentTimeMillis())) } }
        }; relationUndo = null; refreshUsers(); toast = "已撤销"
    }
    private fun matches(s: ReviewSession, c: ReviewCard): Boolean = users[c.entryId]?.isFavorite == true && reviewContent[c.entryId]?.reviewable() == true && (s.filter == null || (!c.initial && users[c.entryId]?.mastery == s.filter))
    private fun nextPosition(s: ReviewSession, after: Int): Int = s.cards.indices.firstOrNull { it > after && !s.cards[it].scored && !s.cards[it].skipped && matches(s,s.cards[it]) } ?: s.cards.size
    fun reviewCard() = session?.let { s -> s.cards.getOrNull(s.position)?.takeIf { matches(s,it) } }
    fun reviewEntry() = reviewCard()?.let { reviewContent[it.entryId] }
    fun reviewEntries() = session?.cards?.filter { matches(session!!,it) && !it.scored && !it.skipped }?.mapNotNull { reviewContent[it.entryId] } ?: emptyList()
    fun reviewNext(skip: Boolean = true) = act {
        val s = session ?: return@act
        val cards = s.cards.mapIndexed { i,c -> if (i == s.position && !c.scored && skip) c.copy(skipped = true) else c }
        val changed = s.copy(cards = cards); session = changed.copy(position = nextPosition(changed,s.position)); learning.save(session!!)
    }
    fun reviewPrevious() = act { val s = session ?: return@act; val index = s.cards.indices.lastOrNull { it < s.position && matches(s,s.cards[it]) } ?: return@act; session = s.copy(position = index); learning.save(session!!) }
    fun continueSkipped() = act { val s = session ?: return@act; val changed = s.copy(cards = s.cards.map { it.copy(skipped = false) }); session = changed.copy(position = nextPosition(changed,-1)); learning.save(session!!) }
    fun rate(entry: EntryEntity, grade: Mastery) = act {
        val s = session ?: return@act; val c = reviewCard() ?: return@act
        require(c.entryId == entry.id)
        val changed = s.copy(cards = s.cards.mapIndexed { i,item -> if (i == s.position) item.copy(scored = true) else item })
        val next = changed.copy(position = nextPosition(changed,s.position))
        if (learning.rate(s,c,grade,next)) { session = next; refreshUsers(); toast = "已记录：${grade.label}" }
    }
    fun undoRating() = act { val s = session ?: return@act; session = learning.undo(s); refreshUsers(); toast = "已撤销评分" }
    fun allUserRecords() = users.values.toList()
    fun filteredEvents(): List<ActivityEvent> { val since = if (draft.period == 0) null else LocalDate.now().minusDays((draft.period - 1).toLong()).toString(); return events.filter { it.undoneAt == null && (since == null || it.localDate >= since) && it.localDate <= LocalDate.now().toString() } }
    fun edit(entry: EntryEntity) { if (entry.id.startsWith("personal:")) { editing = entry; detailOpen = false } }
    fun closeEditor() { editing = null }
    fun savePersonal(text: String, meaning: String, title: String, quote: String, url: String, tags: String) = act {
        val old = editing ?: return@act; require(old.id.startsWith("personal:") && text.isNotBlank() && text.codePointCount(0,text.length) <= 4000) { "正文需为 1–4,000 码点" }
        val list = tags.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct(); require(list.size <= 20) { "最多 20 个标签" }
        db.withTransaction {
            require(db.entryDao().get(old.id) == old) { "内容后来已修改，请重新打开" }
            db.entryDao().update(old.copy(text = text, meaning = meaning, workTitle = title, quote = quote, context = url, tagsJson = appJson.toJson(list), recordVersion = old.recordVersion + 1))
            db.userEntryDao().get(old.id)?.let { db.userEntryDao().put(it.copy(revision = it.revision + 1)) }
        }; editing = null; refreshUsers(); loadSessionEntries(); toast = "已保存个人内容"
    }
    fun chooseImport(uri: Uri, format: String) = act { importFile = transfer.parse(uri,format); importPreview = null; importResult = null }
    fun previewImport(mapping: Map<String,String>, defaultKind: String, rowKinds: Map<Int,String> = emptyMap()) = act { importPreview = transfer.preview(importFile ?: return@act,mapping,defaultKind,rowKinds) }
    fun confirmImport(favorite: Boolean, update: Boolean) = act { importResult = transfer.commit(importPreview ?: return@act,favorite,update); importFile = null; importPreview = null; refreshUsers(); toast = "导入已保存" }
    fun undoImport(journal: ImportJournal) = act { transfer.undo(journal); importResult = journal.copy(undone = true); refreshUsers(); toast = "已撤销本批导入" }
    fun clearImport() { importFile = null; importPreview = null; importResult = null }
    fun exportTo(uri: Uri, format: String) = act { saveMode(); val text = if (format == "backup") withContext(Dispatchers.IO) { backup.encode(backup.snapshot()) } else transfer.export(format); transfer.write(uri,text); toast = "文件已导出" }
    fun chooseRestore(uri: Uri) = act { restorePreview = withContext(Dispatchers.IO) { backup.decode(transfer.read(uri,64 * 1024 * 1024)) } }
    fun previewRecovery() = act { restorePreview = withContext(Dispatchers.IO) { backup.decode(db.stateDao().get("recovery-point")?.json ?: error("暂无恢复点")) } }
    fun cancelRestore() { restorePreview = null }
    fun confirmRestore() = act {
        val p = restorePreview ?: return@act
        backup.restore(p); restorePreview = null; relationUndo = null; managerOpen = false; settingsOpen = false; detailOpen = false
        mode = preferences.mode; readPreferences(); scope = "favorite"; saved = emptyList(); restoreMode(); refreshUsers(); toast = "恢复已完成，原状态已保存为恢复点"
    }
    fun clearError() { error = null }
    fun clearToast() { toast = null }
}
