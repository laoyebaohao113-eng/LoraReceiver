package com.lora.receiver;

import java.util.ArrayList;
import java.util.List;

/**
 * 串口帧协议解析器
 *
 * 帧格式（单片机 → 安卓）：
 * [0]     0xAA  帧头0
 * [1]     0x55  帧头1
 * [2]     文件编号 (1~30)
 * [3:4]   包总数 (大端)
 * [5:6]   当前包号 (0起, 大端)
 * [7:8]   数据长度 (大端)
 * [9:9+N] 数据
 * [末2字节] CRC16-CCITT (大端)
 *
 * 安卓 → 单片机应答：
 * 0x06 = ACK (接收正确)
 * 0x15 = NAK (CRC错误，请求重发)
 */
public class FrameParser {

    public static final byte ACK = 0x06;
    public static final byte NAK = 0x15;

    private static final int HEADER_0  = 0xAA;
    private static final int HEADER_1  = 0x55;
    private static final int OVERHEAD  = 11;   // 2+1+2+2+2+2

    /** 解析结果 */
    public static class Frame {
        public int     fileNo;       // 文件编号 (1~30)
        public int     pktTotal;     // 包总数
        public int     pktCurrent;   // 当前包号 (0起)
        public int     dataLen;      // 数据字节数
        public byte[]  data;         // 原始数据
        public boolean crcOK;        // CRC校验是否通过
    }

    // 滚动缓冲区
    private final List<Byte> mBuf = new ArrayList<>(8192);

    /**
     * 喂入新收到的字节，返回所有解析成功的帧
     */
    public List<Frame> feed(byte[] bytes, int len) {
        List<Frame> result = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            mBuf.add(bytes[i]);
        }
        Frame f;
        while ((f = tryParse()) != null) {
            result.add(f);
        }
        return result;
    }

    private Frame tryParse() {
        // 1. 找帧头 0xAA 0x55
        int start = -1;
        for (int i = 0; i < mBuf.size() - 1; i++) {
            if ((mBuf.get(i) & 0xFF) == HEADER_0 &&
                (mBuf.get(i + 1) & 0xFF) == HEADER_1) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            // 没有帧头，保留最后1字节（可能是下一帧头的第一字节）
            if (mBuf.size() > 1) {
                int keep = mBuf.size() - 1;
                mBuf.subList(0, keep).clear();
            }
            return null;
        }
        // 丢弃帧头之前的垃圾
        if (start > 0) {
            mBuf.subList(0, start).clear();
        }
        // 2. 检查是否有足够字节读取头部
        if (mBuf.size() < OVERHEAD) return null;

        // 3. 读数据长度
        int dataLen = ((mBuf.get(7) & 0xFF) << 8) | (mBuf.get(8) & 0xFF);
        if (dataLen < 0 || dataLen > 4096) {
            // 异常数据长度，跳过这个假帧头
            mBuf.remove(0);
            return tryParse();
        }
        int totalLen = OVERHEAD + dataLen;

        // 4. 检查数据是否完整
        if (mBuf.size() < totalLen) return null;

        // 5. 取出完整帧
        byte[] raw = new byte[totalLen];
        for (int i = 0; i < totalLen; i++) {
            raw[i] = mBuf.get(i);
        }
        mBuf.subList(0, totalLen).clear();

        // 6. CRC校验
        int crcCalc = crc16(raw, totalLen - 2);
        int crcRecv = ((raw[totalLen - 2] & 0xFF) << 8) | (raw[totalLen - 1] & 0xFF);

        Frame frame = new Frame();
        frame.fileNo     = raw[2] & 0xFF;
        frame.pktTotal   = ((raw[3] & 0xFF) << 8) | (raw[4] & 0xFF);
        frame.pktCurrent = ((raw[5] & 0xFF) << 8) | (raw[6] & 0xFF);
        frame.dataLen    = dataLen;
        frame.data       = new byte[dataLen];
        System.arraycopy(raw, 9, frame.data, 0, dataLen);
        frame.crcOK      = (crcCalc == crcRecv);

        return frame;
    }

    /** CRC16-CCITT (初值0xFFFF，多项式0x1021) */
    public static int crc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0)
                    crc = (crc << 1) ^ 0x1021;
                else
                    crc <<= 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public void reset() {
        mBuf.clear();
    }
}
