# 构建与设备验证环境

项目包含 Gradle 8.11.1 Wrapper，并固定官方发行包 SHA-256。`scripts/bootstrap-android.sh` 为 Linux x86_64 安装 Android SDK 35、Build Tools 35.0.0、Platform Tools 和完整 JDK 21；命令行工具和开发 JDK 均校验固定 SHA-256。工具链放在项目同级 `.toolchains/`，`local.properties` 不进入版本库。

2026-10-07 的云环境只有 Java 21 JRE，缺少 `javac` 和 `jlink`。已安装完整 JDK 21，Wrapper 在未指定 `JAVA_HOME` 时选择此 JDK。Wrapper 将继承的 HTTP(S) 代理主机和端口传给 Java，并保留现有代理及 CA 信任；下载没有关闭 TLS 校验或使用直连绕过代理。

本环境没有 `/dev/kvm`，API 35 AOSP x86_64 软件模拟器在首次启动时触发系统 Watchdog，`system_server` 因主线程阻塞 90 秒被重启。已改用最低支持版本 API 30 AOSP x86_64，单核、540×960、240 DPI。`scripts/start-emulator.sh` 在无 KVM 时默认此配置，有 KVM 时默认 API 35，可通过 `AI_ASSISTANT_EMULATOR_API` 和 `AI_ASSISTANT_EMULATOR_PORT` 选择版本与端口。

本次设备使用 `emulator-5556`。在可丢弃的 AOSP userdebug 模拟器中，使用 `adb root` 后首次写入尚未设置的 `ro.hw_timeout_multiplier=10` 成功，读取结果确认为 `10`。因为 Android 在 zygote 中缓存 Java `Build.HW_TIMEOUT_MULTIPLIER`，首次启动后才设置属性还需要执行 `adb shell stop` 和 `adb shell start`，让框架重新读取；完整内核重启会清除这次只读属性初始写入。这项设置用于放宽软件模拟器的系统超时，不改变应用代码、安全确认或业务超时。当前 SDK 的 `-prop ro.hw_timeout_multiplier=10` 参数无效，会提示仅支持 `qemu.*`，因此启动脚本不使用该参数。

设备端任务执行、Accessibility 截图与敏感操作确认由 `scripts/device-smoke.sh` 验证。Root 和 Shizuku 的实际授权、系统权限与不同厂商 ROM 行为，仍需在具备相应环境的真机上验证；模拟器的 `adb root` 不会给应用进程授予 `su` 权限。
