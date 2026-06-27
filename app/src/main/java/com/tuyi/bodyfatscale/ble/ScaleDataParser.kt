package com.tuyi.bodyfatscale.ble

import com.tuyi.bodyfatscale.model.ScaleMeasurement
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 体脂秤 BLE 数据解析器
 *
 * 支持多种数据格式：
 * 1. BLE 标准 Body Composition Measurement (0x2A9C)
 * 2. BLE 标准 Weight Measurement (0x2A9D)
 * 3. 小米/华米体脂秤广播数据格式（Manufacturer Data）
 * 4. Yoda1 体脂秤数据格式
 */
object ScaleDataParser {

    /**
     * 解析 Body Composition Measurement (0x2A9C) 数据
     *
     * 标准格式：
     * - 字节 0: Flags
     *   - Bit 0: Imperial (BMI/身高单位)
     *   - Bit 1: 测量单位 (0=SI, 1=Imperial)
     *   - Bit 2: 时间戳是否存在
     *   - Bit 3: 用户ID是否存在
     *   - Bit 4: BMI和身高是否存在
     *   - Bit 5-7: 保留
     * - 如果时间戳存在: 年(u16) 月(u8) 日(u8) 时(u8) 分(u8) 秒(u8)
     * - 如果用户ID存在: 用户ID(u8)
     * - 如果BMI和身高存在: BMI(u16) 身高(u16)
     * - 体重 (u16, 单位 kg*100 或 lb*100)
     * - 体脂百分比 (u16, *10)
     *
     * Xiaomi/Huami 自定义 Body Composition History (0x2A2F) 格式：
     * - 字节 0: 命令
     * - 字节 1+: 数据
     *   命令 0x02 (测量数据):
     *   数据格式同广播数据
     */
    fun parseBodyCompositionData(data: ByteArray): ScaleMeasurement? {
        if (data.isEmpty()) return null

        // 尝试解析为标准 Body Composition Measurement (0x2A9C)
        val standardResult = parseStandardBodyComp(data)
        if (standardResult != null) return standardResult

        // 尝试解析为 Xiaomi 自定义历史数据
        val xiaomiResult = parseXiaomiHistoryData(data)
        if (xiaomiResult != null) return xiaomiResult

        // 尝试解析为 Yoda 数据格式
        val yodaResult = parseYodaData(data)
        if (yodaResult != null) return yodaResult

        return null
    }

    /**
     * 解析 Weight Measurement (0x2A9D) 数据
     *
     * 格式：
     * - 字节 0: Flags
     *   - Bit 0: 单位 (0=SI/kg, 1=Imperial/lb)
     *   - Bit 1: 时间戳存在
     *   - Bit 2: 用户ID存在
     *   - Bit 3-4: BMI和身高存在
     *   - Bit 5-7: 保留
     * - 体重 (u16, SI单位时 kg*100)
     */
    fun parseWeightData(data: ByteArray): ScaleMeasurement? {
        if (data.isEmpty()) return null

        return try {
            val flags = data[0].toInt() and 0xFF
            val isImperial = (flags and 0x01) != 0
            var offset = 1

            // 跳过时间戳
            if ((flags and 0x02) != 0) offset += 7
            // 跳过用户ID
            if ((flags and 0x04) != 0) offset += 1

            if (offset + 2 > data.size) return null

            val weightValue = byteArrayOf(data[offset], data[offset + 1])
                .let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short }
                .toInt() and 0xFFFF

            val weightKg = if (isImperial) {
                weightValue / 100f * 0.453592f // lb -> kg
            } else {
                weightValue / 100f
            }

            ScaleMeasurement(
                weightKg = weightKg,
                isStabilized = true,
                hasImpedance = false,
                impedance = 0
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析广播数据（Scan Record）中的制造商特定数据
     *
     * Xiaomi/Mi Body Composition Scale 广播数据格式 (Service Data 0x16):
     * 数据长度 17 字节
     * - 前 4 字节: UUID
     * - 后 13 字节: 数据载荷
     *
     * 载荷格式:
     * - 字节 0-1: 控制字节
     * - 字节 2-3: 年份 (小端)
     * - 字节 4: 月
     * - 字节 5: 日
     * - 字节 6: 时
     * - 字节 7: 分
     * - 字节 8: 秒
     * - 字节 9-10: 阻抗 (小端)
     * - 字节 11-12: 体重 (小端，kg*200 / lb*100 / 斤*100)
     *
     * 控制字节:
     * - Bit 7: isPounds
     * - Bit 8: isEmptyLoad
     * - Bit 9: isCatty (斤)
     * - Bit 10: isStabilized
     * - Bit 14: hasImpedance
     */
    fun parseAdvertisementData(scanRecord: ByteArray?): ScaleMeasurement? {
        if (scanRecord == null) return null

        return try {
            parseXiaomiAdData(scanRecord)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析小米体脂秤广播数据
     */
    private fun parseXiaomiAdData(data: ByteArray): ScaleMeasurement? {
        // 查找 Service Data AD type (0x16)
        var offset = 0
        while (offset < data.size) {
            val length = data[offset].toInt() and 0xFF
            if (length == 0) break
            if (offset + length >= data.size) break

            val type = data[offset + 1].toInt() and 0xFF

            // Service Data - 16-bit UUID (AD Type 0x16)
            if (type == 0x16 && length >= 17) {
                // Xiaomi 体脂秤通常使用 UUID: 0x181B, 0x181D, 或 0xFEE0
                val uuidHigh = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + 3].toInt() and 0xFF)

                if (uuidHigh == 0x181B || uuidHigh == 0x181D) {
                    return parseXiaomiPayload(data.copyOfRange(offset + 4, offset + length + 1))
                }
            }

            // Manufacturer Specific Data (AD Type 0xFF)
            if (type == 0xFF && length >= 5) {
                val companyId = ((data[offset + 3].toInt() and 0xFF) shl 8) or
                        (data[offset + 2].toInt() and 0xFF)
                // Xiaomi company ID = 0x0579
                // Yoda1 often uses 0xFFFF or other
                if (length >= 4 + 2) {
                    // Try yoda format: 2 bytes weight in BigEndian / 100
                    return parseYodaData(data.copyOfRange(offset + 2, offset + length + 1))
                }
            }

            offset += length + 1
        }
        return null
    }

    /**
     * 解析小米 13 字节载荷
     * 参考: ble-in-xiaomi 项目
     */
    private fun parseXiaomiPayload(payload: ByteArray): ScaleMeasurement? {
        if (payload.size < 13) return null

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val control1 = buf.get(0).toInt() and 0xFF
        val control2 = buf.get(1).toInt() and 0xFF
        val control = (control2.toLong() shl 8) or control1.toLong()

        val isEmptyLoad = (control and (1L shl 8)) != 0L
        val isStabilized = (control and (1L shl 10)) != 0L
        val hasImpedance = (control and (1L shl 14)) != 0L
        val isCatty = (control and (1L shl 9)) != 0L
        val isPounds = (control and (1L shl 7)) != 0L

        val weightRaw = buf.getShort(11).toInt() and 0xFFFF
        val weightKg = when {
            isPounds -> (weightRaw / 100f) * 0.45359237f
            isCatty -> (weightRaw / 100f) * 0.5f
            else -> weightRaw / 200f // Xiaomi 体重单位是 kg*200
        }

        val impedance = if (hasImpedance) {
            buf.getShort(9).toInt() and 0xFFFF
        } else 0

        return ScaleMeasurement(
            weightKg = weightKg,
            impedance = impedance,
            isStabilized = isStabilized,
            hasImpedance = hasImpedance,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * 解析 Yoda1 体重秤数据
     * Yoda1 使用 BLE 制造商数据，2 字节大端体重值 / 100
     */
    private fun parseYodaData(data: ByteArray): ScaleMeasurement? {
        if (data.size < 2) return null

        return try {
            val weightRaw = ((data[0].toInt() and 0xFF) shl 8) or
                    (data[1].toInt() and 0xFF)
            if (weightRaw == 0 || weightRaw > 30000) return null

            val weightKg = weightRaw / 100f
            ScaleMeasurement(
                weightKg = weightKg,
                isStabilized = true,
                hasImpedance = false,
                timestamp = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析 BLE 标准 Body Composition 特征值数据
     * UUID: 0x2A9C
     */
    private fun parseStandardBodyComp(data: ByteArray): ScaleMeasurement? {
        if (data.size < 3) return null

        return try {
            val flags = data[0].toInt() and 0xFF
            val isImperial = (flags and 0x01) != 0
            var offset = 1

            // Skip timestamp if present
            if ((flags and 0x04) != 0) offset += 7
            // Skip user ID if present
            if ((flags and 0x08) != 0) offset += 1
            // Skip BMI and height if present
            if ((flags and 0x10) != 0) offset += 4

            if (offset + 2 > data.size) return null

            val weightRaw = ByteBuffer.wrap(data, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xFFFF

            val weightKg = if (isImperial) {
                (weightRaw / 100f) * 0.45359237f
            } else {
                weightRaw / 100f
            }

            // 体脂数据（如果有）
            var impedance = 0
            var hasImpedance = false
            if (offset + 4 <= data.size) {
                // 体脂百分比 (u16, *10)
                val fatRaw = ByteBuffer.wrap(data, offset + 2, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                    .toInt() and 0xFFFF
                if (fatRaw in 1..1000) {
                    hasImpedance = true
                }
            }

            ScaleMeasurement(
                weightKg = weightKg,
                impedance = impedance,
                isStabilized = true,
                hasImpedance = hasImpedance,
                timestamp = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析小米/华米体脂秤历史记录特征值数据
     * UUID: 00002a2f-0000-3512-2118-0009af100700
     */
    private fun parseXiaomiHistoryData(data: ByteArray): ScaleMeasurement? {
        if (data.size < 3) return null

        return try {
            val cmd = data[0].toInt() and 0xFF

            // 命令 0x02 = 测量数据
            if (cmd == 0x02 && data.size >= 15) {
                // 去掉命令字节后的 13 字节载荷，同广播数据格式
                val payload = data.copyOfRange(1, data.size.coerceAtMost(14))
                return parseXiaomiPayload(payload)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从扫描记录（完整广播包）中提取体脂秤测量数据
     * 在扫描阶段就直接提取数据（无需连接）
     */
    fun parseFromScanRecord(scanRecord: ByteArray?): ScaleMeasurement? {
        return parseAdvertisementData(scanRecord)
    }
}
