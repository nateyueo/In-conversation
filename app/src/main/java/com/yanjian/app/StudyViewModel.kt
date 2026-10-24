package com.yanjian.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class StudyViewModel : ViewModel() {
    var mode by mutableStateOf(StudyMode.WORD); private set
    var page by mutableStateOf(Page.DISCOVER); private set
    var settingsOpen by mutableStateOf(false); private set
    var users by mutableStateOf<Map<String, UserEntryEntity>>(emptyMap()); private set
    private var saved by mutableStateOf<List<EntryEntity>>(emptyList())
    private var queue by mutableStateOf<List<EntryEntity>>(emptyList())
    val entries: List<EntryEntity> get() = (queue + saved).distinctBy { it.id }
    var currentIndex by mutableIntStateOf(0); private set
    var flipped by mutableStateOf(false); private set
    var detailOpen by mutableStateOf(false); private set
    private var detail by mutableStateOf<EntryEntity?>(null)
    var filter by mutableStateOf(Mastery.UNFAMILIAR); private set
    private var reviewedThisSession by mutableStateOf(setOf<String>())
    var sentenceRatio by mutableIntStateOf(60); private set
    var selectedCategories by mutableStateOf(CONTENT_CATEGORIES.toSet()); private set
    var selectedPurposes by mutableStateOf(setOf("daily", "writing")); private set
    var toast by mutableStateOf<String?>(null); private set
    var contentMessage by mutableStateOf<String?>("正在加载本地内容…"); private set
    var hasMoreSaved by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    private lateinit var db: YanjianDatabase
    private lateinit var preferences: StudyPreferences
    private lateinit var userRepository: UserRepository
    private var loadJob: Job? = null
    private var preferencesDirty = false
    private var importWarning: String? = null
    private var ready = false
    private val kind get() = if (mode == StudyMode.WORD) "word" else "sentence"

    fun initialize(context: Context) {
        if (::db.isInitialized) return
        db = YanjianDatabase.get(context)
        preferences = StudyPreferences(context.applicationContext)
        userRepository = UserRepository(db)
        sentenceRatio = preferences.ratio; selectedCategories = preferences.categories
        selectedPurposes = preferences.purposes; mode = preferences.mode
        loadJob = viewModelScope.launch {
            try {
                OfficialContentImporter(db).importPackage { java.io.InputStreamReader(context.applicationContext.assets.open("content/official-content.json"), Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                importWarning = if (e is java.io.FileNotFoundException) "正式内容包未就绪；已有收藏仍可查看。" else "内容包无法加载，已保留原有内容和学习记录。"
            }
            ready = true
            try { refreshUsers(); loadQueue() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { contentMessage = "本地内容暂时无法读取，请重新打开应用。" }
        }
    }
    private suspend fun refreshUsers() {
        users = db.userEntryDao().inMode(kind).associateBy { it.entryId }
        saved = db.entryDao().userContent(kind, maxOf(100, saved.size), 0)
        hasMoreSaved = saved.size < users.values.count { it.isFavorite }
    }
    fun loadMoreSaved() {
        if (busy || !hasMoreSaved) return
        act { saved = saved + db.entryDao().userContent(kind, 100, saved.size); hasMoreSaved = saved.size < users.values.count { it.isFavorite } }
    }
    private suspend fun loadQueue() {
        val result = RecommendationRepository(db, preferences).queue(mode)
        queue = result.entries; currentIndex = 0
        contentMessage = importWarning ?: result.message
        current()?.let { preferences.shown(it) }
        preferencesDirty = false
    }
    private fun reload() {
        if (!ready) return
        loadJob?.cancel()
        queue = emptyList(); currentIndex = 0; contentMessage = "正在加载本地内容…"
        loadJob = viewModelScope.launch {
            try { refreshUsers(); loadQueue() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { contentMessage = "本地内容暂时无法读取，请重新打开应用。" }
        }
    }
    fun toggleMode() {
        if (!::preferences.isInitialized || busy) return
        mode = if (mode == StudyMode.WORD) StudyMode.SENTENCE else StudyMode.WORD
        preferences.mode = mode; flipped = false; detailOpen = false; reviewedThisSession = emptySet()
        saved = emptyList(); users = emptyMap()
        reload()
    }
    fun navigate(target: Page) { page = target; flipped = false; detailOpen = false; if (target == Page.REVIEW) reviewedThisSession = emptySet() }
    fun toggleSettings() {
        settingsOpen = !settingsOpen
        if (settingsOpen) detailOpen = false
        // Apply changed preferences when leaving settings, without shuffling during recomposition.
        if (!settingsOpen && preferencesDirty && current() == null) reload()
    }
    fun updateFilter(value: Mastery) { filter = value; flipped = false; reviewedThisSession = emptySet() }
    fun setRatio(value: Int) { sentenceRatio = value.coerceIn(0, 100); if (::preferences.isInitialized) preferences.ratio = sentenceRatio; preferencesDirty = true }
    fun toggleCategory(value: String) {
        selectedCategories = if (value in selectedCategories) selectedCategories - value else selectedCategories + value
        if (::preferences.isInitialized) preferences.categories = selectedCategories
        preferencesDirty = true
    }
    fun togglePurpose(value: String) {
        selectedPurposes = if (value in selectedPurposes) selectedPurposes - value else selectedPurposes + value
        if (::preferences.isInitialized) preferences.purposes = selectedPurposes
        preferencesDirty = true
    }
    fun showDetail(entry: EntryEntity? = current()) { detail = entry; detailOpen = entry != null }
    fun detailEntry(): EntryEntity? = detail
    fun closeDetail() { detailOpen = false; detail = null }
    fun flip() { flipped = !flipped }
    fun next(delta: Int = 1) {
        if (preferencesDirty && delta > 0) { reload(); return }
        flipped = false; detailOpen = false
        if (delta < 0) currentIndex = (currentIndex - 1).coerceAtLeast(0)
        else if (currentIndex + 1 < queue.size) currentIndex++
        else { reload(); return }
        current()?.let { preferences.shown(it) }
    }
    fun discoverEntries(): List<EntryEntity> = queue
    fun current(): EntryEntity? = queue.getOrNull(currentIndex)
    fun isFavorite(id: String) = users[id]?.isFavorite == true
    fun favorites(): List<EntryEntity> = saved.filter { users[it.id]?.isFavorite == true }
    private fun act(action: suspend () -> Unit) {
        if (busy || !::db.isInitialized) return
        busy = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast = "这次操作未保存，请重试。" }
            finally { busy = false }
        }
    }
    fun toggleFavorite(entry: EntryEntity) = act {
        val updated = userRepository.favorite(entry.id)
        refreshUsers(); toast = if (updated.isFavorite) "已收藏" else "已取消收藏"
    }
    fun hide(entry: EntryEntity) = act {
        userRepository.hide(entry.id); refreshUsers()
        queue = queue.filterNot { it.id == entry.id }; currentIndex = currentIndex.coerceAtMost((queue.size - 1).coerceAtLeast(0))
        if (queue.isEmpty()) loadQueue() else current()?.let { preferences.shown(it) }
        flipped = false; toast = "已加入不再推荐"
    }
    fun rate(entry: EntryEntity, grade: Mastery) {
        if (entry.id in reviewedThisSession) return
        act {
            userRepository.rate(entry.id, grade); reviewedThisSession = reviewedThisSession + entry.id
            refreshUsers(); flipped = false; toast = "已记录：${grade.label}"
        }
    }
    fun reviewEntries(): List<EntryEntity> = favorites().filter { it.id !in reviewedThisSession && (users[it.id]?.mastery ?: Mastery.UNFAMILIAR.name) == filter.name }
    fun allUserRecords() = users.values.toList()
    fun clearToast() { toast = null }
}
