package com.tyverse.smswebhook.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyverse.smswebhook.RelayApplication
import com.tyverse.smswebhook.data.EndpointDraft
import com.tyverse.smswebhook.data.MessageDetail
import com.tyverse.smswebhook.data.QueueStats
import com.tyverse.smswebhook.data.RelaySettings
import com.tyverse.smswebhook.data.UserInputException
import com.tyverse.smswebhook.root.RootStatus
import com.tyverse.smswebhook.esim.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiFeedback(val busy: Boolean = false, val message: String = "", val ready: Boolean = false)
data class PermissionState(val sms: Boolean = false, val notifications: Boolean = false,
    val batteryExempt: Boolean = false, val cardNotifications: Boolean = false)

class RelayViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as RelayApplication).container
    private val sharing = SharingStarted.WhileSubscribed(5_000)
    private val _feedback = MutableStateFlow(UiFeedback())
    val feedback = _feedback.asStateFlow()
    val settings = c.settings.flow.catch { notify("无法读取设置，请检查应用存储") }
        .stateIn(viewModelScope, sharing, RelaySettings())
    val messages = c.repository.dao.observeRecent().catch { notify("无法读取记录") }
        .stateIn(viewModelScope, sharing, emptyList())
    val stats = c.repository.dao.observeStats().catch { notify("无法读取队列统计") }
        .stateIn(viewModelScope, sharing, QueueStats())
    val cards = c.esim.cards.catch { notify("无法读取卡片资料，请检查存储和加密密钥") }
        .stateIn(viewModelScope, sharing, emptyList())
    val cardResults = c.esim.results.catch { notify("无法读取提醒结果") }
        .stateIn(viewModelScope, sharing, emptyMap())
    private val _cardDraft = MutableStateFlow<EsimDraft?>(null)
    val cardDraft = _cardDraft.asStateFlow()
    private val _cardEditorError = MutableStateFlow("")
    val cardEditorError = _cardEditorError.asStateFlow()
    private val _cardTarget = MutableStateFlow<String?>(null)
    val cardTarget = _cardTarget.asStateFlow()
    fun openCard(id: String) { _cardTarget.value = id }
    fun closeCard() { _cardTarget.value = null }
    fun newCard() { _cardEditorError.value = ""; _cardDraft.value = EsimDraft() }
    fun editCard(view: EsimCardView) { _cardEditorError.value = ""; _cardDraft.value = EsimDraft.from(view) }
    fun dismissCardEditor() { _cardDraft.value = null }
    fun saveCard(draft: EsimDraft) = action {
        _cardEditorError.value = ""
        c.esim.save(draft); _cardDraft.value = null; notify("卡片已保存")
    }
    fun renewCard(card: EsimCard) = action {
        val renewed = c.esim.renew(card.id, card.revision)
        notify("已续期，新到期时间：${EsimPolicy.format(renewed.expiresAt, renewed.zoneId)}")
    }
    fun deleteCard(card: EsimCard) = action {
        c.esim.delete(card.id, card.revision); closeCard(); notify("卡片已删除")
    }
    private val _draft = MutableStateFlow(EndpointDraft())
    val draft = _draft.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty = _dirty.asStateFlow()
    private val _permissions = MutableStateFlow(PermissionState())
    val permissions = _permissions.asStateFlow()
    private val _root = MutableStateFlow(RootStatus())
    val root = _root.asStateFlow()
    private val _detail = MutableStateFlow<MessageDetail?>(null)
    val detail = _detail.asStateFlow()

    init {
        action {
            _draft.value = c.repository.draft()
            _feedback.value = _feedback.value.copy(ready = true)
        }
        refreshPermissions()
    }
    fun edit(draft: EndpointDraft) { _draft.value = draft; _dirty.value = true }
    fun notify(message: String) { _feedback.value = _feedback.value.copy(message = message) }
    fun clearNotice() { _feedback.value = _feedback.value.copy(message = "") }
    fun dismissDetail() { _detail.value = null }
    fun refreshPermissions() {
        val app = getApplication<Application>()
        _permissions.value = PermissionState(
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            c.root.isExempt(), c.notifications.cardNotificationsAvailable())
        viewModelScope.launch(Dispatchers.IO) { runCatching { c.esim.recover() } }
    }
    fun save() = action {
        c.repository.saveEndpoint(_draft.value); _dirty.value = false; notify("机器人配置已保存")
    }
    fun test() = action {
        c.repository.saveEndpoint(_draft.value); _dirty.value = false
        c.repository.sendTest(); notify("测试已入队")
    }
    fun toggle(enabled: Boolean) = action {
        if (enabled && _dirty.value) throw UserInputException("请先保存机器人地址和过滤规则")
        c.repository.setEnabled(enabled)
        notify(if (enabled) "已开启转发" else "已暂停转发")
    }
    fun retry(id: String) = action { c.repository.retry(id); notify("已安排重试") }
    fun retryWithCurrentEndpoint(id: String) = action {
        if (_dirty.value) throw UserInputException("请先保存新的机器人配置")
        c.repository.retry(id, currentEndpoint = true); notify("已安排重试")
    }
    fun showDetail(id: String) = action { _detail.value = c.repository.detail(id) }
    fun recover() = action { c.repository.recover(); c.esim.recover(); notify("队列和卡片提醒已检查") }
    fun clearCompleted() = action {
        val count = c.repository.dao.clearCompleted(); notify("已清理 $count 条已发送记录")
    }
    fun checkRoot() = action { _root.value = c.root.check(); refreshPermissions() }
    fun addWhitelist() = action { _root.value = c.root.addWhitelist(); refreshPermissions() }
    fun removeWhitelist() = action { _root.value = c.root.removeWhitelist(); refreshPermissions() }

    private fun action(block: suspend () -> Unit) {
        if (_feedback.value.busy) return
        _feedback.value = _feedback.value.copy(busy = true)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: UserInputException) { failed(e.message ?: "请检查输入") }
            catch (_: Exception) { failed("操作未完成，请检查权限、存储或加密密钥后重试") }
            finally { _feedback.value = _feedback.value.copy(busy = false) }
        }
    }
    private fun failed(message: String) {
        if (_cardDraft.value != null) _cardEditorError.value = message
        notify(message)
    }
}
