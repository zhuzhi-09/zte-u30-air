package com.zhuzhi.ufi.data

data class Status(
    val networkType: String = "",
    val provider: String = "",
    val connected: Boolean = false,
    val signalBars: Int = 0,
    val rsrp: Int? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val monthlyRx: Long = 0L,
    val monthlyTx: Long = 0L,
    val monthlySeconds: Long = 0L,
    val rxSpeed: Long = 0L,
    val txSpeed: Long = 0L,
    val wanIp: String = "",
    val ipv6: String = "",
    val firmware: String = "",
    val imei: String = "",
    val iccid: String = "",
    val simState: String = "",
    val simSlot: Int? = null,
    val smartSwitch: Boolean? = null,
    val loggedIn: Boolean = false,
) {
    val signalDbm: String get() = rsrp?.let { "$it dBm" } ?: "—"
}

data class WifiInfo(
    val ssid: String = "",
    val password: String = "",
    val authMode: String = "",
    val enabled: Boolean? = null,
    val maxStations: Int = 0,
    val broadcast: Boolean = true,
    val isolate: Boolean = false,
)

data class AclInfo(
    val mode: String = "0",
    val blackMacs: List<String> = emptyList(),
    val blackNames: List<String> = emptyList(),
    val whiteMacs: List<String> = emptyList(),
    val whiteNames: List<String> = emptyList(),
) {
    fun isBlocked(mac: String): Boolean = blackMacs.any { it.equals(mac, ignoreCase = true) }
}

data class ClientDevice(
    val mac: String,
    val hostname: String,
    val ip: String,
) {
    val displayName: String get() = hostname.ifBlank { "未知设备" }
}

sealed interface LoginResult {
    data object Success : LoginResult
    data class Failure(val message: String) : LoginResult
}
