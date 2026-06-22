package com.rescue.sendimage;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把"串口 + 接收解析 + 发送状态机 + 前台服务"封装起来,给 MainActivity 调用,
 * 让 UI 类保持精简。UI 回调通过 Ui 接口回传(都在主线程外调用,UI 自己 marshal)。
 */
public class SendController {

    public interface Ui {
        void log(String tag, String msg);
        void progress(int imgIdx, int imgTotal, int pktIdx, int pktTotal, int retry);
        void sendDone(boolean ok, String msg);
    }

    private final Context appCtx;
    private final Ui ui;
    private final SerialPortHelper serial = new SerialPortHelper();
    private final RxFrameParser rxParser;
    private ImageSender sender;
    private Thread senderThread;

    public SendController(Context ctx, Ui ui) {
        this.appCtx = ctx.getApplicationContext();
        this.ui = ui;
        this.rxParser = new RxFrameParser(new RxFrameParser.AckListener() {
            @Override public void onAck(Protocol.Ack a) { if (sender != null) sender.offerAck(a); }
        });
        this.serial.setListener(new SerialPortHelper.Listener() {
            @Override public void onData(byte[] d, int n) {
                // 1) 先原样打印所有收到的字节(不管能不能匹配成 ACK)
                ui.log("RX", HexUtil.toHex(d, 0, n) + "  (" + n + "B)");
                // 2) 再喂解析器;解析器异常不影响原始日志,也不能搞死读线程
                try { rxParser.feed(d, n); }
                catch (Throwable t) { ui.log("ERR", "解析器异常(原始字节已打印,忽略): " + t.getMessage()); }
            }
            @Override public void onError(String m) { ui.log("ERR", m); }
            @Override public void onInfo(String m) { ui.log("DBG", m); }
        });
    }

    public boolean isOpen() { return serial.isOpen(); }

    /** 打开串口;成功返回 true */
    public boolean open(String path, int baud) {
        try { serial.open(path, baud); ui.log("OK", "已打开 " + path + " @ " + baud + " 8N1。"); return true; }
        catch (Throwable t) { ui.log("ERR", "打开失败: " + t.getClass().getSimpleName() + " - " + t.getMessage()); return false; }
    }

    public void close() { serial.close(); }

    public boolean isSending() { return senderThread != null && senderThread.isAlive(); }

    /** 设置串口分段写(保险,默认关) */
    public void setChunk(boolean en, int size, int delayMs) { serial.setChunk(en, size, delayMs); }

    /** 发送一批 txt 文件(整批=多个,单张=1个);ctrlGapMs=控制命令间隔,settleMs=首包前安定延时 */
    public void send(List<File> files, int timeoutMs, int maxRetries, int ctrlGapMs, int settleMs) {
        if (!serial.isOpen()) { ui.log("ERR", "请先打开串口。"); return; }
        if (isSending()) { ui.log("INFO", "正在发送中。"); return; }
        sender = new ImageSender(files, timeoutMs, maxRetries, ctrlGapMs, settleMs,
            new ImageSender.Tx() { public void send(byte[] f) throws Exception { serial.send(f); } },
            new ImageSender.Callback() {
                public void log(String t, String m) { ui.log(t, m); }
                public void progress(int i, int it, int pk, int pt, int rt) { ui.progress(i, it, pk, pt, rt); }
                public void done(boolean ok, String msg) {
                    SendService.stop(appCtx);
                    ui.sendDone(ok, msg);
                }
            });
        SendService.start(appCtx);
        senderThread = new Thread(sender, "img-sender");
        senderThread.start();
        ui.log("INFO", "开始发送,待发 " + files.size() + " 个 txt。");
    }

    public void stop() { if (sender != null) sender.stop(); }

    /** 诊断用:发一条 0x02 控制帧(fire-and-forget,不等 ACK),用来看板子有没有回原始字节 */
    public void sendTestFrame() {
        if (!serial.isOpen()) { ui.log("ERR", "请先打开串口。"); return; }
        byte[] f = Protocol.buildCmd(Protocol.CMD_POWERON);
        try { serial.send(f); ui.log("TX", "[测试帧0x02] " + HexUtil.toHex(f) + "  (看下面有没有 [RX])"); }
        catch (Exception e) { ui.log("ERR", "发送测试帧失败: " + e.getMessage()); }
    }

    /**
     * 【对照实验】发一个格式完全合法的 0x01 图片数据帧,但数据部分只有 n 字节(10/64/190)。
     * 先走控制命令时序(0x02→0x03→0x05→0x06)+ 安定延时(避开 LoRa 忙期),再发这一帧,
     * 打印完整 [TXHEX],之后看 [RX] 板子有没有反应。fire-and-forget,不等 ACK。
     * 用途:短包(10/64)有反应、190满包不回 → 坐实长帧丢字节。
     */
    public void sendShortDataTest(final int n, final int ctrlGapMs, final int settleMs) {
        if (!serial.isOpen()) { ui.log("ERR", "请先打开串口。"); return; }
        if (isSending()) { ui.log("INFO", "正在发送中,先停止。"); return; }
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                ui.log("INFO", "===== 短包对照测试:数据 " + n + " 字节 =====");
                int[] cmds = {Protocol.CMD_POWERON, Protocol.CMD_SDCARD, Protocol.CMD_CONVERT_START, Protocol.CMD_CONVERT_DONE};
                String[] names = {"开机", "插卡", "开始转换", "转换完成"};
                for (int i = 0; i < cmds.length; i++) {
                    byte[] cf = Protocol.buildCmd(cmds[i]);
                    serial.send(cf);
                    ui.log("TX", "[" + names[i] + "0x0" + cmds[i] + "] " + HexUtil.toHex(cf));
                    if (ctrlGapMs > 0) Thread.sleep(ctrlGapMs);
                }
                if (settleMs > 0) { ui.log("INFO", "安定延时 " + settleMs + "ms…"); Thread.sleep(settleMs); }
                // 拼一个合法 0x01 帧:图总数1 图号0 总包数1 包号0,数据为 0x00..递增
                byte[] dummy = new byte[n];
                for (int i = 0; i < n; i++) dummy[i] = (byte) (i & 0xFF);
                byte[] frame = Protocol.buildData(1, 0, 1, 0, dummy, 0, n);
                ui.log("TX", "[短数据帧 " + n + "字节] 整帧 " + frame.length + "字节");
                ui.log("TXHEX", HexUtil.toHex(frame));
                serial.send(frame);
                ui.log("INFO", "已发短数据帧(" + n + "字节),观察下方有没有 [RX]。");
            } catch (Exception e) {
                ui.log("ERR", "短包测试异常: " + e.getMessage());
            }
            }
        }, "short-test");
        t.start();
    }

    public void shutdown() {
        if (sender != null) sender.stop();
        serial.close();
        SendService.stop(appCtx);
    }

    public static List<File> singleList(File f) {
        List<File> l = new ArrayList<>();
        l.add(f);
        return l;
    }
}
