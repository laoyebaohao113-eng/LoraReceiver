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
    private final int ctrlGapMs;        // 控制命令之间的间隔(ms),可调
    private final int settleMs;         // 0x06 后、首包数据前的"安定延时"(ms),让板子跑完控制命令引发的 LoRa 活动
    private final Tx tx;
    private final Callback cb;

    private final BlockingQueue<Protocol.Ack> ackQ = new LinkedBlockingQueue<>();
    private final Queue<Integer> reReq = new ConcurrentLinkedQueue<>(); // 0x10 重请求的图片号
    private volatile boolean stop = false;
    private static int s_batchId = 0;   // 批序号(每发一批+1, 写进目录 dir.bin)
    private static final String DIR_FILE_NAME = "dir.bin";  // 0.9.6: 目录文件名(非.txt, 不会被当图片重发)

    public ImageSender(List<File> files, int timeoutMs, int maxRetries, int ctrlGapMs, int settleMs, Tx tx, Callback cb) {
        this.files = files;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.ctrlGapMs = ctrlGapMs;
        this.settleMs = settleMs;
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

            // 0.9.6: 生成"目录文件"dir.bin(落地)并作为本批最后一项发出去(图号=img_total-1),
            // 走和图片一样的可靠 ACK 管线; 废弃旧的空中 0x07 帧。约定:收端最高图号那张即目录。
            List<File> sendList = new ArrayList<>(files);
            File dirFile = buildAndWriteDirectory(files);
            if (dirFile != null) sendList.add(dirFile);
            else cb.log("ERR", "目录文件生成失败,本批不带目录(C端将缺目录)");
            final int imgTotal = sendList.size();
            cb.log("INFO", "开始发送:图片 " + files.size() + " 张 + 目录 " + (dirFile != null ? 1 : 0)
                    + " = " + imgTotal + " 项;超时=" + timeoutMs + "ms,最大重试=" + maxRetries);

            // 控制命令时序
            if (!sendCmd(Protocol.CMD_POWERON, "开机成功")) return;
            if (!sendCmd(Protocol.CMD_SDCARD, "已插入SD卡")) return;
            if (!sendCmd(Protocol.CMD_CONVERT_START, "开始转换图片")) return;
            if (!sendCmd(Protocol.CMD_CONVERT_DONE, "图片转换完成")) return;
            // 0.9.6: 旧的"空中 0x07 图片信息帧"已废弃。目录改走 dir.bin 文件,
            // 已在上面作为 sendList 的最后一项,走正常数据管线(ACK 流控)发出。
            if (stop) { cb.done(false, "已停止"); return; }

            // 【安定延时】首包数据前等一会儿,让板子把控制命令引发的 LoRa 收发跑完、回到空闲,
            // 再发 208 字节长帧,避免撞 LoRa 忙期导致板子 UART 中断漏读字节(帧尾错位被丢弃)。
            if (settleMs > 0) {
                cb.log("INFO", "安定延时 " + settleMs + "ms(等板子空闲再发第一包数据)…");
                try { Thread.sleep(settleMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (stop) { cb.done(false, "已停止"); return; }
            }

            // 工作队列:先发 0..N-1;0x10 重请求的图片追加到队尾(断点续传)
            List<Integer> work = new ArrayList<>();
            for (int i = 0; i < imgTotal; i++) work.add(i);

            int wi = 0;
            while (wi < work.size() && !stop) {
                int imgIdx = work.get(wi); wi++;
                boolean isDir = (dirFile != null) && (imgIdx == imgTotal - 1);   // 最后一项=目录
                byte[] data;
                try {
                    data = TxtSource.readBytes(sendList.get(imgIdx));
                } catch (Exception e) {
                    cb.done(false, "读文件失败: " + sendList.get(imgIdx).getName() + " - " + e.getMessage());
                    return;
                }
                int pktTotal = (data.length + Protocol.PACKET_SIZE - 1) / Protocol.PACKET_SIZE;
                if (pktTotal == 0) pktTotal = 1;
                cb.log("INFO", "发送" + (isDir ? "目录" : ("第 " + imgIdx + " 张")) + "(" + sendList.get(imgIdx).getName()
                        + ",图号" + imgIdx + "/" + (imgTotal - 1) + "," + data.length + "字节,共 " + pktTotal + " 包)");

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

    /**
     * 控制命令(0x02~0x06):母体固件对控制命令不回 UART 应答(已对母体源码核实),
     * 故 fire-and-forget——只发不等 ACK,发完留 ctrlGapMs 间隔让板子处理状态机。
     * 只有 0x01 数据帧才做 ACK 流控(见 sendDataWithAck)。
     */
    private boolean sendCmd(int cmd, String name) {
        byte[] frame = Protocol.buildCmd(cmd);
        try { tx.send(frame); }
        catch (Exception e) { cb.log("ERR", "发送异常: " + e.getMessage()); cb.done(false, "串口发送异常"); return false; }
        cb.log("TX", "[" + name + "] " + HexUtil.toHex(frame) + "  (控制命令,不等ACK)");
        ctrlGap();
        return true;
    }

    /** 控制命令之间的间隔(只延时,停止判断交给 run() 统一处理) */
    private void ctrlGap() {
        if (ctrlGapMs <= 0) return;
        try { Thread.sleep(ctrlGapMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * 发一包数据并等"成功 ACK"。只认带图号/包号的成功数据应答(27 00 07 01 01 x0 y0 y1):
     *   a.cmd==0x01 && a.result==1 && a.hasIndex && 图号/包号都对得上。
     * 其它杂散应答(如板子对别的帧失败时发的通用错误帧 27 00 04 01 02 03,无图号/包号)一律忽略,
     * 不当本包失败、不立刻重发;在本包的超时窗口内继续等真正的成功 ACK。超时才重发。
     * (这与旧 App 一致:按图号/包号匹配,容忍杂散帧。)
     */
    private boolean sendDataWithAck(byte[] frame, int imgIdx, int pktIdx, int pktTotal, int imgTotal) {
        for (int retry = 0; retry <= maxRetries && !stop; retry++) {
            ackQ.clear();
            try { tx.send(frame); }
            catch (Exception e) { cb.log("ERR", "发送异常: " + e.getMessage()); return false; }
            cb.log("TX", "[数据 图" + imgIdx + " 包" + pktIdx + "/" + (pktTotal - 1) + "] "
                    + frame.length + "字节" + (retry > 0 ? "  (重试" + retry + ")" : ""));
            // 诊断:打印整帧 hex,便于核对头/长度/校验/帧尾(包大可能较长)
            cb.log("TXHEX", HexUtil.toHex(frame));
            cb.progress(imgIdx, imgTotal, pktIdx, pktTotal, retry);

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!stop) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                Protocol.Ack a = pollAck(remain);
                if (a == null) break; // 超时
                if (a.cmd == Protocol.CMD_DATA && a.result == 1 && a.hasIndex
                        && a.imgNo == imgIdx && a.pktNo == pktIdx) {
                    cb.log("OK", "图" + imgIdx + " 包" + pktIdx + " ACK 成功");
                    return true;
                }
                // 杂散/不匹配应答:忽略,继续等本包的成功 ACK
                cb.log("DBG", "忽略杂散应答: " + a + "(非本包成功ACK,继续等)");
            }
            cb.log("ERR", "图" + imgIdx + " 包" + pktIdx + " 等成功ACK超时,重发");
        }
        return false;
    }

    /**
     * 0.9.6: 生成"目录文件" dir.bin(内容 = 0x07 载荷:batch_id/N/entry_size + N×{bytes4 pkts2 crc16}),
     * 写到 txt 同目录, 返回该 File(失败返回 null)。N = 真实图片张数(不含目录自身)。
     * 该文件随后作为本批最后一项发出, 收端约定"最高图号那张 = 目录"。
     */
    private File buildAndWriteDirectory(List<File> imgs) {
        java.io.FileOutputStream fos = null;
        try {
            int n = imgs.size();
            int[] sizes = new int[n];
            int[] crcs = new int[n];
            for (int k = 0; k < n; k++) {
                byte[] d = TxtSource.readBytes(imgs.get(k));
                sizes[k] = d.length;
                crcs[k] = Protocol.crc16Ccitt(d, 0, d.length);
            }
            int batchId = (s_batchId++) & 0xFF;
            byte[] payload = Protocol.buildDirectoryPayload(batchId, sizes, crcs);
            File dir = new File(imgs.get(0).getParentFile(), DIR_FILE_NAME);
            fos = new java.io.FileOutputStream(dir);
            fos.write(payload);
            fos.flush();
            cb.log("OK", "目录 " + DIR_FILE_NAME + " 生成:batch" + batchId + " N" + n
                    + " " + payload.length + "字节(作为图号" + n + "发出)");
            return dir;
        } catch (Exception e) {
            cb.log("ERR", "目录生成异常: " + e.getMessage());
            return null;
        } finally {
            if (fos != null) { try { fos.close(); } catch (Exception ignore) { } }
        }
    }

    private Protocol.Ack pollAck(long ms) {
        try { return ackQ.poll(ms, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { return null; }
    }
}
