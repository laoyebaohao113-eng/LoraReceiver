# DeviceProbe — 设备信息探测 App + GitHub 云端编译

一个极简、只读的安卓 App:点一个按钮,把"系统/机型/ABI、root、`/dev/tty*` 串口节点(重点 `/dev/ttyS1`)、USB 设备、外部存储读写"全探测一遍,显示在屏幕并写入 `/sdcard/device_probe_result.txt`。**不修改设备任何东西**。

- 版本 0.1.0;界面就一个按钮 + 滚动文本框;零第三方依赖。
- 目标 `targetSdk 28` / `minSdk 21`,兼容 Android 9 定制机。
- 不需要本地安卓环境:用 GitHub Actions 在云端编译出 debug APK 下载。

---

## 一、建仓库 + 放文件(傻瓜步骤)

> 关键:`settings.gradle`、`app/`、`.github/` 必须在**仓库根目录**。也就是把本文件夹
> (`android-DeviceProbe`)里的**内容**放到仓库根,不要把 `android-DeviceProbe` 这层目录也带进去。

**方式 A:网页上传(最简单,不用装 git)**
1. 登录 github.com → 右上角 ＋ → **New repository** → 填名字(如 `device-probe`)→ 选 **Private** 或 Public 都行 → **Create repository**。
2. 进入空仓库页 → 点 **uploading an existing file**(或 Add file → Upload files)。
3. 把 `android-DeviceProbe` 文件夹里的所有东西**拖进去**:`settings.gradle`、`build.gradle`、`gradle.properties`、`.gitignore`、`app/`(整个文件夹)、`.github/`(整个文件夹)。
   - ⚠️ 网页拖拽请连**子文件夹一起拖**,确保 `app/src/...` 和 `.github/workflows/build.yml` 的层级保留。
   - ⚠️ `.github` 是隐藏名(点开头),拖文件夹时它会一起带上;若没带上,单独再传一次 `.github/workflows/build.yml`(上传时在文件名框里输入 `.github/workflows/build.yml` 可手动建出层级)。
4. 下方 **Commit changes**。提交后,push 会**自动触发**编译(见第二步)。

**方式 B:用 git 命令(你会用 git 的话)**
```
# 在 android-DeviceProbe 目录里
git init
git add .
git commit -m "DeviceProbe v0.1.0"
git branch -M main
git remote add origin https://github.com/你的用户名/device-probe.git
git push -u origin main
```

---

## 二、触发编译

- **自动**:只要有 push(包括第一步的上传提交),Actions 就会自动跑。
- **手动**:仓库页 → 上方 **Actions** 标签 → 左侧选 **Build APK** → 右侧 **Run workflow** → 选分支 → 绿色 **Run workflow**。

首次编译约 **3–6 分钟**(要下载 Gradle/SDK)。

---

## 三、下载 APK

1. 仓库页 → **Actions** 标签 → 点最新那条运行记录(绿勾=成功)。
2. 拉到页面最下方 **Artifacts** → 点 **DeviceProbe-debug-apk** 下载(是个 zip)。
3. 解压 zip,里面就是 **`app-debug.apk`**。

> 如果是红叉(失败):点进去看哪一步红了,把日志贴回来我看。

---

## 四、装到定制安卓前要开什么设置

1. **允许安装未知应用**:设置 → 安全 →「未知来源 / 允许安装未知应用」打开(Android 9 一般在"安全"或对应文件管理器的权限里)。
2. 把 `app-debug.apk` 拷进设备(U盘/SD/数据线均可),用文件管理器点它安装。
   - 或用电脑 `adb install app-debug.apk`。
3. **首次打开**会弹**存储权限**,点允许(为了写 `/sdcard/device_probe_result.txt`)。
4. 点「开始探测」。结果在屏幕,也写到 `/sdcard/device_probe_result.txt`,可拷出来发我。

### 关于 root 和 /dev/ttyS1
- 这个 App **本身不需要 root**就能跑;它只是**报告** `/dev/ttyS1` 是否存在、权限、本 App 能不能 open。
- 如果 `/dev/ttyS1` 报"存在但 open 失败=EACCES(权限不够)",说明节点在、但普通 App 没权限——那一步正是我们要确认的结论(可能需要 root 或改节点权限)。把探测结果发我,据此定 A 端 App 的串口方案。

---

## 五、出问题怎么办
- 编译失败:Actions 里点开失败的步骤,复制报错贴回。常见就是某个 action 版本/SDK 组件,我调一下 yml。
- 装不上:多半是"未知来源"没开,或机器只认特定签名(定制机偶尔锁安装)——告诉我具体提示。
