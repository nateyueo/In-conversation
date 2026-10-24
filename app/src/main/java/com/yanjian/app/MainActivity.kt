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
import androidx.lifecycle.viewmodel.compose.viewModel
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { YanjianApp() } }
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
        Row(verticalAlignment = Alignment.Bottom) { Text(if (vm.mode == StudyMode.WORD) "在日常与文字中，\n遇见一个好词。" else "有些话，\n值得慢慢说。", fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 34.sp, color = fg, modifier = Modifier.weight(1f)); Text("${if (entry == null) 0 else vm.currentIndex + 1} / ${vm.discoverEntries().size}", color = muted, fontSize = 12.sp) }
        Spacer(Modifier.height(18.dp))
        if (entry != null) {
            vm.contentMessage?.let { Text(it, color = muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp)) }
            Surface(Modifier.fillMaxWidth().heightIn(min = 310.dp).clickable { vm.flip() }, shape = RoundedCornerShape(28.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line), shadowElevation = 3.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).background(accent, CircleShape)); Spacer(Modifier.width(8.dp)); Text(if (vm.mode == StudyMode.WORD) if (entry.text.length == 2) "双字表达" else "四字表达" else if (entry.genre == "practical") "日常实用" else "经典表达", color = muted, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text("言 · ${entry.category}", color = muted, fontSize = 10.sp) }
                    Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AnimatedContent(targetState = vm.flipped, label = "flipText") { flipped -> Text(if (flipped) entry.backText() else entry.text, fontFamily = FontFamily.Serif, fontSize = if (flipped) 20.sp else if (vm.mode == StudyMode.WORD) 38.sp else 26.sp, lineHeight = 39.sp, textAlign = TextAlign.Center, color = fg, fontWeight = FontWeight.Medium) }
                        Spacer(Modifier.height(16.dp)); Text(if (vm.flipped) "点击返回正文" else if (entry.meaning.isBlank()) "点击翻面查看用法或来源" else "点击翻面查看简释", color = muted, fontSize = 11.sp)
                    }
                    HorizontalDivider(color = line); Spacer(Modifier.height(13.dp))
                    Column(Modifier.fillMaxWidth()) { Text(entry.sourceLabel(), color = muted, fontSize = 10.sp); if (entry.workTitle.isNotBlank()) Text(entry.workTitle, color = accent, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(15.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.KeyboardArrowUp, null, tint = muted, modifier = Modifier.size(16.dp)); Text("查看原句与详情", color = muted, fontSize = 11.sp, modifier = Modifier.clickable { vm.showDetail() }) }
            Spacer(Modifier.height(18.dp)); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.hide(entry) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, line), colors = ButtonDefaults.outlinedButtonColors(contentColor = fg)) { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("不再推荐", fontSize = 12.sp) }
                Button(onClick = { vm.toggleFavorite(entry) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = if (vm.isFavorite(entry.id)) accent else if (vm.mode == StudyMode.WORD) Color(0xFFEAE6DD) else Color(0xFF39434B), contentColor = if (vm.isFavorite(entry.id) && vm.mode == StudyMode.WORD) Color.White else fg)) { Icon(if (vm.isFavorite(entry.id)) Icons.Outlined.CheckCircle else Icons.Outlined.BookmarkBorder, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(if (vm.isFavorite(entry.id)) "已收藏" else "收藏", fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { vm.next(-1) }) { Icon(Icons.Outlined.ChevronLeft, null); Text("上一条", color = muted, fontSize = 11.sp) }; TextButton(onClick = { vm.next() }) { Text("下一条", color = muted, fontSize = 11.sp); Icon(Icons.Outlined.ChevronRight, null) } }
        }
        else Text(vm.contentMessage ?: "当前偏好暂无内容，请调整领域、用途或比例。", color = muted, fontSize = 14.sp, lineHeight = 24.sp, modifier = Modifier.padding(vertical = 35.dp))
    }
}

@Composable private fun SavedPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    val saved = vm.favorites()
    Column(Modifier.fillMaxSize().padding(horizontal = 23.dp, vertical = 23.dp)) {
        Eyebrow("YOUR COLLECTION", "收藏夹", muted)
        Row(Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) { Text("收好喜欢的表达", fontFamily = FontFamily.Serif, fontSize = 24.sp, color = fg, modifier = Modifier.weight(1f)); Text("${vm.allUserRecords().count { it.isFavorite }} 个", color = muted, fontSize = 11.sp) }
        Surface(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp), shape = RoundedCornerShape(18.dp), color = if (vm.mode == StudyMode.WORD) Color(0xFFE9E5DC) else Color(0xFF28343F)) { Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("今日复习", color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text("按记忆状态，重新遇见这些表达", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }; Button(onClick = { vm.navigate(Page.REVIEW) }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (vm.mode == StudyMode.WORD) Color.White else Night), shape = RoundedCornerShape(12.dp)) { Text("开始复习", fontSize = 11.sp) } } }
        if (saved.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("还没有收藏的表达。\n去发现页挑几个喜欢的吧。", color = muted, textAlign = TextAlign.Center, lineHeight = 26.sp) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(saved, key = { it.id }) { entry -> Surface(shape = RoundedCornerShape(17.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line), modifier = Modifier.fillMaxWidth().clickable { vm.showDetail(entry) }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(entry.text, fontFamily = FontFamily.Serif, fontSize = 17.sp, color = fg); Text(entry.backText(), fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 6.dp)) }; Text(vm.users[entry.id]?.mastery?.let { runCatching { Mastery.valueOf(it).label }.getOrDefault("待学习") } ?: "待学习", color = accent, fontSize = 10.sp) } } }
            if (vm.hasMoreSaved) item { TextButton(onClick = { vm.loadMoreSaved() }, enabled = !vm.busy) { Text("加载更多收藏") } }
        }
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
            Surface(Modifier.fillMaxWidth().heightIn(min = 248.dp).padding(top = 18.dp).clickable { vm.flip() }, shape = RoundedCornerShape(23.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line), shadowElevation = 2.dp) { Box(Modifier.padding(28.dp), contentAlignment = Alignment.Center) { AnimatedContent(vm.flipped, label = "reviewFlip") { isBack -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(entry.text, fontFamily = FontFamily.Serif, fontSize = if (vm.mode == StudyMode.WORD) 31.sp else 21.sp, textAlign = TextAlign.Center, color = fg, lineHeight = 34.sp); if (isBack) { Spacer(Modifier.height(14.dp)); Text(entry.backText(), color = muted, fontSize = 13.sp, lineHeight = 22.sp, textAlign = TextAlign.Center) } } } } }
            if (vm.flipped) { TextButton(onClick = { vm.showDetail(entry) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.KeyboardArrowUp, null); Text("来源与详情", fontSize = 11.sp, color = muted) }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { labels.forEach { grade -> OutlinedButton(onClick = { vm.rate(entry, grade) }, enabled = !vm.busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Text(grade.label, color = if (grade == Mastery.KNOWN) accent else fg, fontSize = 11.sp) } } } } else Text("点击卡片 · 查看简释或来源", color = muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
        }
        if (vm.hasMoreSaved) TextButton(onClick = { vm.loadMoreSaved() }, enabled = !vm.busy) { Text("加载更多收藏供复习") }
    }
}

@Composable private fun StatsPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    val records = vm.allUserRecords(); val favorites = records.count { it.isFavorite }; val reviewed = records.sumOf { it.reviewCount }; val counts = Mastery.entries.associateWith { grade -> records.count { it.mastery == grade.name } }
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 23.dp, vertical = 23.dp)) {
        Eyebrow("LEARNING INSIGHTS", "学习数据", muted); Text("一点一点，变成自己的话。", fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 34.sp, color = fg, modifier = Modifier.padding(top = 15.dp))
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("累计收藏", favorites.toString().padStart(2, '0'), if (vm.mode == StudyMode.WORD) "个词语" else "个句子", Modifier.weight(1f), card, line, fg, muted); StatCard("累计复习", reviewed.toString().padStart(2, '0'), "次记忆评分", Modifier.weight(1f), card, line, fg, muted) }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), shape = RoundedCornerShape(18.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Column(Modifier.padding(18.dp)) { Text("当前掌握程度", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(16.dp)); Mastery.entries.filter { it != Mastery.NEW }.forEach { grade -> val n = counts[grade] ?: 0; Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(grade.label, color = muted, fontSize = 11.sp, modifier = Modifier.width(64.dp)); Box(Modifier.weight(1f).height(7.dp).clip(CircleShape).background(line)) { Box(Modifier.fillMaxHeight().fillMaxWidth(if (records.isEmpty()) 0f else n.toFloat() / records.size).background(accent, CircleShape)) }; Text(n.toString(), color = fg, fontSize = 11.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End) } } } }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), shape = RoundedCornerShape(18.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Column(Modifier.padding(18.dp)) { Text("记忆曲线", color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("积累真实复习记录后展示。当前不生成预测数据。", color = muted, fontSize = 11.sp, lineHeight = 19.sp) } }
    }
}
@Composable private fun StatCard(title: String, value: String, foot: String, modifier: Modifier, card: Color, line: Color, fg: Color, muted: Color) { Surface(modifier, shape = RoundedCornerShape(16.dp), color = card, border = androidx.compose.foundation.BorderStroke(1.dp, line)) { Column(Modifier.padding(17.dp)) { Text(title, color = muted, fontSize = 11.sp); Text(value, color = fg, fontFamily = FontFamily.Serif, fontSize = 30.sp, modifier = Modifier.padding(vertical = 7.dp)); Text(foot, color = muted, fontSize = 10.sp) } } }

@Composable private fun SettingsPage(vm: StudyViewModel, fg: Color, muted: Color, card: Color, line: Color, accent: Color) {
    val cats = CONTENT_CATEGORIES.toList()
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 23.dp, vertical = 23.dp)) {
        Eyebrow("PERSONALIZE", "偏好设置", muted); Text("让推荐更像你。", fontFamily = FontFamily.Serif, fontSize = 26.sp, color = fg, modifier = Modifier.padding(top = 17.dp, bottom = 28.dp))
        Text("句子内容比例", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("默认 60% 日常实用，40% 经典表达。", color = muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 9.dp)); Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("日常实用 ${vm.sentenceRatio}%", color = fg, fontSize = 12.sp); Text("经典表达 ${100 - vm.sentenceRatio}%", color = fg, fontSize = 12.sp) }
        Slider(value = vm.sentenceRatio.toFloat(), onValueChange = { vm.setRatio(it.toInt() / 10 * 10) }, valueRange = 0f..100f, steps = 9, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
        HorizontalDivider(Modifier.padding(vertical = 20.dp), color = line); Text("内容领域", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("多选领域取并集；全不选时不推荐。所需类型为空时请调整偏好。", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cats.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { cat -> val selected = cat in vm.selectedCategories; Surface(Modifier.weight(1f).clickable { vm.toggleCategory(cat) }, shape = CircleShape, color = if (selected) (if (vm.mode == StudyMode.WORD) Color(0xFFEAE6DD) else Color(0xFF39434B)) else card, border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) accent else line)) { Text(cat, Modifier.padding(horizontal = 9.dp, vertical = 12.dp), textAlign = TextAlign.Center, color = if (selected) fg else muted, fontSize = 11.sp) } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } } }
        HorizontalDivider(Modifier.padding(vertical = 20.dp), color = line)
        Text("内容用途", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf("daily" to "日常", "writing" to "写作").forEach { (tag, label) -> FilterChip(selected = tag in vm.selectedPurposes, onClick = { vm.togglePurpose(tag) }, label = { Text(label) }) } }
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
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(line).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(20.dp)); Text(entry.category, color = accent, fontSize = 10.sp)
                Spacer(Modifier.height(12.dp)); Text(entry.text, fontFamily = FontFamily.Serif, fontSize = 27.sp, color = fg, fontWeight = FontWeight.SemiBold)
                if (entry.meaning.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(entry.meaning, color = muted, fontSize = 13.sp, lineHeight = 22.sp) }
                Spacer(Modifier.height(23.dp)); Text(entry.sourceLabel(), color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp)); Text(entry.sourceDetails(), color = fg, fontSize = 14.sp, lineHeight = 25.sp)
                if (entry.context.isNotBlank()) { HorizontalDivider(Modifier.padding(vertical = 20.dp), color = line); Text("语境与使用", color = muted, fontSize = 11.sp); Spacer(Modifier.height(8.dp)); Text(entry.context, color = fg, fontSize = 13.sp, lineHeight = 23.sp) }
                Spacer(Modifier.height(25.dp)); OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("收起详情", color = fg) }; Spacer(Modifier.height(20.dp))
            }
        }
    }
}
