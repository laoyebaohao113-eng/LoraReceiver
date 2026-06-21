# SendImageNew — A 端图传 App  v0.2.0(完整版)

A 端(拍照源)安卓 App 的**干净重写版**,复刻旧 App SendVideo 的图传功能(去掉全部文档扫描死代码)。
取图 → JPEG压缩 → 按《PX30与STM32通信协议》分包组帧 → 串口逐包发给 A 板 → 收 ACK 流控/超时重发。

## 功能
1. **取图**:默认从 `/storage/emulated/0/DCIM/Camera`(旧 App 写死路径)读 JPG;界面可改文件夹;「选图」按钮选单张兜底。
2. **压缩**:JPEG,质量可调(默认 80)。
3. **分包组帧**:严格按协议——每包 190 字节;数据帧 `0x39 … CHK_H CHK_L 0xAA`(命令 0x01),字段含视频总数/当前图片/总包数/当前包号/RSSI(发送端0)/SNR(0)/预留;16 位累加和校验。
4. **控制命令**:0x02 开机成功、0x03 已插SD卡、0x05 开始转换、0x06 转换完成、0x04 发送完成(11字节命令帧)。
5. **ACK 流控 + 超时重发**:收到匹配的成功 ACK 才发下一包;失败/超时重发当前包。超时(默认 1500ms)、最大重试(默认 8)均界面可调。0x07 图片信息帧本版**不发**(后续需要再加)。
6. **串口/波特率界面可切换**(默认 ttyS0 / 921600 / 8N1,不重新编译就能改)。
7. **日志**:大滚动区,`[TX]`/`[RX]`/`[OK]`/`[ERR]`/`[INFO]` 带时间戳 hex;进度行显示 图i/N 包j/M;「复制全部日志」;同时写 `/sdcard/serial_log.txt`。
8. **最小前台服务**:长时间发送期间保活。

## 串口层(自包含,零外部依赖)
`android.serialport.SerialPort`(本工程源码)+ `app/src/main/jniLibs/<abi>/libserial_port.so`(取自旧 App,已在本机 MT8788 验证)。
云端编译无需 NDK、无需联网拉库。打开 `new SerialPort(file, baud, 8, 0, 1, 0)`(8N1),无需 root。
> ⚠️ `jniLibs` 下 4 个 `.so` 必须一并传到仓库,否则运行时 `UnsatisfiedLinkError`。

## 源码结构
```
android/serialport/SerialPort.java          串口JNI类(匹配.so)
com/rescue/sendimage/
  MainActivity.java     界面 + 全部按钮 + 日志 + 串口/发送线程接线
  SerialPortHelper.java 开/收/发串口(open(path,baud) + 接收线程)
  RxFrameParser.java    接收字节流 → 切出 0x27 ACK 帧 → 解析
  Protocol.java         按协议拼数据帧/命令帧、校验、ACK 解析(命令码常量)
  ImageSource.java      列 JPG / JPEG 压缩(默认文件夹常量)
  ImageSender.java      发送状态机:命令时序 + 逐图逐包 + ACK流控 + 超时重发
  SendService.java      最小前台服务(保活)
  HexUtil.java          hex 工具
```

## 云端编译 / 装机
- `.github/workflows/build.yml`(自动定位工程目录版)→ GitHub Actions 出 `app-debug.apk`。
- 工具链 JDK17 + Gradle 8.7 + AGP 8.5.2 + compileSdk34 / minSdk21 / targetSdk28。
- 装机:开"允许安装未知应用",首次运行给存储权限;选好串口/波特率 → 打开串口 → (改文件夹或选图)→ 开始发送。
- 把 `/sdcard/serial_log.txt` 或屏幕日志(复制按钮)发我看收发情况。

⚠️ 本 App 未在真机编译/运行验证,以你烧录后的现象/日志为准。
