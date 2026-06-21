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
                ui.log("RX", HexUtil.toHex(d, 0, n) + "  (" + n + "B)");
                rxParser.feed(d, n);
            }
            @Override public void onError(String m) { ui.log("ERR", m); }
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

    /** 发送一批 txt 文件(整批=多个,单张=1个) */
    public void send(List<File> files, int timeoutMs, int maxRetries) {
        if (!serial.isOpen()) { ui.log("ERR", "请先打开串口。"); return; }
        if (isSending()) { ui.log("INFO", "正在发送中。"); return; }
        sender = new ImageSender(files, timeoutMs, maxRetries,
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
