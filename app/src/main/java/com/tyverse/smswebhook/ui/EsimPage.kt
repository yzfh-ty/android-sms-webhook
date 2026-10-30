package com.tyverse.smswebhook.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyverse.smswebhook.data.DeliveryState
import com.tyverse.smswebhook.esim.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

@Composable
fun EsimPage(model: RelayViewModel, permissions: PermissionState, busy: Boolean,
    requestNotifications: () -> Unit, openSettings: () -> Unit) {
    val cards by model.cards.collectAsState()
    val results by model.cardResults.collectAsState()
    val target by model.cardTarget.collectAsState()
    val draft by model.cardDraft.collectAsState()
    val editorError by model.cardEditorError.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(target) { listState.scrollToItem(0) }
    var renewing by remember { mutableStateOf<EsimCardView?>(null) }
    var deleting by remember { mutableStateOf<EsimCard?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    BackHandler(enabled = target != null && draft == null) { model.closeCard() }
    val selected = cards.find { it.card.id == target }
    LazyColumn(state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (target == null) "${cards.size} 张卡片" else "卡片详情", style = MaterialTheme.typography.titleLarge)
                if (target == null) Button(onClick = model::newCard, enabled = !busy) { Text("添加卡片") }
                else TextButton(onClick = model::closeCard) { Text("返回列表") }
            }
        }
        if (!permissions.cardNotifications) item {
            CardNotificationHint(permissions, requestNotifications, openSettings)
        }
        if (target != null) {
            if (selected == null) item { Text("正在读取卡片；如该卡片已删除，请返回列表。") }
            else {
                item { EsimSummary(selected, now, onClick = {}, detailed = true) }
                item {
                    EsimPanel {
                        val card = selected.card
                        Text("提醒设置", style = MaterialTheme.typography.titleMedium)
                        Text(if (card.reminderEnabled) "提前 ${durationText(card.leadMinutes)} · ${repeatText(card.repeatMinutes)}" else "提醒已关闭")
                        Text("渠道：${channelText(card.localEnabled, card.wecomEnabled)}")
                        Text("下次提醒：${when {
                            !card.reminderEnabled -> "提醒已关闭"
                            card.nextReminderAt == null -> "本周期已生成提醒"
                            card.nextReminderAt <= now -> "等待提醒"
                            else -> EsimPolicy.format(card.nextReminderAt, card.zoneId)
                        }}")
                        val result = results[card.id]?.takeIf { it.event.ruleVersion == card.ruleVersion }
                        if (result != null) {
                            Text("最近提醒：${EsimPolicy.format(result.event.createdAt, card.zoneId)}")
                            if (card.localEnabled) Text("手机：${when (result.event.localState) {
                                "POSTED" -> "已提交通知栏"; "BLOCKED" -> "通知不可用，等待授权或开启渠道"; else -> "等待提醒"
                            }}")
                            if (card.wecomEnabled) Text("企业微信：${remoteText(result.remoteState)}")
                            if (!result.remoteError.isNullOrBlank()) Text(result.remoteError, color = MaterialTheme.colorScheme.error)
                            if (result.remoteState in listOf(DeliveryState.NEEDS_ATTENTION, DeliveryState.RETRY_WAIT))
                                OutlinedButton(onClick = { model.retry(result.event.id) }, enabled = !busy) { Text("重试企业微信提醒") }
                        }
                        if (card.lastRenewedAt != null) Text("上次续期：${EsimPolicy.format(card.lastRenewedAt, card.zoneId)}")
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { renewing = selected }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("已续期") }
                        OutlinedButton(onClick = { model.editCard(selected) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("编辑卡片") }
                        TextButton(onClick = { deleting = selected.card }, enabled = !busy) { Text("删除卡片", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        } else {
            if (cards.isEmpty()) item { EsimPanel { Text("还没有卡片"); Text("手动添加卡片，记录备注、到期时间和独立的续期周期。") } }
            items(cards, key = { it.card.id }) { view -> EsimSummary(view, now, { model.openCard(view.card.id) }) }
        }
        item { Text("提醒可能受系统后台限制而延迟；恢复运行后会检查错过的提醒。", style = MaterialTheme.typography.bodySmall) }
    }
    renewing?.let { snapshot ->
        val card = snapshot.card
        val estimated = EsimPolicy.renewAt(now, card.periodCount, card.periodUnit, card.zoneId)
        AlertDialog(onDismissRequest = { if (!busy) renewing = null }, title = { Text("确认已续期？") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("卡片：${card.name}", style = MaterialTheme.typography.titleMedium)
                if (snapshot.info.identifier.isNotEmpty()) Text("识别信息：${snapshot.info.identifier}")
                Text("当前到期：${EsimPolicy.format(card.expiresAt, card.zoneId)}")
                Text("续期周期：${card.periodCount} ${if (card.periodUnit == "MONTHS") "个月" else "天"}")
                Text("从本次确认时刻重新起算\n预计新到期：${EsimPolicy.format(estimated, card.zoneId)}\n时区：${card.zoneId}")
                Text(if (card.reminderEnabled) "提前 ${durationText(card.leadMinutes)} · ${repeatText(card.repeatMinutes)}\n${channelText(card.localEnabled, card.wecomEnabled)}" else "本卡提醒已关闭")
                if (estimated < card.expiresAt) Text("新到期时间早于原到期时间，请确认是否现在重新起算。", color = MaterialTheme.colorScheme.error)
                Text("确认后取消旧周期尚未发送的提醒，备注和其他卡片保持原样。")
            }
        }, confirmButton = { TextButton(onClick = { renewing = null; model.renewCard(card) }, enabled = !busy) { Text("确认已续期") } },
            dismissButton = { TextButton(onClick = { renewing = null }, enabled = !busy) { Text("取消") } })
    }
    deleting?.let { card -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除卡片？") },
        text = { Text("卡片：${card.name}\n\n本卡资料和未发送提醒将被删除，已发送的企业微信消息会保留。") },
        confirmButton = { TextButton(onClick = { deleting = null; model.deleteCard(card) }, enabled = !busy) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    draft?.let { EsimEditor(it, editorError, busy, permissions, requestNotifications, openSettings, model::dismissCardEditor, model::saveCard) }
}

@Composable
private fun EsimSummary(view: EsimCardView, now: Long, onClick: () -> Unit, detailed: Boolean = false) {
    val card = view.card
    val state = EsimPolicy.status(card.expiresAt, card.leadMinutes, now)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleLarge)
            Text(state + if (!card.reminderEnabled) " · 提醒已关闭" else "",
                color = if (state == "已到期") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("到期：${EsimPolicy.format(card.expiresAt, card.zoneId)}")
            val remaining = card.expiresAt - now
            Text(if (remaining <= 0) "已超过到期时间" else if (remaining < 3_600_000) "不足 1 小时到期" else
                "剩余 ${remaining / 86_400_000} 天 ${(remaining % 86_400_000) / 3_600_000} 小时")
            Text("续期周期：${card.periodCount} ${if (card.periodUnit == "MONTHS") "个月" else "天"}")
            if (detailed) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("时区：${card.zoneId}")
                    Text("运营商/套餐：${view.info.provider.ifBlank { "未填写" }}")
                    Text("号码/识别信息：${view.info.identifier.ifBlank { "未填写" }}")
                    Text("备注\n${view.info.notes.ifBlank { "暂无备注" }}")
                }
            } else if (view.info.notes.isNotEmpty()) Text(view.info.notes, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CardNotificationHint(permissions: PermissionState, request: () -> Unit, settings: () -> Unit) {
    EsimPanel {
        Text("手机到期通知尚不可用", color = MaterialTheme.colorScheme.error)
        Text("请允许通知并开启系统设置中的“eSIM 到期提醒”渠道。卡片资料仍可保存。")
        OutlinedButton(onClick = if (permissions.notifications) settings else request) {
            Text(if (permissions.notifications) "打开通知设置" else "允许通知")
        }
    }
}

@Composable
private fun EsimEditor(initial: EsimDraft, editorError: String, busy: Boolean, permissions: PermissionState,
    requestNotifications: () -> Unit, openSettings: () -> Unit, close: () -> Unit, save: (EsimDraft) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var provider by rememberSaveable { mutableStateOf(initial.provider) }
    var identifier by rememberSaveable { mutableStateOf(initial.identifier) }
    var notes by rememberSaveable { mutableStateOf(initial.notes) }
    var expiresAt by rememberSaveable { mutableLongStateOf(initial.expiresAt) }
    var period by rememberSaveable { mutableStateOf(initial.periodCount.toString()) }
    var unit by rememberSaveable { mutableStateOf(initial.periodUnit) }
    var enabled by rememberSaveable { mutableStateOf(initial.reminderEnabled) }
    var leadDays by rememberSaveable { mutableStateOf(initial.leadMinutes % 1440 == 0L) }
    var lead by rememberSaveable { mutableStateOf((initial.leadMinutes / if (leadDays) 1440 else 60).toString()) }
    var repeating by rememberSaveable { mutableStateOf(initial.repeatMinutes > 0) }
    var repeatDays by rememberSaveable { mutableStateOf(initial.repeatMinutes % 1440 == 0L) }
    var repeat by rememberSaveable { mutableStateOf(if (initial.repeatMinutes == 0L) "1" else (initial.repeatMinutes / if (repeatDays) 1440 else 60).toString()) }
    var local by rememberSaveable { mutableStateOf(initial.localEnabled) }
    var wecom by rememberSaveable { mutableStateOf(initial.wecomEnabled) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    fun dismiss() { if (!busy) { if (dirty) discard = true else close() } }
    fun mark() { dirty = true }
    fun submit() {
        val found = mutableMapOf<String, String>()
        if (name.trim().length !in 1..50) found["name"] = "请输入 1～50 字的卡片名称"
        if (provider.length > 100) found["provider"] = "最多 100 字"
        if (identifier.length > 100) found["identifier"] = "最多 100 字"
        if (notes.length > 2000) found["notes"] = "备注最多 2,000 字"
        val count = period.toIntOrNull()
        if (count == null || count !in 1..if (unit == "MONTHS") 120 else 3650) found["period"] = "请输入 1～${if (unit == "MONTHS") 120 else 3650} 的整数"
        val leadValue = lead.toLongOrNull()
        val repeatValue = repeat.toLongOrNull()
        if (leadValue == null || leadValue !in 0..if (leadDays) 3650 else 87600) found["lead"] = "请输入范围内的非负整数（最多 3,650 天）"
        if (repeating && (repeatValue == null || repeatValue !in 1..if (repeatDays) 3650 else 87600)) found["repeat"] = "请输入正整数间隔（最多 3,650 天）"
        if (enabled && !local && !wecom) found["channels"] = "请至少选择一种提醒渠道"
        errors = found
        if (found.isEmpty()) {
            focus.clearFocus()
            keyboard?.hide()
            save(initial.copy(name = name, provider = provider, identifier = identifier,
            notes = notes, expiresAt = expiresAt, periodCount = count!!, periodUnit = unit,
            reminderEnabled = enabled, leadMinutes = leadValue!! * if (leadDays) 1440 else 60,
            repeatMinutes = if (repeating) repeatValue!! * if (repeatDays) 1440 else 60 else 0,
                localEnabled = local, wecomEnabled = wecom))
        }
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Text(if (initial.id == null) "添加 eSIM 卡片" else "编辑卡片", Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    EditorField("卡片名称", name, { name = it; mark() }, errors["name"], busy)
                    EditorField("运营商/套餐（选填）", provider, { provider = it; mark() }, errors["provider"], busy)
                    EditorField("号码/识别信息（选填）", identifier, { identifier = it; mark() }, errors["identifier"], busy)
                    EditorField("备注（选填，仅保存在应用内）", notes, { notes = it; mark() }, errors["notes"], busy, multiline = true)
                    HorizontalDivider()
                    Text("到期与续期", style = MaterialTheme.typography.titleMedium)
                    Text("${EsimPolicy.format(expiresAt, initial.zoneId)}\n时区：${initial.zoneId}")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            val time = Instant.ofEpochMilli(expiresAt).atZone(ZoneId.of(initial.zoneId))
                            DatePickerDialog(context, { _, y, m, d ->
                                expiresAt = java.time.LocalDate.of(y, m + 1, d).atTime(time.toLocalTime())
                                    .atZone(time.zone).toInstant().toEpochMilli(); mark()
                            }, time.year, time.monthValue - 1, time.dayOfMonth).show()
                        }) { Text("选择日期") }
                        OutlinedButton(enabled = !busy, onClick = {
                            val time = Instant.ofEpochMilli(expiresAt).atZone(ZoneId.of(initial.zoneId))
                            TimePickerDialog(context, { _, h, m ->
                                expiresAt = time.withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant().toEpochMilli(); mark()
                            }, time.hour, time.minute, true).show()
                        }) { Text("选择时间") }
                    }
                    EditorField("续期周期", period, { period = it; mark() }, errors["period"], busy, numeric = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(unit == "DAYS", { unit = "DAYS"; mark() }, { Text("天") }, enabled = !busy)
                        FilterChip(unit == "MONTHS", { unit = "MONTHS"; mark() }, { Text("日历月") }, enabled = !busy)
                    }
                    Text("点击“已续期”并确认后，从确认时刻重新计算上述周期。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    EsimSwitch("开启本卡提醒", enabled, !busy) { enabled = it; mark() }
                    EditorField("提前多久提醒（0 为到期时）", lead, { lead = it; mark() }, errors["lead"], busy, numeric = true)
                    DurationUnits(leadDays, !busy) { leadDays = it; mark() }
                    EsimSwitch("重复提醒", repeating, !busy) { repeating = it; mark() }
                    if (repeating) {
                        EditorField("每隔多久提醒", repeat, { repeat = it; mark() }, errors["repeat"], busy, numeric = true)
                        DurationUnits(repeatDays, !busy) { repeatDays = it; mark() }
                        Text("到期后继续提醒，直到续期、关闭提醒或删除卡片。", style = MaterialTheme.typography.bodySmall)
                    } else Text("每个周期仅提醒一次。", style = MaterialTheme.typography.bodySmall)
                    EsimSwitch("手机通知", local, !busy) { local = it; mark() }
                    EsimSwitch("企业微信机器人", wecom, !busy) { wecom = it; mark() }
                    errors["channels"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (wecom) Text("使用设置中已保存的机器人，发送名称和到期时间，不发送备注。", style = MaterialTheme.typography.bodySmall)
                    if (local && !permissions.cardNotifications) CardNotificationHint(permissions, requestNotifications, openSettings)
                    Spacer(Modifier.height(12.dp))
                }
                if (editorError.isNotEmpty()) Text(editorError, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = ::dismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = ::submit, enabled = !busy, modifier = Modifier.weight(1f)) { Text("保存卡片") }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { discard = false; close() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable private fun EditorField(label: String, value: String, edit: (String) -> Unit, error: String?, busy: Boolean,
    numeric: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value, edit, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        enabled = !busy, singleLine = !multiline, minLines = if (multiline) 3 else 1, isError = error != null,
        supportingText = if (error == null) null else ({ Text(error) }),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
}
@Composable private fun DurationUnits(days: Boolean, enabled: Boolean, edit: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(days, { edit(true) }, { Text("天") }, enabled = enabled)
        FilterChip(!days, { edit(false) }, { Text("小时") }, enabled = enabled)
    }
}
@Composable private fun EsimSwitch(label: String, checked: Boolean, enabled: Boolean, edit: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(vertical = 12.dp)); Switch(checked, edit, enabled = enabled)
    }
}
@Composable private fun EsimPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
private fun durationText(minutes: Long): String = if (minutes % 1440 == 0L) "${minutes / 1440} 天" else "${minutes / 60} 小时"
private fun repeatText(minutes: Long): String = if (minutes == 0L) "仅一次" else "每隔 ${durationText(minutes)}重复"
private fun channelText(local: Boolean, wecom: Boolean): String = listOfNotNull(if (local) "手机通知" else null,
    if (wecom) "企业微信" else null).joinToString("、").ifEmpty { "未选择" }
private fun remoteText(state: String?): String = when (state) {
    DeliveryState.SUCCEEDED -> "已发送"; DeliveryState.NEEDS_ATTENTION -> "需处理"
    DeliveryState.RETRY_WAIT -> "等待重试"; DeliveryState.IN_FLIGHT -> "发送中"
    DeliveryState.CANCELLED -> "已取消"; null -> "投递记录已清理或等待更新"; else -> "待发送"
}
