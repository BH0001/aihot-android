# AIHOT 图标适配

正式 App 沿用 AIHOT 网站现有标志。本目录仅保留最终采用的图标、官网原图及预览。

- 原始彩色图标：https://aihot.news/apple-icon.png （180 × 180）、https://aihot.news/icon.png （128 × 128）。下载日期：2026-10-05。
- Android 彩色图层直接使用官网 180 像素 PNG，保持原图；适配桌面系统遮罩。
- 单色矢量取自官网首页 `AIHOT 首页` 链接内 SVG 的 O 标志两条路径，归一化到 Android 安全区。
- `ai-sulan-logo.svg` 是嵌入原图的 SVG 容器，不是全矢量重绘；1024 PNG 是放大导出，不能视为新增原始细节。
- `ai-sulan-mark.svg` 是可编辑的单色矢量图形。
- AIHOT 标志来源于网站，未声称是本项目原创。App 为个人阅读封装。

这些 AIHOT 标志材料不适用本项目原创代码的 MIT 再授权，范围见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

运行 `python scripts/render-logo.py` 可重新生成资源，需要 Pillow 和 CairoSVG；预览排版使用 Windows 的 Microsoft YaHei 字体。其他系统需替换脚本中的字体路径，构建 APK 本身不需要运行该脚本。
