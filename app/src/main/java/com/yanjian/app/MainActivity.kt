package com.yanjian.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Paper = Color(0xFFF4F1EA)
private val PaperCard = Color(0xFFFFFDF8)
private val Ink = Color(0xFF242622)
private val Muted = Color(0xFF686D65)
private val Terracotta = Color(0xFFA04A35)
private val PaperLine = Color(0xFFDDD8CE)
private val Night = Color(0xFF151E27)
private val NightCard = Color(0xFF202B35)
private val NightMuted = Color(0xFFAAB3BA)
private val Gold = Color(0xFFDDC6A1)
private val NightLine = Color(0xFF3A4650)

enum class StudyMode { WORD, SENTENCE }
enum class Page { DISCOVER, SAVED, REVIEW, STATS, SETTINGS }
enum class Mastery(val label: String) { NEW("待学习"), UNFAMILIAR("不熟悉"), FAMILIAR("熟悉"), KNOWN("已会") }

@Entity(tableName = "entries", indices = [Index(value = ["kind"]), Index(value = ["text"])])
data class EntryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val text: String,
    val meaning: String,
    val workTitle: String,
    val quote: String,
    val category: String,
    val genre: String,
    val context: String,
    val isSample: Boolean = true
)

@Entity(tableName = "user_entries", primaryKeys = ["entryId"])
data class UserEntryEntity(
    val entryId: String,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val mastery: String = Mastery.UNFAMILIAR.name,
    val reviewCount: Int = 0,
    val lastReviewedAt: Long? = null
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE kind = :kind ORDER BY id")
    suspend fun byKind(kind: String): List<EntryEntity>
    @Query("SELECT * FROM entries ORDER BY id")
    suspend fun all(): List<EntryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<EntryEntity>)
}

@Dao
interface UserEntryDao {
    @Query("SELECT * FROM user_entries WHERE entryId = :id LIMIT 1")
    suspend fun get(id: String): UserEntryEntity?
    @Query("SELECT * FROM user_entries")
    suspend fun all(): List<UserEntryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: UserEntryEntity)
}

@Database(entities = [EntryEntity::class, UserEntryEntity::class], version = 1, exportSchema = true)
abstract class YanjianDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun userEntryDao(): UserEntryDao
    companion object {
        @Volatile private var instance: YanjianDatabase? = null
        fun get(context: android.content.Context): YanjianDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, YanjianDatabase::class.java, "yanjian-local.db")
                .build().also { instance = it }
        }
    }
}

private fun seedEntries() = listOf(
    EntryEntity("sample-word-001", "word", "希冀", "怀着期待与盼望。", "待补充经核验作品", "", "期待", "classic", "书面色彩较浓，适合描述对未来的期待。该条为原创演示样例，不是影视原句。"),
    EntryEntity("sample-word-002", "word", "怅然", "因有所失而感到失落。", "待补充经核验作品", "", "情绪", "practical", "表达心里空落落的失落感，常用于事情结束或愿望落空之后。该条为原创演示样例，不是影视原句。"),
    EntryEntity("sample-word-003", "word", "欲言又止", "想说什么，却又停住了。", "待补充经核验作品", "", "沟通", "practical", "常用于有顾虑、难以启齿或情绪复杂的场景。该条为原创演示样例，不是影视原句。"),
    EntryEntity("sample-sentence-001", "sentence", "我想把这句话好好说完。", "表达希望被认真倾听。", "原创演示句", "我想把这句话好好说完。", "沟通", "practical", "这是原创教学示例，不归属于任何影视作品。"),
    EntryEntity("sample-sentence-002", "sentence", "有些告别，不一定要说得很响亮。", "温和地表达告别与释怀。", "原创演示句", "有些告别，不一定要说得很响亮。", "告别", "classic", "这是原创教学示例，不归属于任何影视作品。"),
    EntryEntity("sample-sentence-003", "sentence", "愿你有勇气，也有选择的余地。", "表达祝福与支持。", "原创演示句", "愿你有勇气，也有选择的余地。", "鼓励", "classic", "这是原创教学示例，不归属于任何影视作品。")
)

class StudyViewModel : ViewModel() {
    var mode by mutableStateOf(StudyMode.WORD); private set
    var page by mutableStateOf(Page.DISCOVER); private set
    var settingsOpen by mutableStateOf(false); private set
    var entries by mutableStateOf<List<EntryEntity>>(emptyList()); private set
    var users by mutableStateOf<Map<String, UserEntryEntity>>(emptyMap()); private set
    var currentIndex by mutableIntStateOf(0); private set
    var flipped by mutableStateOf(false); private set
    var detailOpen by mutableStateOf(false); private set
    var selectedDetailId by mutableStateOf<String?>(null); private set
    var filter by mutableStateOf(Mastery.UNFAMILIAR); private set
    private var reviewedThisSession by mutableStateOf(setOf<String>())
    var sentenceRatio by mutableIntStateOf(60); private set
    var selectedCategories by mutableStateOf(setOf("日常实用", "经典表达", "情感表达", "人生感悟")); private set
    var toast by mutableStateOf<String?>(null); private set
    private lateinit var db: YanjianDatabase
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)

    fun initialize(context: android.content.Context) {
        if (::db.isInitialized) return
        db = YanjianDatabase.get(context)
        scope.launch {
            val dao = db.entryDao()
            if (dao.all().isEmpty()) dao.insertAll(seedEntries())
            refresh()
        }
    }
    private suspend fun refresh() {
        entries = db.entryDao().all()
        users = db.userEntryDao().all().associateBy { it.entryId }
    }
    fun toggleMode() { mode = if (mode == StudyMode.WORD) StudyMode.SENTENCE else StudyMode.WORD; currentIndex = 0; flipped = false; detailOpen = false }
    fun navigate(target: Page) { page = target; flipped = false; detailOpen = false; if (target == Page.REVIEW) reviewedThisSession = emptySet() }
    fun toggleSettings() { settingsOpen = !settingsOpen; if (settingsOpen) detailOpen = false }
    fun updateFilter(value: Mastery) { filter = value; flipped = false; reviewedThisSession = emptySet() }
    fun setRatio(value: Int) { sentenceRatio = value.coerceIn(0, 100) }
    fun toggleCategory(value: String) { selectedCategories = if (value in selectedCategories) selectedCategories - value else selectedCategories + value }
    fun showDetail(entry: EntryEntity? = current()) { selectedDetailId = entry?.id; detailOpen = entry != null }
    fun detailEntry(): EntryEntity? = entries.firstOrNull { it.id == selectedDetailId }
    fun closeDetail() { detailOpen = false; selectedDetailId = null }
    fun flip() { flipped = !flipped }
    fun next(delta: Int = 1) { val list = discoverEntries(); if (list.isNotEmpty()) currentIndex = (currentIndex + delta + list.size) % list.size; flipped = false; detailOpen = false }
    fun discoverEntries(): List<EntryEntity> {
        val base = entries.filter { it.kind == (if (mode == StudyMode.WORD) "word" else "sentence") && users[it.id]?.isHidden != true }
        if (mode == StudyMode.WORD) return base
        val practical = base.filter { it.genre == "practical" }; val classic = base.filter { it.genre == "classic" }
        return (practical.take((base.size * sentenceRatio / 100).coerceAtLeast(if (sentenceRatio > 0) 1 else 0)) + classic).distinctBy { it.id } + practical.drop((base.size * sentenceRatio / 100).coerceAtLeast(if (sentenceRatio > 0) 1 else 0))
    }
    fun current(): EntryEntity? { val list = discoverEntries(); return list.getOrNull(currentIndex.mod(maxOf(list.size, 1))) }
    fun isFavorite(id: String) = users[id]?.isFavorite == true
    fun favorites(): List<EntryEntity> = entries.filter { e -> e.kind == (if (mode == StudyMode.WORD) "word" else "sentence") && users[e.id]?.isFavorite == true }
    fun toggleFavorite(entry: EntryEntity) { scope.launch { val old = db.userEntryDao().get(entry.id) ?: UserEntryEntity(entry.id); db.userEntryDao().put(old.copy(isFavorite = !old.isFavorite, mastery = if (!old.isFavorite) Mastery.UNFAMILIAR.name else old.mastery)); refresh(); toast = if (!old.isFavorite) "已收藏" else "已取消收藏" } }
    fun hide(entry: EntryEntity) { scope.launch { val old = db.userEntryDao().get(entry.id) ?: UserEntryEntity(entry.id); db.userEntryDao().put(old.copy(isHidden = true)); refresh(); next(); toast = "已加入不再推荐" } }
    fun rate(entry: EntryEntity, grade: Mastery) { scope.launch { val old = db.userEntryDao().get(entry.id) ?: UserEntryEntity(entry.id, isFavorite = true); db.userEntryDao().put(old.copy(isFavorite = true, mastery = grade.name, reviewCount = old.reviewCount + 1, lastReviewedAt = System.currentTimeMillis())); reviewedThisSession = reviewedThisSession + entry.id; refresh(); flipped = false; toast = "已记录：${grade.label}" } }
    fun reviewEntries(): List<EntryEntity> = favorites().filter { it.id !in reviewedThisSession && (users[it.id]?.mastery ?: Mastery.UNFAMILIAR.name) == filter.name }
    fun allUserRecords() = users.values.toList()
    fun clearToast() { toast = null }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); val db = YanjianDatabase.get(this); kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { if (db.entryDao().all().isEmpty()) db.entryDao().insertAll(seedEntries()) }; setContent { YanjianApp() } }
}

@Composable fun YanjianApp(vm: StudyViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.initialize(context) }
    val wordMode = vm.mode == StudyMode.WORD
    val bg by animateColorAsState(if (wordMode) Paper else Night, tween(420), label = "background")
    val fg by animateColorAsState(if (wordMode) Ink else Color(0xFFF1EDE4), tween(420), label = "foreground")
    val card by animateColorAsState(if (wordMode) PaperCard else NightCard, tween(420), label = "card")
    val muted by animateColorAsState(if (wordMode) Muted else NightMuted, tween(420), label = "muted")
    val accent by animateColorAsState(if (wordMode) Terracotta else Gold, tween(420), label = "accent")
    val line by animateColorAsState(if (wordMode) PaperLine else NightLine, tween(420), label = "line")
    BackHandler(enabled = vm.detailOpen || vm.settingsOpen) { if (vm.detailOpen) vm.closeDetail() else vm.toggleSettings() }
    MaterialTheme(colorScheme = if (wordMode) lightColorScheme(primary = Terracotta, background = Paper, surface = PaperCard) else darkColorScheme(primary = Gold, background = Night, surface = NightCard)) {
        Box(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, fg, muted, card, line, accent)
                AnimatedContent(targetState = if (vm.settingsOpen) Page.SETTINGS else vm.page, transitionSpec = { (fadeIn(tween(160)) + slideInHorizontally { it / 20 }) togetherWith (fadeOut(tween(100)) + slideOutHorizontally { -it / 20 }) }, label = "page") { target ->
                    when (target) {
                        Page.DISCOVER -> DiscoverPage(vm, fg, muted, card, line, accent, bg)
                        Page.SAVED -> SavedPage(vm, fg, muted, card, line, accent)
                        Page.REVIEW -> ReviewPage(vm, fg, muted, card, line, accent, bg)
                        Page.STATS -> StatsPage(vm, fg, muted, card, line, accent)
                        Page.SETTINGS -> SettingsPage(vm, fg, muted, card, line, accent)
                    }
                }
                if (!vm.settingsOpen) BottomBar(vm, fg, muted, line, accent)
            }
            if (vm.detailOpen) vm.detailEntry()?.let { DetailSheet(it, fg, muted, card, line, accent, { vm.closeDetail() }) }
            vm.toast?.let { message -> LaunchedEffect(message) { kotlinx.coroutines.delay(1700); vm.clearToast() }; Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp), shape = CircleShape, color = if (wordMode) Ink else PaperCard, shadowElevation = 8.dp) { Text(message, Modifier.padding(horizontal = 18.dp, vertical = 11.dp), color = if (wordMode) Paper else Ink, fontSize = 12.sp) } }
        }
    }
}

@Composable private fun TopBar(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(66.dp).padding(horizontal = 22.dp).border(width = 0.dp, color = Color.Transparent), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("言间", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, letterSpacing = 3.sp, color = fg)
        Row(Modifier.clip(CircleShape).background(if (vm.mode == StudyMode.WORD) Color(0xFFEAE6DD) else Color(0xFF28343F)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ModePill("词语", vm.mode == StudyMode.WORD, fg, muted, card) { if (vm.mode != StudyMode.WORD) vm.toggleMode() }
            ModePill("句子", vm.mode == StudyMode.SENTENCE, fg, muted, card) { if (vm.mode != StudyMode.SENTENCE) vm.toggleMode() }
        }
        IconButton(onClick = { vm.toggleSettings() }, modifier = Modifier.size(46.dp)) { Icon(if (vm.settingsOpen) Icons.Outlined.Close else Icons.Outlined.Settings, contentDescription = "偏好设置 / 返回", tint = fg) }
    }
    HorizontalDivider(color = line.copy(alpha = .7f))
}
@Composable private fun ModePill(label: String, selected: Boolean, fg: Color, muted: Color, card: Color, onClick: () -> Unit) { Box(Modifier.clip(CircleShape).background(if (selected) card else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 9.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 12.sp, color = if (selected) fg else muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) } }

@Composable private fun DiscoverPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color, bg: Color) {
    val entry = vm.current()
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(if (vm.mode == StudyMode.WORD) "WORDS, IN CONTEXT" else "LINES, IN CONTEXT", color = muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("每日发现", color = muted, fontSize = 11.sp) }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) { Text(if (vm.mode == StudyMode.WORD) "从一句台词，\n遇见一个好词。" else "有些话，\n值得慢慢说。", fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 34.sp, color = fg, modifier = Modifier.weight(1f)); Text("${(vm.currentIndex + 1).toString().padStart(2, '0')} / ${vm.discoverEntries().size.toString().padStart(2, '0')}", color = muted, fontSize = 12.sp) }
        Spacer(Modifier.height(18.dp))
        if (entry != null) {
            Surface(Modifier.fillMaxWidth().heightIn(min = 310.dp).clickable { vm.flip() }, shape = RoundedCornerShape(28.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line), shadowElevation = 3.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).background(accent, CircleShape)); Spacer(Modifier.width(8.dp)); Text(if (vm.mode == StudyMode.WORD) if (entry.text.length == 2) "双字表达" else "四字表达" else if (entry.genre == "practical") "日常实用" else "经典表达", color = muted, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text("言 · ${entry.category}", color = muted, fontSize = 10.sp) }
                    Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AnimatedContent(targetState = vm.flipped, label = "flipText") { flipped -> if (!flipped) Text(entry.text, fontFamily = FontFamily.Serif, fontSize = if (vm.mode == StudyMode.WORD) 38.sp else 26.sp, lineHeight = 39.sp, textAlign = TextAlign.Center, color = fg, fontWeight = FontWeight.Medium) else Text(entry.meaning, fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 32.sp, textAlign = TextAlign.Center, color = fg) }
                        Spacer(Modifier.height(16.dp)); Text(if (vm.flipped) "点击返回词语" else "点击翻面查看简释", color = muted, fontSize = 11.sp)
                    }
                    HorizontalDivider(color = line); Spacer(Modifier.height(13.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (entry.quote.isBlank()) "原创演示内容 · 非影视原句" else "影视原句 · 作品来源", color = muted, fontSize = 10.sp); Text(entry.workTitle, color = accent, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(15.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.KeyboardArrowUp, null, tint = muted, modifier = Modifier.size(16.dp)); Text("查看原句与详情", color = muted, fontSize = 11.sp, modifier = Modifier.clickable { vm.showDetail() }) }
            Spacer(Modifier.height(18.dp)); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.hide(entry) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, line), colors = ButtonDefaults.outlinedButtonColors(contentColor = fg)) { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("不再推荐", fontSize = 12.sp) }
                Button(onClick = { vm.toggleFavorite(entry) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = if (vm.isFavorite(entry.id)) accent else if (vm.mode == StudyMode.WORD) Color(0xFFEAE6DD) else Color(0xFF39434B), contentColor = if (vm.isFavorite(entry.id) && vm.mode == StudyMode.WORD) Color.White else fg)) { Icon(if (vm.isFavorite(entry.id)) Icons.Outlined.CheckCircle else Icons.Outlined.BookmarkBorder, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(if (vm.isFavorite(entry.id)) "已收藏" else "收藏", fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { vm.next(-1) }) { Icon(Icons.Outlined.ChevronLeft, null); Text("上一条", color = muted, fontSize = 11.sp) }; TextButton(onClick = { vm.next() }) { Text("下一条", color = muted, fontSize = 11.sp); Icon(Icons.Outlined.ChevronRight, null) } }
        }
    }
}

@Composable private fun SavedPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    val saved = vm.favorites()
    Column(Modifier.fillMaxSize().padding(horizontal = 23.dp, vertical = 23.dp)) {
        Eyebrow("YOUR COLLECTION", "收藏夹", muted)
        Row(Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) { Text("收好喜欢的表达", fontFamily = FontFamily.Serif, fontSize = 24.sp, color = fg, modifier = Modifier.weight(1f)); Text("${saved.size} 个", color = muted, fontSize = 11.sp) }
        Surface(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp), shape = RoundedCornerShape(18.dp), color = if (vm.mode == StudyMode.WORD) Color(0xFFE9E5DC) else Color(0xFF28343F)) { Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("今日复习", color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text("按记忆状态，重新遇见这些表达", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }; Button(onClick = { vm.navigate(Page.REVIEW) }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (vm.mode == StudyMode.WORD) Color.White else Night), shape = RoundedCornerShape(12.dp)) { Text("开始复习", fontSize = 11.sp) } } }
        if (saved.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("还没有收藏的表达。\n去发现页挑几个喜欢的吧。", color = muted, textAlign = TextAlign.Center, lineHeight = 26.sp) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(saved, key = { it.id }) { entry -> Surface(shape = RoundedCornerShape(17.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line), modifier = Modifier.fillMaxWidth().clickable { vm.showDetail(entry) }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(entry.text, fontFamily = FontFamily.Serif, fontSize = 17.sp, color = fg); Text(entry.meaning, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 6.dp)) }; Text(vm.allUserRecords().firstOrNull { it.entryId == entry.id }?.mastery?.let { runCatching { Mastery.valueOf(it).label }.getOrDefault("待学习") } ?: "待学习", color = accent, fontSize = 10.sp) } } } }
    }
}

@Composable private fun ReviewPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color, bg: Color) {
    val all = vm.reviewEntries(); val entry = all.firstOrNull(); val labels = listOf(Mastery.UNFAMILIAR, Mastery.FAMILIAR, Mastery.KNOWN)
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 23.dp, vertical = 23.dp)) {
        Eyebrow("SPACED RECALL", "翻卡复习", muted); Text("复习", fontFamily = FontFamily.Serif, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = fg, modifier = Modifier.padding(top = 16.dp, bottom = 21.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { labels.forEach { grade -> val selected = vm.filter == grade; Surface(Modifier.weight(1f).clickable { vm.updateFilter(grade) }, shape = RoundedCornerShape(13.dp), color = if (selected) (if (vm.mode == StudyMode.WORD) Color(0xFFEAE4D8) else Color(0xFF39434B)) else card, border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) accent else line)) { Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(grade.label, color = fg, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal); Text(when (grade) { Mastery.UNFAMILIAR -> "重点复习"; Mastery.FAMILIAR -> "间隔巩固"; else -> "长期抽查" }, color = muted, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp)) } } } }
        Spacer(Modifier.height(25.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(line)); Spacer(Modifier.width(10.dp)); Text("${if (entry == null) 0 else 1} / ${all.size}", color = muted, fontSize = 10.sp) }
        if (entry == null) Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.CheckCircle, null, tint = accent, modifier = Modifier.size(36.dp)); Spacer(Modifier.height(15.dp)); Text("本组暂无待复习内容", color = fg, fontSize = 14.sp); Text("先到发现页收藏一些表达", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) } }
        else {
            Surface(Modifier.fillMaxWidth().height(248.dp).padding(top = 18.dp).clickable { vm.flip() }, shape = RoundedCornerShape(23.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line), shadowElevation = 2.dp) { Box(Modifier.padding(28.dp), contentAlignment = Alignment.Center) { AnimatedContent(vm.flipped, label = "reviewFlip") { isBack -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(entry.text, fontFamily = FontFamily.Serif, fontSize = if (vm.mode == StudyMode.WORD) 31.sp else 21.sp, textAlign = TextAlign.Center, color = fg, lineHeight = 34.sp); if (isBack) { Spacer(Modifier.height(14.dp)); Text(entry.meaning, color = muted, fontSize = 13.sp, lineHeight = 22.sp, textAlign = TextAlign.Center) } } } } }
            if (vm.flipped) { TextButton(onClick = { vm.showDetail(entry) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.KeyboardArrowUp, null); Text("影视原句与详情", fontSize = 11.sp, color = muted) }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { labels.forEach { grade -> OutlinedButton(onClick = { vm.rate(entry, grade) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Text(grade.label, color = if (grade == Mastery.KNOWN) accent else fg, fontSize = 11.sp) } } } } else Text("点击卡片 · 查看释义", color = muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
        }
    }
}

@Composable private fun StatsPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    val idsInMode = vm.entries.filter { it.kind == (if (vm.mode == StudyMode.WORD) "word" else "sentence") }.map { it.id }.toSet()
    val records = vm.allUserRecords().filter { it.entryId in idsInMode }; val favorites = vm.favorites().size; val reviewed = records.sumOf { it.reviewCount }; val counts = Mastery.entries.associateWith { grade -> records.count { it.mastery == grade.name } }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 23.dp, vertical = 23.dp)) {
        Eyebrow("LEARNING INSIGHTS", "学习数据", muted); Text("一点一点，变成自己的话。", fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 34.sp, color = fg, modifier = Modifier.padding(top = 15.dp))
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("累计收藏", favorites.toString().padStart(2, '0'), if (vm.mode == StudyMode.WORD) "个词语" else "个句子", Modifier.weight(1f), card, line, fg, muted); StatCard("累计复习", reviewed.toString().padStart(2, '0'), "次记忆评分", Modifier.weight(1f), card, line, fg, muted) }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), shape = RoundedCornerShape(18.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Column(Modifier.padding(18.dp)) { Text("当前掌握程度", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(16.dp)); Mastery.entries.filter { it != Mastery.NEW }.forEach { grade -> val n = counts[grade] ?: 0; Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(grade.label, color = muted, fontSize = 11.sp, modifier = Modifier.width(64.dp)); Box(Modifier.weight(1f).height(7.dp).clip(CircleShape).background(line)) { Box(Modifier.fillMaxHeight().fillMaxWidth(if (records.isEmpty()) 0f else n.toFloat() / records.size).background(accent, CircleShape)) }; Text(n.toString(), color = fg, fontSize = 11.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End) } } } }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), shape = RoundedCornerShape(18.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Column(Modifier.padding(18.dp)) { Text("记忆曲线", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("积累真实复习记录后展示。当前不生成预测数据。", color = muted, fontSize = 11.sp, lineHeight = 19.sp) } }
    }
}
@Composable private fun StatCard(title: String, value: String, foot: String, modifier: Modifier, card: Color, line: Color, fg: Color, muted: Color) { Surface(modifier, shape = RoundedCornerShape(16.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Column(Modifier.padding(17.dp)) { Text(title, color = muted, fontSize = 11.sp); Text(value, color = fg, fontFamily = FontFamily.Serif, fontSize = 30.sp, modifier = Modifier.padding(vertical = 7.dp)); Text(foot, color = muted, fontSize = 10.sp) } } }

@Composable private fun SettingsPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    val cats = listOf("日常实用", "经典表达", "情感表达", "人生感悟", "安慰", "拒绝", "告别", "思念", "鼓励", "克制")
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 23.dp, vertical = 23.dp)) {
        Eyebrow("PERSONALIZE", "偏好设置", muted); Text("让推荐更像你。", fontFamily = FontFamily.Serif, fontSize = 26.sp, color = fg, modifier = Modifier.padding(top = 17.dp, bottom = 28.dp))
        Text("句子内容比例", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("默认 60% 日常实用，40% 经典表达。", color = muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 9.dp)); Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("日常实用 ${vm.sentenceRatio}%", color = fg, fontSize = 12.sp); Text("经典表达 ${100 - vm.sentenceRatio}%", color = fg, fontSize = 12.sp) }
        Slider(value = vm.sentenceRatio.toFloat(), onValueChange = { vm.setRatio(it.toInt() / 10 * 10) }, valueRange = 0f..100f, steps = 9, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
        HorizontalDivider(Modifier.padding(vertical = 20.dp), color = line); Text("情感表达偏好", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("选择希望看到的类型板块。", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cats.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { cat -> val selected = cat in vm.selectedCategories; Surface(Modifier.weight(1f).clickable { vm.toggleCategory(cat) }, shape = CircleShape, color = if (selected) (if (vm.mode == StudyMode.WORD) Color(0xFFEAE6DD) else Color(0xFF39434B)) else card, border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) accent else line)) { Text(cat, Modifier.padding(horizontal = 9.dp, vertical = 12.dp), textAlign = TextAlign.Center, color = if (selected) fg else muted, fontSize = 11.sp) } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } } }
        HorizontalDivider(Modifier.padding(vertical = 22.dp), color = line); Text("数据保存在本机", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("首版无需账号或数据库服务器。用户导入与备份将在后续版本接入。", color = muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 26.dp))
    }
}

@Composable private fun BottomBar(vm: StudyViewModel, fg: Color, muted: Color, line: Color, accent: Color) {
    HorizontalDivider(color = line)
    Row(Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        NavItem("发现", Icons.Outlined.Explore, vm.page == Page.DISCOVER, muted, accent, Modifier.weight(1f)) { vm.navigate(Page.DISCOVER) }
        NavItem("收藏", Icons.Outlined.BookmarkBorder, vm.page == Page.SAVED || vm.page == Page.REVIEW, muted, accent, Modifier.weight(1f)) { vm.navigate(Page.SAVED) }
        NavItem("数据", Icons.Outlined.Insights, vm.page == Page.STATS, muted, accent, Modifier.weight(1f)) { vm.navigate(Page.STATS) }
    }
}
@Composable private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, muted: Color, accent: Color, modifier: Modifier, onClick: () -> Unit) { Column(modifier.clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = if (selected) accent else muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.height(4.dp)); Text(label, color = if (selected) accent else muted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) } }
@Composable private fun Eyebrow(left: String, right: String, muted: Color) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(left, color = muted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(right, color = muted, fontSize = 11.sp) } }

@Composable private fun DetailSheet(entry: EntryEntity, fg: Color, muted: Color, card: Color, line: Color, accent: Color, close: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)).clickable(onClick = close), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.76f).clickable(enabled = false) {}, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), color = card) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 25.dp, vertical = 13.dp)) {
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(line).align(Alignment.CenterHorizontally)); Spacer(Modifier.height(20.dp)); Text("${entry.category} · ${if (entry.kind == "word") "词语释义" else "句子语境"}", color = accent, fontSize = 10.sp, letterSpacing = 1.2.sp); Spacer(Modifier.height(12.dp)); Text(entry.text, fontFamily = FontFamily.Serif, fontSize = 27.sp, color = fg, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(10.dp)); Text(entry.meaning, color = muted, fontSize = 13.sp, lineHeight = 22.sp); Spacer(Modifier.height(23.dp)); Text("影视原句", color = muted, fontSize = 11.sp); Spacer(Modifier.height(8.dp)); Text(if (entry.quote.isBlank()) "该条是原创演示内容，尚未关联可核验影视原句。" else "“${entry.quote}”", fontFamily = FontFamily.Serif, color = fg, fontSize = 17.sp, lineHeight = 29.sp); Spacer(Modifier.height(9.dp)); Text(entry.workTitle, color = accent, fontSize = 11.sp); HorizontalDivider(Modifier.padding(vertical = 20.dp), color = line); Text("语境与使用", color = muted, fontSize = 11.sp); Spacer(Modifier.height(8.dp)); Text(entry.context, color = fg, fontSize = 13.sp, lineHeight = 23.sp); Spacer(Modifier.height(25.dp)); OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("收起详情", color = fg) }; Spacer(Modifier.height(20.dp))
            }
        }
    }
}
