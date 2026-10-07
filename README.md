# 舟行 AI · Android Phone Agent

独立实现的 Android AI 手机助手 MVP：**一句话 → Planner → 手机执行 → 验证结果**。借鉴任务规划、能力路由、屏幕观察与反馈的通用架构，不包含 Qwen Intelligence 的闭源代码，也不依赖其私有接口。

需要 Android 11 / API 30 及以上。源码在 `app/` 和 `core/`；构建好的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 已实现

- 文字输入、系统语音识别输入；中文任务界面、状态与步骤记录。
- AI Planner：用户配置兼容 OpenAI Chat Completions 的 HTTPS 接口，支持 Qwen；严格解析结构化计划、限制步骤与重试预算。
- 离线 Planner：支持打开系统设置、已安装应用、网址、浏览器搜索、跨 App 顺序任务，以及授权后的 WiFi / 亮度控制。
- 执行顺序：Intent → DeepLink → HTTPS API → 固定 Shell 命令 → Accessibility。每一步必须提供结果验证条件。
- Accessibility：读取新鲜控件树、按精确选择器点击、填写、滚动；请求时获取截图并交给支持视觉的模型定位。模糊、无效、密码控件与不完整屏幕快照会拒绝操作。
- 完成验证：前台包名、可见页面文字、实际系统设置值、HTTP 状态；发出操作不算完成。
- 状态机、原子写入任务记忆、执行日志、断点恢复、安全操作有限重试、一次有界重规划。未知副作用暂停，避免重复发送或付款。
- Root：真实 `su` 执行固定命令；Shizuku：真实 AIDL UserService 执行固定命令。能力未授权时给出失败证据。
- 付款、删除、发送消息、API 写入、自定义链接及不确定的界面操作必须二次确认。确认绑定会话、步骤、实际参数和一次性凭证；界面操作还绑定当前页面，变化后重新确认。
- 用户主动启动的前台任务服务；通知显示状态和停止操作。云端文字与截图分别选择，截图上传默认关闭，Key 由 Android Keystore 加密保存，禁用备份。

## 安装和试用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开「舟行 AI」→「连接」→ 开启无障碍服务，阅读提示后在系统设置中开启。
2. 不需要模型 Key 即可输入 `打开设置`，或 `打开设置，然后打开时钟`（App 名称以设备实际安装名称为准）。执行后查看任务记忆中的结果证据。
3. 使用 AI：进入「配置模型与 API 白名单」，填写自己的 HTTPS `chat/completions` 地址、模型名称与 Key，选择允许云端规划。默认示例地址：`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`，模型 `qwen-vl-max`。不同地区和供应商请修改地址；项目不附带凭证。
4. 需要视觉定位时，单独允许截图上传并使用支持图片输入的模型。截图不保存到任务记忆。当前 MVP 的坐标操作必须落在唯一、可识别的可点击控件上；纯画布、没有 Accessibility 控件的页面会安全拒绝。
5. 需要系统控制时选择 Root 或 Shizuku，并在相应工具中授权。然后可输入 `关闭 WiFi`、`把亮度设置到 40%`。固定命令清单见架构文档。
6. 遇到待确认操作，点击任务通知返回助手，检查实际目标、输入内容、API 方法/请求体或系统命令，确认后返回目标 App 执行。屏幕变化会要求重新核对。

语音输入依赖手机上已安装的语音识别服务。浏览器任务需要设置默认浏览器并保证网页网络可用。离线搜索的证据仅是页面出现查询文字，不代表搜索结果正确或业务目标完成。

## 构建

环境：完整 JDK 21（产物目标 JVM 17）、Android SDK 35 / build-tools 35.0.0、Gradle 8.11.1。Gradle Wrapper 和 Linux 引导脚本已提供，下载使用 SHA256 校验。

```bash
# 已有 Android Studio SDK：在 local.properties 写 sdk.dir=/你的/sdk/目录
# Linux 首次环境可用以下脚本下载到 ../.toolchains 并接受 Android SDK 许可
./scripts/bootstrap-android.sh --accept-licenses
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`local.properties`、构建缓存、签名文件不进入版本库。未配置 `JAVA_HOME` 时 Wrapper 可使用引导脚本安装的 JDK。云端环境存在代理时 Wrapper 保留继承代理与系统 CA。

## 测试和项目结构

```text
core/   纯 Kotlin 契约、Planner、校验、安全策略、任务引擎、回归测试
app/    Android 界面、前台任务服务、无障碍、网络、Root / Shizuku、原子存储
docs/   架构、安全边界和实际验证记录
scripts/ 可复现的工具链、模拟器和设备冒烟测试
```

```bash
./scripts/start-emulator.sh
# 等待 sys.boot_completed=1；下列脚本只在测试模拟器自动授权无障碍与通知
./scripts/device-smoke.sh
```

设备测试覆盖打开设置、跨 App 顺序任务、以及「显示」设置点击前确认/点击后验证；当前运行状态与结果见验证记录。固定计划夹具仅替代模型响应，执行、确认、存储与验证使用真实 Android 适配器。真实手机自动权限配置必须显式设置 `ALLOW_REAL_DEVICE=1`。

实际通过的检查和未验证范围记录在 [docs/VALIDATION.md](docs/VALIDATION.md)。架构与安全分析见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)、[docs/THREAT_MODEL.md](docs/THREAT_MODEL.md)。

## MVP 边界

这是可构建、可安装的原生原型，复杂第三方 App 的流程由模型及可观察界面决定。每个验证条件只证明指定事实，任意任务仍可能规划错误；支付业务最终状态、任意 App 全覆盖、纯画布自动化不在当前验证范围。敏感操作也可能因页面变化而暂停。

任务 API 只访问用户配置的精确 HTTPS 域名白名单，拒绝私有 DNS 和重定向；当前不支持为任意任务 API 配置账户认证，模型 Key 不会转发给任务 API。Root / Shizuku 的实际可用性依赖设备、系统版本及授权，不会默认启用。

无后台唤醒词、无限循环、任意 shell、验证码/密码自动填写或直接支付接口。任务通知与系统无障碍开关提供停止路径；将应用上架需要根据实际分发渠道补充隐私政策和 Android 权限用途审核。
