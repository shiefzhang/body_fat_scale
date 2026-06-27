package com.tuyi.bodyfatscale.model

/**
 * 体脂秤测量结果数据类
 */
data class ScaleMeasurement(
    /** 体重 (kg) */
    val weightKg: Float = 0f,
    /** 阻抗 (ohm)，用于计算体成分 */
    val impedance: Int = 0,
    /** 是否已稳定（秤上数据不再变化） */
    val isStabilized: Boolean = false,
    /** 是否有阻抗数据（是否可以计算体脂） */
    val hasImpedance: Boolean = false,
    /** 时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        val EMPTY = ScaleMeasurement()
    }
}

/**
 * 完整的体成分分析结果
 */
data class BodyComposition(
    val weightKg: Float,
    val bmi: Float,
    /** 体脂率 (%) */
    val bodyFatPercent: Float,
    /** 水分率 (%) */
    val bodyWaterPercent: Float,
    /** 肌肉量 (kg) */
    val muscleMassKg: Float,
    /** 骨骼量 (kg) */
    val boneMassKg: Float,
    /** 内脏脂肪等级 (1-59) */
    val visceralFatLevel: Int,
    /** 基础代谢率 (kcal) */
    val basalMetabolicRate: Int,
    /** 蛋白质率 (%) (估算) */
    val proteinPercent: Float,
    /** 去脂体重 (kg) */
    val leanBodyMassKg: Float
)

/**
 * 用户个人资料（用于体成分计算）
 */
data class UserProfile(
    val heightCm: Float = 170f,
    val age: Int = 30,
    val isMale: Boolean = true
)
