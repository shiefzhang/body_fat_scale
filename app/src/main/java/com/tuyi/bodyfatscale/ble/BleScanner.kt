package com.tuyi.bodyfatscale.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * BLE 设备扫描器
 *
 * 扫描发现附近的 BLE 设备，标注可能的体脂秤设备。
 * 不再过滤设备——显示全部 BLE 设备，体脂秤打 badge 标记。
 *
 * ⚠️ 排坑记录：
 * 1. 之前 isScaleDevice() 过滤条件写反导致有制造商数据的设备全被排除
 * 2. 某些品牌手机对 null filters 支持有问题，改用 emptyList()
 * 3. 某些手机需要位置服务开启才能返回 BLE 扫描结果
 */
class BleScanner(private val bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "BleScanner"

        val SCALE_NAME_KEYWORDS = listOf(
            "MX80", "Yoda", "yoda", "YODA", "途一", "TUYI",
            "MIBCS", "Mi Scale", "小米体脂秤", "体脂秤", "Scale",
            "Body", "body", "HEALTH"
        )

        val SCALE_SERVICE_UUIDS = setOf(
            "0000181b-0000-1000-8000-00805f9b34fb",
            "0000181d-0000-1000-8000-00805f9b34fb",
            "0000fee0-0000-1000-8000-00805f9b34fb",
            "0000fee7-0000-1000-8000-00805f9b34fb"
        )
    }

    private val _scannedDevices = MutableLiveData<List<BleDeviceInfo>>(emptyList())
    val scannedDevices: LiveData<List<BleDeviceInfo>> = _scannedDevices

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    /** 扫描健康状态：收到多少次扫描回调 */
    private val _scanCallbackCount = MutableLiveData(0)
    val scanCallbackCount: LiveData<Int> = _scanCallbackCount

    /** 系统诊断信息 */
    private val _diagnostics = MutableLiveData("")
    val diagnostics: LiveData<String> = _diagnostics

    private val handler = Handler(Looper.getMainLooper())
    private val deviceMap = mutableMapOf<String, BleDeviceInfo>()
    private var callbackCount = 0

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            callbackCount++
            _scanCallbackCount.postValue(callbackCount)

            val device = result.device
            val name = device.name ?: ""
            val address = device.address
            val rssi = result.rssi

            Log.d(TAG, "onScanResult #$callbackCount: name='$name' addr=$address rssi=$rssi")

            val info = BleDeviceInfo(
                name = name,
                address = address,
                rssi = rssi,
                scanRecord = result.scanRecord?.bytes,
                isPossibleScale = isPossibleScale(result)
            )

            if (!deviceMap.containsKey(address) || deviceMap[address]!!.rssi < rssi) {
                deviceMap[address] = info
            }
            _scannedDevices.postValue(
                deviceMap.entries
                    .sortedByDescending { it.value.rssi }
                    .map { it.value }
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            if (results != null) {
                for (result in results) {
                    callbackCount++
                    val device = result.device
                    val name = device.name ?: ""
                    val address = device.address
                    val rssi = result.rssi

                    val info = BleDeviceInfo(
                        name = name, address = address, rssi = rssi,
                        scanRecord = result.scanRecord?.bytes,
                        isPossibleScale = isPossibleScale(result)
                    )
                    if (!deviceMap.containsKey(address) || deviceMap[address]!!.rssi < rssi) {
                        deviceMap[address] = info
                    }
                }
                _scanCallbackCount.postValue(callbackCount)
                _scannedDevices.postValue(
                    deviceMap.entries.sortedByDescending { it.value.rssi }.map { it.value }
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val msg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行中"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支持 BLE 扫描"
                SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                else -> "扫描失败 (错误码: $errorCode)"
            }
            Log.e(TAG, "onScanFailed: $msg")
            _errorMessage.postValue(msg)
        }
    }

    /**
     * 判断是否可能是体脂秤设备（仅标记，不用于过滤）
     */
    @SuppressLint("MissingPermission")
    private fun isPossibleScale(result: ScanResult): Boolean {
        val name = result.device.name ?: ""
        if (SCALE_NAME_KEYWORDS.any { name.contains(it, ignoreCase = true) }) return true
        val record = result.scanRecord ?: return false
        val uuids = record.serviceUuids ?: emptyList()
        return uuids.any { uuid ->
            SCALE_SERVICE_UUIDS.any { uuidStr ->
                uuid.toString().regionMatches(0, uuidStr, 0, 16, ignoreCase = true)
            }
        }
    }

    /** 构建诊断信息 */
    private fun buildDiagnostics(): String {
        val adapter = bluetoothAdapter
        val sb = StringBuilder()
        sb.appendLine("[BLE诊断]")
        sb.appendLine("蓝牙适配器: ${if (adapter != null) "可用" else "不可用"}")
        sb.appendLine("蓝牙状态: ${if (adapter?.isEnabled == true) "已开启" else "未开启"}")
        sb.appendLine("BLE扫描器: ${if (adapter?.bluetoothLeScanner != null) "可用" else "不可用"}")
        sb.appendLine("已发现设备: ${deviceMap.size}")
        sb.appendLine("回调次数: $callbackCount")
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter ?: run {
            _errorMessage.postValue("手机不支持蓝牙或蓝牙适配器不可用")
            _diagnostics.postValue("蓝牙适配器为 null")
            return
        }

        if (!adapter.isEnabled) {
            _errorMessage.postValue("蓝牙未开启，请先打开蓝牙")
            _diagnostics.postValue("蓝牙状态: 关闭")
            return
        }

        val bleScanner = adapter.bluetoothLeScanner ?: run {
            _errorMessage.postValue("BLE 扫描器不可用（可能手机不支持 BLE）")
            _diagnostics.postValue("BLE 扫描器为 null")
            return
        }

        // 清除之前的数据
        deviceMap.clear()
        callbackCount = 0
        _scannedDevices.postValue(emptyList())
        _scanCallbackCount.postValue(0)

        // 使用 emptyList() 而不是 null（某些国产手机对 null 支持有问题）
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            // 先停止可能残留的扫描
            try {
                bleScanner.stopScan(scanCallback)
            } catch (_: Exception) {}

            bleScanner.startScan(filters, settings, scanCallback)
            _isScanning.postValue(true)
            _errorMessage.postValue(null)
            _diagnostics.postValue(buildDiagnostics())

            Log.d(TAG, "startScan() 调用成功, filters=emptyList()")

            // 3秒后更新诊断（看看是否有回调）
            handler.postDelayed({
                _diagnostics.postValue(buildDiagnostics())
                // 5秒后如果还是没有设备，提示用户
                if (deviceMap.isEmpty()) {
                    _errorMessage.postValue(
                        "没有发现 BLE 设备。请确认：\n" +
                        "1. 手机蓝牙已开启\n" +
                        "2. 手机位置服务已开启（部分手机需要）\n" +
                        "3. 体脂秤已激活（用脚轻踩秤面）\n" +
                        "4. 手机靠近体脂秤（30cm内）"
                    )
                }
            }, 5000)

            // 自动停止扫描（60秒超时）
            handler.postDelayed({
                stopScan()
            }, 60000)

        } catch (e: SecurityException) {
            val msg = "蓝牙权限不足: ${e.message}"
            Log.e(TAG, msg)
            _errorMessage.postValue(msg)
        } catch (e: Exception) {
            val msg = "启动扫描异常: ${e.message}"
            Log.e(TAG, msg)
            _errorMessage.postValue(msg)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            val adapter = bluetoothAdapter
            if (adapter?.isEnabled == true) {
                adapter.bluetoothLeScanner?.stopScan(scanCallback)
            }
        } catch (_: Exception) {}
        _isScanning.postValue(false)
        _scanCallbackCount.postValue(callbackCount)
        _diagnostics.postValue(buildDiagnostics())
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "stopScan() 完成, 共收到 $callbackCount 次回调, 发现 ${deviceMap.size} 个设备")
    }
}

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int,
    val scanRecord: ByteArray?,
    val isPossibleScale: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDeviceInfo) return false
        return address == other.address
    }

    override fun hashCode() = address.hashCode()
}
