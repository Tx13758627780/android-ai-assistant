# 验证记录

日期：2026-10-07。工具链：JDK 21、Gradle 8.11.1、AGP 8.9.3、Kotlin 2.1.20、Android SDK / Build Tools 35。

## 已完成的源码检查

统一执行：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

结果：**BUILD SUCCESSFUL**。49 项核心测试、7 项 Android 适配器策略测试，共 **56 项全部通过**，无失败、错误或跳过。核心 XML 报告位于 `core/build/test-results/test/`，适配器报告位于 `app/build/test-results/testDebugUnitTest/`。

验证内容包括：严格模型 JSON 和字段校验；中文多步骤规划；安装包名来源；模型截图默认关闭；密码及凭据上下文脱敏；目标唯一性；有界重试和重规划；跨 App 状态记忆；取消；伪造、过期和重放确认；原子恢复检查点；未知副作用不重复；编码 URL 和敏感 GET 不能绕过确认；私有 DNS 以可处理的网络异常拒绝，避免异步 HTTP 线程崩溃。

`assembleDebug` 和 `assembleDebugAndroidTest` 均通过。安装包使用 Debug 签名，`apksigner verify --verbose` 验证通过（APK v2 签名）。

Android Lint：**0 错误、21 警告**，主要为固定依赖版本可升级、KTX 写法建议、中文文本尚未集中到多语言资源及最低版本的冗余判断。备份与设备迁移已显式排除私有数据。未使用 lint baseline 掩盖错误。

## Android 设备验证

`DeviceFlowTest` 提供三个实际设备集成场景；测试 APK 已编译，以下业务断言尚未取得设备通过结果：

1. 从输入框启动“打开设置”，确认真实前台和证据；连续执行两次，覆盖上次终态对新前台服务的影响。
2. “打开设置，然后打开 Clock”，检查两个 App 的真实操作、持久化状态与完成证据。
3. 固定计划夹具打开设置并通过 Accessibility 进入 Display；先检查停在确认状态，再通过真实 Activity 确认、返回目标 App、点击和验证 Brightness level。

计划夹具仅替代云模型响应；执行器、前台服务、状态机、权限、页面读取、确认界面和结果验证使用实际 Android 实现。

本轮设备运行停在测试准备阶段：UiAutomation 默认抑制被测试的 Accessibility 服务，以及测试辅助命令把组件名引用符当成设置内容，导致业务场景未能执行。最终测试夹具已使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 并修正辅助命令，编译通过；最终版本由使用者运行设备测试，本记录不宣称上述三个场景通过。

设备环境为无 KVM 的 API 30 AOSP x86_64 软件模拟器。此前 API 35 触发系统看门狗；可丢弃 userdebug 模拟器通过 `adb root` 设置初始 `ro.hw_timeout_multiplier=10` 后重启 Android framework，放宽模拟器系统超时。

复现：

```bash
./scripts/start-emulator.sh
# 软件模拟器若触发系统看门狗，仅在 AOSP 测试模拟器上执行：
adb -s emulator-5554 root
adb -s emulator-5554 shell setprop ro.hw_timeout_multiplier 10
adb -s emulator-5554 shell stop
adb -s emulator-5554 shell start
# 等待 PackageManager 和 Launcher 就绪
ANDROID_SERIAL=emulator-5554 ./scripts/device-smoke.sh
```

设备脚本自动权限配置仅用于模拟器；真实手机需要显式设置 `ALLOW_REAL_DEVICE=1`。`adb root` 没有给助手应用进程授予 Root 权限。软件模拟器问题与修复细节见 [BUILD_ENVIRONMENT.md](BUILD_ENVIRONMENT.md)。

## 持续构建

`.github/workflows/android.yml` 在 push、Pull Request 或手动触发时使用 JDK 21 / SDK 35，运行核心与适配器单元测试、Lint、Debug APK 和设备测试 APK 编译，并上传 APK 与检查报告。不自动发布 Release，也不在 CI 中运行模拟器；工作流执行结果以 GitHub Actions 实际记录为准。

## 未验证范围

未提供模型 API 凭据，因此没有向真实 Qwen / 其他模型发送云请求；协议、结构化响应、视觉输入及校验以可控 transport 进行测试。语音依赖设备已安装的识别服务，当前软件模拟器未验证实际麦克风识别。

Root 与 Shizuku 适配器是真实实现，编译和固定命令策略测试通过；尚未在具备 `su` / Shizuku 授权的真机上执行。没有实际付款、删除个人数据或发送真实消息。任意第三方 App、厂商 ROM、Android 15 后台限制和真实业务完成状态需要逐个适配、验证。

纯画布且没有唯一 Accessibility 控件的坐标点击会拒绝；当前没有宣称任意 App / 任意任务均可自动完成。
