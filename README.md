# 途一智能体脂秤 MX80 — BLE Android App

> 通过 BLE 连接途一智能体脂秤 MX80（`yoda.scales.mx80`），读取体重、阻抗，并计算体成分（体脂率、水分率、肌肉量、骨量、内脏脂肪等级、基础代谢等）。

## 目录

- [架构概览](#架构概览)
- [BLE 协议分析](#ble-协议分析)
- [数据流](#数据流)
- [模块设计](#模块设计)
  - [1. BLE 扫描器 (BleScanner)](#1-ble-扫描器-blescanner)
  - [2. BLE 连接服务 (ScaleConnectionService)](#2-ble-连接服务-scaleconnectionservice)
  - [3. 数据解析器 (ScaleDataParser)](#3-数据解析器-scaledataparser)
  - [4. 体成分计算器 (BodyCompositionCalculator)](#4-体成分计算器-bodycompositioncalculator)
  - [5. 数据模型](#5-数据模型)
  - [6. ViewModel (ScaleViewModel)](#6-viewmodel-scaleviewmodel)
  - [7. UI 界面 (MainScreen)](#7-ui-界面-mainscreen)
  - [8. 主入口 (MainActivity)](#8-主入口-mainactivity)
- [设备发现策略](#设备发现策略)
- [体成分算法说明](#体成分算法说明)
- [权限清单](#权限清单)
- [构建系统](#构建系统)
- [在其他平台重构](#在其他平台重构)
- [常见问题](#常见问题)

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  MainScreen (Jetpack Compose)                        │   │
│  │  ┌───────────┐ ┌───────────┐ ┌──────────────────┐   │   │
│  │  │ ScanView  │ │ ResultView│ │ UserProfileDialog │   │   │
│  │  └─────┬─────┘ └─────┬─────┘ └──────────────────┘   │   │
│  └────────┼──────────────┼──────────────────────────────┘   │
│           │              │                                   │
│  ┌────────▼──────────────▼──────────────────────────────┐   │
│  │           ScaleViewModel (StateFlow)                  │   │
│  │  协调 BLE 扫描、连接、数据解析、体成分计算           │   │
│  └────────┬──────────────┬──────────────────────────────┘   │
├───────────┼──────────────┼──────────────────────────────────┤
│           │              │          BLE & Logic Layer        │
│  ┌────────▼──────┐ ┌────▼────────────┐                      │
│  │  BleScanner   │ │ScaleConnection  │                      │
│  │  扫描/发现设备  │ │    Service      │  ← Android BLE API  │
│  │  标记体脂秤    │ │ GATT连接/通知订阅│                      │
│  └───────┬───────┘ └────┬────────────┘                      │
│          │              │                                    │
│  ┌───────▼──────────────▼──────────────────────────────┐   │
│  │              ScaleDataParser                        │   │
│  │  标准0x2A9C / 0x2A9D / 小米广播 / Yoda1 / 小米历史  │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                    │
│  ┌──────────────────────▼──────────────────────────────┐   │
│  │         BodyCompositionCalculator                   │   │
│  │  Lukaski/Bolton BIA公式 + Mifflin-St Jeor BMR方程   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## BLE 协议分析

### 目标设备

| 属性 | 值 |
|------|------|
| 产品 | 途一智能体脂秤 MX80 |
| 型号 | `yoda.scales.mx80` |
| 生态 | 米家 (Mi Home) |
| 通信 | BLE (Bluetooth Low Energy) 4.0+ |

### BLE 服务与特征值

本 App 支持以下服务 UUID，按优先级尝试连接：

| 服务 | UUID | 特征值 | UUID | 说明 |
|------|------|--------|------|------|
| Body Composition | `0000181b-...` | Body Composition Measurement | `00002a9c-...` | BLE 标准体成分协议 |
| Weight Scale | `0000181d-...` | Weight Measurement | `00002a9d-...` | BLE 标准体重协议 |
| Huami 自定义 | `00001530-...-0009af100700` | Body Composition History | `00002a2f-...-0009af100700` | 小米/华米历史记录 |
| 回退 | 所有服务 | 可 NOTIFY 的特征值 | - | 遍历所有特征值 |

### 数据格式

#### 格式 1: BLE 标准 Body Composition Measurement (0x2A9C)

```
字节 0: Flags
  Bit 0: Imperial (BMI/身高单位)
  Bit 1: 测量单位 (0=SI, 1=Imperial)
  Bit 2: 时间戳存在
  Bit 3: 用户ID存在
  Bit 4: BMI和身高存在
[时间戳] 年(u16) 月(u8) 日(u8) 时(u8) 分(u8) 秒(u8)
[用户ID] u8
[BMI+身高] u16*10, u16*10
体重 u16 (kg*100 或 lb*100)
体脂 u16 (百分比*10)
```

#### 格式 2: 小米体脂秤广播数据 (Service Data 0x16)

```
载荷 13 字节 (小端序):
字节 0-1: 控制字
  Bit 7: isPounds (磅)
  Bit 8: isEmptyLoad (空载)
  Bit 9: isCatty (斤)
  Bit 10: isStabilized (已稳定)
  Bit 14: hasImpedance (有阻抗)
字节 2-3: 年份
字节 4: 月, 5: 日, 6: 时, 7: 分, 8: 秒
字节 9-10: 阻抗 (ohm)
字节 11-12: 体重 (kg*200, lb*100, 或 斤*100)
```

#### 格式 3: Yoda1 制造商数据 (Manufacturer Data 0xFF)

```
2 字节大端序: 体重值 / 100 = kg
例: [0x15, 0xA7] = 5543 / 100 = 55.43 kg
```

#### 格式 4: 小米历史记录特征值 (0x2A2F)

```
字节 0: 命令码 (0x02 = 测量数据)
字节 1-13: 同广播数据载荷 (13字节)
```

---

## 数据流

```
体脂秤 BLE 广播/通知
  │
  ├─ 扫描阶段 (无需连接)
  │   └─ BleScanner.onScanResult()
  │       └─ ScaleDataParser.parseFromScanRecord()
  │           ├─ 解析 Xiaomi Service Data (0x16)
  │           └─ 解析 Yoda1 Manufacturer Data (0xFF)
  │           └─ 返回 ScaleMeasurement(weightKg)
  │
  └─ 连接阶段 (GATT)
      └─ ScaleConnectionService
          ├─ onCharacteristicChanged() → 通知数据
          └─ onCharacteristicRead()   → 主动读取
              └─ ScaleDataParser
                  ├─ parseBodyCompositionData()
                  │   ├─ parseStandardBodyComp()   (0x2A9C)
                  │   ├─ parseXiaomiHistoryData()  (0x2A2F)
                  │   └─ parseYodaData()           (自定义)
                  └─ parseWeightData()             (0x2A9D)
                  └─ 返回 ScaleMeasurement(weightKg, impedance)
  │
  └─ 体成分计算
      └─ BodyCompositionCalculator.calculate()
          └─ 输入: weightKg + impedance + UserProfile
          └─ 输出: BodyComposition (10项指标)
  │
  └─ UI 展示
      └─ MainScreen / MeasurementResult / BodyCompositionResult
```

---

## 模块设计

### 1. BLE 扫描器 (BleScanner)

**职责**：扫描附近的 BLE 设备，识别可能的体脂秤，提供诊断信息。

**关键设计**：

- **不过滤设备**：显示所有 BLE 设备，体脂秤仅作标记（`isPossibleScale`），不用于过滤。这是吸取了早期版本过滤条件写反导致搜不到设备的教训。
- **兼容性**：使用 `emptyList<ScanFilter>()` 而非 `null`，因为部分国产手机对 `null` filters 支持有问题。
- **诊断**：提供 `scanCallbackCount`（回调次数）、`diagnostics`（BLE 状态）LiveData，方便排查扫描问题。
- **超时**：60 秒自动停止，5 秒无声纳时提示用户检查蓝牙/位置/体脂秤。

**数据类** `BleDeviceInfo`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | 设备广播名 |
| `address` | String | MAC 地址 |
| `rssi` | Int | 信号强度 (dBm) |
| `scanRecord` | ByteArray? | 原始广播包 |
| `isPossibleScale` | Boolean | 是否可能是体脂秤 |

### 2. BLE 连接服务 (ScaleConnectionService)

**职责**：GATT 连接、服务发现、特征值通知订阅、数据接收。

**关键设计**：

- **连接状态机**：`DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTING`
- **特征值发现优先级**：
  1. Body Composition Service (0x181B) → Measurement (0x2A9C)
  2. Weight Scale Service (0x181D) → Measurement (0x2A9D)
  3. Huami 自定义 Service → History (0x2A2F)
  4. 回退：遍历所有具有 NOTIFY 属性的特征值
- **通知订阅**：通过 CCCD (Client Characteristic Configuration Descriptor) 启用通知
- **连接超时**：15 秒
- **Android 13+ 兼容**：处理 `onCharacteristicChanged` 的新旧 API 差异

### 3. 数据解析器 (ScaleDataParser)

**职责**：解析各种 BLE 数据格式为统一的 `ScaleMeasurement`。

**支持的格式**（按优先级）：
1. BLE 标准 Body Composition Measurement (0x2A9C)
2. BLE 标准 Weight Measurement (0x2A9D)
3. 小米/华米历史记录特征值 (0x2A2F)
4. 小米体脂秤广播数据 (Service Data 0x16)
5. Yoda1 制造商数据 (Manufacturer Data 0xFF)

**设计模式**：Object 单例，纯函数式，无状态。每种格式一个私有方法，`parseBodyCompositionData` 作为统一入口逐一尝试各解析器。

### 4. 体成分计算器 (BodyCompositionCalculator)

**职责**：基于体重、阻抗和用户资料计算 10 项体成分指标。

**算法来源**：

| 指标 | 算法/公式 | 来源 |
|------|-----------|------|
| BMI | 体重(kg) / 身高(m)² | WHO 标准 |
| 去脂体重 (LBM) | Lukaski & Bolonchuk BIA 公式 | Lukaski, H.C. et al. (1986) |
| 体脂率 | (体重 - LBM) / 体重 × 100 | 由 LBM 推导 |
| 水分率 | LBM × 0.73 / 体重 × 100 | 生理学常数 |
| 肌肉量 | LBM × 0.8 | 经验估算 |
| 骨量 | 基于身高²/体重 × 系数 | 经验公式 |
| 内脏脂肪等级 | BMI + 年龄 + 性别调整 | 经验模型 |
| 基础代谢 (BMR) | Mifflin-St Jeor 方程 | Mifflin, M.D. et al. (1990) |

**Lukaski BIA 公式**：

```
男性: LBM = 0.65 × (身高² / 阻抗) + 0.27 × 体重 + 9.53
女性: LBM = 0.56 × (身高² / 阻抗) + 0.25 × 体重 + 7.07
```

**无阻抗回退**：当体脂秤未提供阻抗数据时（仅体重秤模式），使用 BMI 估算体脂百分比。

### 5. 数据模型

```
ScaleMeasurement           BodyComposition           UserProfile
├─ weightKg: Float         ├─ weightKg: Float        ├─ heightCm: Float
├─ impedance: Int          ├─ bmi: Float             ├─ age: Int
├─ isStabilized: Boolean   ├─ bodyFatPercent: Float  └─ isMale: Boolean
├─ hasImpedance: Boolean   ├─ bodyWaterPercent: Float
└─ timestamp: Long         ├─ muscleMassKg: Float
                           ├─ boneMassKg: Float
                           ├─ visceralFatLevel: Int
                           ├─ basalMetabolicRate: Int
                           ├─ proteinPercent: Float
                           └─ leanBodyMassKg: Float
```

### 6. ViewModel (ScaleViewModel)

**职责**：管理 UI 状态，协调 BLE 扫描、连接、数据解析和体成分计算。

**设计**：
- 使用 `StateFlow<UiState>` 管理 UI 状态（单向数据流）
- 通过 `LiveData.observeForever` 在 ViewModel 协程中观察 BleScanner 和 ScaleConnectionService 的 LiveData
- 提供方法：`startScan()`, `stopScan()`, `connectToDevice()`, `disconnect()`, `updateUserProfile()`

### 7. UI 界面 (MainScreen)

**技术栈**：Jetpack Compose + Material 3

**界面分区**：

| 状态 | 显示内容 |
|------|----------|
| 未连接/未扫描 | 扫描按钮 + 空状态引导 + 设备列表(体脂秤分组/其他设备分组) |
| 扫描中 | 进度指示器 + 回调计数 + 操作指引 |
| 已连接/测量中 | "请站上体脂秤" 提示 + 实时体重 |
| 测量完成 | 体重卡片 + 4×2 指标网格 + 底部留白 |

**组件分解**：
- `MainScreen` → `ConnectionStatusBar` + `ScanSection` / `MeasurementResult`
- `ScanSection` → `DeviceCard`（带"体脂秤"徽章）
- `MeasurementResult` → `BodyCompositionResult` → `MetricGrid` → `MetricCard`
- `UserProfileDialog` → 身高/年龄/性别设置

### 8. 主入口 (MainActivity)

**职责**：权限管理、蓝牙启用、位置服务提示、扫描启动链路编排。

**扫描启动链路**：
```
用户点击"搜索体脂秤"
  → requestBluetoothPermissions()
    ├─ 权限未授权 → 弹出权限请求 → 用户授权 → ensureBluetoothThenScan()
    └─ 权限已授权 → ensureBluetoothThenScan()
       ├─ 蓝牙关闭 → 弹出蓝牙启用 → 用户开启 → checkPermissionAndScan()
       └─ 蓝牙已开 → warnLocationAndScan()
          ├─ 位置关闭 → 温和提示"如果搜不到请开启位置"
          └─ 位置已开 → 无提示
          └─ 开始扫描 → viewModel.startScan()
```

---

## 设备发现策略

本 App 经历了三次迭代才达到当前稳定的发现策略：

| 版本 | 策略 | 问题 |
|------|------|------|
| v1 | 严格过滤：名称匹配 + UUID 匹配 + 制造商数据匹配 | **BUG**: 过滤条件写反，有制造商数据的设备全被排除 |
| v2 | 移除过滤，显示全部 BLE 设备，打 badge 标记 | `null filters` 在某些国产手机上兼容性差 |
| v3 | `emptyList()` + 回调计数 + 位置服务提示 | ✅ 稳定 |

**当前策略**：
1. `emptyList<ScanFilter>()` 启动扫描（兼容国产手机）
2. 所有设备加入列表，`isPossibleScale()` 做标记
3. UI 分组显示：体脂秤设备 / 其他设备
4. 扫描 5 秒无声纳时主动提示用户排查

---

## 体成分算法说明

### 当有阻抗数据时（完整 BIA 分析）

```
输入: 体重(kg)、阻抗(ohm, 50kHz)、身高(cm)、年龄、性别

1. BMI = 体重 / (身高/100)²
2. LBM = Lukaski BIA 公式 (男性/女性不同)
3. 体脂率 = (体重 - LBM) / 体重 × 100
4. 水分率 = LBM × 0.73 / 体重 × 100
5. 肌肉量 = LBM × 0.8
6. 骨量 = 基于身高²/体重的经验公式
7. 内脏脂肪等级 = BMI + 年龄 + 性别 经验模型
8. BMR = Mifflin-St Jeor 方程
9. 蛋白质率 = 剩余比例
```

### 当无阻抗数据时（仅体重秤模式）

```
仅使用体重和用户资料:
1. BMI 计算
2. LBM 基于 BMI 估算 (无 BIA)
3. 其他指标同上有 BIA 流程
```

---

## 权限清单

| 权限 | 最低 SDK | 最高 SDK | 用途 |
|------|----------|----------|------|
| `BLUETOOTH` | - | 31 | 传统蓝牙通信 |
| `BLUETOOTH_ADMIN` | - | 31 | 蓝牙管理 |
| `BLUETOOTH_SCAN` | 31 | - | Android 12+ BLE 扫描 |
| `BLUETOOTH_CONNECT` | 31 | - | Android 12+ BLE 连接 |
| `ACCESS_FINE_LOCATION` | - | 32 | Android 11- BLE 扫描需要位置权限 |
| `ACCESS_COARSE_LOCATION` | - | 32 | 同上，粗略位置 |

> 注意：`ACCESS_FINE_LOCATION` 设置了 `maxSdkVersion="32"`，Android 12+ 使用 `BLUETOOTH_SCAN` 替代，无需位置权限。

---

## 构建系统

| 工具 | 版本 |
|------|------|
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Compose BOM | 2024.12.01 |
| Compile SDK | 35 |
| Min SDK | 26 |
| Target SDK | 35 |
| Gradle | 8.9 |

```bash
# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK
./gradlew assembleRelease
```

---

## 在其他平台重构

本项目的 BLE 协议和体成分算法已完全解耦，可直接在其他平台复用。

### 需移植的核心逻辑

| 模块 | 文件 | 语言无关性 | 移植难度 |
|------|------|-----------|----------|
| BLE 数据解析 | `ScaleDataParser.kt` | 纯算法，无平台依赖 | ★☆☆ |
| 体成分计算 | `BodyCompositionCalculator.kt` | 纯数学公式 | ★☆☆ |
| 数据模型 | `ScaleMeasurement.kt` | 3 个简单结构体 | ★☆☆ |
| BLE 协议 UUID | `ScaleConnectionService.kt` 中的常量 | 标准 UUID 字符串 | ★☆☆ |

### 其他平台建议

| 平台 | BLE 库 | UI 框架 | 建议 |
|------|--------|---------|------|
| iOS / Swift | CoreBluetooth | SwiftUI / UIKit | 可直接移植 Parser + Calculator |
| React Native | react-native-ble-plx / react-native-bluetooth-classic | React Native | 用 JS 实现 Parser + Calculator |
| Flutter | flutter_blue_plus / flutter_reactive_ble | Flutter Widgets | 用 Dart 实现 Parser + Calculator |
| Python / Linux | bleak / pygatt | - | 适合做后台服务或 CLI 工具 |
| ESP32 / 嵌入式 | ESP-IDF BLE | - | 可作为 BLE 网关读取秤数据 |

### 移植关键点

1. **解析 Xiaomi 广播数据**：在扫描阶段无需连接即可获取体重数据。iOS 上使用 `centralManager(_:didDiscover:advertisementData:rssi:)` 的 `advertisementData` 参数。
2. **Yoda1 格式**：2 字节 BigEndian / 100，在 Manufacturer Data 的 payload 开始处读取。
3. **标准 Body Composition**：连接后订阅 `0x2A9C` 通知，解析 GATT 特征值数据。

---

## 常见问题

### Q: 扫描不到体脂秤？

请确认：
1. 手机蓝牙已开启
2. **手机位置服务已开启**（部分手机需要）
3. 体脂秤已激活（站上去或用脚轻踩秤面）
4. 手机靠近体脂秤（30cm 以内）

如果仍然不行，查看屏幕底部的诊断信息：
- `回调: 0` = 蓝牙没有收到任何广播
- `回调: >0` = 收到广播，但设备未被识别

### Q: 体脂率不准确？

体成分计算基于 BIA 算法的估算值，精度受以下因素影响：
1. 用户身高、年龄、性别设置是否正确（点击右上角人物图标设置）
2. 阻抗数据的准确性（体脂秤电极片是否干净、脚部是否干燥）
3. 算法本身基于公开文献的简化模型，与商用的 Holtek JNI 库存在差异

### Q: 数据格式不兼容其他 App 的导出？

本 App 是实测途一 MX80 的参考实现。数据解析结果存储在内存中，未持久化。
如需导出功能，可在 `ScaleViewModel` 中扩展数据存储/导出逻辑。

---

## License

MIT
