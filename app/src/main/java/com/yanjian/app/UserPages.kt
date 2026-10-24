package com.yanjian.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonParser
import kotlin.math.abs

fun Modifier.cardGestures(left: () -> Unit, right: () -> Unit, up: () -> Unit): Modifier = pointerInput(left,right,up) {
    var total = Offset.Zero
    detectDragGestures(onDragStart = { total = Offset.Zero }, onDragEnd = {
        val threshold = 48.dp.toPx()
        if (abs(total.x) > threshold && abs(total.x) > abs(total.y) * 1.25f) { if (total.x < 0) left() else right() }
        else if (total.y < -threshold && abs(total.y) > abs(total.x) * 1.25f) up()
    }, onDrag = { change, delta -> total += delta; if (abs(total.x) > 12.dp.toPx() || total.y < -12.dp.toPx()) change.consume() })
}

@Composable fun CollectionPage(vm: StudyViewModel) {
    val items = vm.favorites(); val favorites = vm.allUserRecords().count { it.isFavorite }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (!vm.managerOpen) {
            Text("收藏", fontSize = 26.sp, fontFamily = FontFamily.Serif)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$favorites 条 · 到期 ${vm.dueCount} · 初次 ${vm.initialCount}", Modifier.weight(1f), fontSize = 12.sp)
                Button(onClick = { vm.startReview(practice = vm.dueCount + vm.initialCount == 0) }, enabled = favorites > 0 && !vm.busy) {
                    Text(when { vm.dueCount > 0 -> "开始复习"; vm.initialCount > 0 -> "开始学习"; else -> "自由练习" })
                }
            }
        }
        OutlinedTextField(vm.draft.search, { vm.setDraft(vm.draft.copy(search = it)) }, Modifier.fillMaxWidth(), label = { Text("搜索正文、释义、来源或标签") }, singleLine = true)
        if (vm.scope == "favorite") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("ALL" to "全部") + Mastery.entries.map { it.name to it.label }).forEach { (id,label) -> FilterChip(vm.draft.status == id, { vm.setDraft(vm.draft.copy(status = id)) }, { Text(label) }) }
                FilterChip(vm.draft.dueOnly, { vm.setDraft(vm.draft.copy(dueOnly = !vm.draft.dueOnly)) }, { Text("仅到期") })
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("recent" to "最近收藏", "due" to "到期优先", "mastery" to "掌握程度").forEach { (id,label) -> TextButton(onClick = { vm.setDraft(vm.draft.copy(sort = id)) }) { Text(if (vm.draft.sort == id) "· $label" else label, fontSize = 12.sp) } }
            }
        }
        if (items.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when { vm.draft.search.isNotBlank() -> "没有找到"; vm.draft.status != "ALL" || vm.draft.dueOnly -> "当前筛选没有内容"; vm.scope == "hidden" -> "没有屏蔽的内容"; vm.scope == "personal" -> "导入后，个人内容会出现在这里"; else -> "把想用的表达留在这里" }, textAlign = TextAlign.Center)
                if (vm.draft.search.isNotBlank() || vm.draft.status != "ALL" || vm.draft.dueOnly) TextButton(onClick = { vm.setDraft(vm.draft.copy(search = "", status = "ALL", dueOnly = false)) }) { Text("清除筛选") }
                else if (vm.scope == "favorite") TextButton(onClick = { vm.navigate(Page.DISCOVER) }) { Text("去发现") }
            }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items,key = { it.id }) { e ->
                Surface(Modifier.fillMaxWidth().clickable { if (vm.managerOpen && e.id.startsWith("personal:")) vm.edit(e) else vm.showDetail(e) }, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text(e.text, fontFamily = FontFamily.Serif, fontSize = 19.sp, maxLines = 3)
                        Text(if (!e.reviewable()) "待补充释义" else e.meaning.ifBlank { e.sourceLabel() }, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.users[e.id]?.mastery?.let { Mastery.valueOf(it).label } ?: "待学习", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            if (vm.scope == "hidden") TextButton(onClick = { vm.restoreHidden(e) }) { Text("恢复推荐") }
                            else TextButton(onClick = { vm.toggleFavorite(e) }) { Text(if (vm.isFavorite(e.id)) "取消收藏" else "收藏") }
                        }
                    }
                }
            }
            if (vm.hasMoreSaved) item { TextButton(onClick = { vm.loadMoreSaved() }, enabled = !vm.busy) { Text("加载更多") } }
        }
        if (vm.relationUndo != null) TextButton(onClick = { vm.undoRelation() }) { Text("撤销上次操作") }
        if (!vm.managerOpen && items.isNotEmpty()) TextButton(onClick = { vm.startReview(practice = true, filtered = true) }) { Text("练习当前结果 · 不改计划") }
    }
}

@Composable fun LearningPage(vm: StudyViewModel) {
    val s = vm.session; val c = vm.reviewCard(); val entry = vm.reviewEntry(); val labels = Mastery.entries.filter { it != Mastery.NEW }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navigate(Page.SAVED) }) { Text("返回") }
            Text(if (s?.practice == true) "自由练习" else if (c?.initial == true) "首次学习" else "今日复习", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 19.sp)
            TextButton(onClick = { vm.undoRating() }, enabled = !vm.busy && s?.practice != true) { Text("撤销评分") }
        }
        if (s?.practice != true) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { labels.forEach { grade ->
            val count = s?.cards?.count { !it.initial && !it.scored && vm.users[it.entryId]?.mastery == grade.name && vm.users[it.entryId]?.isFavorite == true } ?: 0
            FilterChip(vm.filter == grade, { vm.updateFilter(grade) }, { Text("${grade.label} $count",fontSize = 12.sp) }, Modifier.weight(1f).heightIn(min = 48.dp))
        } }
        if (entry != null && c != null) {
            Text(if (c.scored) "已记录" else if (c.initial) "初次" else "待复习", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 10.dp))
            Surface(Modifier.weight(1f).fillMaxWidth().cardGestures({ vm.reviewNext() }, { vm.reviewPrevious() }, { vm.showDetail(entry) }).clickable { vm.flip() }, shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(if (c.flipped) entry.backText() else entry.text, fontFamily = FontFamily.Serif, fontSize = if (vm.mode == StudyMode.WORD && !c.flipped) 34.sp else 23.sp, lineHeight = 36.sp, textAlign = TextAlign.Center)
                    Text(if (c.flipped) "点击返回正文" else "点击揭晓", fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.reviewPrevious() }) { Text("上一张") }
                TextButton(onClick = { vm.showDetail(entry) }) { Text("来源与详情") }
                TextButton(onClick = { vm.reviewNext() }) { Text(if (c.scored || s?.practice == true) "下一张" else "跳过") }
            }
            if (s?.practice != true) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) { labels.forEach { grade -> Button(onClick = { vm.rate(entry,grade) }, enabled = c.revealed && !c.scored && !vm.busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(grade.label) } } }
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val skipped = s?.cards?.count { it.skipped && !it.scored } ?: 0
            Text(if (vm.filter != null) "这一类暂时没有到期内容" else if (skipped > 0) "还有 $skipped 条" else "本轮已完成", fontSize = 22.sp)
            Text("完成 ${s?.cards?.count { it.scored } ?: 0} · 初次 ${s?.cards?.count { it.scored && it.initial } ?: 0}", Modifier.padding(16.dp))
            if (skipped > 0) Button(onClick = { vm.continueSkipped() }) { Text("继续跳过项") }
            vm.filter?.let { grade -> TextButton(onClick = { vm.updateFilter(grade) }) { Text("返回全队列") } }
            TextButton(onClick = { vm.startReview(practice = true, restart = true) }) { Text("自由练习 · 不改计划") }
            TextButton(onClick = { vm.navigate(Page.SAVED) }) { Text("返回收藏") }
        }
        Text(if (s?.practice == true) "仅翻卡阅读，不改变学习计划" else "简化调度 · 不熟悉 1 天 / 熟悉 3 天 / 已会 7 天", fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable fun InsightsPage(vm: StudyViewModel) {
    val events = vm.filteredEvents(); val records = vm.allUserRecords().filter { it.isFavorite }; val reviews = events.filter { it.type == "review" }
    val seen = events.filter { it.type == "exposure" }.map { it.entryId }.distinct().size
    val added = events.filter { it.type == "favorite" }.map { it.entryId }.distinct().size
    val historical = (vm.allUserRecords().sumOf { it.reviewCount } - vm.events.count { it.type == "review" && it.undoneAt == null }).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("学习数据", fontSize = 26.sp, fontFamily = FontFamily.Serif)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1 to "今日",7 to "7日",30 to "30日",0 to "全部").forEach { (n,label) -> FilterChip(vm.draft.period == n,{ vm.setDraft(vm.draft.copy(period = n)) },{ Text(label) }) } }
        listOf("浏览内容" to seen,"有效复习次数" to reviews.size,"复习内容" to reviews.map { it.entryId }.distinct().size,"期间新增收藏" to added).forEach { (label,n) -> Row(Modifier.fillMaxWidth()) { Text(label,Modifier.weight(1f)); Text(n.toString(),fontSize = 23.sp) } }
        val exposedIds = events.filter { it.type == "exposure" }.map { it.entryId }.toSet()
        val converted = events.filter { it.type == "favorite" && it.entryId in exposedIds }.map { it.entryId }.distinct().size
        Text("浏览后收藏 $converted / $seen",fontSize = 13.sp)
        HorizontalDivider(); Text("当前收藏 ${records.size}",fontSize = 18.sp)
        Mastery.entries.forEach { grade -> val n = records.count { it.mastery == grade.name }; Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) { Text(grade.label,Modifier.width(80.dp)); LinearProgressIndicator(progress = { if (records.isEmpty()) 0f else n.toFloat() / records.size }, modifier = Modifier.weight(1f)); Text("  $n") } }
        Text("真实复习趋势",fontSize = 18.sp)
        if (reviews.isEmpty()) Text("完成复习后出现记录",fontSize = 13.sp)
        else reviews.groupBy { it.localDate }.toSortedMap().forEach { (date,list) -> Row(Modifier.fillMaxWidth()) { Text(date,Modifier.weight(1f)); Text("${list.size} 次") } }
        if (historical > 0) Text("旧版本历史合计 $historical 次，缺少事件日期，不计入日期趋势。",fontSize = 12.sp)
        Text("按事件发生时的当地日期记录；撤销评分不计入有效次数。",fontSize = 12.sp)
    }
}

@Composable fun DataTools(vm: StudyViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var importFormat by remember { mutableStateOf("csv") }
    var exportFormat by remember { mutableStateOf("json") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.chooseImport(uri,importFormat) } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::chooseRestore) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let { uri -> vm.exportTo(uri,exportFormat) } }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "收起内容与备份" else "我的内容、导入与备份") }
    if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.openManager() }) { Text("我的内容 / 屏蔽管理") }
        Text("内容导入 · UTF-8 · 10 MiB / 10,000 条",fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("csv","json","txt").forEach { format -> OutlinedButton(onClick = { importFormat = format; pick.launch(arrayOf("*/*")) },enabled = !vm.busy) { Text(format.uppercase()) } } }
        Row { TextButton(onClick = { exportFormat = "json"; create.launch("言间-个人内容.json") }) { Text("内容导出 JSON") }; TextButton(onClick = { exportFormat = "csv"; create.launch("言间-个人内容.csv") }) { Text("导出 CSV") } }
        Text("CSV 对公式开头加单引号，JSON 保留原文。",fontSize = 11.sp)
        Button(onClick = { exportFormat = "backup"; create.launch("言间-完整备份.json") },modifier = Modifier.fillMaxWidth()) { Text("创建完整备份") }
        OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) },modifier = Modifier.fillMaxWidth()) { Text("从完整备份恢复") }
        TextButton(onClick = { vm.previewRecovery() }) { Text("查看上次恢复前的恢复点") }
        if (vm.batches.isNotEmpty()) {
            Text("导入批次",fontSize = 15.sp)
            vm.batches.forEach { batch -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${batch.id.take(8)} · ${batch.changes.size} 条",Modifier.weight(1f)); TextButton(onClick = { vm.undoImport(batch) },enabled = !batch.undone && !vm.busy) { Text(if (batch.undone) "已撤销" else "撤销本批") } } }
        }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable private fun Panel(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(),color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(title,Modifier.weight(1f),fontSize = 22.sp); TextButton(onClick = close) { Text("返回") } }
                content()
            }
        }
    }
}

@Composable fun UserPanels(vm: StudyViewModel) {
    if (vm.managerOpen) Panel("内容管理", { vm.back() }) {
        Row { FilterChip(vm.scope == "personal",{ vm.setScope("personal") },{ Text("我的内容") }); Spacer(Modifier.width(12.dp)); FilterChip(vm.scope == "hidden",{ vm.setScope("hidden") },{ Text("不再推荐") }) }
        CollectionPage(vm)
    }
    vm.importFile?.let { file -> Panel("导入预览", { vm.clearImport() }) {
        var mapping by remember(file) { mutableStateOf(file.mapping) }; var kind by remember(file) { mutableStateOf(vm.kind) }
        var favorite by remember(file) { mutableStateOf(false) }; var update by remember(file) { mutableStateOf(false) }
        var rowKinds by remember(file) { mutableStateOf<Map<Int,String>>(emptyMap()) }; var rowNumber by remember(file) { mutableStateOf("") }
        val preview = vm.importPreview
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${file.rows.size} 条 · ${file.format.uppercase()}")
            Text("默认类型"); Row { FilterChip(kind == "word",{ kind = "word" },{ Text("词语") }); Spacer(Modifier.width(8.dp)); FilterChip(kind == "sentence",{ kind = "sentence" },{ Text("句子") }) }
            OutlinedTextField(rowNumber,{ rowNumber = it.filter(Char::isDigit) },Modifier.fillMaxWidth(),label = { Text("单独修正原文件行号（可选）") },singleLine = true)
            Row { TextButton(onClick = { rowNumber.toIntOrNull()?.takeIf { n -> file.rows.any { it.line == n } }?.let { rowKinds = rowKinds + (it to "word") } }) { Text("此行改词语") }; TextButton(onClick = { rowNumber.toIntOrNull()?.takeIf { n -> file.rows.any { it.line == n } }?.let { rowKinds = rowKinds + (it to "sentence") } }) { Text("此行改句子") } }
            if (rowKinds.isNotEmpty()) Text("已修正：" + rowKinds.entries.joinToString { "第 ${it.key} 行=${if (it.value == "word") "词语" else "句子"}" },fontSize = 12.sp)
            Text("字段映射（点击切换列）",fontSize = 13.sp)
            file.mapping.keys.forEach { field -> TextButton(onClick = { val options = listOf("") + file.headers; val current = options.indexOf(mapping[field]); mapping = mapping + (field to options[(current + 1) % options.size]) }) { Text("${fieldLabel(field)} ← ${mapping[field].orEmpty().ifBlank { "不使用" }}") } }
            Button(onClick = { vm.previewImport(mapping,kind,rowKinds) },enabled = !vm.busy) { Text("解析并预览") }
            if (preview != null && preview.file.mapping == mapping && preview.defaultKind == kind && preview.rowKinds == rowKinds) {
                Text("有效 ${preview.candidates.size} · 完全重复 ${preview.candidates.count { it.duplicate }} · 待补充 ${preview.candidates.count { it.entry.meaning.isBlank() }} · 错误 ${preview.errors.size}")
                preview.errors.forEach { Text(it,fontSize = 12.sp,color = MaterialTheme.colorScheme.error) }
                (preview.candidates.take(10) + preview.candidates.filter { it.old != null && !it.duplicate }).distinctBy { it.line }.forEach { c -> Text("第 ${c.line} 行 · ${if (c.entry.kind == "word") "词语" else "句子"} · ${if (c.duplicate) "跳过重复" else if (c.old != null) "内容变更" else "新建"}\n${c.entry.text}\n${c.entry.meaning}",fontSize = 13.sp)
                    c.old?.takeIf { !c.duplicate }?.let { old -> Text("原：${old.text} / ${old.meaning} / ${old.workTitle}\n新：${c.entry.text} / ${c.entry.meaning} / ${c.entry.workTitle}",fontSize = 12.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(favorite,{ favorite = it }); Text("同时收藏（缺释义先待补充）") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(update,{ update = it }); Text("同键变更更新；未选则保留原条") }
                val count = preview.candidates.count { !it.duplicate && (it.old == null || update) }
                Button(onClick = { vm.confirmImport(favorite,update) },enabled = !vm.busy && count > 0,modifier = Modifier.fillMaxWidth()) { Text("确认保存 $count 条，跳过 ${file.rows.size - count} 条") }
            }
        }
    } }
    vm.importResult?.let { result -> AlertDialog(onDismissRequest = { vm.clearImport() }, title = { Text("导入批次 ${result.id.take(8)}") }, text = { Text("保存 ${result.changes.size} 条，跳过 ${result.skipped} 条${if (result.undone) " · 已撤销" else ""}") },confirmButton = { TextButton(onClick = { vm.clearImport() }) { Text("完成") } },dismissButton = { TextButton(onClick = { vm.undoImport(result) },enabled = !vm.busy && !result.undone) { Text("撤销本批") } }) }
    vm.editing?.let { entry -> Panel("编辑个人内容", { vm.closeEditor() }) {
        var text by remember(entry) { mutableStateOf(entry.text) }; var meaning by remember(entry) { mutableStateOf(entry.meaning) }
        var title by remember(entry) { mutableStateOf(entry.workTitle) }; var quote by remember(entry) { mutableStateOf(entry.quote) }; var url by remember(entry) { mutableStateOf(entry.context) }
        var tags by remember(entry) { mutableStateOf(JsonParser.parseString(entry.tagsJson).asJsonArray.joinToString("|") { it.asString }) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(text,{ text = it },Modifier.fillMaxWidth(),label = { Text("正文") })
            OutlinedTextField(meaning,{ meaning = it },Modifier.fillMaxWidth(),label = { Text("释义（补齐后可学习）") })
            OutlinedTextField(title,{ title = it },Modifier.fillMaxWidth(),label = { Text("来源名称") })
            OutlinedTextField(quote,{ quote = it },Modifier.fillMaxWidth(),label = { Text("来源原句") })
            OutlinedTextField(url,{ url = it },Modifier.fillMaxWidth(),label = { Text("来源链接") })
            OutlinedTextField(tags,{ tags = it },Modifier.fillMaxWidth(),label = { Text("标签，以 | 分隔") })
        }
        Button(onClick = { vm.savePersonal(text,meaning,title,quote,url,tags) },enabled = !vm.busy,modifier = Modifier.fillMaxWidth()) { Text("保存") }
    } }
    vm.restorePreview?.let { p -> AlertDialog(onDismissRequest = { vm.cancelRestore() }, title = { Text("替换当前用户数据？") }, text = { Text("备份：个人内容 ${p.entries.count { it.id.startsWith("personal:") }} 条，收藏 ${p.users.count { it.isFavorite }} 条，事件 ${p.events.size} 条，会话 ${p.records.count { it.type == "session" }} 个。\n当前：收藏 ${vm.allUserRecords().count { it.isFavorite }} 条（当前模式）。\n保留当前官方词库；替换前自动保存恢复点。") },confirmButton = { Button(onClick = { vm.confirmRestore() },enabled = !vm.busy) { Text("确认替换恢复") } },dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text("取消") } }) }
    vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.clearError() },title = { Text("操作未完成") },text = { Text(message) },confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("知道了") } }) }
}
private fun fieldLabel(key: String) = mapOf("kind" to "类型","text" to "内容","shortMeaning" to "释义","category" to "类别","tags" to "标签","sourceTitle" to "来源名称","sourceQuote" to "原句","sourceUrl" to "来源链接","externalId" to "外部编号")[key] ?: key
