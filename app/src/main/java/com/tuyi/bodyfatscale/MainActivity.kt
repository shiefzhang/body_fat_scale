package com.tuyi.bodyfatscale

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.tuyi.bodyfatscale.ui.screens.MainScreen
import com.tuyi.bodyfatscale.ui.screens.ScaleViewModel
import com.tuyi.bodyfatscale.ui.theme.TuyiBodyFatScaleTheme

/**
 * 途一智能体脂秤 MX80 Android App
 *
 * 通过 BLE 连接体脂秤，读取体重和体成分数据
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: ScaleViewModel
    private var bluetoothAdapter: BluetoothAdapter? = null

    /** BLE 权限请求 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        Log.d(TAG, "权限结果: $permissions, 全部授权: $allGranted")
        viewModel.updatePermissionStatus(allGranted)
        if (allGranted) {
            // 权限已授权，检查蓝牙 + 提示位置 → 开始扫描
            ensureBluetoothThenScan()
        } else {
            viewModel.showError("蓝牙权限被拒绝，请在系统设置中手动开启")
        }
    }

    /** 蓝牙启用请求 */
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "蓝牙启用回调")
        // 用户刚开启了蓝牙 → 开始扫描
        checkPermissionAndScan()
    }

    /** 位置服务设置页面返回回调 */
    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "位置设置返回")
        // 用户可能刚刚开启了位置 → 尝试扫描
        checkPermissionAndScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[ScaleViewModel::class.java]

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        viewModel.initialize(this, bluetoothAdapter)

        setContent {
            TuyiBodyFatScaleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestPermission = { requestBluetoothPermissions() },
                        onOpenLocationSettings = { openLocationSettings() }
                    )
                }
            }
        }
    }

    // ====================== 扫描启动链路 ======================

    /**
     * 当用户点击"搜索体脂秤"时调用
     * 链路：权限 → 蓝牙 → 位置提示 → 扫描
     */
    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val needsRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest) {
            permissionLauncher.launch(permissions)
        } else {
            viewModel.updatePermissionStatus(true)
            // 权限已有 → 走蓝牙检查链路
            ensureBluetoothThenScan()
        }
    }

    /**
     * 检查权限，如果已授权则走蓝牙+位置+扫描链路
     */
    private fun checkPermissionAndScan() {
        if (!hasRequiredPermissions()) {
            viewModel.showError("蓝牙权限不足，请在系统设置中开启")
            return
        }
        viewModel.updatePermissionStatus(true)
        ensureBluetoothThenScan()
    }

    /**
     * 检查蓝牙 → 如果已开启则继续（位置提示+扫描）
     */
    private fun ensureBluetoothThenScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            // 蓝牙未开启，请求开启（用户确认后回调 → checkPermissionAndScan）
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                bluetoothEnableLauncher.launch(intent)
            } catch (_: Exception) {
                viewModel.showError("无法打开蓝牙，请在系统设置中手动开启")
            }
            return
        }

        // 蓝牙已开启 → 提示位置 + 开始扫描
        warnLocationAndScan()
    }

    /**
     * 非阻塞地提示位置服务（仅提示，不拦截扫描）
     */
    private fun warnLocationAndScan() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val isLocationEnabled = try {
            if (locationManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locationManager.isLocationEnabled
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                            @Suppress("DEPRECATION")
                            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                }
            } else {
                true // 无法获取 LocationManager，不阻塞
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查位置服务异常: ${e.message}")
            true // 异常不阻塞
        }

        if (!isLocationEnabled) {
            // 仅提示，不拦截扫描！
            viewModel.showMessageTip(
                "提示：部分手机需要开启「位置服务」才能搜索到 BLE 设备。\n" +
                "如果扫描不到设备，请在设置中开启位置信息。"
            )
        }

        // 无论如何都开始扫描
        viewModel.startScan()
    }

    /**
     * 打开位置服务设置
     */
    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        try {
            locationSettingsLauncher.launch(intent)
        } catch (_: Exception) {
            viewModel.showMessageTip("请在系统设置中手动开启位置服务")
        }
    }

    /**
     * 检查是否拥有 BLE 相关权限
     */
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
    }
}
