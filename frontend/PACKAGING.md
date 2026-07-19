# Android 与 Windows 打包

客户端默认连接已部署站点 `https://zhiyexueye.top`，安装后需要联网。Java 后端、MySQL、Redis 和 AI 服务不会被写入客户端安装包。

## Android APK

Capacitor 8 的 Android 构建需要 JDK 21。首次构建前安装 JDK 21，并设置 Android SDK 路径（Windows PowerShell）：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
npm run package:android
```

调试 APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`，整理后的副本位于 `release/android/`。正式发布前应创建并妥善保存签名密钥，再使用 Android Studio 或 Gradle 生成签名 release APK/AAB；不要把签名密钥或密码提交到 Git。

## Windows EXE

```powershell
npm run package:windows
```

NSIS 安装包输出到 `release/windows/`。当前产物未购买代码签名证书，因此 Windows SmartScreen 可能显示未知发布者。

## 更换服务地址

- Android：修改 `capacitor.config.json` 的 `server.url` 后执行 `npm run sync:android`。
- Windows：运行前设置环境变量 `VISIONARY_TUTOR_APP_URL`；发布固定地址时修改 `electron/main.cjs` 的默认值并重新打包。
