# 用 GitHub 云端编译 APK（不用装 Android Studio）

GitHub 的服务器自带 Android SDK。把本工程推上去，GitHub Actions 会自动编译出 .apk，
你在网页上直接下载即可。

---

## 一、把工程推到 GitHub

建议**新建一个干净的仓库**（或用你的空仓库），把本文件夹（`netsend-client-app`）里的
**全部文件推到仓库根目录**，分支用 `main`。

要推送的内容（根目录）：
```
.github/workflows/build-apk.yml   ← 编译脚本（关键）
www/                              ← 已构建好的客户端网页
capacitor.config.json
package.json
.gitignore
README_GITHUB.md / README_APK.md
```
> 不需要推 `node_modules/` 和 `android/`（`.gitignore` 已忽略，CI 会自己生成）。

用命令行推送（在本文件夹里）：
```bash
git init
git add .
git commit -m "pid client app"
git branch -M main
git remote add origin https://github.com/你的用户名/你的仓库.git
git push -u origin main
```
（也可以直接在 GitHub 网页 “Add file → Upload files” 把这些文件拖上去。）

---

## 二、触发编译

推上去后，GitHub 会**自动开始编译**。也可手动触发：
- 仓库页 → **Actions** 标签 → 左边选 **Build Android APK** → 右边 **Run workflow**。

等几分钟，那次运行变成**绿色对勾**即编译成功。

---

## 三、下载 APK

- 进入那次运行（Actions → 点开最新一条 Build Android APK）。
- 页面底部 **Artifacts** → 点 **pid-client-apk** 下载（是个 zip）。
- 解压得到 `app-debug.apk` → 发给客户/自己装到手机（手机需允许“安装未知来源应用”）。

---

## 四、装好后首次使用

1. 打开 APP → 右上角 **齿轮（服务器设置）**。
2. 填 netsend 地址：`http://你的公网IP:8000`（带 http:// 和端口）。
3. 保存并重连 → 顶部“已连接”即可用。地址记在手机本地，下次自动用。

---

## 五、以后更新

- 客户端网页改了（在隔壁 `netsend-frontend`）：
  ```bash
  # netsend-frontend 里
  npm run build
  ```
  把 `dist/*` 覆盖到本工程 `www/`（注意保留 `www/index.html` 里那句
  `<script>if(!location.hash){location.hash="#/c/devices"}</script>`），
  再 `git add . && git commit -m update && git push`，GitHub 会自动重新出包。

---

## 六、编译失败怎么办

- 进 Actions 那次运行，点开**红色**的步骤看日志。
- 常见：Gradle/SDK 首次拉取慢或偶发失败 → 重新点 **Run workflow** 再跑一次多半就过。
- 若卡在某依赖，把红色那段日志发我，我来调 `build-apk.yml`。
