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

private val pageTitles = listOf("概览", "投递记录", "Webhook 设置", "运行诊断")
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
                        Text("短信 → WEBHOOK", style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace)
                    }
                    Text(when { !permissions.sms && config.enabled -> "需要短信权限"
                        config.enabled -> "短信转发已开启"
                        else -> "准备好，连接你的通知" },
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (config.enabled) "收到新短信后，先安全保存，再发送到你的 Webhook。"
                        else "配置一个接收地址，让这台手机成为你的短信通知入口。",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(if (config.enabled) "转发已启用" else "转发已暂停", fontWeight = FontWeight.SemiBold)
                            Text("再次开启会恢复暂停任务", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = config.enabled, onCheckedChange = toggle, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "短信转发开关" })
                    }
                }
            }
        }
        if (!permissions.sms) item { InfoPanel("先授权短信接收", "仅接收新短信，不读取历史收件箱。") {
            Button(onClick = requestSms, enabled = !busy) { Text("授予短信权限") }
        } }
        if (config.url.isEmpty()) item { InfoPanel("设置你的 Webhook", "保存接收地址后，先发送一条测试通知。") {
            Button(onClick = settings, enabled = !busy) { Text("配置 Webhook") }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("已接受", stats.succeeded, Modifier.weight(1f))
            Metric("待发送", stats.pending, Modifier.weight(1f))
            Metric("需处理", stats.failed, Modifier.weight(1f))
        } }
        if (stats.paused > 0) item { Hint("${stats.paused} 条短信任务已暂停，开启转发后会恢复。") }
        if (config.healthError.isNotEmpty()) item { ErrorPanel(config.healthError) }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("当前接收端")
            Text(config.url.ifEmpty { "尚未配置" }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("最近收信  ${formatTime(config.lastReceivedAt)}", style = MaterialTheme.typography.bodySmall)
        } }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            SectionTitle("最近投递"); TextButton(onClick = records) { Text("查看全部") }
        } }
        if (recent.isEmpty()) item { EmptyState("等待第一条通知", "发送测试通知，或授权后向手机发送短信。") }
        items(recent, key = { it.id }) { MessageCard(it, !busy, { detail(it.id) }) }
        item { Hint("界面无需常驻。后台执行受系统调度影响，故障和重试可在记录中查看。") }
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
        2 -> it.state == DeliveryState.SUCCEEDED
        3 -> it.state == DeliveryState.NEEDS_ATTENTION
        else -> true
    } }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Hint("显示最近 200 条记录。正文默认隐藏，点开详情查看。已接受表示接收端返回成功。") }
        item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "待发送", "已接受", "需处理").forEachIndexed { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
            }
        } }
        if (filtered.isEmpty()) item { EmptyState("这里还没有记录", "符合当前筛选条件的通知会显示在这里。") }
        items(filtered, key = { it.id }) { row -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MessageCard(row, !busy, { detail(row.id) })
            if (row.state in listOf(DeliveryState.NEEDS_ATTENTION, DeliveryState.RETRY_WAIT))
                TextButton(onClick = { retry(row.id) }, enabled = !busy) { Text("重试此任务") }
        } }
        if (records.any { it.state == DeliveryState.SUCCEEDED }) item {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { confirmClear = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("清理已接受记录")
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清理已接受记录？") }, text = { Text("将删除所有已接受记录及其正文。未完成任务会保留。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; clear() }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun SettingsPage(draft: EndpointDraft, edit: (EndpointDraft) -> Unit, dirty: Boolean,
    busy: Boolean, save: () -> Unit, test: () -> Unit) {
    var showToken by rememberSaveable { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    val urlError = if (dirty) WebhookProtocol.validationError(draft.url.trim(), BuildConfig.DEBUG) else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        InfoPanel("连接你的接收端", "使用通用 JSON POST 协议。认证与签名为可选项。")
        OutlinedTextField(value = draft.url, onValueChange = { edit(draft.copy(url = it)) },
            modifier = Modifier.fillMaxWidth(), label = { Text("Webhook 地址") },
            placeholder = { Text("https://example.com/hooks/sms") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy,
            isError = urlError != null,
            supportingText = { Text(urlError ?: if (BuildConfig.DEBUG) "调试可用 http://10.0.2.2:8765/hooks/sms" else "使用有效证书的 HTTPS 地址") })
        SecretField("Bearer Token（可选）", draft.token, showToken, { showToken = !showToken }, !busy) {
            edit(draft.copy(token = it))
        }
        SecretField("HMAC 签名密钥（可选）", draft.secret, showSecret, { showSecret = !showSecret }, !busy) {
            edit(draft.copy(secret = it))
        }
        Hint("正文和认证信息加密保存在本机。修改配置只影响新任务，积压任务继续使用创建时的地址和认证。")
        Button(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存配置") }
        OutlinedButton(onClick = test, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("保存并发送测试")
        }
        if (dirty) Text("有尚未保存的修改", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("接收端需要做什么")
        Hint("接收 application/json 请求，以 Idempotency-Key 去重，成功后返回 2xx。发送超时可能触发重试。")
        Hint("当前版本不内置钉钉或企业微信的消息模板，需使用支持本项目 JSON 协议的接收端。")
        HorizontalDivider()
        SectionTitle("短信过滤规则")
        Hint("留空表示不限制。每行一个条件，同组满足任意一条；发送方与包含关键词两组需同时满足，排除关键词优先。测试通知不受过滤限制。")
        OutlinedTextField(value = draft.senderAllowlist, onValueChange = { edit(draft.copy(senderAllowlist = it)) },
            label = { Text("发送方白名单（可选）") }, placeholder = { Text("10086\n106xxxx") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !busy,
            supportingText = { Text("完整匹配实际收到的发送方，每行一个") })
        OutlinedTextField(value = draft.includeKeywords, onValueChange = { edit(draft.copy(includeKeywords = it)) },
            label = { Text("正文包含任一关键词（可选）") }, modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 5, enabled = !busy)
        OutlinedTextField(value = draft.excludeKeywords, onValueChange = { edit(draft.copy(excludeKeywords = it)) },
            label = { Text("正文排除关键词（可选）") }, modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 5, enabled = !busy)
        Hint("采用不区分大小写的文字匹配，不执行正则。未匹配的短信不保存正文、不进入投递队列；更改规则不影响已入队任务。")
        OutlinedButton(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存过滤规则和配置") }
    }
}

@Composable
private fun Diagnostics(config: RelaySettings, permissions: PermissionState, root: RootStatus,
    busy: Boolean, requestSms: () -> Unit, requestNotifications: () -> Unit,
    openBattery: () -> Unit, openAppSettings: () -> Unit, checkRoot: () -> Unit,
    addWhitelist: () -> Unit, removeWhitelist: () -> Unit, recover: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        InfoPanel("先检查状态，再调整设置", "普通收信不要求 Root。Root 只用于增强省电配置，不承诺进程永远存活。")
        Panel {
            StatusLine("短信接收权限", if (permissions.sms) "已授权" else "未授权", permissions.sms)
            StatusLine("故障通知权限", if (permissions.notifications) "已授权" else "未授权", permissions.notifications)
            StatusLine("电池优化豁免", if (permissions.batteryExempt) "已豁免" else "未豁免", permissions.batteryExempt)
            if (!permissions.sms) OutlinedButton(onClick = requestSms, enabled = !busy) { Text("授予短信权限") }
            if (!permissions.notifications) OutlinedButton(onClick = requestNotifications, enabled = !busy) { Text("允许故障提醒") }
        }
        OutlinedButton(onClick = openBattery, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("打开系统电池优化设置") }
        TextButton(onClick = openAppSettings, enabled = !busy) { Text("打开应用系统设置 / 自启动设置") }
        SectionTitle("Root 增强")
        Panel {
            Text(root.message, style = MaterialTheme.typography.bodyMedium)
            Hint("点击检测可能出现 Root 管理器授权窗口。")
            OutlinedButton(onClick = checkRoot, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("检测 Root 授权") }
            Button(onClick = addWhitelist, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用 Root 添加省电白名单") }
            if (config.rootWhitelistAdded) TextButton(onClick = removeWhitelist, enabled = !busy) { Text("撤销本应用添加的白名单") }
        }
        SectionTitle("队列恢复")
        Panel {
            Text("最近收信：${formatTime(config.lastReceivedAt)}", style = MaterialTheme.typography.bodyMedium)
            Text("最近投递执行：${formatTime(config.lastWorkerAt)}", style = MaterialTheme.typography.bodyMedium)
            Text("规则过滤次数：${config.filteredCount}", style = MaterialTheme.typography.bodyMedium)
            if (config.lastFilterReason.isNotEmpty()) Hint("最近过滤原因：${config.lastFilterReason}")
            OutlinedButton(onClick = recover, enabled = !busy) { Text("检查并恢复队列") }
        }
        if (config.healthError.isNotEmpty()) ErrorPanel(config.healthError)
        Hint("系统重启后首次解锁恢复队列。强行停止后需要重新打开应用。厂商自启动和长期熄屏表现仍需在实际手机验证。")
        Hint("当前已实现白名单诊断与撤销；未安装额外的 Root 看门狗或开机脚本。")
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
                StatusBadge(row.state)
            }
            Text(formatTime(row.receivedAt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.lastError.isNotEmpty()) Text(row.lastError, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("尝试 ${row.attempts} 次" + (row.lastHttpStatus?.let { " · HTTP $it" } ?: "") +
                if (row.state == DeliveryState.RETRY_WAIT) " · 下次最早 ${formatTime(row.nextAttemptAt)}" else "",
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
            StatusBadge(row.state)
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("发送方：${row.sender}")
                Text(detail.body, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                Text("接收：${formatTime(row.receivedAt)}")
                Text("短信时间：${formatTime(row.smsTimestamp)}")
                Text("订阅 ID：${row.subscriptionId ?: "未知"}")
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
        title = { Text("更改此任务的接收端？") },
        text = { Text("将使用当前已保存的地址和认证重新发送此条通知：\n$currentUrl\n\n原接收端可能已经处理过该通知。") },
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
            DeliveryState.SUCCEEDED -> "已接受"
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
@Composable private fun InfoPanel(title: String, text: String, content: @Composable () -> Unit = {}) {
    Panel { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Hint(text); content() }
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
@Composable private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_records), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
@Composable private fun SecretField(label: String, value: String, show: Boolean, toggle: () -> Unit,
    enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = toggle) { Text(if (show) "隐藏" else "显示") } })
}
private fun maskSender(sender: String): String = if (sender.length >= 8)
    sender.take(3) + "****" + sender.takeLast(4) else sender
private fun formatTime(at: Long): String = if (at <= 0) "暂无" else
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
