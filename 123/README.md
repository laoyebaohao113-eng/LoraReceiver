# SendImageNew — A 端串口调试 App  v0.1.1

A 端(拍照源)安卓 App 的**干净重写版**第一步,串口调试:
选择串口(默认 `/dev/ttyS0`)+ 波特率(默认 921600,可改)→ 打开 → 发一帧写死的测试控制帧 → 持续读串口,
TX/RX 都以 `[TX]/[RX]` + hex + 时间戳显示,日志同时写 `/sdcard/serial_log.txt`。
**先不做**拍照/分包/传图。与反编译版 `ffdagdfc` 并存,互不影响。

## 设备前提(来自探测报告)
- MT8788 工控机,Android 11 / API30,**无 USB 串口、不能 root**。
- A 端走**硬件 tty** `/dev/ttyS1`(实测权限 `rwxrwxrwx`,本 App 可直接 open、可读写)。
- 因为是硬件 tty 不是 USB,用 **android-serialport-api**(不能用 android-B 的 usb-serial-for-android)。

## 串口层(自包含,零外部依赖)
- 用 **android-serialport-api**,但**不依赖外部库**:`android.serialport.SerialPort`(本工程源码)+
  `app/src/main/jniLibs/<abi>/libserial_port.so`(**取自旧 App ffdagdfc,已在本机 MT8788 验证**)。
- 云端编译**无需 NDK、无需联网拉库**(.so 是预编译的,只打包不编译)。
- 打开:`new SerialPort(new File(path), baud, 8, 0, 1, 0)`(device, baudrate, dataBits, parity, stopBits, flags = 8N1),不需要 root/chmod。串口路径与波特率运行时从界面传入。
- 后台线程持续读,收到字节实时转 hex 显示(板子回 `0x27` 帧就能看到)。

> SerialPort.java 的 native `open` 签名与参数顺序严格按旧 App smali 复刻,必须与那个 .so 对得上;不要改动。

## 测试帧(写死)
`39 00 08 04 00 00 00 00 00 04 AA` —— 旧 App 真实抓到的控制帧,符合协议第2节 0x39 帧族
(`0x39` 头 / 长度 / 类型 / 载荷 / 1字节校验=sum(byte[3..N-2])&0xFF=0x04 / `0xAA` 尾)。
本步只验证能否通上、板子回不回,故用原值。

## 界面(v0.1.1)
- 串口下拉(`/dev/ttyS0`~`ttyS3`,默认 ttyS0)+ 波特率输入框(默认 921600,可改)——**不重新编译就能切换**。
- 按钮:「打开串口/关闭串口」「发送测试帧」「清空日志」「复制全部日志」。
- 大日志区,可滚动、可选中;每行带时间戳;`[TX]`=发出、`[RX]`=收到,字节均 hex;板子回的任何字节实时显示。
- 日志同时写入 `/sdcard/serial_log.txt`(屏幕清空不影响文件,文件留完整历史)。首次运行需给存储权限。

## 云端编译(GitHub Actions)
- `.github/workflows/build.yml` 已配好,**自动定位工程目录**(不管放仓库哪层都能编),产出 `app-debug.apk` 作 artifact。
- 工具链:JDK17 + Gradle 8.7 + AGP 8.5.2 + compileSdk34 / minSdk21 / targetSdk28。
- 步骤同前:建仓库 → 传文件(`.github` 要在仓库根)→ push 自动编译 → Actions 下 artifact 解压得 APK。

### 注意:必须带上 .so 二进制
本工程串口层是自包含的,`app/src/main/jniLibs/` 下的 4 个 `libserial_port.so` **必须一并传到仓库**
(它们是二进制文件,GitHub 网页上传/Git 都能正常处理)。少了它们,App 能编译但运行时会 `UnsatisfiedLinkError`。

## 装机/验证
1. 设置开"允许安装未知应用",装 `app-debug.apk`。
2. 打开 App → 点「打开串口」→ 看是否 `[OK] 已打开`(失败会显示 errno/原因)。
3. 点「发送测试帧」→ 日志出现 `TX -> 39 00 08 ...`。
4. 若板子有回应,日志会出现 `RX <- 27 ...`。把日志发我,据此定下一步(拍照/分包)。

⚠️ 本 App 未在真机编译/运行验证过,以你烧录后的现象/日志为准。
