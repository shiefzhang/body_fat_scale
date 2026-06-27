package com.tuyi.bodyfatscale.ble

import com.tuyi.bodyfatscale.model.BodyComposition
import com.tuyi.bodyfatscale.model.UserProfile
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 体成分计算器
 *
 * 基于体重、阻抗以及用户个人资料（身高、年龄、性别）
 * 使用生物电阻抗分析（BIA）方法计算各项体成分指标。
 *
 * 算法基于 Lukaski/Bolton 公式和公开发表的BIA研究成果，
 * 与小米/华米体脂秤使用的 Holtek JNI 库算法类似。
 */
object BodyCompositionCalculator {

    /**
     * 计算完整体成分
     *
     * @param weightKg 体重 (kg)
     * @param impedance 阻抗 (ohm)，50kHz
     * @param profile 用户个人资料
     * @return BodyComposition 对象，包含所有计算指标
     */
    fun calculate(
        weightKg: Float,
        impedance: Int,
        profile: UserProfile
    ): BodyComposition {
        val bmi = calculateBMI(weightKg, profile.heightCm)
        val lbm = calculateLeanBodyMass(weightKg, impedance, profile)
        val bmiValue = bmi
        val fatMass = weightKg - lbm
        val fatPercent = (fatMass / weightKg * 100).coerceIn(3f, 60f)

        // 水分率：去脂体重的约 73%
        val waterPercent = (lbm / weightKg * 0.73f * 100).coerceIn(30f, 80f)

        // 肌肉量：去脂体重的约 80%
        val muscleMassKg = (lbm * 0.8f).coerceAtLeast(0f)

        // 骨量：去脂体重的约 3-5%
        val boneMassKg = calculateBoneMass(weightKg, profile)

        // 内脏脂肪等级
        val visceralFatLevel = calculateVisceralFat(weightKg, bmi, profile.age, profile.isMale)

        // 基础代谢率 (Mifflin-St Jeor 方程)
        val bmr = calculateBMR(weightKg, profile.heightCm, profile.age, profile.isMale)

        // 蛋白质率估算
        val proteinPercent = (100f - fatPercent - waterPercent -
                (boneMassKg / weightKg * 100)).coerceIn(10f, 25f)

        return BodyComposition(
            weightKg = weightKg,
            bmi = (bmiValue * 10).roundToInt() / 10f,
            bodyFatPercent = (fatPercent * 10).roundToInt() / 10f,
            bodyWaterPercent = (waterPercent * 10).roundToInt() / 10f,
            muscleMassKg = (muscleMassKg * 10).roundToInt() / 10f,
            boneMassKg = (boneMassKg * 10).roundToInt() / 10f,
            visceralFatLevel = visceralFatLevel.coerceIn(1, 59),
            basalMetabolicRate = bmr,
            proteinPercent = (proteinPercent * 10).roundToInt() / 10f,
            leanBodyMassKg = (lbm * 10).roundToInt() / 10f
        )
    }

    /**
     * 计算 BMI
     */
    private fun calculateBMI(weightKg: Float, heightCm: Float): Float {
        if (heightCm <= 0) return 0f
        val heightM = heightCm / 100f
        return weightKg / (heightM * heightM)
    }

    /**
     * 计算去脂体重 (Lean Body Mass)
     *
     * 使用 Lukaski & Bolonchuk 公式估算：
     * 男性: LBM = 0.65 * (height² / impedance) + 0.27 * weight + 9.53
     * 女性: LBM = 0.56 * (height² / impedance) + 0.25 * weight + 7.07
     *
     * 当没有阻抗数据时，使用 BMI 估算
     */
    private fun calculateLeanBodyMass(
        weightKg: Float,
        impedance: Int,
        profile: UserProfile
    ): Float {
        if (impedance <= 0) {
            // 无阻抗数据时，使用 BMI 估算
            val bmi = calculateBMI(weightKg, profile.heightCm)
            val estimatedLbmPercent = when {
                profile.isMale -> 85f - (bmi - 18.5f) * 0.8f
                else -> 78f - (bmi - 18.5f) * 0.7f
            }.coerceIn(50f, 90f)
            return weightKg * estimatedLbmPercent / 100f
        }

        val heightCm = profile.heightCm
        val heightResistance = (heightCm * heightCm).toFloat() / impedance.toFloat()

        return if (profile.isMale) {
            (0.65f * heightResistance + 0.27f * weightKg + 9.53f)
                .coerceIn(weightKg * 0.5f, weightKg * 0.95f)
        } else {
            (0.56f * heightResistance + 0.25f * weightKg + 7.07f)
                .coerceIn(weightKg * 0.45f, weightKg * 0.9f)
        }
    }

    /**
     * 计算骨量
     * 使用简化公式：骨量 ≈ 0.051 * (身高² / 体重) + 0.073 * 体重 - 1.57 (男性)
     *                              0.044 * (身高² / 体重) + 0.063 * 体重 - 0.86 (女性)
     */
    private fun calculateBoneMass(weightKg: Float, profile: UserProfile): Float {
        val heightCm = profile.heightCm
        val ratio = (heightCm * heightCm) / weightKg

        return if (profile.isMale) {
            (0.051f * ratio + 0.073f * weightKg - 1.57f).coerceAtLeast(1.5f)
        } else {
            (0.044f * ratio + 0.063f * weightKg - 0.86f).coerceAtLeast(1.0f)
        }
    }

    /**
     * 估算内脏脂肪等级 (1-59)
     *
     * 基于 BMI、腰围估算、年龄和性别
     */
    private fun calculateVisceralFat(
        weightKg: Float,
        bmi: Float,
        age: Int,
        isMale: Boolean
    ): Int {
        // 简单估算模型
        val baseLevel = when {
            bmi < 18.5f -> 1f
            bmi < 24f -> 3f
            bmi < 28f -> 6f
            bmi < 32f -> 10f
            else -> 14f
        }

        val ageFactor = (age - 20).coerceAtLeast(0) * 0.05f
        val genderFactor = if (isMale) 1.2f else 1.0f

        return (baseLevel * genderFactor + ageFactor).roundToInt()
            .coerceIn(1, 59)
    }

    /**
     * 计算基础代谢率 (BMR)
     * Mifflin-St Jeor 方程
     */
    private fun calculateBMR(
        weightKg: Float,
        heightCm: Float,
        age: Int,
        isMale: Boolean
    ): Int {
        return if (isMale) {
            (10f * weightKg + 6.25f * heightCm - 5f * age + 5f).roundToInt()
        } else {
            (10f * weightKg + 6.25f * heightCm - 5f * age - 161f).roundToInt()
        }
    }
}
