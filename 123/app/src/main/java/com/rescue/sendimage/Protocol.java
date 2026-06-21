package com.rescue.sendimage;

/**
 * PX30↔STM32 串口协议(严格按《PX30与STM32通信协议_Ver1_0_260422》)。
 *
 * 帧头 0x39(本端发) / 0x27(板子回);帧尾 0xAA;大端;半双工,收到正确 ACK 再发下一包。
 *
 * 图片数据帧(命令 0x01),整帧 = 数据n + 18 字节:
 *   [0]=0x39 头
 *   [1..2]=数据长度 LH:LL = n + 15(指 Byte3 到帧尾 0xAA 的字节数)
 *   [3]=C 命令=0x01
 *   [4]=D1 视频总数
 *   [5]=D2 当前视频号(0起)
 *   [6..7]=D3:D4 该视频总包数(大端)
 *   [8..9]=D5:D6 当前包号(大端,0起)
 *   [10..11]=D7:D8 RSSI(发送端=0)
 *   [12]=D9 SNR(发送端=0)
 *   [13..14]=D10:D11 预留(0,0)
 *   [15..15+n-1]=视频数据(每包≤190,最后一包按实际)
 *   [15+n..16+n]=CHK_H:CHK_L = 从 Byte3 到最后数据字节的16位累加和(大端)
 *   [17+n]=0xAA 帧尾
 *
 * 控制命令帧(0x02~0x06 等),固定 11 字节:
 *   39 00 08 CC 00 00 00 00 00 CHK AA   (发送端那5字节全0;CHK=sum(Byte3..Byte8)&0xFF)
 */
public final class Protocol {

    private Protocol() { }

    public static final byte HEAD = 0x39;
    public static final byte TAIL = (byte) 0xAA;
    public static final byte ACK_HEAD = 0x27;

    /** 每包有效数据最大字节数(协议规定) */
    public static final int PACKET_SIZE = 190;

    // 命令码
    public static final int CMD_DATA          = 0x01; // 视频(图片)数据
    public static final int CMD_POWERON       = 0x02; // 开机成功
    public static final int CMD_SDCARD        = 0x03; // 已插入SD卡
    public static final int CMD_SEND_DONE     = 0x04; // 视频数据发送完成
    public static final int CMD_CONVERT_START = 0x05; // 开始转换图片
    public static final int CMD_CONVERT_DONE  = 0x06; // 图片转换完成

    /** 拼图片数据帧;data 从 off 起取 n 字节(n≤190) */
    public static byte[] buildData(int imgTotal, int imgIdx, int pktTotal, int pktIdx,
                                   byte[] data, int off, int n) {
        int lenField = n + 15;
        byte[] f = new byte[n + 18];
        f[0] = HEAD;
        f[1] = (byte) ((lenField >> 8) & 0xFF);
        f[2] = (byte) (lenField & 0xFF);
        f[3] = (byte) CMD_DATA;
        f[4] = (byte) (imgTotal & 0xFF);
        f[5] = (byte) (imgIdx & 0xFF);
        f[6] = (byte) ((pktTotal >> 8) & 0xFF);
        f[7] = (byte) (pktTotal & 0xFF);
        f[8] = (byte) ((pktIdx >> 8) & 0xFF);
        f[9] = (byte) (pktIdx & 0xFF);
        // [10..14] RSSI/SNR/预留:发送端全 0
        System.arraycopy(data, off, f, 15, n);
        int sum = 0;
        for (int i = 3; i < 15 + n; i++) sum += (f[i] & 0xFF);   // Byte3 到最后数据字节
        f[15 + n] = (byte) ((sum >> 8) & 0xFF);
        f[16 + n] = (byte) (sum & 0xFF);
        f[17 + n] = TAIL;
        return f;
    }

    /** 拼控制命令帧(0x02~0x06 等) */
    public static byte[] buildCmd(int cmd) {
        byte[] f = new byte[11];
        f[0] = HEAD;
        f[1] = 0x00;
        f[2] = 0x08;
        f[3] = (byte) cmd;
        // [4..8] 全 0
        int sum = 0;
        for (int i = 3; i < 9; i++) sum += (f[i] & 0xFF);
        f[9] = (byte) (sum & 0xFF);
        f[10] = TAIL;
        return f;
    }

    /** 解析出的 ACK */
    public static final class Ack {
        public int cmd;       // 对应命令(0x01..0x06,或 0x10 重请求)
        public int result;    // 1=成功 2=失败(数据/命令应答)
        public int imgNo;     // 数据ACK:图片号(新格式才有)
        public int pktNo;     // 数据ACK:包号(新格式才有)
        public boolean hasIndex; // 是否带 图片号/包号
        public byte[] raw;
        @Override public String toString() {
            return "ACK cmd=0x" + Integer.toHexString(cmd) + " result=" + result
                    + (hasIndex ? (" img=" + imgNo + " pkt=" + pktNo) : "");
        }
    }
}
