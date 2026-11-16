# 隐私说明 / Privacy Policy

CueFlow 的设计目标是“数据不离开设备”。

## 收集哪些数据？

**不收集任何数据。**

- 没有账号系统。
- 没有 `INTERNET` 权限，应用在技术上无法向网络发送内容。
- 没有广告 SDK、统计 SDK、崩溃上报 SDK 或推送 SDK。
- 没有设备标识符、位置或通讯录访问。
- 相机和麦克风只在用户主动点击“录制视频”时访问，用于本地录制。
- 导入的 Word、PDF、Markdown、HTML、RTF、ODT、EPUB 等文档只在本地读取；不会上传，也不会申请网络权限。
- 图片和扫描版 PDF 的 OCR 使用随 APK 打包的 PP-OCRv6 small 模型，在设备本地推理，不会上传图片或识别结果。

## 数据保存在哪里？

- 脚本：应用私有目录中的 `files/scripts.json`。
- 设置：应用私有的 `SharedPreferences`。
- 录制视频：Android 10+ 保存到系统相册的 `Movies/CueFlow`；Android 7–9 保存到应用外部文件目录，并可通过系统分享导出。
- 应用关闭系统云备份和设备迁移备份；需要迁移时请使用内置的“备份全部脚本 / 从备份恢复”。

卸载应用会删除应用私有目录中的脚本。请先导出备份。

## 导出与备份

导出的 `.txt` 和 `.json` 文件由用户自己选择保存位置。这些文件是明文的，可能被其他应用或云盘读取，请妥善保管。

## 权限

- `CAMERA`：仅在用户打开录制小窗时使用。
- `RECORD_AUDIO`：仅在用户允许并开始录制时录入声音；拒绝后仍可录制无声视频。
- 不声明网络权限，也不声明存储权限。

## 录制文件

录制文件只保存在本机。应用不会上传，也不会自动分享。只有用户点击“分享”时，文件才会通过 Android 系统分享面板交给用户选择的目标应用。

## 联系

如对隐私有疑问，请通过项目仓库的 Issue 联系维护者。

---

CueFlow collects no data and requests no network permission. Scripts and settings live only in the app's private storage. Exported backups are plain text and are controlled entirely by the user.
