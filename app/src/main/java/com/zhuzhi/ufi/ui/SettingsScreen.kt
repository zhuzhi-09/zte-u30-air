package com.zhuzhi.ufi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhuzhi.ufi.UiState
import com.zhuzhi.ufi.UfiViewModel

@Composable
fun SettingsScreen(state: UiState, vm: UfiViewModel) {
    var showWifiOff by remember { mutableStateOf(false) }
    var editSsid by remember { mutableStateOf(false) }
    var editPassword by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showReboot by remember { mutableStateOf(false) }
    var showSim by remember { mutableStateOf(false) }

    val wifi = state.wifi

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("无线网络") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column {
                    SwitchRow(
                        icon = Icons.Rounded.Wifi,
                        title = "WiFi 开关",
                        subtitle = if (wifi?.enabled == true) "已开启" else "已关闭",
                        checked = wifi?.enabled == true,
                        onCheckedChange = { enabled ->
                            if (enabled) vm.setWifiEnabled(true) else showWifiOff = true
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ValueRow(
                        icon = Icons.Rounded.Router,
                        title = "WiFi 名称",
                        value = wifi?.ssid?.ifBlank { "—" } ?: "—",
                        onClick = { editSsid = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ValueRow(
                        icon = Icons.Rounded.Lock,
                        title = "WiFi 密码",
                        value = if (wifi?.password.isNullOrBlank()) "—" else "••••••••",
                        onClick = { editPassword = true },
                    )
                }
            }
        }

        if (state.status?.simSlot != null) {
            item { SectionHeader("双卡") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column {
                        ValueRow(
                            icon = Icons.Rounded.SimCard,
                            title = "当前 SIM",
                            value = simLabel(state.status?.simSlot),
                            onClick = { showSim = true },
                        )
                        if (state.status?.smartSwitch != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            SwitchRow(
                                icon = Icons.Rounded.SwapHoriz,
                                title = "智能切换",
                                subtitle = if (state.status?.smartSwitch == true) "信号差时自动换卡" else "已关闭",
                                checked = state.status?.smartSwitch == true,
                                onCheckedChange = vm::setSmartSwitch,
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeader("设备") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column {
                    SwitchRow(
                        icon = Icons.Rounded.Lightbulb,
                        title = "指示灯",
                        subtitle = if (state.indicatorLight == true) "已开启" else "已关闭",
                        checked = state.indicatorLight == true,
                        onCheckedChange = vm::setIndicatorLight,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ValueRow(
                        icon = Icons.Rounded.Bedtime,
                        title = "休眠模式",
                        value = sleepLabel(state.sleepMinutes),
                        onClick = { showSleep = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ValueRow(
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = "重启设备",
                        value = "约 30 秒恢复",
                        onClick = { showReboot = true },
                        danger = true,
                    )
                }
            }
        }

        item { SectionHeader("关于") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    InfoRow("设备地址", state.host.ifBlank { "—" })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    InfoRow("固件版本", state.status?.firmware?.ifBlank { "—" } ?: "—")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    InfoRow("IMEI", state.status?.imei?.ifBlank { "—" } ?: "—")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    InfoRow("SIM ICCID", state.status?.iccid?.ifBlank { "—" } ?: "—")
                }
            }
        }

        item {
            OutlinedButton(
                onClick = vm::logout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("退出登录并清除密码")
            }
        }

        item {
            Row(modifier = Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "数据直接来自设备 WebUI，不经过中兴云端",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showWifiOff) {
        AlertDialog(
            onDismissRequest = { showWifiOff = false },
            title = { Text("关闭 WiFi？") },
            text = { Text("关闭后本机会立即断开与设备的连接，需要靠近设备通过实体按键或重新开机才能恢复。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showWifiOff = false
                    vm.setWifiEnabled(false)
                }) { Text("关闭", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showWifiOff = false }) { Text("取消") }
            },
        )
    }

    if (editSsid) {
        TextEditDialog(
            title = "修改 WiFi 名称",
            label = "SSID",
            initial = wifi?.ssid.orEmpty(),
            helper = "1-32 个字符，修改后设备会重启无线",
            validate = { it.isNotBlank() && it.length <= 32 },
            errorText = "名称不能为空且不超过 32 个字符",
            onDismiss = { editSsid = false },
            onConfirm = { value ->
                editSsid = false
                vm.updateWifi(
                    ssid = value,
                    password = wifi?.password.orEmpty(),
                    maxStations = wifi?.maxStations ?: 10,
                    broadcast = wifi?.broadcast ?: true,
                    isolate = wifi?.isolate ?: false,
                )
            },
        )
    }

    if (editPassword) {
        TextEditDialog(
            title = "修改 WiFi 密码",
            label = "新密码",
            initial = "",
            helper = "8-63 位字符，修改后需要重新连接 WiFi",
            validate = { it.length in 8..63 },
            errorText = "密码长度需为 8-63 位",
            keyboardType = KeyboardType.Password,
            onDismiss = { editPassword = false },
            onConfirm = { value ->
                editPassword = false
                vm.updateWifi(
                    ssid = wifi?.ssid.orEmpty(),
                    password = value,
                    maxStations = wifi?.maxStations ?: 10,
                    broadcast = wifi?.broadcast ?: true,
                    isolate = wifi?.isolate ?: false,
                )
            },
        )
    }

    if (showSleep) {
        val options = listOf(0, 5, 10, 15, 30)
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("无连接自动休眠") },
            text = {
                Column {
                    options.forEach { minutes ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSleep = false
                                    vm.setSleepMinutes(minutes)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.sleepMinutes == minutes,
                                onClick = {
                                    showSleep = false
                                    vm.setSleepMinutes(minutes)
                                },
                            )
                            Text(sleepLabel(minutes))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSleep = false }) { Text("取消") }
            },
        )
    }

    if (showSim) {
        val current = state.status?.simSlot ?: 0
        AlertDialog(
            onDismissRequest = { showSim = false },
            title = { Text("切换 SIM 卡") },
            text = {
                Column {
                    Text(
                        "切换后设备会重新注册网络，本机将短暂断网（约 30 秒），随后自动恢复。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    listOf(0, 1).forEach { slot ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSim = false
                                    if (slot != current) vm.switchSim(slot)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = current == slot,
                                onClick = {
                                    showSim = false
                                    if (slot != current) vm.switchSim(slot)
                                },
                            )
                            Text(simLabel(slot))
                            if (slot == current) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSim = false }) { Text("取消") }
            },
        )
    }

    if (showReboot) {
        AlertDialog(
            onDismissRequest = { showReboot = false },
            title = { Text("重启设备？") },
            text = { Text("设备将断开所有连接并在约 30 秒后恢复。确定重启吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showReboot = false
                    vm.reboot()
                }) { Text("重启", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showReboot = false }) { Text("取消") }
            },
        )
    }
}

private fun simLabel(slot: Int?): String = when (slot) {
    0 -> "卡 1"
    1 -> "卡 2"
    else -> "—"
}

private fun sleepLabel(minutes: Int?): String = when {
    minutes == null -> "—"
    minutes <= 0 -> "关闭"
    else -> "$minutes 分钟"
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    label: String,
    initial: String,
    helper: String,
    validate: (String) -> Boolean,
    errorText: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    val valid = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        touched = true
                    },
                    label = { Text(label) },
                    singleLine = true,
                    isError = touched && !valid,
                    supportingText = {
                        Text(if (touched && !valid) errorText else helper)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(value.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
