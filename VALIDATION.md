# AI 热点 1.0.3 名称变更验证

日期：2026-10-05。包名 `dev.personal.aihotreader`，版本 `1.0.3`，`versionCode=5`。

本次将应用名称由「AI 速览」改为「AI 热点」，同步项目说明、安装文档和图标预览。未更改阅读、导航、收藏、主题或系统安全区的应用逻辑。打包脚本同时将许可证和第三方标志说明随交付文件保留。

## 本次实际检查

- Release 构建成功；现有导航单元测试 14 项通过。
- Release Lint：0 错误、18 条警告，数量与 1.0.2 相同。
- 直接读取最终 APK：应用名称为 `AI 热点`，包名 `dev.personal.aihotreader`，版本 `1.0.3`、内部版本号 `5`，minSdk 26、targetSdk 36。
- `apksigner verify` 通过，使用 APK Signature Scheme v2；RSA 3072 位签名与 1.0.2 一致。
- APK SHA-256：`0f04db911a31dbf0adb852afea00ef26218bec0156ba7756889dc845de7e9b8d`。
- 签名证书 SHA-256：`9cada096072666bee46a3fa549020df0ef65506ca882990163f7641a0400631c`。
- 图标预览已重新生成并检查，展示名称为「AI 热点」，继续沿用 AIHOT 网站标志。

构建、Lint、导航测试与 APK 元数据的脱敏证据见 [1.0.3 证据目录](docs/validation/v1.0.3)。

## 运行检查与历史记录

本次启动已有 Android 16 模拟器后，ADB 持续显示设备离线，未完成实际安装或启动验证；应用名称、版本和签名以最终 APK 的静态核验为准。未重跑收藏升级或完整阅读回归，不将 1.0.2 的功能测试计作本版通过。没有实体手机参与；WebView 144 或以上环境仍未实测。旧 WebView 69 不能完整运行当前 AIHOT 网站。

1.0.2 的主题、导航、键盘、横竖屏、真实网站、收藏覆盖升级及异常检查详见[历史验证报告](docs/validation/v1.0.2.md)及[历史脱敏证据](docs/validation/README.md)。README 的网站截图明确标注为 1.0.2 历史截图。

## 交付

签名 APK、源码 ZIP、Logo ZIP、安装说明及 SHA-256 校验文件存于 `dist/1.0.3`，公开下载见 [v1.0.3 Release](https://github.com/BH0001/aihot-android/releases/tag/v1.0.3)。签名私钥、密码和本机配置不进入仓库或源码包。
