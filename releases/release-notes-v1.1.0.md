# CueFlow 1.1.0

CueFlow 1.1.0 — 本地优先的 Android 提词器，新增 PP-OCRv6 small 离线 OCR。

## 下载

- `CueFlow-1.1.0.apk`（最新正式签名版，Android 7.0+）
- 旧版本保留：
  - [v1.0.2](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.2)
  - [v1.0.1](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.1)
  - [v1.0.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.0)

## 本次更新

- 新增 **PP-OCRv6 small** 离线 OCR（检测 + 识别模型约 34 MB）。
- 支持导入 PNG / JPG / WebP / BMP 图片，识别后生成脚本。
- 扫描版 PDF 没有文字层时，自动逐页本地 OCR；文字型 PDF 仍优先直接提取。
- 支持简体中文、繁体中文、英文、日文及多种拉丁语系文字。
- 纯本地 TensorFlow Lite CPU 推理，不联网、不上传图片。
- 导入时显示“正在本地识别”进度；模型按需加载并在识别结束后释放。
- 保留 1.0.0 / 1.0.1 / 1.0.2 的 APK 和 Release。

## 校验

SHA-256：

`E04AF2387F5220E40F7E7672F3BB4098357ACD5D833C5E4849A4B42F5A2D2129`

## 说明

- 完全离线，无 `INTERNET` 权限。
- 相机/麦克风只在本机录制时使用。
- APK 使用项目测试签名证书；发布到应用商店前请用你自己的密钥重新签名。
