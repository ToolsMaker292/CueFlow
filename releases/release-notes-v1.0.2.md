# CueFlow 1.0.2

CueFlow 1.0.2 — 本地优先的 Android 提词器，新增多格式文档导入。

## 下载

- `CueFlow-1.0.2.apk`（最新正式签名版，Android 7.0+）
- 旧版本保留：
  - [v1.0.1](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.1)
  - [v1.0.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.0)

## 本次更新

支持 10 种常用文档格式：

- Word：`.docx`、`.doc`
- PDF：`.pdf`（文字型；扫描版需要 OCR）
- 纯文本：`.txt`、`.md` / `.markdown`、`.csv`
- 网页：`.html` / `.htm` / `.xhtml`
- 富文本：`.rtf`
- 办公文档：`.odt`
- 电子书：`.epub`

所有格式都在设备本地解析，不上传原文件，不需要网络权限。文件选择器已覆盖这些扩展名；导入失败时会区分格式不支持、没有文字和文件过大。

## 校验

SHA-256：

`37705783129A2A616F509E8C46B82FA7D36B3AC7CA71544D3EBFF3C3493B8525`

## 说明

- 完全离线，无 `INTERNET` 权限。
- 相机/麦克风只在本机录制时使用。
- APK 使用项目测试签名证书；发布到应用商店前请用你自己的密钥重新签名。
