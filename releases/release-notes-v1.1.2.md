# CueFlow 1.1.2

CueFlow 1.1.2 — 新增独立【拍照识别】入口，拍照后直接使用现有离线 OCR。

## 下载

- `CueFlow-1.1.2.apk`（最新正式签名版，Android 7.0+）
- 旧版本保留：
  - [v1.1.1](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.1.1)
  - [v1.1.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.1.0)
  - [v1.0.2](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.2)
  - [v1.0.1](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.1)
  - [v1.0.0](https://github.com/ToolsMaker292/CueFlow/releases/tag/v1.0.0)

## 本次更新

- 首页新增【拍照识别】按钮，打开相机预览，拍照后直接进入离线 OCR。
- 拍照与视频录制共用同一个 `CameraRuntime` / CameraX provider，不重复初始化相机。
- 绑定时主动解绑旧 session，页面退出只释放自己的 session，避免拍照和录像争抢相机。
- 拍照 JPEG 继续走 `MainViewModel.importDocument` → `OcrImporter` → `PP-OCRv6 small`，与图片导入共用 OCR 逻辑。
- 保留 EXIF 方向处理与 90°/180°/270° 自动旋转重试。
- 保留 1.1.1 及更早版本的 APK 和 Release。

## 校验

SHA-256：

`4E10D01A46EC7F975CFCCDD244BC4ABA02E71EC8F4E88D024A3990502271F8E5`

## 说明

- 完全离线，无 `INTERNET` 权限。
- 相机/麦克风只在本机录制或拍照识别时使用。
- APK 使用项目测试签名证书；发布到应用商店前请用自己的密钥重新签名。
