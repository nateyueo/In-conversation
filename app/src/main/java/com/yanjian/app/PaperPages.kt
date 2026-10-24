package com.yanjian.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable internal fun Landscape(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ink_landscape), null, modifier, contentScale = ContentScale.Fit, alpha = .72f)
}

@Composable internal fun PaperDiscovery(vm: StudyViewModel) {
    val entry = vm.current()
    LaunchedEffect(entry?.id, vm.ready) { if (entry != null && vm.ready) vm.visible(entry) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardHeight = (maxHeight - 154.dp).coerceIn(320.dp, 560.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (entry != null) {
                Surface(Modifier.fillMaxWidth().height(cardHeight).cardGestures({ vm.next() }, { vm.next(-1) }), shape = RoundedCornerShape(24.dp), color = PaperCard, shadowElevation = 2.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("✦  ${if (entry.genre == "practical") "日常实用" else "经典表达"}", color = Terracotta, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(entry.category, color = Muted, fontSize = 11.sp)
                        }
                        key(entry.id, vm.flipped) {
                            val scroll = rememberScrollState()
                            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 25.dp)) {
                                Column(Modifier.fillMaxSize().verticalScroll(scroll).clickable { vm.flip() }.padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(if (vm.flipped) entry.backText() else entry.text, fontFamily = FontFamily.Serif,
                                        fontSize = if (vm.flipped) 22.sp else if (entry.kind == "word") 38.sp else 26.sp,
                                        lineHeight = if (entry.kind == "word" && !vm.flipped) 54.sp else 40.sp,
                                        textAlign = if (entry.text.length > 70) TextAlign.Start else TextAlign.Center, color = Ink)
                                    if (vm.flipped && entry.meaning.isBlank()) Text("可先收藏阅读", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
                                }
                            }
                            Text(if (scroll.maxValue > 0) "上下滑动读全文 · 点击${if (vm.flipped) "返回正文" else "查看释义"}" else if (vm.flipped) "点击返回正文" else "点击查看释义", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 5.dp))
                        }
                        Box(Modifier.fillMaxWidth().height(90.dp)) {
                            Landscape(Modifier.fillMaxSize())
                            if (entry.readingCredit().isNotBlank()) Text(entry.readingCredit(), color = Ink, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).background(PaperCard.copy(alpha = .8f)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                        HorizontalDivider(color = PaperLine, modifier = Modifier.padding(horizontal = 20.dp))
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { vm.hide(entry) }) { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("不再推荐", fontSize = 12.sp) }
                            TextButton(onClick = { vm.toggleFavorite(entry) }) { Icon(if (vm.isFavorite(entry.id)) Icons.Outlined.CheckCircle else Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (vm.isFavorite(entry.id)) "已收藏" else "收藏", fontSize = 12.sp) }
                            TextButton(onClick = { vm.showDetail() }) { Text("详情 ›", fontSize = 12.sp) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { vm.next(-1) }) { Icon(Icons.Outlined.ChevronLeft, null); Text("上一条", fontSize = 12.sp) }
                    Text("${vm.currentIndex + 1} / ${vm.discoverEntries().size}", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { vm.next() }) { Text("下一条", fontSize = 12.sp); Icon(Icons.Outlined.ChevronRight, null) }
                }
                Text("好词好句，慢慢积累。", color = Muted, fontFamily = FontFamily.Serif, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Landscape(Modifier.fillMaxWidth().height(170.dp))
                Text(vm.contentMessage ?: "当前偏好暂无内容", textAlign = TextAlign.Center, color = Muted, modifier = Modifier.padding(24.dp))
                if (vm.ready) Button(onClick = { if (vm.roundComplete) vm.nextRound() else vm.toggleSettings() }) { Text(if (vm.roundComplete) "开始下一轮" else "调整偏好") }
            }
            if (vm.relationUndo != null) TextButton(onClick = { vm.undoRelation() }) { Text("撤销上次操作") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable internal fun PaperInsights(vm: StudyViewModel) {
    val events = vm.filteredEvents()
    val records = vm.allUserRecords().filter { it.isFavorite }
    val reviews = events.filter { it.type == "review" }
    val metrics = listOf("浏览内容" to events.filter { it.type == "exposure" }.map { it.entryId }.distinct().size,
        "复习次数" to reviews.size, "复习内容" to reviews.map { it.entryId }.distinct().size,
        "新增收藏" to events.filter { it.type == "favorite" }.map { it.entryId }.distinct().size)
    val colors = listOf(Color(0xFFDDD0AE), Color(0xFFCBA46E), Color(0xFFA9AF80), Color(0xFF71846A))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("学习统计", fontSize = 23.sp, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
            Text(if (vm.mode == StudyMode.WORD) "词语积累" else "句子积累", color = Muted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color(0xFFEDE3CF)), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(1 to "今日", 7 to "近7日", 30 to "近30日", 0 to "全部").forEach { (days, label) ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape).background(if (vm.draft.period == days) Terracotta else Color.Transparent).clickable { vm.setDraft(vm.draft.copy(period = days)) }, contentAlignment = Alignment.Center) {
                    Text(label, color = if (vm.draft.period == days) Color.White else Muted, fontSize = 13.sp)
                }
            }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = PaperCard) {
            Column(Modifier.padding(20.dp)) {
                metrics.chunked(2).forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp), color = PaperLine)
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (label, value) -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value.toString(), fontFamily = FontFamily.Serif, fontSize = 38.sp, color = Ink)
                            Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                        } }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = PaperCard) {
            Column(Modifier.padding(20.dp)) {
                Text("掌握程度", fontSize = 18.sp, fontFamily = FontFamily.Serif)
                Text("当前收藏 · 不随日期筛选变化", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(142.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "当前收藏 ${records.size} 条；" + Mastery.entries.joinToString { grade -> "${grade.label} ${records.count { it.mastery == grade.name }} 条" } }) {
                            val width = 19.dp.toPx(); val inset = width / 2
                            drawArc(PaperLine, 0f, 360f, false, Offset(inset, inset), Size(size.width - width, size.height - width), style = Stroke(width))
                            var start = -90f
                            Mastery.entries.forEachIndexed { index, grade ->
                                val sweep = if (records.isEmpty()) 0f else records.count { it.mastery == grade.name }.toFloat() / records.size * 360f
                                if (sweep > 0) drawArc(colors[index], start, sweep, false, Offset(inset, inset), Size(size.width - width, size.height - width), style = Stroke(width))
                                start += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(records.size.toString(), fontSize = 31.sp, fontFamily = FontFamily.Serif); Text("已收藏", fontSize = 11.sp, color = Muted) }
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Mastery.entries.forEachIndexed { index, grade -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(colors[index], CircleShape)); Spacer(Modifier.width(8.dp))
                            Text(grade.label, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                            Text(records.count { it.mastery == grade.name }.toString(), fontSize = 15.sp)
                        } }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = PaperCard) {
            Column(Modifier.padding(20.dp)) {
                Text("复习足迹", fontSize = 18.sp, fontFamily = FontFamily.Serif)
                val today = LocalDate.now()
                val first = when (vm.draft.period) { 1 -> today; 7 -> today.minusDays(6); 30 -> today.minusDays(29); else -> reviews.minOfOrNull { LocalDate.parse(it.localDate) } ?: today.minusDays(6) }
                val last = maxOf(today, reviews.maxOfOrNull { LocalDate.parse(it.localDate) } ?: today)
                val counts = reviews.groupingBy { it.localDate }.eachCount()
                val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
                val maxCount = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
                Text(if (reviews.isEmpty()) "还没有复习记录，开始积累第一天" else "${reviews.size} 次复习，每一步都有记录", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp, bottom = 20.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    days.forEach { date ->
                        val count = counts[date.toString()] ?: 0
                        Column(Modifier.width(30.dp).semantics { contentDescription = "$date 复习 $count 次" }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(count.toString(), color = Muted, fontSize = 10.sp)
                            Box(Modifier.height(82.dp).width(13.dp), contentAlignment = Alignment.BottomCenter) { Box(Modifier.fillMaxWidth().height((if (count == 0) 2f else 78f * count / maxCount).dp).clip(RoundedCornerShape(4.dp)).background(if (count == 0) PaperLine else Terracotta)) }
                            Text("${date.monthValue}/${date.dayOfMonth}", fontSize = 9.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        val historical = (vm.allUserRecords().sumOf { it.reviewCount } - vm.events.count { it.type == "review" && it.undoneAt == null }).coerceAtLeast(0)
        if (historical > 0) Text("另有旧版记录 $historical 次，因日期未知未计入趋势。", fontSize = 11.sp, color = Muted)
        Landscape(Modifier.fillMaxWidth().height(92.dp))
    }
}
