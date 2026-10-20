package com.zhuzhi.ufi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhuzhi.ufi.data.AclInfo
import com.zhuzhi.ufi.data.ClientDevice
import com.zhuzhi.ufi.data.LoginResult
import com.zhuzhi.ufi.data.NetworkUtil
import com.zhuzhi.ufi.data.SecureStore
import com.zhuzhi.ufi.data.Status
import com.zhuzhi.ufi.data.WifiInfo
import com.zhuzhi.ufi.data.ZteApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class Phase { Booting, Login, Ready }

data class UiState(
    val phase: Phase = Phase.Booting,
    val host: String = "",
    val hostInput: String = "",
    val passwordInput: String = "",
    val remember: Boolean = true,
    val connecting: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val status: Status? = null,
    val wifi: WifiInfo? = null,
    val devices: List<ClientDevice> = emptyList(),
    val online: Boolean = false,
    val lastUpdated: Long = 0L,
    val tab: Int = 0,
    val message: String? = null,
    val indicatorLight: Boolean? = null,
    val sleepMinutes: Int? = null,
    val acl: AclInfo? = null,
    val savedPassword: String? = null,
)

class UfiViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var api: ZteApi? = null
    private var pollJob: Job? = null
    private var tick = 0

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val savedHost = SecureStore.getString(context, "host")
            val savedPassword = SecureStore.getSecret(context, "password")
            _state.update {
                it.copy(
                    host = savedHost.orEmpty(),
                    hostInput = savedHost.orEmpty(),
                    remember = savedPassword != null,
                    savedPassword = savedPassword,
                )
            }
            val host = savedHost ?: withContext(Dispatchers.IO) {
                ZteApi.discover(NetworkUtil.gateway(context))
            }
            if (host == null) {
                _state.update {
                    it.copy(
                        phase = Phase.Login,
                        error = "未发现设备，请确认手机已连接随身WiFi，或手动填写地址",
                    )
                }
                return@launch
            }
            _state.update { it.copy(host = host, hostInput = host) }
            if (savedPassword != null) {
                connectTo(host, savedPassword)
            } else {
                _state.update { it.copy(phase = Phase.Login) }
            }
        }
    }

    fun onHostChange(value: String) = _state.update { it.copy(hostInput = value.trim()) }

    fun onPasswordChange(value: String) = _state.update { it.copy(passwordInput = value) }

    fun onRememberChange(value: Boolean) = _state.update { it.copy(remember = value) }

    fun connect() {
        val current = _state.value
        val host = current.hostInput.ifBlank { current.host }
        val password = current.passwordInput
        if (host.isBlank()) {
            _state.update { it.copy(error = "请填写设备地址") }
            return
        }
        if (password.isBlank()) {
            _state.update { it.copy(error = "请输入 WebUI 密码") }
            return
        }
        viewModelScope.launch { connectTo(host, password) }
    }

    private suspend fun connectTo(host: String, password: String) {
        _state.update { it.copy(connecting = true, error = null) }
        val client = ZteApi("http://$host")
        val result = runCatching { withContext(Dispatchers.IO) { client.login(password) } }
            .getOrElse { LoginResult.Failure(it.friendly()) }
        if (result is LoginResult.Failure) {
            _state.update { it.copy(phase = Phase.Login, connecting = false, error = result.message) }
            return
        }
        api = client
        val context = getApplication<Application>()
        SecureStore.putString(context, "host", host)
        if (_state.value.remember) {
            SecureStore.putSecret(context, "password", password)
        } else {
            SecureStore.removeSecret(context, "password")
        }
        _state.update {
            it.copy(
                phase = Phase.Ready,
                host = host,
                hostInput = host,
                connecting = false,
                busy = false,
                error = null,
                savedPassword = password,
                online = true,
            )
        }
        loadExtras()
        startPolling()
        refreshOnce()
    }

    private suspend fun loadExtras() {
        val client = api ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                val light = client.getIndicatorLight()
                val sleep = client.getSleepMinutes()
                _state.update { it.copy(indicatorLight = light, sleepMinutes = sleep) }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        tick = 0
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)
                refreshOnce(soft = true)
            }
        }
    }

    private suspend fun refreshOnce(soft: Boolean = false) {
        val client = api ?: return
        try {
            val status = withContext(Dispatchers.IO) { client.fetchStatus() }
            if (!status.loggedIn) {
                if (!relogin()) {
                    _state.update {
                        it.copy(
                            phase = Phase.Login,
                            online = false,
                            error = "登录会话已失效，请重新登录",
                            savedPassword = null,
                        )
                    }
                    return
                }
            }
            tick++
            var wifi = _state.value.wifi
            var devices = _state.value.devices
            var acl = _state.value.acl
            if (wifi == null || tick % 2 == 1) {
                wifi = runCatching { withContext(Dispatchers.IO) { client.fetchWifi() } }
                    .getOrElse { wifi }
                acl = runCatching { withContext(Dispatchers.IO) { client.fetchAcl() } }
                    .getOrElse { acl }
            }
            if (devices.isEmpty() || tick % 2 == 0) {
                devices = runCatching { withContext(Dispatchers.IO) { client.fetchDevices() } }
                    .getOrElse { devices }
            }
            _state.update {
                it.copy(
                    status = status,
                    wifi = wifi,
                    devices = devices,
                    acl = acl,
                    online = true,
                    phase = Phase.Ready,
                    lastUpdated = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(online = false) }
            if (!soft) {
                _state.update { it.copy(message = e.friendly()) }
            }
        }
    }

    private suspend fun relogin(): Boolean {
        val password = _state.value.savedPassword ?: return false
        val host = _state.value.host
        val fresh = ZteApi("http://$host")
        val result = runCatching { withContext(Dispatchers.IO) { fresh.login(password) } }.getOrNull()
        return if (result is LoginResult.Success) {
            api = fresh
            true
        } else {
            false
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshOnce() }
    }

    fun setTab(index: Int) = _state.update { it.copy(tab = index) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setWifiEnabled(enabled: Boolean) {
        action(if (enabled) "已开启 WiFi" else "已关闭 WiFi") { it.setWifiEnabled(enabled) }
    }

    fun setIndicatorLight(enabled: Boolean) {
        val client = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val ok = runCatching { withContext(Dispatchers.IO) { client.setIndicatorLight(enabled) } }
            _state.update {
                it.copy(
                    busy = false,
                    indicatorLight = if (ok.isSuccess) enabled else it.indicatorLight,
                    message = if (ok.isSuccess) {
                        if (enabled) "指示灯已开启" else "指示灯已关闭"
                    } else {
                        ok.exceptionOrNull()?.friendly() ?: "操作失败"
                    },
                )
            }
        }
    }

    fun setSleepMinutes(minutes: Int) {
        val client = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val ok = runCatching { withContext(Dispatchers.IO) { client.setSleepMinutes(minutes) } }
            _state.update {
                it.copy(
                    busy = false,
                    sleepMinutes = if (ok.isSuccess) minutes else it.sleepMinutes,
                    message = if (ok.isSuccess) "休眠设置已保存" else (ok.exceptionOrNull()?.friendly() ?: "操作失败"),
                )
            }
        }
    }

    fun updateWifi(ssid: String, password: String, maxStations: Int, broadcast: Boolean, isolate: Boolean) {
        val client = api ?: return
        val authMode = _state.value.wifi?.authMode?.ifBlank { "WPA2PSK" } ?: "WPA2PSK"
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    client.updateWifi(ssid, password, authMode, maxStations, broadcast, isolate)
                }
            }
            if (ok.isSuccess) {
                _state.update { it.copy(busy = false, message = "WiFi 设置已保存，设备会重启无线") }
                delay(2500)
                refreshOnce()
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        message = ok.exceptionOrNull()?.friendly() ?: "保存失败",
                    )
                }
            }
        }
    }

    fun switchSim(slot: Int) {
        val client = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { withContext(Dispatchers.IO) { client.switchSim(slot) } }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) {
                        "已切换到卡${slot + 1}，设备正在重新注册网络（约 30 秒）"
                    } else {
                        result.exceptionOrNull()?.friendly() ?: "切换失败"
                    },
                )
            }
        }
    }

    fun setSmartSwitch(enabled: Boolean) {
        action(if (enabled) "已开启双卡智能切换" else "已关闭双卡智能切换") {
            it.setSmartSwitch(enabled)
        }
    }

    fun blockDevice(device: ClientDevice) {
        val client = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    client.blockDevice(device.mac, device.displayName)
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) {
                        "${device.displayName} 已加入黑名单"
                    } else {
                        result.exceptionOrNull()?.friendly() ?: "操作失败"
                    },
                )
            }
            if (result.isSuccess) {
                delay(800)
                refreshOnce()
            }
        }
    }

    fun unblockDevice(mac: String) {
        val client = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { withContext(Dispatchers.IO) { client.unblockDevice(mac) } }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) "已解除拉黑" else (result.exceptionOrNull()?.friendly() ?: "操作失败"),
                )
            }
            if (result.isSuccess) {
                delay(800)
                refreshOnce()
            }
        }
    }

    fun renameDevice(mac: String, name: String) {
        val client = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                withContext(Dispatchers.IO) { client.renameDevice(mac, name) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) "名称已更新" else (result.exceptionOrNull()?.friendly() ?: "操作失败"),
                )
            }
            if (result.isSuccess) {
                delay(600)
                refreshOnce()
            }
        }
    }

    fun reboot() {
        val client = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { withContext(Dispatchers.IO) { client.reboot() } }
            _state.update { it.copy(busy = false, online = false, message = "设备正在重启，请稍候") }
        }
    }

    fun logout() {
        val client = api
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client?.logout() } }
            pollJob?.cancel()
            api = null
            val context = getApplication<Application>()
            SecureStore.removeSecret(context, "password")
            _state.update {
                it.copy(
                    phase = Phase.Login,
                    status = null,
                    wifi = null,
                    devices = emptyList(),
                    online = false,
                    savedPassword = null,
                    passwordInput = "",
                    remember = false,
                )
            }
        }
    }

    private fun action(successMessage: String, block: suspend (ZteApi) -> Unit) {
        val client = api ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { block(client) }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) successMessage
                    else (result.exceptionOrNull()?.friendly() ?: "操作失败"),
                )
            }
            if (result.isSuccess) {
                delay(700)
                refreshOnce()
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

private fun Throwable.friendly(): String = when (this) {
    is SocketTimeoutException -> "设备响应超时，请稍后重试"
    is ConnectException, is UnknownHostException -> "无法连接设备，请检查是否已连接随身WiFi"
    is IOException -> "网络错误：${message ?: "连接中断"}"
    else -> message ?: "操作失败"
}
