# CueFlow 1.1.1

CueFlow 1.1.1 — 修复图片 OCR 在部分相册/相机图片上失败的问题。

## 下载

- `CueFlow-1.1.1.apk`（最新正式签名版，Android 7.0+）
- 旧版本保留：
  - [v1.1.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.1.0)
  - [v1.0.2](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.2)
  - [v1.0.1](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.1)
  - [v1.0.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.0)

## 本次修复

- 图片只读取一次并写入临时文件，兼容只允许打开一次的内容提供器（部分相册/文件管理器）。
- 读取 EXIF 方向并自动旋转，修复手机竖拍照片方向错误。
- 首次识别失败时自动尝试 90° / 180° / 270° 旋转，再判定是否失败。
- 保留 PP-OCRv6 small 离线 OCR、图片格式和扫描 PDF 支持。

## 校验

SHA-256：

`875A0802C04943652CB3793F5F6B8352361344B565A38E6555DCB063CE720EEB`

## 说明

- 完全离线，无 `INTERNET` 权限。
- 相机/麦克风只在本机录制时使用。
- APK 使用项目测试签名证书；发布到应用商店前请用自己的密钥重新签名。
