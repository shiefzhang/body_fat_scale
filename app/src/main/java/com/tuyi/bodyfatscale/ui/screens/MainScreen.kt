package com.tuyi.bodyfatscale.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuyi.bodyfatscale.ble.BleDeviceInfo
import com.tuyi.bodyfatscale.ble.ScaleConnectionService
import com.tuyi.bodyfatscale.model.BodyComposition

/**
 * 主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ScaleViewModel = viewModel(),
    onRequestPermission: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("途一智能体脂秤", fontWeight = FontWeight.Bold)
                },
                actions = {
                    // 用户资料设置
                    IconButton(onClick = { viewModel.toggleUserProfileDialog() }) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = "个人设置"
                        )
                    }
                    // 连接状态指示
                    if (uiState.connectionState == ScaleConnectionService.ConnectionState.CONNECTED) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(
                                Icons.Filled.BluetoothConnected,
                                contentDescription = "已连接",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 连接状态栏
            ConnectionStatusBar(
                connectionState = uiState.connectionState,
                connectedDevice = uiState.connectedDevice,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 错误消息
            uiState.errorMessage?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearError() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        // 如果包含位置服务关键词，显示"去开启"按钮
                        if (error.contains("位置服务") || error.contains("位置信息")) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onOpenLocationSettings,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("去开启位置服务", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // 主要内容区域
            when (uiState.connectionState) {
                ScaleConnectionService.ConnectionState.CONNECTED -> {
                    // 已连接 - 显示测量结果
                    MeasurementResult(
                        bodyComposition = uiState.bodyComposition,
                        scaleMeasurement = uiState.scaleMeasurement,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    // 扫描/未连接状态
                    ScanSection(
                        isScanning = uiState.isScanning,
                        devices = uiState.scannedDevices,
                        hasPermission = uiState.hasBluetoothPermission,
                        scanCallbackCount = uiState.scanCallbackCount,
                        diagnostics = uiState.diagnostics,
                        onStartScan = { viewModel.startScan() },
                        onStopScan = { viewModel.stopScan() },
                        onConnect = { device -> viewModel.connectToDevice(device) },
                        onRequestPermission = onRequestPermission,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 底部诊断栏
            if (uiState.connectionState != ScaleConnectionService.ConnectionState.CONNECTED &&
                uiState.isScanning && uiState.scanCallbackCount == 0) {
                Text(
                    text = "扫描中... 回调: ${uiState.scanCallbackCount} | 设备: ${uiState.scannedDevices.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }

    // 用户资料对话框
    if (uiState.showUserProfileDialog) {
        UserProfileDialog(
            currentProfile = uiState.userProfile,
            onSave = { height, age, isMale ->
                viewModel.updateUserProfile(height, age, isMale)
                viewModel.toggleUserProfileDialog()
            },
            onDismiss = { viewModel.toggleUserProfileDialog() }
        )
    }
}

/**
 * 连接状态指示条
 */
@Composable
private fun ConnectionStatusBar(
    connectionState: ScaleConnectionService.ConnectionState,
    connectedDevice: BleDeviceInfo?,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (connectionState) {
        ScaleConnectionService.ConnectionState.DISCONNECTED -> "未连接" to Color.Gray
        ScaleConnectionService.ConnectionState.CONNECTING -> "连接中..." to Color(0xFFF9AB00)
        ScaleConnectionService.ConnectionState.CONNECTED -> "已连接" to Color(0xFF34A853)
        ScaleConnectionService.ConnectionState.DISCONNECTING -> "断开中..." to Color.Gray
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
        if (connectedDevice != null) {
            Text(
                text = " | ${connectedDevice.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 扫描设备区域
 */
@Composable
private fun ScanSection(
    isScanning: Boolean,
    devices: List<BleDeviceInfo>,
    hasPermission: Boolean,
    scanCallbackCount: Int,
    diagnostics: String,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDeviceInfo) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 扫描按钮
        Button(
            onClick = {
                if (hasPermission) {
                    if (isScanning) onStopScan() else onStartScan()
                } else {
                    onRequestPermission()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("停止扫描")
            } else {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (hasPermission) "搜索体脂秤" else "需要蓝牙权限"
                )
            }
        }

        // 设备列表
        if (devices.isEmpty() && !isScanning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "尚未发现设备",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "请确认：\n1. 手机蓝牙已开启\n2. 体脂秤已开机（站上去激活）\n3. 手机靠近体脂秤（30cm内）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (devices.isEmpty() && isScanning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "正在扫描 BLE 设备...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scanCallbackCount == 0) {
                        Text(
                            "扫描已启动，但尚未收到任何广播。\n请确认：\n" +
                            "1. 手机蓝牙已开启\n" +
                            "2. 手机位置服务已开启（部分手机需要）\n" +
                            "3. 体脂秤已激活（用脚轻踩秤面）\n" +
                            "4. 手机靠近体脂秤（30cm内）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            "已收到 $scanCallbackCount 个广播信号...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            val scaleDevices = devices.filter { it.isPossibleScale }
            val otherDevices = devices.filter { !it.isPossibleScale }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 体脂秤设备分区
                if (scaleDevices.isNotEmpty()) {
                    item {
                        Text(
                            "体脂秤设备 (${scaleDevices.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(scaleDevices) { device ->
                        DeviceCard(
                            deviceInfo = device,
                            isScaleBadge = true,
                            onClick = { onConnect(device) }
                        )
                    }
                }

                // 其他 BLE 设备分区
                item {
                    Text(
                        if (scaleDevices.isNotEmpty()) "其他设备 (${otherDevices.size})" else "附近 BLE 设备 (${otherDevices.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = if (scaleDevices.isNotEmpty()) 8.dp else 4.dp)
                    )
                }
                items(otherDevices) { device ->
                    DeviceCard(
                        deviceInfo = device,
                        isScaleBadge = false,
                        onClick = { onConnect(device) }
                    )
                }
            }
        }
    }
}

/**
 * 设备卡片
 */
@Composable
private fun DeviceCard(
    deviceInfo: BleDeviceInfo,
    isScaleBadge: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 设备图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isScaleBadge) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isScaleBadge) Icons.Filled.MonitorWeight else Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = if (isScaleBadge) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = deviceInfo.name.ifEmpty { "未知设备" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (isScaleBadge) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "体脂秤",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = deviceInfo.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 信号强度
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.SignalCellularAlt,
                    contentDescription = null,
                    tint = when {
                        deviceInfo.rssi > -60 -> Color(0xFF34A853)
                        deviceInfo.rssi > -80 -> Color(0xFFF9AB00)
                        else -> Color(0xFFD93025)
                    },
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "${deviceInfo.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 测量结果展示
 */
@Composable
private fun MeasurementResult(
    bodyComposition: BodyComposition?,
    scaleMeasurement: com.tuyi.bodyfatscale.model.ScaleMeasurement?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        if (bodyComposition == null) {
            // 等待测量
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.DirectionsWalk,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "请站上体脂秤",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "等待测量完成...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 实时体重显示
                if (scaleMeasurement != null && scaleMeasurement.weightKg > 0) {
                    Text(
                        text = "${"%.1f".format(scaleMeasurement.weightKg)} kg",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (scaleMeasurement.isStabilized) {
                        Text(
                            "✓ 已稳定",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF34A853)
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        } else {
            // 完整测量结果
            BodyCompositionResult(
                bodyComposition = bodyComposition,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        }
    }
}

/**
 * 体成分结果展示
 */
@Composable
private fun BodyCompositionResult(
    bodyComposition: BodyComposition,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 体重大数字显示
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "体重",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${"%.1f".format(bodyComposition.weightKg)} kg",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "BMI: ${bodyComposition.bmi}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Text(
                            "去脂: ${"%.1f".format(bodyComposition.leanBodyMassKg)} kg",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // 指标网格
        item {
            MetricGrid(bodyComposition = bodyComposition)
        }

        // 底部空白
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/**
 * 指标网格
 */
@Composable
private fun MetricGrid(bodyComposition: BodyComposition) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "体脂率",
                value = "${bodyComposition.bodyFatPercent}%",
                icon = Icons.Outlined.Face,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "水分率",
                value = "${bodyComposition.bodyWaterPercent}%",
                icon = Icons.Outlined.WaterDrop,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "肌肉量",
                value = "${"%.1f".format(bodyComposition.muscleMassKg)} kg",
                icon = Icons.Outlined.FitnessCenter,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "骨量",
                value = "${"%.1f".format(bodyComposition.boneMassKg)} kg",
                icon = Icons.Outlined.Biotech,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "内脏脂肪",
                value = "${bodyComposition.visceralFatLevel}",
                icon = Icons.Outlined.FavoriteBorder,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "基础代谢",
                value = "${bodyComposition.basalMetabolicRate} kcal",
                icon = Icons.Outlined.LocalFireDepartment,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "蛋白质率",
                value = "${bodyComposition.proteinPercent}%",
                icon = Icons.Outlined.Science,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "BMI",
                value = "${bodyComposition.bmi}",
                icon = Icons.Outlined.MonitorWeight,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 单个指标卡片
 */
@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 用户资料设置对话框
 */
@Composable
private fun UserProfileDialog(
    currentProfile: com.tuyi.bodyfatscale.model.UserProfile,
    onSave: (heightCm: Float, age: Int, isMale: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var height by remember { mutableStateOf(currentProfile.heightCm.toString()) }
    var age by remember { mutableStateOf(currentProfile.age.toString()) }
    var isMale by remember { mutableStateOf(currentProfile.isMale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("个人设置", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "设置个人参数以提高体成分计算精度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 身高
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("身高 (cm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 年龄
                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it.filter { c -> c.isDigit() } },
                    label = { Text("年龄") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 性别
                Text("性别", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = isMale,
                        onClick = { isMale = true },
                        label = { Text("男") },
                        leadingIcon = if (isMale) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = !isMale,
                        onClick = { isMale = false },
                        label = { Text("女") },
                        leadingIcon = if (!isMale) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val heightVal = height.toFloatOrNull() ?: currentProfile.heightCm
                    val ageVal = age.toIntOrNull() ?: currentProfile.age
                    onSave(heightVal, ageVal, isMale)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
