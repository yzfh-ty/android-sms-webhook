package com.tyverse.smswebhook.root

import android.content.Context
import android.os.PowerManager
import com.tyverse.smswebhook.data.SettingsStore
import com.tyverse.smswebhook.data.UserInputException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.io.IOException

data class RootStatus(val available: Boolean = false, val checked: Boolean = false,
    val message: String = "尚未检测 Root", val batteryExempt: Boolean = false)

class RootController(private val context: Context, private val settings: SettingsStore) {
    private val lock = Mutex()
    fun isExempt(): Boolean = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    private suspend fun command(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        coroutineScope {
            val process = try { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
                catch (_: IOException) { throw UserInputException("未找到可用的 su，请检查手机 Root 环境") }
            try {
                val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText().take(16_384) } }
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw UserInputException("Root 授权或命令超时，请先在 Root 管理器中授权")
                }
                process.exitValue() to output.await()
            } finally { process.destroy(); process.inputStream.close(); process.outputStream.close() }
        }
    }

    suspend fun check(): RootStatus = lock.withLock {
        try {
            val (code, output) = command("id")
            val allowed = code == 0 && Regex("\\buid=0\\b").containsMatchIn(output)
            RootStatus(allowed, true, if (allowed) "Root 授权可用" else "未获得 Root 授权；标准模式仍可使用", isExempt())
        } catch (e: UserInputException) { RootStatus(false, true, e.message.orEmpty(), isExempt()) }
        catch (_: Exception) { RootStatus(false, true, "未找到可用的 su；标准模式仍可使用", isExempt()) }
    }

    suspend fun addWhitelist(): RootStatus = lock.withLock {
        if (isExempt()) return@withLock RootStatus(message = "已在省电豁免列表中，无需修改", batteryExempt = true)
        val pkg = context.packageName
        require(pkg.matches(Regex("[A-Za-z0-9_.]+")))
        settings.setRootAdded(true)
        val (code, _) = try { command("dumpsys deviceidle whitelist +$pkg") }
            catch (e: Exception) { if (!isExempt()) settings.setRootAdded(false); throw e }
        delay(250)
        if (code != 0 || !isExempt()) {
            if (!isExempt()) settings.setRootAdded(false)
            throw UserInputException("未能添加白名单，请检查 Root 授权或 ROM 是否支持此命令")
        }
        RootStatus(true, true, "已添加本应用的省电白名单", true)
    }

    suspend fun removeWhitelist(): RootStatus = lock.withLock {
        if (!settings.current().rootWhitelistAdded) throw UserInputException("当前白名单不是本应用添加的，不会替你删除")
        val pkg = context.packageName
        require(pkg.matches(Regex("[A-Za-z0-9_.]+")))
        val (code, _) = command("dumpsys deviceidle whitelist -$pkg")
        delay(250)
        if (code != 0 || isExempt()) throw UserInputException("撤销未生效，请检查 Root 授权与系统省电设置")
        settings.setRootAdded(false)
        RootStatus(true, true, "已撤销本应用添加的白名单", false)
    }
}
