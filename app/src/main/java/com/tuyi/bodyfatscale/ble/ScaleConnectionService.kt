package com.tuyi.bodyfatscale.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.tuyi.bodyfatscale.model.ScaleMeasurement
import java.util.UUID

/**
 * BLE 体脂秤连接与数据读取服务
 *
 * 处理与途一智能体脂秤 MX80 的 BLE 连接、特征值发现、
 * 数据通知订阅和数据解析。
 */
class ScaleConnectionService(private val context: Context) {

    /** 连接状态 */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    /** 标准 BLE 服务与特征值 UUID */
    companion object {
        // Body Composition Service (BLE 标准)
        val BODY_COMPOSITION_SERVICE = UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb")
        val BODY_COMPOSITION_MEASUREMENT = UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb")

        // Weight Scale Service (BLE 标准)
        val WEIGHT_SCALE_SERVICE = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb")
        val WEIGHT_MEASUREMENT = UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb")

        // Xiaomi / Huami custom service
        val HUAMI_CONFIG_SERVICE = UUID.fromString("00001530-0000-3512-2118-0009af100700")
        val SCALE_CONFIG = UUID.fromString("00001542-0000-3512-2118-0009af100700")
        val BODY_COMPOSITION_HISTORY = UUID.fromString("00002a2f-0000-3512-2118-0009af100700")

        // 通用
        val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 扫描超时
        private const val CONNECTION_TIMEOUT_MS = 15000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableLiveData(ConnectionState.DISCONNECTED)
    val connectionState: LiveData<ConnectionState> = _connectionState

    private val _scaleMeasurement = MutableLiveData<ScaleMeasurement>()
    val scaleMeasurement: LiveData<ScaleMeasurement> = _scaleMeasurement

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private var bluetoothGatt: BluetoothGatt? = null
    private var bodyCompCharacteristic: BluetoothGattCharacteristic? = null
    private var weightCharacteristic: BluetoothGattCharacteristic? = null
    private var historyCharacteristic: BluetoothGattCharacteristic? = null

    /** BLE GATT 回调 */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.postValue(ConnectionState.CONNECTED)
                    // 发现服务
                    handler.postDelayed({
                        gatt.discoverServices()
                    }, 300)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.postValue(ConnectionState.DISCONNECTED)
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 查找体脂秤相关特征值
                discoverScaleCharacteristics(gatt)
            } else {
                _errorMessage.postValue("服务发现失败: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleCharacteristicData(data)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(value)
        }

        private fun handleCharacteristicData(data: ByteArray) {
            val measurement = ScaleDataParser.parseBodyCompositionData(data)
            if (measurement != null) {
                _scaleMeasurement.postValue(measurement)
            }

            // 同时也尝试解析为体重数据
            val weightMeasurement = ScaleDataParser.parseWeightData(data)
            if (weightMeasurement != null) {
                _scaleMeasurement.postValue(weightMeasurement)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleReadData(data)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleReadData(value)
            }
        }

        private fun handleReadData(data: ByteArray) {
            val measurement = ScaleDataParser.parseBodyCompositionData(data)
            if (measurement != null) {
                _scaleMeasurement.postValue(measurement)
            }
        }
    }

    /**
     * 发现体脂秤相关特征值并订阅通知
     */
    @SuppressLint("MissingPermission")
    private fun discoverScaleCharacteristics(gatt: BluetoothGatt) {
        // 1. 检查 Body Composition Service
        val bodyCompService = gatt.getService(BODY_COMPOSITION_SERVICE)
        if (bodyCompService != null) {
            val char = bodyCompService.getCharacteristic(BODY_COMPOSITION_MEASUREMENT)
            if (char != null) {
                bodyCompCharacteristic = char
                enableNotification(gatt, char)
                return
            }
        }

        // 2. 检查 Weight Scale Service
        val weightService = gatt.getService(WEIGHT_SCALE_SERVICE)
        if (weightService != null) {
            val char = weightService.getCharacteristic(WEIGHT_MEASUREMENT)
            if (char != null) {
                weightCharacteristic = char
                enableNotification(gatt, char)
                return
            }
        }

        // 3. 检查 Huami 自定义服务 (History characteristic)
        val huamiService = gatt.getService(HUAMI_CONFIG_SERVICE)
        if (huamiService != null) {
            val char = huamiService.getCharacteristic(BODY_COMPOSITION_HISTORY)
            if (char != null) {
                historyCharacteristic = char
                enableNotification(gatt, char)
                return
            }
        }

        // 4. 遍历所有服务查找可通知的特征值
        for (service in gatt.services) {
            for (characteristic in service.characteristics) {
                val props = characteristic.properties
                if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    enableNotification(gatt, characteristic)
                }
            }
        }
    }

    /**
     * 启用特征值通知
     */
    @SuppressLint("MissingPermission")
    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (success) {
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    /**
     * 连接到体脂秤设备
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            return
        }

        _connectionState.postValue(ConnectionState.CONNECTING)
        _errorMessage.postValue(null)

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        bluetoothGatt = gatt

        // 连接超时处理
        handler.postDelayed({
            if (_connectionState.value == ConnectionState.CONNECTING) {
                disconnect()
                _errorMessage.postValue("连接超时")
            }
        }, CONNECTION_TIMEOUT_MS)
    }

    /**
     * 断开连接
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            _connectionState.postValue(ConnectionState.DISCONNECTING)
            try {
                gatt.disconnect()
            } catch (_: Exception) {}
        }
        _connectionState.postValue(ConnectionState.DISCONNECTED)
        bodyCompCharacteristic = null
        weightCharacteristic = null
        historyCharacteristic = null
        handler.removeCallbacksAndMessages(null)
    }

    fun cleanup() {
        disconnect()
    }
}
