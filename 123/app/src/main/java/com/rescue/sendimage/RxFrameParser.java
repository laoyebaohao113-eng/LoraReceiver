package com.rescue.sendimage;

/**
 * 接收字节流解析器:从串口字节里切出完整的 0x27 ACK 帧并解析。
 *
 * ACK 帧:27 LH LL ... CHK AA,整帧长度 = 3 + 数据长度(LH:LL)。
 *   命令ACK:  27 00 04 CC RR chk AA            (RR:01成功/02失败)
 *   数据ACK新:27 00 07 01 01 x0 y0 y1 chk AA   (x0图片号 y0y1包号)
 *   数据ACK旧:27 00 04 01 RR chk AA            (作废,但容错接受)
 *   重请求:   27 00 04 10 img chk AA
 */
public class RxFrameParser {

    public interface AckListener {
        void onAck(Protocol.Ack ack);
    }

    private static final int MAX = 512;
    private final byte[] buf = new byte[MAX];
    private int len = 0;
    private final AckListener listener;

    public RxFrameParser(AckListener l) {
        this.listener = l;
    }

    /** 喂入新收到的字节 */
    public void feed(byte[] data, int n) {
        for (int i = 0; i < n; i++) {
            if (len >= MAX) {
                // 溢出保护:左移半个缓冲
                System.arraycopy(buf, MAX / 2, buf, 0, MAX / 2);
                len = MAX / 2;
            }
            buf[len++] = data[i];
        }
        scan();
    }

    /** 反复尝试从缓冲头部对齐并切出完整帧 */
    private void scan() {
        while (true) {
            // 找到 0x27 帧头,丢弃前面的杂字节
            int start = -1;
            for (int i = 0; i < len; i++) {
                if (buf[i] == Protocol.ACK_HEAD) { start = i; break; }
            }
            if (start < 0) { len = 0; return; }
            if (start > 0) { System.arraycopy(buf, start, buf, 0, len - start); len -= start; }

            if (len < 3) return;                       // 不够读长度字段
            int dataLen = ((buf[1] & 0xFF) << 8) | (buf[2] & 0xFF);
            int frameLen = 3 + dataLen;                // 整帧字节数
            if (frameLen < 4 || frameLen > MAX) {       // 长度异常,丢掉这个头继续找
                shift(1); continue;
            }
            if (len < frameLen) return;                 // 还没收全,等下次
            if ((buf[frameLen - 1] & 0xFF) != (Protocol.TAIL & 0xFF)) {
                shift(1); continue;                     // 帧尾不对,丢头重找
            }
            // 切出一帧
            byte[] f = new byte[frameLen];
            System.arraycopy(buf, 0, f, 0, frameLen);
            shift(frameLen);
            emit(f);
        }
    }

    private void shift(int k) {
        if (k >= len) { len = 0; }
        else { System.arraycopy(buf, k, buf, 0, len - k); len -= k; }
    }

    private void emit(byte[] f) {
        if (listener == null) return;
        Protocol.Ack a = new Protocol.Ack();
        a.raw = f;
        a.cmd = f[3] & 0xFF;
        int dataLen = ((f[1] & 0xFF) << 8) | (f[2] & 0xFF);
        if (a.cmd == Protocol.CMD_DATA && dataLen == 0x07 && f.length >= 10) {
            // 数据ACK新格式:27 00 07 01 RR x0 y0 y1 chk AA
            a.result = f[4] & 0xFF;
            a.imgNo = f[5] & 0xFF;
            a.pktNo = ((f[6] & 0xFF) << 8) | (f[7] & 0xFF);
            a.hasIndex = true;
        } else if (f.length >= 7) {
            // 命令ACK / 旧数据ACK / 重请求:27 00 04 CC RR chk AA
            a.result = f[4] & 0xFF;
            a.hasIndex = false;
        }
        listener.onAck(a);
    }
}
