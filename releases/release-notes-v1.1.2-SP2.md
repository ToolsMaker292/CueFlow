# CueFlow 1.1.2 SP2

CueFlow 1.1.2 SP2 — 修复 PDF 一律走 OCR 的问题。

## 下载

- `CueFlow-1.1.2-SP2.apk`（最新正式签名版，Android 7.0+）
- 旧版本保留：
  - [v1.1.2](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.1.2)
  - [v1.1.1](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.1.1)
  - [v1.1.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.1.0)
  - [v1.0.2](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.2)
  - [v1.0.1](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.1)
  - [v1.0.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.0)

## 本次修复

- 文字型 PDF 现在先用 PDFBox 读取文字层，直接导入原文，不再进入 OCR。
- 只有没有文字层的扫描版 PDF 才进入逐页 OCR。
- OCR 分支继续按页串行处理：每页 Bitmap 在 `finally` 中回收，`PdfRenderer`、文件描述符和 TFLite 解释器都在 `use` / `finally` 中释放，避免旧问题时序导致的重复回收和内存泄漏。
- 保留 1.1.2 的拍照识别、PP-OCRv6 small 离线 OCR 和所有旧功能。

## 校验

SHA-256：

`60E71D530A62283A675ADCFCFEDE7A453378334895974DFA3098EC1F44737EA5`

## 说明

- 完全离线，无 `INTERNET` 权限。
- 相机/麦克风只在本机录制或拍照识别时使用。
- APK 使用项目测试签名证书；发布到应用商店前请用自己的密钥重新签名。
