package com.tuyi.bodyfatscale.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuyi.bodyfatscale.ble.BleScanner
import com.tuyi.bodyfatscale.ble.BleDeviceInfo
import com.tuyi.bodyfatscale.ble.BodyCompositionCalculator
import com.tuyi.bodyfatscale.ble.ScaleConnectionService
import com.tuyi.bodyfatscale.ble.ScaleDataParser
import com.tuyi.bodyfatscale.model.BodyComposition
import com.tuyi.bodyfatscale.model.ScaleMeasurement
import com.tuyi.bodyfatscale.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 体脂秤主 ViewModel
 *
 * 管理 BLE 扫描、连接、数据解析和 UI 状态
 */
class ScaleViewModel : ViewModel() {

    companion object {
        private const val TAG = "ScaleViewModel"
    }

    // ---- UI 状态 ----

    /** 应用界面状态 */
    data class UiState(
        val isScanning: Boolean = false,
        val connectionState: ScaleConnectionService.ConnectionState =
            ScaleConnectionService.ConnectionState.DISCONNECTED,
        val scannedDevices: List<BleDeviceInfo> = emptyList(),
        val connectedDevice: BleDeviceInfo? = null,
        val scaleMeasurement: ScaleMeasurement? = null,
        val bodyComposition: BodyComposition? = null,
        val userProfile: UserProfile = UserProfile(),
        val errorMessage: String? = null,
        val showUserProfileDialog: Boolean = false,
        val hasBluetoothPermission: Boolean = false,
        /** 诊断信息：BLE状态、回调次数等 */
        val diagnostics: String = "",
        /** 扫描回调收到的总次数（0 表示扫描未反馈任何设备） */
        val scanCallbackCount: Int = 0
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var bleScanner: BleScanner? = null
    private var connectionService: ScaleConnectionService? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    /**
     * 初始化 ViewModel
     */
    fun initialize(context: Context, bluetoothAdapter: BluetoothAdapter?) {
        this.bluetoothAdapter = bluetoothAdapter
        bleScanner = BleScanner(bluetoothAdapter)
        connectionService = ScaleConnectionService(context.applicationContext)

        // 观察扫描结果
        viewModelScope.launch {
            bleScanner?.scannedDevices?.observeForever { devices ->
                _uiState.value = _uiState.value.copy(scannedDevices = devices)
            }
        }

        viewModelScope.launch {
            bleScanner?.isScanning?.observeForever { scanning ->
                _uiState.value = _uiState.value.copy(isScanning = scanning)
            }
        }

        viewModelScope.launch {
            bleScanner?.errorMessage?.observeForever { error ->
                if (error != null) {
                    _uiState.value = _uiState.value.copy(errorMessage = error)
                }
            }
        }

        // 观察诊断信息
        viewModelScope.launch {
            bleScanner?.diagnostics?.observeForever { text ->
                _uiState.value = _uiState.value.copy(diagnostics = text)
            }
        }

        // 观察扫描回调计数
        viewModelScope.launch {
            bleScanner?.scanCallbackCount?.observeForever { count ->
                _uiState.value = _uiState.value.copy(scanCallbackCount = count)
            }
        }

        // 观察连接状态
        viewModelScope.launch {
            connectionService?.connectionState?.observeForever { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }

        // 观察测量数据
        viewModelScope.launch {
            connectionService?.scaleMeasurement?.observeForever { measurement ->
                if (measurement.isStabilized && measurement.weightKg > 0) {
                    _uiState.value = _uiState.value.copy(scaleMeasurement = measurement)
                    calculateBodyComposition(measurement)
                }
            }
        }

        // 观察错误
        viewModelScope.launch {
            connectionService?.errorMessage?.observeForever { error ->
                if (error != null) {
                    _uiState.value = _uiState.value.copy(errorMessage = error)
                }
            }
        }

        checkPermissions(context)
    }

    /**
     * 检查 BLE 权限
     */
    private fun checkPermissions(context: Context) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
        _uiState.value = _uiState.value.copy(hasBluetoothPermission = hasPermission)
    }

    fun updatePermissionStatus(hasPermission: Boolean) {
        _uiState.value = _uiState.value.copy(hasBluetoothPermission = hasPermission)
    }

    /**
     * 开始扫描设备
     */
    fun startScan() {
        bleScanner?.startScan()
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        bleScanner?.stopScan()
    }

    /**
     * 连接到选中的体脂秤设备
     */
    fun connectToDevice(deviceInfo: BleDeviceInfo) {
        stopScan()
        _uiState.value = _uiState.value.copy(
            connectedDevice = deviceInfo,
            errorMessage = null
        )

        // 通过 BluetoothAdapter 获取 BluetoothDevice
        val device = bluetoothAdapter?.getRemoteDevice(deviceInfo.address)
        if (device != null) {
            connectionService?.connect(device)
        } else {
            _uiState.value = _uiState.value.copy(
                errorMessage = "无法获取设备: ${deviceInfo.address}"
            )
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        connectionService?.disconnect()
        _uiState.value = _uiState.value.copy(
            connectedDevice = null,
            scaleMeasurement = null,
            bodyComposition = null
        )
    }

    /**
     * 根据测量数据计算体成分
     */
    private fun calculateBodyComposition(measurement: ScaleMeasurement) {
        val profile = _uiState.value.userProfile
        val composition = BodyCompositionCalculator.calculate(
            weightKg = measurement.weightKg,
            impedance = measurement.impedance,
            profile = profile
        )
        _uiState.value = _uiState.value.copy(bodyComposition = composition)
    }

    /**
     * 显示/隐藏用户资料对话框
     */
    fun toggleUserProfileDialog() {
        _uiState.value = _uiState.value.copy(
            showUserProfileDialog = !_uiState.value.showUserProfileDialog
        )
    }

    /**
     * 更新用户资料
     */
    fun updateUserProfile(heightCm: Float, age: Int, isMale: Boolean) {
        val profile = UserProfile(
            heightCm = heightCm.coerceIn(100f, 250f),
            age = age.coerceIn(10, 120),
            isMale = isMale
        )
        _uiState.value = _uiState.value.copy(userProfile = profile)

        // 如果有测量数据，重新计算
        _uiState.value.scaleMeasurement?.let { measurement ->
            if (measurement.weightKg > 0) {
                calculateBodyComposition(measurement)
            }
        }
    }

    /**
     * 显示错误消息
     */
    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    /**
     * 显示温和提示消息（与 showError 共用显示位，语义区分）
     */
    fun showMessageTip(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        connectionService?.cleanup()
    }
}
