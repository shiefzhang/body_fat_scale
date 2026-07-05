# 途一智能体脂秤 MX80 Android 客户端

通过 BLE 连接途一智能体脂秤 MX80（`yoda.scales.mx80`）的 Android 应用。

项目已经实现 MiBLE 安全会话登录、加密测量通知解密、体重解析及稳定状态识别。协议结论来自实机 GATT/HCI 抓包和多组已知体重样本；尚未确认的字段不会作为确定功能发布。

## 当前能力

- 扫描并识别 `yoda.scales.mx80`
- 连接 FE95 GATT 服务
- 使用12字节米家云 TOKEN 登录 MiBLE 安全会话
- 通过 HKDF-SHA256、HMAC-SHA256 和 AES-CCM 解密测量通知
- 发送 MX80 要求的逐包应用层 ACK
- 解析实时体重及稳定状态
- 显示 BLE、GATT 和 MiBLE 诊断日志
- 根据用户资料显示体成分公式估算值

## 数据可信度

MX80 当前已确认的直接测量数据只有：

- 体重，分辨率0.01 kg
- 测量是否稳定

已解密数据流中没有发现可确认的体脂、水分、肌肉、骨量等独立字段。载荷中的 `11 94` 在空载以及79.78 kg、94.41 kg、95.03 kg样本中均出现，因此不能将其定义为人体阻抗。

应用显示的体成分均为公式估算值，仅适合观察趋势，不等同于米家结果，也不应作为医疗数据使用。

## BLE 接口文档

其他 App 或平台如需连接 MX80，请阅读：

**[途一智能体脂秤 MX80 BLE 接口文档](docs/MX80_BLE_Interface.html)**

文档包含广播识别、GATT UUID、严格操作时序、TOKEN 登录、HKDF/HMAC/AES-CCM 参数、测量包 ACK、体重解析、校验和及已验证报文样本。文档不包含推测的阻抗解释、体脂算法或未验证的重新注册流程。

## 使用要求

- Android 8.0（API 26）或更高版本
- 支持 BLE 的 Android 设备
- MX80 对应的有效米家云 TOKEN：12字节，即24位十六进制字符

TOKEN 不是常见的 BLE Key。应用不会从米家账户自动抓取 TOKEN，需要用户在调试窗口中导入。

## 使用流程

1. 安装应用并授予蓝牙权限。
2. 唤醒体脂秤，点击扫描。
3. 选择 `yoda.scales.mx80`。
4. 在调试日志窗口导入该秤对应的24位十六进制 TOKEN。
5. 断开并重新连接体脂秤。
6. 等待日志显示安全通道登录成功。
7. 站上秤完成测量；应用在收到稳定帧后更新最终结果。

米家与本应用不要同时连接体脂秤。出现 GATT 连接失败时，请强制停止米家，等待秤熄屏后重新唤醒并连接。

## 技术结构

```text
UI / Jetpack Compose
        |
ScaleViewModel
        |
        +-- BleScanner
        |     扫描、设备识别、诊断日志
        +-- ScaleConnectionService
        |     GATT连接、订阅、读写队列、ACK
        +-- MiSecureSession
        |     MiBLE预初始化、TOKEN登录、密钥派生、AES-CCM解密
        +-- ScaleDataParser
        |     MX80测量载荷、体重、稳定状态、校验和
        +-- BodyCompositionCalculator
              非设备协议数据，仅提供公式估算
```

| 文件 | 职责 |
|---|---|
| `ble/BleScanner.kt` | BLE扫描、MX80识别、日志缓冲 |
| `ble/ScaleConnectionService.kt` | GATT生命周期和严格操作时序 |
| `ble/MiSecureSession.kt` | MiBLE安全会话与测量解密 |
| `ble/ScaleDataParser.kt` | 已解密测量载荷解析 |
| `ble/BodyCompositionCalculator.kt` | 体成分估算，不属于MX80协议 |
| `ui/screens/ScaleViewModel.kt` | 连接状态和测量状态管理 |
| `ui/screens/MainScreen.kt` | Compose界面 |

## 已确认测量载荷

解密明文中的16个 ASCII 十六进制字符转换后得到8字节载荷：

```text
96 AE 1F 2A 11 94 25 57
      -----       -- --
      79.78 kg    状态 校验和
```

| 偏移 | 长度 | 含义 |
|---:|---:|---|
| 0 | 2 | 未知，不解释 |
| 2 | 2 | 体重，无符号大端，单位0.01 kg |
| 4 | 2 | 未知，不解释 |
| 6 | 1 | 状态；bit 0为稳定标志 |
| 7 | 1 | 前7字节无符号求和后的低8位 |

完整连接和加密协议见[接口文档](docs/MX80_BLE_Interface.html)。

## 构建

```bash
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

若仓库缺少 Gradle Wrapper 脚本，可使用本机 Gradle 8.x 执行 `assembleDebug`。Debug APK 输出到：

```text
app/build/outputs/apk/debug/
```

## Android 权限

- Android 12及以上：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`
- Android 11及以下：传统蓝牙权限及 BLE 扫描所需的位置权限

部分 Android 系统即使权限已授予，关闭系统位置服务仍可能影响 BLE 扫描。

## 已知限制

- 必须预先持有有效的12字节米家云 TOKEN。
- TOKEN 获取方式不属于本项目已确认的公开接口。
- 不支持在没有 TOKEN 的情况下自动完成米家绑定。
- 不声明 `11 94` 为阻抗，也不从该字段计算体脂。
- 米家设备插件属于私有实现，本项目不保证估算指标与米家一致。
- 不同厂商的蓝牙栈存在差异，部分设备可能需要重新唤醒秤后重连。

## 安全说明

- 不要把 TOKEN、HCI 抓包或包含私密设备信息的日志提交到公开仓库。
- 应用把 TOKEN 保存在自身私有存储中。
- 发布日志前请检查其中是否包含设备地址、TOKEN或账户信息。

## License

MIT
