# 派点客户端 · 安卓 APP 打包指南（Capacitor）

这个文件夹是把「客户端网页」打包成安卓 .apk 的工程。
网页已构建好放在 `www/`，APP 启动后自动进入客户端首页（我的设备）。
APP 通过 **http 连公网 IP** 访问 netsend（已配置允许明文 http）。

---

## 一、准备（在你自己的电脑上，一次性）

1. 安装 **Node.js**（建议 18+）。
2. 安装 **Android Studio**（自带 Android SDK、Gradle、JDK）。
   - 首次打开 Android Studio，按提示装好 Android SDK（API 33/34）。
3. （可选）手机打开「开发者选项 → USB 调试」，用数据线连电脑可直接装到手机。

---

## 二、生成安卓工程并编译 apk

在本文件夹（`netsend-client-app`）打开命令行，依次执行：

```bash
npm install                 # 安装 Capacitor
npx cap add android         # 第一次生成 android/ 工程（联网下载模板）
npx cap sync android        # 把 www/ 同步进安卓工程
npx cap open android        # 用 Android Studio 打开
```

在 Android Studio 里：
- 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- 编译完成后点 **locate** 找到 apk：
  `android/app/build/outputs/apk/debug/app-debug.apk`
- 把这个 apk 发给客户安装即可（手机需允许「安装未知来源应用」）。

> 用数据线连手机时，也可直接在 Android Studio 点 ▶ Run 安装运行。

---

## 三、首次使用 APP

1. 安装并打开 APP。
2. 右上角 **齿轮（服务器设置）** → 填 netsend 地址，例如：
   `http://你的公网IP:8000`（注意带 http:// 和端口）
3. 保存并重连 → 顶部显示「已连接」即可。

> 服务器地址记在手机本地，下次打开自动用。换地址随时在设置里改，不用重打包。

---

## 四、明文 http 说明（重要）

客户端走 **http 连公网 IP**，安卓默认禁止明文。本工程的 `capacitor.config.json` 已设：
```json
"server": { "androidScheme": "http", "cleartext": true }
```
`cap sync` 后 Capacitor 会在安卓工程里允许明文。若个别机型仍报网络错误，按下面补一步：

打开 `android/app/src/main/AndroidManifest.xml`，给 `<application ...>` 加一句：
```xml
<application android:usesCleartextTraffic="true" ...>
```
再重新 Build。

（更安全的做法是给 netsend 上 HTTPS 证书，到时再说。）

---

## 五、改了网页内容后如何更新 apk

客户端网页代码在隔壁 `netsend-frontend`。改完后：
```bash
# 在 netsend-frontend 里
npm run build
# 把新产物覆盖到本工程 www/
#   把 netsend-frontend/dist/* 复制到 netsend-client-app/www/
#   并保留 www/index.html 里那句 #/c/devices 跳转（覆盖后需重新加，或只覆盖 assets/）
# 然后回本工程
npx cap sync android
# Android Studio 重新 Build APK
```

> 提示：每次覆盖 `www/index.html` 后，确保 `<head>` 里仍有这句：
> `<script>if(!location.hash){location.hash="#/c/devices"}</script>`

---

## 六、应用名 / 图标 / 启动图（可选美化）

- 应用名：`capacitor.config.json` 的 `appName`，或 `android/app/src/main/res/values/strings.xml` 的 `app_name`。
- 应用包名：`appId`（现为 `com.pid.client`，正式发布前可改成你自己的域名倒写）。
- 图标/启动图：把图标放进 `android/app/src/main/res/mipmap-*`，或用
  `@capacitor/assets` 自动生成（`npx @capacitor/assets generate`，需准备 1024×1024 图标）。

---

## 七、常见问题

- **打开就白屏**：多半是服务器地址没填或填错。点齿轮重填 `http://IP:8000`。
- **未连接**：检查手机能否访问该公网 IP:8000（浏览器直接打开试试 `http://IP:8000/api/devices`）。
- **构建报 SDK / Gradle 错误**：用 Android Studio 自带的 SDK/Gradle，按提示同步一次（File → Sync Project with Gradle Files）。
