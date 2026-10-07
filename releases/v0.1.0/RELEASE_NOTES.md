舟行 AI 首个 MVP，独立实现，面向 Android 11 及以上。

安装下方 `PhoneAgent-0.1.0-debug.apk`，打开「连接」启用无障碍服务，然后输入「打开设置」试用。此安装包使用 Debug 签名，适合个人测试。

- 文字与系统语音输入；离线规则或兼容 OpenAI / Qwen 的模型 Planner。
- 优先使用 Intent、DeepLink、HTTPS API、固定 Shell 命令；支持 Accessibility 页面读取、点击、填写和视觉定位。
- 跨 App 步骤、任务记忆、有限重试、恢复检查点和结果验证。
- Root / Shizuku 适配器；敏感或不确定操作逐步二次确认。
- 模型 Key 由 Android Keystore 加密；云端规划与截图上传分别选择，默认关闭。

验证：56 项核心及适配器单元测试通过，APK 编译和签名验证通过，Lint 0 错误。设备测试夹具已修复并编译，但 3 项实际设备业务场景尚未取得通过结果，请使用仓库的 `scripts/device-smoke.sh` 在自己的测试设备上运行。云模型、麦克风、Root / Shizuku 真机授权尚未实测。

使用 AI 规划需配置自己的模型接口与 Key。纯画布且没有唯一 Accessibility 控件的页面目前会拒绝点击。具体架构、安装步骤及验证范围见仓库 README 与 docs/VALIDATION.md。

附件包含 APK、源码 ZIP 和 SHA256 校验清单。
