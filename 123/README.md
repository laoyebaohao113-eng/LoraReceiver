# SendImageNew — A 端图传 App  v0.3.0(完整版,处理/发送两步分开)

A 端(拍照源)安卓 App 干净重写版,**严格复刻旧 App SendVideo 的真实流程**:
黑白图 → 二值化 → JBIG 压缩(写成 txt)→ 按《PX30与STM32通信协议》分包串口发 → 收 ACK。
**发的是 JBIG 产物(txt),不是 JPEG。** 板子认的就是这套。

## 两步分开(方便分步测试)
**第一步「处理」**(不碰串口):
- 读黑白图(默认 `/storage/emulated/0/DCIM/Camera`,界面可改 + 「选图」选单张);
- `DocScannerBinarize.binarize` → `DocScannerJBIG.compress` → 写 `/storage/emulated/0/ceshi/<图名>.txt`(无则建);
- 「处理整批」/「处理选定单张」;完成显示 成功/失败 数,产物留在 ceshi 可单独检查。

**第二步「发送」**(读 ceshi 的 txt):
- 「发送整批」发 ceshi 全部 txt;「发送选定txt」只发下拉选中的一个;
- 控制命令时序 0x02→0x03→0x05→0x06→逐包 0x01→0x04;
- 每包 190 字节、0x39 帧、16 位累加和、收 0x27 ACK;收到匹配成功 ACK 再发下一包;
- 失败/超时重发当前包(超时 1500ms、重试 8 次,可调),超限停下报「卡在第X张第Y包」;
- 支持接收端 0x10「重新请求第N张」断点续传(把第N张排队重发)。

## 图片处理(复刻自旧 App,去掉矫正)
- `com.docscanner.sdk.binarize.DocScannerBinarize`、`com.docscanner.sdk.jbig.DocScannerJBIG`
  **照旧 App smali 严格复刻**(包名/类名/native 签名/loadLibrary/模型拷贝逻辑一致,匹配打包的 .so)。
- 砍掉了矫正 correction(省 ~46MB,不影响 JBIG 输出格式,板子照样认)。
- `unetv2.onnx` 上电时从 `assets/models/` 拷到 `filesDir/docscanner_binarize_models/` 再 `nativeInit`。

## native 库(只打包 arm64-v8a)
`app/src/main/jniLibs/arm64-v8a/`:libopencv_java4、libonnxruntime(+4j_jni)、libc++_shared、
libdocscanner-binarize、libdocscanner-jbig、libserial_port(串口)。+ `assets/models/unetv2.onnx`。
> ⚠️ 这些 `.so` 和 `unetv2.onnx` 必须一并传到仓库(二进制),否则运行时崩。云端编译无需 NDK(只打包不编译)。

## 串口层(自包含)
`android.serialport.SerialPort`(本工程源码)+ libserial_port.so;`new SerialPort(file, baud, 8,0,1,0)`(8N1),无需 root。
默认 ttyS0 / 921600,界面可切换。

## 源码结构
```
android/serialport/SerialPort.java
com/docscanner/sdk/binarize/DocScannerBinarize.java   二值化(复刻)
com/docscanner/sdk/jbig/DocScannerJBIG.java           JBIG压缩(复刻)
com/rescue/sendimage/
  MainActivity.java     两步界面(处理/发送,各整批/单张)+ 串口/日志接线
  ImageProcessor.java   处理流水线:图→二值化→JBIG→ceshi/txt
  TxtSource.java        列 ceshi txt / 读字节
  ImageSender.java      发送状态机:命令时序+逐张逐包+ACK流控+超时重发+0x10续传
  SerialPortHelper.java 串口开/收/发
  RxFrameParser.java    切 0x27 ACK 帧
  Protocol.java         拼帧/校验/ACK 解析/命令码
  SendService.java      前台服务(保活)
  HexUtil.java          hex 工具
```

## 云端编译 / 装机
- `.github/workflows/build.yml`(自动定位工程目录)→ GitHub Actions 出 `app-debug.apk`(~45MB)。
- 装机:开"允许安装未知应用",首次给存储权限 → 先「处理」生成 txt → 打开串口 → 「发送」。
- 把 `/sdcard/serial_log.txt` 或复制的日志发我看。

⚠️ 未在真机编译/运行验证;native 库(opencv/onnx + docscanner)首次真机跑若崩,贴 logcat 我按需微调包装类。
