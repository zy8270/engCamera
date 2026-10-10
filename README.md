# engCamera · 水印相机

一个自用向的 Android 工程水印相机：拍照 / 录像前实时预览水印，成片直接把水印**烧录**进文件。无广告、无登录、无云同步。

> 当前版本：v0.2.6-alpha（迭代中，详见 [交付说明](交付说明.md)）

## 功能特性

- **水印实时预览 + 烧录成片**：拍照前所见即所得，水印直接烧入 JPEG / MP4，不依赖元数据。
- **可配置水印模板**：工程名称（标题）、时间、位置、计数、备注、自定义字段；可增删、拖动排序、启用/隐藏；连续底色样式（标题蓝条 + 正文半透明黑块，统一圆角）。
- **自动定位与中文地址**：定位水印自动反查中文地址（如"广东省广州市天河区"），无网降级为坐标显示；位置/时间支持手动编辑，标签有值优先。
- **计数字段**：拍照成功自动 +1，适合施工/巡检打卡留痕。
- **重力方向跟随**：横拍（±90°）/ 倒置（180°）时水印图标原地旋转、自动重置到对齐角；四个方向成片方向对齐系统相机，水印恒落左下、文字恒正立。
- **水印拖动**：任意方向下可在画面内自由拖动，边界约束三处（预览 / 照片烧录 / 视频合成）共用。
- **录像水印**：录制结束后通过 Media3 Transformer 离线转码烧入，支持画质档位选择（最高 / 超清 / 全高清 / 高清 / 标清）。
- **扫码**：内嵌 ML Kit 二维码/条码识别，识别后气泡提示、一键复制。
- **相册**：按拍摄时间从新到旧展示全部照片/视频，多选删除、捏合调列数、点击跳系统相册查看。
- **历史快填**：字段编辑时显示该标签最近填写的内容（最多 5 条），点击直接填入。

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose（Material3） |
| 相机 | CameraX 1.6.2（拍照 + VideoCapture） |
| 视频水印 | Media3 Transformer 1.11.1（录后转码） |
| 扫码 | ML Kit Barcode Scanning（离线内嵌） |
| 定位 | FusedLocationProviderClient |
| 持久化 | DataStore Preferences + kotlinx.serialization |
| 相册 | MediaStore + Coil |

minSdk 24（Android 7.0+），targetSdk 36。

## 构建

```bash
./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

或在 Android Studio 中直接打开构建。

## 保存位置

成片保存到 `DCIM/Camera/`，文件名前缀 `engCamera_`（如 `engCamera_20261010_153000.jpg`）。

## 已验证设备

- Redmi Note 12 Turbo（Android 15 / MIUI）：四方向拍照、录像水印、7000+ 项相册、中文地址定位
- 模拟器 Pixel 10 Pro

## License

[MIT](LICENSE)
