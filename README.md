# AI 热点 · AIHOT Android Reader

基于 [AIHOT（aihot.news）](https://aihot.news/) 网站的非官方安卓阅读器，使用 Kotlin + WebView，让手机查看 AI 资讯更方便。资讯直接来自原站，联网打开或刷新即可获取更新。

**[下载 APK](https://github.com/BH0001/aihot-android/releases/latest)** · [安装说明](INSTALLATION.md) · [构建说明](BUILDING.md) · [验证记录](VALIDATION.md)

本项目与 AIHOT 官方无隶属关系，不提供自己的新闻服务器、账号系统或采集后台。感谢 AIHOT 提供网站与资讯内容。

## 界面

<img src="docs/images/home-light.png" alt="AIHOT 浅色首页，Android 模拟器截图" width="260"> <img src="docs/images/home-dark.png" alt="AIHOT 深色首页，Android 模拟器截图" width="260">

以上为 1.0.2 的模拟器实测截图；1.0.3 仅更改应用名称，页面布局不变。新闻内容会随原站更新。

## 功能

- 浏览网站精选、热点、日报、模型榜、搜索与收藏。
- 状态栏和手势区域接续网页背景，支持网页独立深浅主题。
- 原生菜单、错误提示与刷新指示跟随网页主题。
- 保留网站导航，不额外叠加应用工具栏；支持下拉刷新。
- 长按页面空白处可回首页、刷新或分享当前页面；返回手势优先返回上一网页。
- AIHOT 站内页面在应用内阅读，第三方 HTTP(S) 链接交给系统浏览器。
- 主页面失败时提供重试，子资源失败不遮盖正文；断网时尽量保留已加载内容。
- 收藏保存在应用本地；没有广告/统计 SDK、通知或额外账户系统。

## 安装与兼容性

当前版本 **1.0.3**，内部版本号 **5**，包名 `dev.personal.aihotreader`。

本次将应用名称由「AI 速览」改为「AI 热点」，功能保持不变。包名与发布签名沿用旧版，可直接覆盖安装，保留原有收藏；不要先卸载。

最低可安装系统为 Android 8.0（API 26）。网站还需要较新的 Android System WebView，系统版本达标并不代表旧 WebView 能完整运行当前网站。

| 1.0.2 历史实测环境 | 验证范围 |
| --- | --- |
| Android 16 / WebView 133 模拟器 | 阅读、搜索、主题、横竖屏、挖孔、键盘、收藏升级保留、文章位置恢复和断网重试 |
| Android 8.0 / WebView 69 模拟器 | 原生布局、主题、菜单及键盘；该旧内核无法完整运行 AIHOT 网站 |

上述为 1.0.2 的历史验证结果，1.0.3 本次检查单独记录于[验证记录](VALIDATION.md)。实体手机、WebView 144 以上和长时间耗电表现尚未实测。历史首次失败与复测结果见[脱敏证据](docs/validation/README.md)。

## 构建

安装 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0，设置 `JAVA_HOME`、`ANDROID_HOME`，或在不提交的 `local.properties` 中配置 `sdk.dir`。

```sh
# macOS / Linux
./gradlew testDebugUnitTest lintRelease assembleDebug
```

```powershell
# Windows
.\gradlew.bat testDebugUnitTest lintRelease assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/`。Gradle Wrapper 固定为 8.13，AGP 8.13.2、Kotlin 2.2.21。签名构建和设备测试见 [BUILDING.md](BUILDING.md)。

## 数据与升级

应用内收藏与浏览器收藏独立保存，没有云同步。使用同一签名和包名、更高版本号的 APK 覆盖安装可保留应用数据；卸载、清除数据或换手机后的恢复不在当前范围内。

应用只申请联网与网络状态权限，不添加原生脚本桥，并关闭 WebView 本地文件访问。网站自行产生的网络请求与数据由原站维护。

## 目录

| 目录 | 内容 |
| --- | --- |
| `app/src/main` | Android 应用、导航规则、图标与页面背景读取脚本 |
| `app/src/test` | 导航策略单元测试 |
| `app/src/androidTest` | 设备端验证及真实网站回归测试 |
| `scripts` | 本机 Release 构建、设备验证、图标导出与打包 |
| `design` | AIHOT 图标原图、适配文件和来源说明 |
| `docs` | 截图、脱敏验证证据与版本说明 |

构建缓存、原始本机验证日志、签名材料及 `dist/` 不提交到仓库。公开安装包见 [Releases](https://github.com/BH0001/aihot-android/releases)。

## 许可证与来源

本项目原创代码采用 [MIT License](LICENSE)。AIHOT 网站、Logo、新闻及第三方品牌保留各自权利，不包含在本项目 MIT 再授权范围内；Gradle Wrapper 等使用其各自许可证。具体范围见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
