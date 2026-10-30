package com.tyverse.smswebhook.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyverse.smswebhook.BuildConfig
import com.tyverse.smswebhook.R
import com.tyverse.smswebhook.data.*
import com.tyverse.smswebhook.root.RootStatus
import com.tyverse.smswebhook.webhook.WebhookProtocol
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val pageTitles = listOf("概览", "投递记录", "企业微信机器人", "运行诊断")
private val navTitles = listOf("概览", "记录", "设置", "诊断")
private val navIcons = listOf(R.drawable.ic_overview, R.drawable.ic_records, R.drawable.ic_settings, R.drawable.ic_health)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayApp(model: RelayViewModel, requestSms: () -> Unit, requestNotifications: () -> Unit,
    openBattery: () -> Unit, openAppSettings: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val config by model.settings.collectAsState()
    val stats by model.stats.collectAsState()
    val records by model.messages.collectAsState()
    val feedback by model.feedback.collectAsState()
    val permissions by model.permissions.collectAsState()
    val root by model.root.collectAsState()
    val draft by model.draft.collectAsState()
    val dirty by model.dirty.collectAsState()
    val detail by model.detail.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback.message) {
        if (feedback.message.isNotEmpty()) { snackbar.showSnackbar(feedback.message); model.clearNotice() }
    }
    Scaffold(modifier = Modifier.fillMaxSize().imePadding(),
        topBar = { TopAppBar(title = { Column {
            Text("SMS WEBHOOK", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
            Text(pageTitles[page], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        } }, actions = {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(end = 20.dp)) {
                Text("ANDROID ${Build.VERSION.RELEASE}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }) },
        bottomBar = { NavigationBar {
            navTitles.forEachIndexed { index, label ->
                NavigationBarItem(selected = page == index, onClick = { page = index },
                    icon = { Icon(painterResource(navIcons[index]), contentDescription = null) },
                    label = { Text(label) })
            }
        } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (feedback.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (page) {
                0 -> Overview(config, stats, records.take(3), permissions, feedback.busy || !feedback.ready,
                    model::toggle, { page = 2 }, { page = 1 }, requestSms, model::showDetail)
                1 -> Records(records, feedback.busy, model::showDetail, model::retry, model::clearCompleted)
                2 -> SettingsPage(draft, model::edit, dirty, feedback.busy || !feedback.ready, model::save, model::test)
                3 -> Diagnostics(config, permissions, root, feedback.busy, requestSms, requestNotifications,
                    openBattery, openAppSettings, model::checkRoot, model::addWhitelist,
                    model::removeWhitelist, model::recover)
            }
        }
    }
    detail?.let { MessageDialog(it, config.url, feedback.busy, model::dismissDetail, model::retry, model::retryWithCurrentEndpoint) }
}

@Composable
private fun Overview(config: RelaySettings, stats: QueueStats, recent: List<RelayMessage>,
    permissions: PermissionState, busy: Boolean, toggle: (Boolean) -> Unit, settings: () -> Unit,
    records: () -> Unit, requestSms: () -> Unit, detail: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_sms_notification), null,
                                Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Text("短信 → 企业微信", style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when { !permissions.sms && config.enabled -> "待授权"
                            config.enabled -> "已开启"
                            else -> "已暂停" }, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Switch(checked = config.enabled, onCheckedChange = toggle, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "短信转发开关" })
                    }
                }
            }
        }
        if (!permissions.sms || config.url.isEmpty()) item { Panel {
            if (!permissions.sms) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("短信未授权", Modifier.weight(1f))
                TextButton(onClick = requestSms, enabled = !busy) { Text("授权") }
            }
            if (config.url.isEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("机器人未配置", Modifier.weight(1f))
                TextButton(onClick = settings, enabled = !busy) { Text("配置") }
            }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("已发送", stats.succeeded, Modifier.weight(1f))
            Metric("待发送", stats.pending, Modifier.weight(1f))
            Metric("需处理", stats.failed, Modifier.weight(1f))
        } }
        if (stats.paused > 0) item { Hint("已暂停 ${stats.paused} 条") }
        if (config.healthError.isNotEmpty()) item { ErrorPanel(config.healthError) }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("企业微信机器人")
            Text(WebhookProtocol.maskedUrl(config.url), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("最近收信  ${formatTime(config.lastReceivedAt)}", style = MaterialTheme.typography.bodySmall)
        } }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            SectionTitle("最近投递"); TextButton(onClick = records) { Text("查看全部") }
        } }
        if (recent.isEmpty()) item { EmptyState("暂无通知") }
        items(recent, key = { it.id }) { MessageCard(it, !busy, { detail(it.id) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Records(records: List<RelayMessage>, busy: Boolean, detail: (String) -> Unit,
    retry: (String) -> Unit, clear: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val filtered = records.filter { when (filter) {
        1 -> it.state in listOf(DeliveryState.PENDING, DeliveryState.RETRY_WAIT, DeliveryState.IN_FLIGHT, DeliveryState.PAUSED)
        2 -> it.state == DeliveryState.SUCCEEDED && it.lastBusinessCode == 0
        3 -> it.state == DeliveryState.NEEDS_ATTENTION
        else -> true
    } }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "待发送", "已发送", "需处理").forEachIndexed { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
            }
        } }
        if (filtered.isEmpty()) item { EmptyState("暂无记录") }
        items(filtered, key = { it.id }) { row -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MessageCard(row, !busy, { detail(row.id) })
            if (row.state in listOf(DeliveryState.NEEDS_ATTENTION, DeliveryState.RETRY_WAIT))
                TextButton(onClick = { retry(row.id) }, enabled = !busy) { Text("重试") }
        } }
        if (records.any { it.state == DeliveryState.SUCCEEDED }) item {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { confirmClear = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("清理已发送记录")
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清理已发送记录？") }, text = { Text("未完成任务会保留。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; clear() }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun SettingsPage(draft: EndpointDraft, edit: (EndpointDraft) -> Unit, dirty: Boolean,
    busy: Boolean, save: () -> Unit, test: () -> Unit) {
    var showUrl by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val filtersActive = listOf(draft.senderAllowlist, draft.includeKeywords, draft.excludeKeywords).any { it.isNotBlank() }
    val urlError = if (dirty) WebhookProtocol.validationError(draft.url.trim(), BuildConfig.DEBUG) else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        OutlinedTextField(value = draft.url, onValueChange = { edit(draft.copy(url = it)) },
            modifier = Modifier.fillMaxWidth(), label = { Text("机器人地址") },
            placeholder = { Text("粘贴企业微信 Webhook") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy,
            visualTransformation = if (showUrl) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showUrl = !showUrl }) { Text(if (showUrl) "隐藏" else "显示") } },
            isError = urlError != null,
            supportingText = if (urlError != null) { { Text(urlError) } } else null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = save, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("保存") }
            OutlinedButton(onClick = test, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text("保存并测试")
            }
        }
        if (dirty) Text("未保存", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("短信过滤")
            if (filtersActive) Text(" · 已设置", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showFilters = !showFilters }) { Text(if (showFilters) "收起" else "展开") }
        }
        if (showFilters) {
            Hint("每行一条，留空不过滤")
            OutlinedTextField(value = draft.senderAllowlist, onValueChange = { edit(draft.copy(senderAllowlist = it)) },
                label = { Text("发送方白名单") }, placeholder = { Text("10086") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 5, enabled = !busy)
            OutlinedTextField(value = draft.includeKeywords, onValueChange = { edit(draft.copy(includeKeywords = it)) },
                label = { Text("包含关键词") }, modifier = Modifier.fillMaxWidth(),
                minLines = 1, maxLines = 5, enabled = !busy)
            OutlinedTextField(value = draft.excludeKeywords, onValueChange = { edit(draft.copy(excludeKeywords = it)) },
                label = { Text("排除关键词") }, modifier = Modifier.fillMaxWidth(),
                minLines = 1, maxLines = 5, enabled = !busy)
            OutlinedButton(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存过滤") }
        }
    }
}

@Composable
private fun Diagnostics(config: RelaySettings, permissions: PermissionState, root: RootStatus,
    busy: Boolean, requestSms: () -> Unit, requestNotifications: () -> Unit,
    openBattery: () -> Unit, openAppSettings: () -> Unit, checkRoot: () -> Unit,
    addWhitelist: () -> Unit, removeWhitelist: () -> Unit, recover: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Panel {
            StatusLine("短信接收权限", if (permissions.sms) "已授权" else "未授权", permissions.sms)
            StatusLine("故障通知权限", if (permissions.notifications) "已授权" else "未授权", permissions.notifications)
            StatusLine("电池优化豁免", if (permissions.batteryExempt) "已豁免" else "未豁免", permissions.batteryExempt)
            if (!permissions.sms) OutlinedButton(onClick = requestSms, enabled = !busy) { Text("授予短信权限") }
            if (!permissions.notifications) OutlinedButton(onClick = requestNotifications, enabled = !busy) { Text("允许故障提醒") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = openBattery, enabled = !busy, modifier = Modifier.weight(1f)) { Text("电池设置") }
            OutlinedButton(onClick = openAppSettings, enabled = !busy, modifier = Modifier.weight(1f)) { Text("应用设置") }
        }
        SectionTitle("Root")
        Panel {
            Text(root.message, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = checkRoot, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("检测授权") }
            Button(onClick = addWhitelist, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("添加省电白名单") }
            if (config.rootWhitelistAdded) TextButton(onClick = removeWhitelist, enabled = !busy) { Text("撤销白名单") }
        }
        SectionTitle("运行状态")
        Panel {
            Text("最近收信：${formatTime(config.lastReceivedAt)}", style = MaterialTheme.typography.bodyMedium)
            Text("最近执行：${formatTime(config.lastWorkerAt)}", style = MaterialTheme.typography.bodyMedium)
            Text("已过滤：${config.filteredCount}", style = MaterialTheme.typography.bodyMedium)
            if (config.lastFilterReason.isNotEmpty()) Hint("原因：${config.lastFilterReason}")
            OutlinedButton(onClick = recover, enabled = !busy) { Text("恢复队列") }
        }
        if (config.healthError.isNotEmpty()) ErrorPanel(config.healthError)
        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageCard(row: RelayMessage, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (row.source == "test") "测试通知" else maskSender(row.sender),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusBadge(if (row.state == DeliveryState.SUCCEEDED && row.lastBusinessCode == null) "LEGACY_COMPLETED" else row.state)
            }
            Text(formatTime(row.receivedAt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.lastError.isNotEmpty()) Text(row.lastError, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.state == DeliveryState.RETRY_WAIT) Text("重试 ${formatTime(row.nextAttemptAt)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MessageDialog(detail: MessageDetail, currentUrl: String, busy: Boolean, close: () -> Unit,
    retry: (String) -> Unit, retryCurrent: (String) -> Unit) {
    val row = detail.message
    var confirmRebind by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("通知详情") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusBadge(if (row.state == DeliveryState.SUCCEEDED && row.lastBusinessCode == null) "LEGACY_COMPLETED" else row.state)
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("发送方：${row.sender}")
                Text(detail.body, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                Text("接收：${formatTime(row.receivedAt)}")
                Text("短信时间：${formatTime(row.smsTimestamp)}")
                Text("订阅 ID：${row.subscriptionId ?: "未知"}")
                Text("执行：${row.attempts} 轮 · 已确认 ${row.nextPartIndex} 段")
                row.lastHttpStatus?.let { Text("HTTP：$it") }
                if (row.state == DeliveryState.SUCCEEDED && row.lastBusinessCode == null)
                    Text("旧版记录，未验证企微结果。")
                Text("接收端：${detail.target}")
                Text("任务 ID：${row.id}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (row.lastError.isNotEmpty()) Text(row.lastError, color = MaterialTheme.colorScheme.error)
            } }
            if (row.state in listOf(DeliveryState.NEEDS_ATTENTION, DeliveryState.RETRY_WAIT))
                OutlinedButton(onClick = { confirmRebind = true }, enabled = !busy) { Text("改用当前配置重试") }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } },
        dismissButton = {
            if (row.state in listOf(DeliveryState.NEEDS_ATTENTION, DeliveryState.RETRY_WAIT))
                TextButton(onClick = { close(); retry(row.id) }, enabled = !busy) { Text("按原配置重试") }
        })
    if (confirmRebind) AlertDialog(onDismissRequest = { confirmRebind = false },
        title = { Text("更换机器人并重试？") },
        text = { Text("${WebhookProtocol.maskedUrl(currentUrl)}\n\n更换后从首段重发，原群可能已收到部分消息。") },
        confirmButton = { TextButton(onClick = { confirmRebind = false; close(); retryCurrent(row.id) }) { Text("更改并重试") } },
        dismissButton = { TextButton(onClick = { confirmRebind = false }) { Text("取消") } })
}

@Composable private fun StatusBadge(state: String) {
    val color = when (state) {
        DeliveryState.SUCCEEDED -> MaterialTheme.colorScheme.primaryContainer
        DeliveryState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = RoundedCornerShape(8.dp)) {
        Text(when (state) {
            DeliveryState.SUCCEEDED -> "已发送"
            "LEGACY_COMPLETED" -> "旧版完成"
            DeliveryState.PENDING -> "待发送"
            DeliveryState.IN_FLIGHT -> "发送中"
            DeliveryState.RETRY_WAIT -> "等待重试"
            DeliveryState.PAUSED -> "已暂停"
            else -> "需处理"
        }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Hint(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold) }
@Composable private fun ErrorPanel(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun EmptyState(title: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_records), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}
@Composable private fun Metric(label: String, value: Int, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun StatusLine(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium,
            color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private fun maskSender(sender: String): String = if (sender.length >= 8)
    sender.take(3) + "****" + sender.takeLast(4) else sender
private fun formatTime(at: Long): String = if (at <= 0) "暂无" else
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
