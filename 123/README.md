# DeviceProbe — 设备信息探测 App + GitHub 云端编译

一个极简、只读的安卓 App:点一个按钮,把"系统/机型/ABI、root、`/dev/tty*` 串口节点(重点 `/dev/ttyS1`)、USB 设备、外部存储读写"全探测一遍,显示在屏幕并写入 `/sdcard/device_probe_result.txt`。**不修改设备任何东西**。

- 版本 0.1.0;界面就一个按钮 + 滚动文本框;零第三方依赖。
- 目标 `targetSdk 28` / `minSdk 21`,兼容 Android 9 定制机。
- 不需要本地安卓环境:用 GitHub Actions 在云端编译出 debug APK 下载。

---

## 一、建仓库 + 放文件

> 关键:`settings.gradle`、`app/`、`.github/` 必须在**仓库根目录**(即本文件夹里的内容直接放到仓库根,不要多套一层目录)。

**网页上传(最简单)**
1. github.com → ＋ → New repository → 填名字 → Create。
2. 空仓库页 → Add file → Upload files → 把本文件夹里所有东西拖进去(含 `app/`、`.github/` 整个文件夹)。
3. `.github` 是点开头的隐藏目录,拖文件夹会一起带上;若没带上,单独再传一次 `.github/workflows/build.yml`(上传时文件名框输入完整相对路径即可建出层级)。
4. Commit changes。提交后自动触发编译。

**或用 git**
```
git init
git add .
git commit -m "DeviceProbe v0.1.0"
git branch -M main
git remote add origin https://github.com/你的用户名/仓库名.git
git push -u origin main
```

---

## 二、触发编译
- 自动:push 即触发。
- 手动:仓库页 → Actions → 左侧 Build APK → Run workflow。
- 首次约 3–6 分钟。

## 三、下载 APK
1. Actions → 点最新一条运行(绿勾)。
2. 页面底部 Artifacts → DeviceProbe-debug-apk → 下载(zip)。
3. 解压得 `app-debug.apk`。

## 四、装到定制安卓前的设置
1. 设置里打开"允许安装未知应用 / 未知来源"。
2. 把 APK 拷进设备安装(或 `adb install app-debug.apk`)。
3. 首次打开给存储权限(用于写 `/sdcard/device_probe_result.txt`)。
4. 点"开始探测",结果在屏幕、也写到 `/sdcard/device_probe_result.txt`。

### 关于 root / /dev/ttyS1
- App 本身不需要 root;它只**报告** `/dev/ttyS1` 是否存在、权限、能否 open。
- 若报"存在但 open 失败=EACCES",说明节点在但普通 App 没权限——把结果发我,据此定 A 端 App 的串口方案。

## 五、出问题
- 编译失败:Actions 里点开红色步骤复制报错贴回。
- 装不上:多半是"未知来源"没开,或定制机锁安装。
