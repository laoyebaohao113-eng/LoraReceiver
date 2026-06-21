package com.rescue.sendimage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 发送状态机(第二步,独立操作):把 ceshi 里的 txt(JBIG 产物)按 PX30 协议逐张逐包发出去。
 * 控制命令时序 + 逐包数据 + ACK 流控 + 超时重发 + 0x10 断点续传。
 *
 * 数据源是已处理好的 txt 文件,本类不做任何图片处理。独立线程跑,ACK 由外部 offerAck() 投入。
 */
public class ImageSender implements Runnable {

    public interface Tx {
        void send(byte[] frame) throws Exception;
    }

    public interface Callback {
        void log(String tag, String msg);
        void progress(int imgIdx, int imgTotal, int pktIdx, int pktTotal, int retry);
        void done(boolean ok, String msg);
    }

    private final List<File> files;     // 待发 txt 文件(整批=多个,单张=1个)
    private final int timeoutMs;
    private final int maxRetries;
    private final Tx tx;
    private final Callback cb;

    private final BlockingQueue<Protocol.Ack> ackQ = new LinkedBlockingQueue<>();
    private final Queue<Integer> reReq = new ConcurrentLinkedQueue<>(); // 0x10 重请求的图片号
    private volatile boolean stop = false;

    public ImageSender(List<File> files, int timeoutMs, int maxRetries, Tx tx, Callback cb) {
        this.files = files;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.tx = tx;
        this.cb = cb;
    }

    /** 串口收到的 ACK 投进来:0x10 重请求单独入队,其余进 ackQ */
    public void offerAck(Protocol.Ack a) {
        if (a.cmd == Protocol.CMD_REREQUEST) {
            reReq.offer(a.result);   // 0x10 帧 byte[4]=图片号,解析时放进了 result
            cb.log("RX", "收到重请求:第 " + a.result + " 张");
        } else {
            ackQ.offer(a);
        }
    }

    public void stop() { stop = true; }

    @Override
    public void run() {
        try {
            if (files == null || files.isEmpty()) { cb.done(false, "没有待发送 txt"); return; }
            final int imgTotal = files.size();
            cb.log("INFO", "开始发送,共 " + imgTotal + " 张;超时=" + timeoutMs + "ms,最大重试=" + maxRetries);

            // 控制命令时序
            if (!sendCmd(Protocol.CMD_POWERON, "开机成功")) return;
            if (!sendCmd(Protocol.CMD_SDCARD, "已插入SD卡")) return;
            if (!sendCmd(Protocol.CMD_CONVERT_START, "开始转换图片")) return;
            if (!sendCmd(Protocol.CMD_CONVERT_DONE, "图片转换完成")) return;

            // 0x07 图片信息帧:在首包数据之前,告知接收端共多少张、每张字节数(补包依据)
            int[] sizes = new int[imgTotal];
            for (int i = 0; i < imgTotal; i++) {
                long fl = files.get(i).length();
                if (fl > 0xFFFF) {
                    cb.log("ERR", "第 " + i + " 张 txt=" + fl + " 字节,超 16 位上限,0x07 中该值将被截断为 "
                            + (fl & 0xFFFF));
                }
                sizes[i] = (int) fl;
            }
            if (!sendImageInfo(sizes)) return;

            // 工作队列:先发 0..N-1;0x10 重请求的图片追加到队尾(断点续传)
            List<Integer> work = new ArrayList<>();
            for (int i = 0; i < imgTotal; i++) work.add(i);

            int wi = 0;
            while (wi < work.size() && !stop) {
                int imgIdx = work.get(wi); wi++;
                byte[] data;
                try {
                    data = TxtSource.readBytes(files.get(imgIdx));
                } catch (Exception e) {
                    cb.done(false, "读 txt 失败: " + files.get(imgIdx).getName() + " - " + e.getMessage());
                    return;
                }
                int pktTotal = (data.length + Protocol.PACKET_SIZE - 1) / Protocol.PACKET_SIZE;
                if (pktTotal == 0) pktTotal = 1;
                cb.log("INFO", "发送第 " + imgIdx + " 张(" + files.get(imgIdx).getName()
                        + "," + data.length + "字节,共 " + pktTotal + " 包)");

                for (int pktIdx = 0; pktIdx < pktTotal && !stop; pktIdx++) {
                    int off = pktIdx * Protocol.PACKET_SIZE;
                    int n = Math.min(Protocol.PACKET_SIZE, data.length - off);
                    byte[] frame = Protocol.buildData(imgTotal, imgIdx, pktTotal, pktIdx, data, off, n);
                    if (!sendDataWithAck(frame, imgIdx, pktIdx, pktTotal, imgTotal)) {
                        cb.done(false, "卡在第 " + imgIdx + " 张第 " + pktIdx + " 包(重试" + maxRetries + "次仍失败)");
                        return;
                    }
                }
                // 本张发完,吸收重请求(断点续传)
                Integer rq;
                while ((rq = reReq.poll()) != null) {
                    if (rq != null && rq >= 0 && rq < imgTotal) {
                        cb.log("INFO", "断点续传:把第 " + rq + " 张加入重发队列");
                        work.add(rq);
                    }
                }
            }
            if (stop) { cb.done(false, "已停止"); return; }

            if (!sendCmd(Protocol.CMD_SEND_DONE, "视频数据发送完成")) return;
            cb.done(true, "全部发送完成");
        } catch (Throwable t) {
            cb.log("ERR", "发送线程异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            cb.done(false, "异常中止");
        }
    }

    private boolean sendCmd(int cmd, String name) {
        byte[] frame = Protocol.buildCmd(cmd);
        for (int retry = 0; retry <= maxRetries && !stop; retry++) {
            ackQ.clear();
            try { tx.send(frame); }
            catch (Exception e) { cb.log("ERR", "发送异常: " + e.getMessage()); cb.done(false, "串口发送异常"); return false; }
            cb.log("TX", "[" + name + "] " + HexUtil.toHex(frame) + (retry > 0 ? "  (重试" + retry + ")" : ""));
            Protocol.Ack a = waitAck();
            if (a != null && a.cmd == cmd && a.result == 1) { cb.log("OK", "[" + name + "] ACK 成功"); return true; }
            cb.log("ERR", "[" + name + "] " + (a == null ? "ACK 超时" : "ACK 非成功: " + a) + ",重发");
        }
        cb.done(false, "[" + name + "] 重试" + maxRetries + "次仍失败,中止");
        return false;
    }

    private boolean sendImageInfo(int[] sizes) {
        byte[] frame = Protocol.buildImageInfo(sizes);
        StringBuilder sb = new StringBuilder();
        for (int s : sizes) sb.append(s).append(' ');
        for (int retry = 0; retry <= maxRetries && !stop; retry++) {
            ackQ.clear();
            try { tx.send(frame); }
            catch (Exception e) { cb.log("ERR", "发送异常: " + e.getMessage()); cb.done(false, "串口发送异常"); return false; }
            cb.log("TX", "[图片信息0x07] " + sizes.length + "张 字节数=[" + sb.toString().trim() + "] "
                    + HexUtil.toHex(frame) + (retry > 0 ? "  (重试" + retry + ")" : ""));
            Protocol.Ack a = waitAck();
            if (a != null && a.cmd == Protocol.CMD_IMAGE_INFO && a.result == 1) {
                cb.log("OK", "[图片信息0x07] ACK 成功"); return true;
            }
            cb.log("ERR", "[图片信息0x07] " + (a == null ? "ACK 超时" : "ACK 非成功: " + a) + ",重发");
        }
        cb.done(false, "[图片信息0x07] 重试" + maxRetries + "次仍失败,中止");
        return false;
    }

    private boolean sendDataWithAck(byte[] frame, int imgIdx, int pktIdx, int pktTotal, int imgTotal) {
        for (int retry = 0; retry <= maxRetries && !stop; retry++) {
            ackQ.clear();
            try { tx.send(frame); }
            catch (Exception e) { cb.log("ERR", "发送异常: " + e.getMessage()); return false; }
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
        try { return ackQ.poll(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { return null; }
    }
}
