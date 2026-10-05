# 第三方内容与标志

本仓库的 MIT 许可证适用于维护者编写的应用代码、测试、脚本和文档，不替代下列第三方材料的许可，也不授予第三方商标使用权。

## AIHOT 网站及标志

资讯、网站界面及品牌来自 [AIHOT](https://aihot.news/)。本项目是非官方 Android 阅读封装，与 AIHOT 官方无隶属关系，不托管或再许可网站新闻内容。

以下标志材料不属于本项目原创代码的 MIT 授权范围：

- `design/site-original/` 中的官网 PNG 原图。
- `design/ai-sulan-logo.*`、`design/ai-sulan-mark.*`、`design/logo-preview.png` 中的官网标志及其适配。
- `app/src/main/res/drawable-nodpi/aihot_site_icon.png`。
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` 以及 `scripts/render-logo.py` 中取自官网 SVG 的标志路径。
- `docs/images/` 中截图所含网站界面、新闻及第三方标志。

原图来源：[apple-icon.png](https://aihot.news/apple-icon.png)、[icon.png](https://aihot.news/icon.png)；来源记录见 [图标说明](design/README.md)。保留来源不等于取得独立商标或素材再授权；本项目不对这些材料另行授权。

## Gradle Wrapper

`gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar` 来自 Gradle，保留原文件中的版权信息，适用 Apache License 2.0。许可证全文见 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)。

## 构建依赖

依赖版本列于 `app/build.gradle.kts`。AndroidX、Kotlin、Gradle/Android 构建工具及测试依赖保留各自许可，不因本仓库采用 MIT 而改变。除 Gradle Wrapper 外，依赖源码及构建工具不作为本仓库源码的一部分复制分发。
