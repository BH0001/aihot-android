# 构建说明

## 固定版本

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.13 |
| Kotlin | 2.2.21 |
| Android compileSdk / targetSdk | 36 |
| Android Build Tools | 35.0.0 |
| Android minSdk | 26 |

工具均从官方发布源取得。本机工具保存在工程旁的 `android-toolchain` 中，不修改系统全局 PATH，不随源码包分发。该目录下的 `toolchain.json` 记录 `javaHome`、`androidHome` 和 `gradleHome` 三个绝对路径。

## 在本机复现

在 PowerShell 7（`pwsh`）中执行，本项目脚本不支持系统自带的 Windows PowerShell 5.1：

```powershell
.\scripts\build-release.ps1 -VersionCode 4
```

脚本设置当前进程的工具路径，执行单元测试、Release Lint、Release 打包与签名校验。首次构建需要联网下载 Google/Maven Central 的依赖。下载所需的网络权限按环境规则处理。

## 在其他电脑构建

安装上述 JDK 与 Android SDK。将 `JAVA_HOME` 指向 JDK，`ANDROID_HOME` 指向 SDK；也可通过未提交的 `local.properties` 设置 `sdk.dir`。直接运行：

```powershell
.\gradlew.bat testDebugUnitTest lintRelease assembleDebug
```

Debug APK 可用于开发验证。正式升级必须继续使用原 Release 签名，不能用 Debug 签名覆盖正式安装。

正式签名配置通过环境变量 `AIHOT_SIGNING_PROPERTIES` 或参数 `-PsigningProperties=<绝对路径>` 指定。配置文件是 Java Properties 格式：

```properties
storeFile=C:/private/location/aihot-reader.p12
storePassword=<本地密码>
keyAlias=aihot-reader
keyPassword=<本地密码>
```

构建时未配置正式签名会产生未签名的 Release 包，不能当作可安装交付；本项目的 `build-release.ps1` 会提前拒绝这种情况。签名密钥、密码及配置文件不应被提交或加入源码 ZIP。

本次交付版本名为 `1.0.2`，内部版本号 `versionCode=4`。版本号 1 仅用于本机覆盖升级验证，没有作为成品分发。后续正式更新保持包名与签名不变，通过 `-PappVersionCode=5`（或更大整数）增加版本号。

交付归档在完成 `VALIDATION.md` 后由 `.\scripts\package-release.ps1` 生成，默认位于 `dist/<版本号>`。源码包不包含构建工具、缓存、应用数据或私钥。整理项目时已删除构建中间文件，重新打包前先构建正式 APK。


## 设备验证

自行构建可在专用模拟器或测试设备上运行 Debug 测试，无需维护者的 Release 私钥。例如：

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.personal.aihotreader.EdgeToEdgeTest
```

Windows 将 `./gradlew` 换成 `.\gradlew.bat`。同包名不同签名的 Debug 包无法覆盖发布包，建议使用单独的测试模拟器。

使用 `scripts/test-device.ps1 -Build -InstallTarget` 构建正式 APK 和同签名测试包并安装到本机模拟器。使用 `-Classes` 指定测试类，多个类名须放入同一个引号字符串。真实收藏覆盖升级验证先在旧版执行 `-BookmarkPhase seed`，覆盖安装后执行 `verify`，最后执行 `cleanup`，保留原有收藏。

`EdgeToEdgeTest` 检查安全区、主题及键盘开关；`LiveAppearanceTest` 通过网站实际按钮切换主题并保存截图。Logo 导出脚本的依赖见 `design/README.md`。

以上 PowerShell 辅助脚本面向维护者本机的相邻工具链布局；它们不代表仓库已经包含 Android SDK 或签名材料。公开脱敏验证证据位于 `docs/validation`。
