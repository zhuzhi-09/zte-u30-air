package com.zhuzhi.ufi.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Client for the ZTE U30 Air web UI ("goform" JSON API).
 *
 * Observed behaviour of firmware U30AirV1.0.0B33:
 *  - every request must carry a Referer pointing at the device root, otherwise the
 *    server answers {"Error":"none secure connection"}
 *  - a JSESSIONID cookie (path /goform) keeps the session
 *  - login = SHA256_HEX( SHA256_HEX(password) + LD ) where LD is a per-session nonce
 *  - responses are JSON; missing fields are simply absent
 */
class ZteApi(val baseUrl: String) {

    private val cookies = object : CookieJar {
        private val store = mutableMapOf<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val root: HttpUrl = baseUrl.toHttpUrl()

    private var rd0: String = ""
    private var rd1: String = ""

    private fun request(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Referer", "$baseUrl/")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Accept", "application/json, text/javascript, */*; q=0.01")

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (_: Exception) {
        JSONObject()
    }

    suspend fun get(vararg cmds: String): JSONObject = withContext(Dispatchers.IO) {
        val url = root.newBuilder()
            .addPathSegments("goform/goform_get_cmd_process")
            .addQueryParameter("isTest", "false")
            .addQueryParameter("cmd", cmds.joinToString(","))
            .addQueryParameter("multi_data", "1")
            .build()
        client.newCall(request(url).get().build()).execute().use { response ->
            parse(response.body?.string().orEmpty())
        }
    }

    /**
     * The device requires an AD token on every set_cmd_process call. It is derived from the
     * firmware versions and the *current* RD nonce, so RD must be fetched right before use.
     * Without it the device answers 200 with an empty body and silently ignores the write.
     */
    private suspend fun adToken(): String {
        if (rd0.isBlank() || rd1.isBlank()) {
            val language = get("Language", "cr_version", "wa_inner_version")
            rd0 = language.optString("wa_inner_version")
            rd1 = language.optString("cr_version")
        }
        val rd = get("RD").optString("RD")
        val first = sha256Hex(rd0 + rd1)
        return sha256Hex(first + rd)
    }

    suspend fun post(goformId: String, params: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("isTest", "false")
                .add("goformId", goformId)
            if (goformId != "LOGIN") {
                form.add("AD", adToken())
            }
            params.forEach { (key, value) -> form.add(key, value) }
            val url = root.newBuilder()
                .addPathSegments("goform/goform_set_cmd_process")
                .build()
            client.newCall(request(url).post(form.build()).build()).execute().use { response ->
                parse(response.body?.string().orEmpty())
            }
        }

    /** Like [post], but treats an empty body or a non-"success" result as a failure. */
    suspend fun postChecked(goformId: String, params: Map<String, String> = emptyMap()): JSONObject {
        val response = post(goformId, params)
        val result = response.optString("result")
        if (response.length() == 0 || (result.isNotEmpty() && result != "success")) {
            throw IOException("设备拒绝了该操作（${result.ifBlank { "无响应" }}）")
        }
        return response
    }

    suspend fun login(password: String): LoginResult = withContext(Dispatchers.IO) {
        val ld = get("LD").optString("LD")
        if (ld.isBlank()) {
            return@withContext LoginResult.Failure("无法从设备获取登录凭据，请确认已连接随身WiFi")
        }
        val first = sha256Hex(password).uppercase()
        val token = sha256Hex(first + ld).uppercase()
        val response = post("LOGIN", mapOf("password" to token))
        when (response.optString("result")) {
            "0", "4" -> LoginResult.Success
            "3" -> LoginResult.Failure("密码错误")
            "2" -> LoginResult.Failure("设备已有其他登录会话，请稍后重试")
            "5" -> LoginResult.Failure("设备当前不允许登录")
            else -> LoginResult.Failure("登录失败（${response.optString("result").ifBlank { "无响应" }}）")
        }
    }

    suspend fun logout() {
        runCatching { post("LOGOUT") }
    }

    suspend fun fetchStatus(): Status {
        val json = get(*STATUS_CMDS)
        val type = json.optString("network_type")
        val rsrp = when {
            type.contains("5G", ignoreCase = true) || type.contains("NR", ignoreCase = true) ->
                json.optIntOrNull("Z5g_rsrp")
            type.contains("LTE", ignoreCase = true) || type.contains("4G", ignoreCase = true) ->
                json.optIntOrNull("network_lte_rsrp")
            else -> json.optIntOrNull("network_lte_rsrp")
                ?: json.optIntOrNull("network_rscp")
                ?: json.optIntOrNull("lte_rssi")
        }
        val bars = json.optIntOrNull("network_signalbar")
            ?: json.optIntOrNull("signalbar") ?: 0
        val battery = json.optIntOrNull("battery_vol_percent")
            ?: json.optIntOrNull("battery_value")
        return Status(
            networkType = type.ifBlank { "—" },
            provider = json.optString("network_provider").ifBlank {
                json.optString("network_provider_fullname")
            },
            connected = json.optString("ppp_status").contains("connected"),
            signalBars = bars,
            rsrp = rsrp,
            batteryPercent = battery,
            charging = json.optString("battery_charging") == "1",
            monthlyRx = json.optLong("flux_monthly_rx_bytes"),
            monthlyTx = json.optLong("flux_monthly_tx_bytes"),
            monthlySeconds = json.optLong("flux_monthly_time"),
            rxSpeed = json.optLong("flux_realtime_rx_thrpt"),
            txSpeed = json.optLong("flux_realtime_tx_thrpt"),
            wanIp = json.optString("wan_ipaddr"),
            ipv6 = json.optString("ipv6_wan_ipaddr"),
            firmware = json.optString("wa_inner_version"),
            imei = json.optString("imei"),
            iccid = json.optString("sim_iccid"),
            simState = json.optString("mc_modem_main_state"),
            simSlot = json.optIntOrNull("sim_slot"),
            smartSwitch = json.optString("dual_sim_smart_switch").let {
                if (it.isBlank()) null else it == "1"
            },
            loggedIn = json.optString("loginfo") == "ok",
        )
    }

    suspend fun fetchWifi(): WifiInfo {
        val module = get("queryWiFiModuleSwitch").optString("WiFiModuleSwitch")
        val list = get("queryAccessPointInfo").optJSONArray("ResponseList")
        val ap = if (list != null && list.length() > 0) list.optJSONObject(0) else null
        val password = ap?.optString("Password").orEmpty().let {
            if (it.isBlank()) "" else runCatching {
                String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
        }
        return WifiInfo(
            ssid = ap?.optString("SSID").orEmpty(),
            password = password,
            authMode = ap?.optString("AuthMode").orEmpty(),
            enabled = module.let { if (it.isBlank()) null else it == "1" },
            maxStations = ap?.optInt("ApMaxStationNumber") ?: 0,
            broadcast = ap?.optString("ApBroadcastDisabled") != "1",
            isolate = ap?.optString("ApIsolate") == "1",
        )
    }

    suspend fun fetchDevices(): List<ClientDevice> {
        val list = get("station_list").optJSONArray("station_list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                add(
                    ClientDevice(
                        mac = item.optString("mac_addr"),
                        hostname = item.optString("hostname"),
                        ip = item.optString("ip_addr"),
                    )
                )
            }
        }
    }

    suspend fun switchSim(slot: Int) {
        postChecked("SET_SIM_SLOT", mapOf("sim_slot" to slot.toString()))
    }

    suspend fun setSmartSwitch(enabled: Boolean) {
        postChecked(
            "SET_DUAL_SIM_SMART_SWITCH",
            mapOf("dual_sim_smart_switch" to if (enabled) "1" else "0"),
        )
    }

    suspend fun fetchAcl(): AclInfo {
        val json = get("queryDeviceAccessControlList")
        return AclInfo(
            mode = json.optString("AclMode").ifBlank { "0" },
            blackMacs = splitList(json.optString("BlackMacList")),
            blackNames = splitList(json.optString("BlackNameList")),
            whiteMacs = splitList(json.optString("WhiteMacList")),
            whiteNames = splitList(json.optString("WhiteNameList")),
        )
    }

    suspend fun blockDevice(mac: String, name: String) {
        val acl = fetchAcl()
        if (acl.isBlocked(mac)) return
        writeAcl(
            acl.copy(
                blackMacs = acl.blackMacs + mac,
                blackNames = acl.blackNames + name,
            )
        )
    }

    suspend fun unblockDevice(mac: String) {
        val acl = fetchAcl()
        val index = acl.blackMacs.indexOfFirst { it.equals(mac, ignoreCase = true) }
        if (index < 0) return
        writeAcl(
            acl.copy(
                blackMacs = acl.blackMacs.filterIndexed { i, _ -> i != index },
                blackNames = acl.blackNames.filterIndexed { i, _ -> i != index },
            )
        )
    }

    suspend fun renameDevice(mac: String, hostname: String) {
        postChecked("EDIT_HOSTNAME", mapOf("mac" to mac, "hostname" to hostname))
    }

    private suspend fun writeAcl(acl: AclInfo) {
        postChecked(
            "setDeviceAccessControlList",
            mapOf(
                "AclMode" to "2",
                "WhiteMacList" to joinList(acl.whiteMacs),
                "WhiteNameList" to joinList(acl.whiteNames),
                "BlackMacList" to joinList(acl.blackMacs),
                "BlackNameList" to joinList(acl.blackNames),
            ),
        )
    }

    private fun splitList(raw: String): List<String> =
        raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    private fun joinList(values: List<String>): String =
        if (values.isEmpty()) "" else values.joinToString(";") + ";"

    suspend fun reboot() {
        post("REBOOT_DEVICE")
    }

    suspend fun setWifiEnabled(enabled: Boolean) {
        postChecked("switchWiFiModule", mapOf("SwitchOption" to if (enabled) "1" else "0"))
    }

    suspend fun setIndicatorLight(enabled: Boolean) {
        postChecked("INDICATOR_LIGHT_SETTING", mapOf("indicator_light_switch" to if (enabled) "1" else "0"))
    }

    suspend fun setSleepMinutes(minutes: Int) {
        postChecked("SET_WIFI_SLEEP_INFO", mapOf("sleep_sysIdleTimeToSleep" to minutes.toString()))
    }

    suspend fun getIndicatorLight(): Boolean =
        get("indicator_light_switch").optString("indicator_light_switch") != "0"

    suspend fun getSleepMinutes(): Int =
        get("sleep_sysIdleTimeToSleep").optString("sleep_sysIdleTimeToSleep").toIntOrNull() ?: 0

    suspend fun updateWifi(
        ssid: String,
        password: String,
        authMode: String,
        maxStations: Int,
        broadcast: Boolean,
        isolate: Boolean,
    ) {
        val cipher = if (authMode == "WPAPSKWPA2PSK") "TKIPCCMP" else "CCMP"
        postChecked(
            "SET_WIFI_SSID1_SETTINGS",
            buildMap {
                put("ssid", ssid)
                put("broadcastSsidEnabled", if (broadcast) "1" else "0")
                put("MAX_Access_num", maxStations.toString())
                put("security_mode", authMode)
                put("cipher", cipher)
                put("security_shared_mode", cipher)
                put("NoForwarding", if (isolate) "1" else "0")
                if (password.isNotBlank()) {
                    put("passphrase", Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP))
                }
            }
        )
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    companion object {
        private val STATUS_CMDS = arrayOf(
            "network_type",
            "network_signalbar",
            "signalbar",
            "Z5g_rsrp",
            "network_lte_rsrp",
            "network_rscp",
            "lte_rssi",
            "network_provider",
            "network_provider_fullname",
            "network_simcard_roam",
            "ppp_status",
            "mc_modem_main_state",
            "opms_wan_mode",
            "opms_wan_auto_mode",
            "flux_monthly_tx_bytes",
            "flux_monthly_rx_bytes",
            "flux_monthly_time",
            "flux_realtime_tx_thrpt",
            "flux_realtime_rx_thrpt",
            "battery_vol_percent",
            "battery_charging",
            "battery_value",
            "battery_temperature_level",
            "wifi_onoff_state",
            "wifi_chip1_ssid1_ssid",
            "SSID1",
            "wan_ipaddr",
            "ipv6_wan_ipaddr",
            "loginfo",
            "sim_iccid",
            "sim_slot",
            "dual_sim_smart_switch",
            "imei",
            "wa_inner_version",
            "hardware_version",
            "new_version_state",
        )

        private val CANDIDATES = listOf(
            "192.168.0.1",
            "192.168.1.1",
            "192.168.8.1",
            "192.168.9.1",
            "192.168.100.1",
            "192.168.43.1",
        )

        /** A tiny blocking probe used during discovery; returns true for ZTE web UIs. */
        fun probe(host: String, timeoutMs: Long = 1500): Boolean {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val url = "http://$host/goform/goform_get_cmd_process?isTest=false&cmd=Language&multi_data=1".toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .header("Referer", "http://$host/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    body.contains("\"Language\"")
                }
            } catch (_: Exception) {
                false
            }
        }

        /** Best-effort discovery: the current gateway first, then common device addresses. */
        fun discover(gateway: String?): String? {
            val ordered = buildList {
                gateway?.let { if (!contains(it)) add(it) }
                CANDIDATES.forEach { if (!contains(it)) add(it) }
            }
            return ordered.firstOrNull { probe(it) }
        }
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    val raw = optString(key)
    if (raw.isBlank() || raw == "null") return null
    return raw.toIntOrNull()
}
