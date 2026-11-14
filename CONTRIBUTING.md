# 贡献指南

感谢你愿意改进 CueFlow。这个项目面向真实拍摄和长时间阅读场景，稳定性和可预测性比“功能多”更重要。

## 开发环境

- JDK 17+
- Android SDK 35（Platform + Build-Tools 35.0.0）
- Android Studio Ladybug 或更新版本（可选）
- 一台 Android 7.0+ 设备或模拟器

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

## 代码约定

- Kotlin 官方代码风格，4 空格缩进。
- UI 只使用 Material 3 组件；颜色、字号、圆角从 `MaterialTheme` 读取，不硬编码品牌色。
- 新的用户可见文本必须同时写入 `values/strings.xml` 和 `values-zh-rCN/strings.xml`。
- 数据格式变更必须提升 `ScriptCodec.CURRENT_SCHEMA`，并提供向后兼容的读取逻辑与测试。
- 所有文件写入必须经过 `ScriptRepository` 的原子写入；不要在 UI 层直接写 `filesDir`。
- 不要在主线程执行磁盘 I/O。
- 交互元素需要 content description 或可见文本；触控目标不小于 48dp。

## 硬性约束

以下改动不会被接受：

- 添加 `INTERNET` 权限、广告、分析、账号或远程配置。
- 引入需要联网才能使用的功能。
- 收集脚本内容、设备标识或使用行为。
- 为了“功能丰富”而显著增加冷启动时间或常驻内存。
- 破坏 Android 7.0（API 24）兼容性。

## 提交 PR

1. Fork 并创建分支。
2. 写清改动目的和用户影响。
3. 补充/更新测试。
4. 确保 `testDebugUnitTest` 与 `lintDebug` 通过。
5. UI 改动请附截图（亮色/深色至少各一张，若涉及提词界面请附横屏）。
6. 在 PR 描述中说明是否改变数据格式或权限。

## 报告问题

请提供：

- 手机型号、Android 版本、应用版本；
- 复现步骤；
- 预期结果与实际结果；
- 如涉及崩溃，附 `adb logcat` 中 `com.cueflow.app` 相关片段。

安全问题请不要公开提交，见 [SECURITY.md](SECURITY.md)。
