package com.rescue.sendimage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 发送状态机:控制命令时序 + 逐图逐包数据 + ACK 流控 + 超时重发。
 * 严格按协议:收到正确 ACK 才发下一包;ACK 失败/超时则重发当前包(可配次数)。
 *
 * 在独立线程跑。串口收到的 ACK 由外部(MainActivity)通过 offerAck() 投进来。
 */
public class ImageSender implements Runnable {

    public interface Tx {
        void send(byte[] frame) throws Exception;   // 实际写串口
    }

    public interface Callback {
        void log(String tag, String msg);   // tag: INFO/TX/OK/ERR
        void progress(int imgIdx, int imgTotal, int pktIdx, int pktTotal, int retry);
        void done(boolean ok, String msg);
    }

    private final List<File> images;
    private final int jpegQuality;
    private final int timeoutMs;
    private final int maxRetries;
    private final Tx tx;
    private final Callback cb;

    private final BlockingQueue<Protocol.Ack> ackQ = new LinkedBlockingQueue<>();
    private volatile boolean stop = false;

    public ImageSender(List<File> images, int jpegQuality, int timeoutMs, int maxRetries,
                       Tx tx, Callback cb) {
        this.images = images;
        this.jpegQuality = jpegQuality;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.tx = tx;
        this.cb = cb;
    }

    /** 串口收到的 ACK 投进来 */
    public void offerAck(Protocol.Ack a) {
        ackQ.offer(a);
    }

    public void stop() {
        stop = true;
    }

    @Override
    public void run() {
        try {
            if (images == null || images.isEmpty()) {
                cb.done(false, "没有待发送图片");
                return;
            }
            int imgTotal = images.size();
            cb.log("INFO", "开始发送流程,共 " + imgTotal + " 张图片,质量=" + jpegQuality
                    + ",超时=" + timeoutMs + "ms,最大重试=" + maxRetries);

            // 1) 控制命令时序
            if (!sendCmd(Protocol.CMD_POWERON, "开机成功")) return;
            if (!sendCmd(Protocol.CMD_SDCARD, "已插入SD卡")) return;
            if (!sendCmd(Protocol.CMD_CONVERT_START, "开始转换图片")) return;

            // 2) 压缩所有图片成 JPEG 字节
            List<byte[]> jpgs = new ArrayList<>();
            for (int i = 0; i < imgTotal && !stop; i++) {
                try {
                    byte[] b = ImageSource.compressToJpeg(images.get(i), jpegQuality);
                    jpgs.add(b);
                    cb.log("INFO", "压缩 [" + (i + 1) + "/" + imgTotal + "] "
                            + images.get(i).getName() + " -> " + b.length + " 字节");
                } catch (Exception e) {
                    cb.log("ERR", "压缩失败: " + images.get(i).getName() + " - " + e.getMessage());
                    cb.done(false, "压缩失败,中止");
                    return;
                }
            }
            if (stop) { cb.done(false, "已停止"); return; }

            if (!sendCmd(Protocol.CMD_CONVERT_DONE, "图片转换完成")) return;
            // 0x07 图片信息帧:本端先不发(确认项③选 a)

            // 3) 逐图逐包发数据
            for (int imgIdx = 0; imgIdx < imgTotal && !stop; imgIdx++) {
                byte[] jpg = jpgs.get(imgIdx);
                int pktTotal = (jpg.length + Protocol.PACKET_SIZE - 1) / Protocol.PACKET_SIZE;
                if (pktTotal == 0) pktTotal = 1;
                cb.log("INFO", "发送图片 " + imgIdx + "(" + jpg.length + "字节,共 " + pktTotal + " 包)");
                for (int pktIdx = 0; pktIdx < pktTotal && !stop; pktIdx++) {
                    int off = pktIdx * Protocol.PACKET_SIZE;
                    int n = Math.min(Protocol.PACKET_SIZE, jpg.length - off);
                    byte[] frame = Protocol.buildData(imgTotal, imgIdx, pktTotal, pktIdx, jpg, off, n);
                    if (!sendDataWithAck(frame, imgIdx, pktIdx, pktTotal, imgTotal)) {
                        cb.done(false, "图片 " + imgIdx + " 第 " + pktIdx + " 包重试" + maxRetries + "次仍失败,中止");
                        return;
                    }
                }
            }
            if (stop) { cb.done(false, "已停止"); return; }

            // 4) 发送完成
            if (!sendCmd(Protocol.CMD_SEND_DONE, "视频数据发送完成")) return;

            cb.done(true, "全部图片发送完成");
        } catch (Throwable t) {
            cb.log("ERR", "发送线程异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            cb.done(false, "异常中止");
        }
    }

    /** 发控制命令,等成功 ACK,失败/超时重发 */
    private boolean sendCmd(int cmd, String name) {
        byte[] frame = Protocol.buildCmd(cmd);
        for (int retry = 0; retry <= maxRetries && !stop; retry++) {
            ackQ.clear();
            try { tx.send(frame); } catch (Exception e) {
                cb.log("ERR", "发送异常: " + e.getMessage()); cb.done(false, "串口发送异常"); return false;
            }
            cb.log("TX", "[" + name + "] " + HexUtil.toHex(frame) + (retry > 0 ? "  (重试" + retry + ")" : ""));
            Protocol.Ack a = waitAck();
            if (a != null && a.cmd == cmd && a.result == 1) {
                cb.log("OK", "[" + name + "] 收到成功 ACK");
                return true;
            }
            cb.log("ERR", "[" + name + "] " + (a == null ? "ACK 超时" : "ACK 非成功: " + a) + ",重发");
        }
        cb.done(false, "[" + name + "] 重试" + maxRetries + "次仍失败,中止");
        return false;
    }

    /** 发数据包,等匹配 图片号/包号 的成功 ACK,失败/超时重发 */
    private boolean sendDataWithAck(byte[] frame, int imgIdx, int pktIdx, int pktTotal, int imgTotal) {
        for (int retry = 0; retry <= maxRetries && !stop; retry++) {
            ackQ.clear();
            try { tx.send(frame); } catch (Exception e) {
                cb.log("ERR", "发送异常: " + e.getMessage()); return false;
            }
            cb.log("TX", "[数据 图" + imgIdx + " 包" + pktIdx + "/" + (pktTotal - 1) + "] "
                    + frame.length + "字节" + (retry > 0 ? "  (重试" + retry + ")" : ""));
            cb.progress(imgIdx, imgTotal, pktIdx, pktTotal, retry);
            Protocol.Ack a = waitAck();
            if (a != null && a.cmd == Protocol.CMD_DATA && a.result == 1
                    && (!a.hasIndex || (a.imgNo == imgIdx && a.pktNo == pktIdx))) {
                cb.log("OK", "图" + imgIdx + " 包" + pktIdx + " ACK 成功");
                return true;
            }
            cb.log("ERR", "图" + imgIdx + " 包" + pktIdx + " "
                    + (a == null ? "ACK 超时" : "ACK 不匹配/失败: " + a) + ",重发");
        }
        return false;
    }

    private Protocol.Ack waitAck() {
        try {
            return ackQ.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }
}
