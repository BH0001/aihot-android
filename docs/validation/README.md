# 1.0.2 公开验证证据

这些文件是 2026-10-05 对 `dev.personal.aihotreader` 1.0.2（`versionCode=4`）实际构建与模拟器测试的脱敏副本。只替换本机路径并移除测试 XML 的主机名，测试结果、失败信息和耗时均保留。路径占位符为 `<PROJECT>`、`<TOOLCHAIN>`、`<LOCAL>`；本目录没有签名私钥或密码。

## 实际环境

| 环境 | 已验证范围 |
| --- | --- |
| Pixel 7 模拟器，Android 16 / API 36，WebView 133.0.6943.137 | 阅读器回归、真实页面主题与搜索、阅读位置重建恢复、收藏覆盖升级、断网重试、系统安全区与键盘、横屏及三键导航组合 |
| Pixel 2 模拟器，Android 8.0 / API 26，Chrome/WebView 69.0.3497.100 | 最终正式包安装及原生安全区、主题、菜单错误页、键盘等 5 项；当前 AIHOT 网站在旧 WebView 上不能完整运行 |

## 文件说明

- `v102-release-final.txt`：最终 Release 构建和签名验证；含 APK 与公开证书指纹。
- `v102-lint-release.txt`、`v102-navigation-unit-tests.xml`：Lint 0 错误、18 条警告；导航规则 14 项测试通过。
- `v102-device-core.txt`、`v102-keyboard-theme-recheck.txt`：核心检查首次 13 项中 12 项通过。键盘测试未明确设置 WebView 焦点而失败，补齐测试焦点后独立复测通过；两次结果均保留，没有将首次失败抹去。
- `v102-api26-edges.txt`：Android 8.0 原生布局等 5 项通过，不代表网站旧内核兼容通过。
- `v102-reading-restore-state.txt`：真实文章在 Activity 重建前后保持地址、标题和 `scrollY=5278`，随后返回首页。
- `v102-bookmark-seed.txt`、`v102-live-appearance-upgrade.txt`：在旧版通过网页按钮建立测试收藏；新版真实主题、搜索与升级后收藏共 3 项通过。
- `v102-offline-and-cleanup.txt`：断网阅读、原目标地址重试和测试收藏清理共 3 项通过。
- `v102-threebutton-hole-large-dark.txt`、`v102-portrait-modes.txt`、`v102-portrait-window.txt`：三键导航、模拟挖孔、字体 1.3、系统深色组合的 6 项测试，以及实际导航模式和窗口状态。
- `v102-landscape-corner.txt`、`v102-landscape-modes.txt`、`v102-landscape-window.txt`：手势导航、侧边挖孔、真实横屏组合的 2 项测试，以及实际导航模式和窗口状态。

## 未完成的验证

没有实体手机参与；WebView 144 或以上环境未实测。未进行长期耗电、内存压力和完整 TalkBack 人工验收。无外部浏览器、证书错误和渲染进程崩溃以代码审查为主，不能视为本次设备异常测试通过。浏览器预览不计作 APK 运行验收。

本目录只提供这里列出的 1.0.2 证据；旧版过程记录及本机未脱敏原始文件不随公开仓库提供。版本的完整说明见根目录 [VALIDATION.md](../../VALIDATION.md)。
