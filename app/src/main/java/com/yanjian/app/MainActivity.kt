package com.yanjian.app

import android.os.Build
import android.view.Window
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
internal val Paper = Color(0xFFF4EDDF)
internal val PaperCard = Color(0xFFFFFAEF)
internal val Ink = Color(0xFF363C30)
internal val Muted = Color(0xFF898675)
internal val Terracotta = Color(0xFFAA8453)
internal val PaperLine = Color(0xFFE7DDC9)

enum class StudyMode { WORD, SENTENCE }
enum class Page { DISCOVER, SAVED, REVIEW, STATS, SETTINGS }
enum class Mastery(val label: String) { NEW("待学习"), UNFAMILIAR("不熟悉"), FAMILIAR("熟悉"), KNOWN("已会") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { YanjianApp(window) } }
}

@Composable fun YanjianApp(window: Window, vm: StudyViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.initialize(context) }
    val wordMode = vm.mode == StudyMode.WORD
    val bg = Paper
    val fg = Ink
    val card = PaperCard
    val muted = Muted
    val accent = Terracotta
    val line = PaperLine
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // Older Android versions draw bar colors; edge-to-edge uses the root background.
        window.statusBarColor = bg.toArgb()
        window.navigationBarColor = bg.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    }
    BackHandler(enabled = vm.detailOpen || vm.settingsOpen || vm.managerOpen || vm.editing != null || vm.importFile != null || vm.importResult != null || vm.restorePreview != null || vm.page == Page.REVIEW) { vm.back() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Terracotta, onPrimary = Color.White, primaryContainer = Color(0xFFEDE3CF), onPrimaryContainer = Ink, background = Paper, surface = PaperCard, onSurface = Ink, onBackground = Ink, secondary = Color(0xFF7E896B), secondaryContainer = Color(0xFFE5D8BC), onSecondaryContainer = Ink, surfaceVariant = Color(0xFFEDE3CF), onSurfaceVariant = Muted, outline = PaperLine)) {
        CompositionLocalProvider(LocalContentColor provides fg) {
        Box(Modifier.fillMaxSize().background(bg).navigationBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, fg, muted, card, line, accent)
                AnimatedContent(modifier = Modifier.weight(1f), targetState = if (vm.settingsOpen) Page.SETTINGS else vm.page, transitionSpec = { (fadeIn(tween(160)) + slideInHorizontally { it / 20 }) togetherWith (fadeOut(tween(100)) + slideOutHorizontally { -it / 20 }) }, label = "page") { target ->
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
            if (vm.detailOpen) vm.detailEntry()?.let { DetailSheet(it, fg, muted, card, line, accent, { vm.closeDetail() }, vm) }
            UserPanels(vm)
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            vm.toast?.let { message -> LaunchedEffect(message) { kotlinx.coroutines.delay(1700); vm.clearToast() }; Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp), shape = CircleShape, color = if (wordMode) Ink else PaperCard, shadowElevation = 8.dp) { Text(message, Modifier.padding(horizontal = 18.dp, vertical = 11.dp), color = if (wordMode) Paper else Ink, fontSize = 12.sp) } }
        }
        }
    }
}

@Composable private fun TopBar(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    Box(Modifier.fillMaxWidth().statusBarsPadding()) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.flower_branch), null, Modifier.size(105.dp).align(Alignment.TopStart), alpha = .65f)
        Column(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("言间", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 29.sp, letterSpacing = 6.sp, color = fg)
            Text("在文字里，遇见更好的自己", color = muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
            Row(Modifier.clip(CircleShape).background(Color(0xFFEDE3CF)), verticalAlignment = Alignment.CenterVertically) {
                ModePill("词语", vm.mode == StudyMode.WORD, fg, muted, accent) { if (vm.mode != StudyMode.WORD) vm.toggleMode() }
                ModePill("句子", vm.mode == StudyMode.SENTENCE, fg, muted, accent) { if (vm.mode != StudyMode.SENTENCE) vm.toggleMode() }
            }
        }
        IconButton(onClick = { vm.toggleSettings() }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp).size(48.dp)) { Icon(if (vm.settingsOpen) Icons.Outlined.Close else Icons.Outlined.Settings, contentDescription = "偏好设置 / 返回", tint = fg, modifier = Modifier.size(21.dp)) }
    }
}
@Composable private fun ModePill(label: String, selected: Boolean, fg: Color, muted: Color, card: Color, onClick: () -> Unit) { Box(Modifier.width(108.dp).heightIn(min = 48.dp).clip(CircleShape).background(if (selected) card else Color.Transparent).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp, color = if (selected) Color.White else muted, letterSpacing = 2.sp) } }

@Composable private fun DiscoverPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color, bg: Color) {
    PaperDiscovery(vm)
}

@Composable private fun SavedPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) = CollectionPage(vm)
@Composable private fun ReviewPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color, bg: Color) = LearningPage(vm)
@Composable private fun StatsPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) = InsightsPage(vm)
@Composable private fun StatCard(title: String, value: String, foot: String, modifier: Modifier, card: Color, line: Color, fg: Color, muted: Color) { Surface(modifier, shape = RoundedCornerShape(16.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Column(Modifier.padding(17.dp)) { Text(title, color = muted, fontSize = 11.sp); Text(value, color = fg, fontFamily = FontFamily.Serif, fontSize = 30.sp, modifier = Modifier.padding(vertical = 7.dp)); Text(foot, color = muted, fontSize = 10.sp) } } }

@Composable private fun SettingsPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    var categoriesOpen by remember(vm.mode) { mutableStateOf(false) }
    val selectedCategoryCount = CONTENT_CATEGORIES.count { it in vm.selectedCategories }
    val categorySummary = when (selectedCategoryCount) {
        CONTENT_CATEGORIES.size -> "全部领域"
        0 -> "未选择"
        else -> "已选 $selectedCategoryCount 项"
    }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = card) {
            Column(Modifier.padding(22.dp)) {
                Text("让推荐更像你。", fontFamily = FontFamily.Serif, fontSize = 25.sp, color = fg)
                Text("选择你喜欢的内容倾向，\n留一些文字，陪伴日常。", color = muted, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                Landscape(Modifier.fillMaxWidth().height(75.dp))
                Text("句子内容比例", color = fg, fontSize = 15.sp)
                Slider(value = vm.sentenceRatio.toFloat(), onValueChange = { vm.setRatio(it.toInt() / 10 * 10) }, valueRange = 0f..100f, steps = 9)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("${vm.sentenceRatio}%", fontFamily = FontFamily.Serif, fontSize = 24.sp); Text("日常实用", fontSize = 13.sp, color = muted) }
                    Column(horizontalAlignment = Alignment.End) { Text("${100 - vm.sentenceRatio}%", fontFamily = FontFamily.Serif, fontSize = 24.sp); Text("经典表达", fontSize = 13.sp, color = muted) }
                }
                HorizontalDivider(Modifier.padding(vertical = 22.dp), color = line)
                Text("内容用途", color = fg, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf("daily" to "日常交流", "writing" to "写作表达").forEach { (tag, label) -> FilterChip(selected = tag in vm.selectedPurposes, onClick = { vm.togglePurpose(tag) }, label = { Text(label) }) } }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
            Box {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(role = Role.Button) { categoriesOpen = !categoriesOpen }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("内容领域", color = fg, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text(categorySummary, color = muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(if (categoriesOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = muted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = categoriesOpen,
                    onDismissRequest = { categoriesOpen = false },
                    modifier = Modifier.width(260.dp).heightIn(max = 320.dp),
                    shape = RoundedCornerShape(18.dp),
                    containerColor = card,
                    tonalElevation = 0.dp
                ) {
                    CONTENT_CATEGORIES.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category, color = fg, fontSize = 14.sp) },
                            onClick = { vm.toggleCategory(category) },
                            trailingIcon = { Checkbox(checked = category in vm.selectedCategories, onCheckedChange = null) }
                        )
                    }
                }
            }
        }
        DataTools(vm)
        Text("内容与学习记录保存在本机", color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable private fun BottomBar(vm: StudyViewModel, fg: Color, muted: Color, line: Color, accent: Color) {
    HorizontalDivider(color = line)
    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        NavItem("发现", Icons.Outlined.Explore, vm.page == Page.DISCOVER, muted, accent, Modifier.weight(1f)) { vm.navigate(Page.DISCOVER) }
        NavItem("收藏", Icons.Outlined.BookmarkBorder, vm.page == Page.SAVED || vm.page == Page.REVIEW, muted, accent, Modifier.weight(1f)) { vm.navigate(Page.SAVED) }
        NavItem("数据", Icons.Outlined.Insights, vm.page == Page.STATS, muted, accent, Modifier.weight(1f)) { vm.navigate(Page.STATS) }
    }
}
@Composable private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, muted: Color, accent: Color, modifier: Modifier, onClick: () -> Unit) { Column(modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = if (selected) accent else muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.height(4.dp)); Text(if (selected) "· $label ·" else label, color = if (selected) accent else muted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) } }
@Composable private fun Eyebrow(left: String, right: String, muted: Color) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(left, color = muted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(right, color = muted, fontSize = 11.sp) } }

@Composable private fun DetailSheet(entry: EntryEntity, fg: Color, muted: Color, card: Color, line: Color, accent: Color, close: () -> Unit, vm: StudyViewModel) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)).clickable(onClick = close), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.76f).clickable(enabled = false) {}, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), color = card) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 25.dp, vertical = 13.dp)) {
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(line).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp)); Text(entry.text, fontFamily = FontFamily.Serif, fontSize = if (entry.kind == "sentence") 22.sp else 27.sp, lineHeight = if (entry.kind == "sentence") 33.sp else 38.sp, color = fg, fontWeight = FontWeight.SemiBold)
                if (entry.meaning.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(entry.meaning, color = muted, fontSize = 13.sp, lineHeight = 22.sp) }
                if (entry.readingCredit().isNotBlank()) Text(entry.readingCredit(), color = accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                if (entry.meaning.isBlank()) Text("释义待补充，可先收藏阅读", color = muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                if (entry.context.isNotBlank() && !entry.id.startsWith("personal:") && !entry.context.contains("http")) { HorizontalDivider(Modifier.padding(vertical = 20.dp), color = line); Text("语境与使用", color = muted, fontSize = 11.sp); Spacer(Modifier.height(8.dp)); Text(entry.context, color = fg, fontSize = 13.sp, lineHeight = 23.sp) }
                var sourceOpen by remember(entry.id) { mutableStateOf(false) }
                TextButton(onClick = { sourceOpen = !sourceOpen }) { Text(if (sourceOpen) "收起出处与授权" else "出处与授权 ›", color = muted, fontSize = 12.sp) }
                if (sourceOpen) Text(entry.sourceDetails(), color = muted, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 20.dp))
                Button(onClick = { vm.toggleFavorite(entry) }, modifier = Modifier.fillMaxWidth()) { Text(if (vm.isFavorite(entry.id)) "取消收藏" else "收藏") }
                if (entry.id.startsWith("personal:")) TextButton(onClick = { vm.edit(entry) }) { Text("编辑个人内容") }
                if (vm.users[entry.id]?.isHidden == true) TextButton(onClick = { vm.restoreHidden(entry) }) { Text("恢复推荐") }
                if (vm.relationUndo != null) TextButton(onClick = { vm.undoRelation() }) { Text("撤销上次收藏 / 屏蔽") }
                Spacer(Modifier.height(25.dp)); OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("收起详情", color = fg) }; Spacer(Modifier.height(20.dp))
            }
        }
    }
}
