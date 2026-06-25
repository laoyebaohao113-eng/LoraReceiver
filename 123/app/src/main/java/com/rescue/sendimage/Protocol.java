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
 *   39 00 08 CC 00 00 00 00 00 CHK AA   (发送端那5字节全0;CHK=sum(Byte3..Byte8)&0xFF,单字节)
 *
 * 图片信息帧(命令 0x07)—— v0.9.2 富版(详见 buildImageInfo 与协议文档):
 *   39  LH:LL  07  batch_id  N  entry_size  [bytes4 pkts2 crc16_2]×N  CHK  AA
 *   在 0x06 之后、第一包 0x01 之前发送;C/server 用它做补包与拼图依据。
 *   ⚠ 当前因 firmware-A 校验bug(非0x01帧16位累加和比单字节)未真发, 等A端那轮修了再开。
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
    public static final int CMD_IMAGE_INFO    = 0x07; // 图片信息(总张数+每张字节数,补包依据)
    public static final int CMD_REREQUEST     = 0x10; // 接收端重新请求第N张(断点续传)

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

    /**
     * 拼图片信息帧(命令 0x07)—— v0.9.2 富版(已与 protocol/系统通信协议_现状版.md 对齐)。
     *
     * 整帧(0x39 信封,App→A):
     *   39  LH:LL  07  batch_id  N  entry_size  [条目×N]  CHK  AA
     *   LH:LL = 4 + ENTRY_SIZE*N(指 Byte3=cmd 到最后数据字节的字节数)
     *   batch_id  : 本批序号(每发一批+1, 防多批/多C串批)
     *   N         : 图片张数
     *   entry_size: 每条字节数(=8, 自描述, 以后扩字段不破坏旧解析)
     *   每条(8B): bytes(4B大端) + pkt_count(2B大端) + crc16(2B大端)
     *     bytes      = 该图(txt)总字节数(4字节, 不再受 64KB 限制)
     *     pkt_count  = ceil(bytes/190)
     *     crc16      = 该图整图 CRC-16/CCITT(init 0xFFFF, poly 0x1021)
     *   CHK = sum(Byte3..最后数据字节) & 0xFF —— 单字节(母本非0x01帧均单字节)
     *
     * 注意:目前【不要真发】此帧——firmware-A 对非0x01帧的校验是"16位累加和 vs 单字节"
     *   比较(uart.c bug),0x07 累加和>255 必判失败回错误帧。等 A 端那轮修了校验、
     *   并让 A 把 0x07 转发给 B、B 能收 0x07,再打开发送。本函数只负责"正确生成"。
     *
     * @param batchId 本批序号(0..255)
     * @param sizes   每张图片(txt)字节数, 顺序与发送一致
     * @param crc16s  每张图片的 CRC-16/CCITT(与 sizes 等长, 调用方用 crc16Ccitt 算)
     */
    public static final int IMAGE_INFO_ENTRY_SIZE = 8;

    public static byte[] buildImageInfo(int batchId, int[] sizes, int[] crc16s) {
        int n = sizes.length;
        int es = IMAGE_INFO_ENTRY_SIZE;
        int lenField = 4 + es * n;                       // Byte3 到最后数据字节
        byte[] f = new byte[6 + es * n + 3];             // HEAD+LH+LL + (cmd+batch+N+es) + N*es + CHK+TAIL
        int p = 0;
        f[p++] = HEAD;
        f[p++] = (byte) ((lenField >> 8) & 0xFF);
        f[p++] = (byte) (lenField & 0xFF);
        f[p++] = (byte) CMD_IMAGE_INFO;                  // [3]
        f[p++] = (byte) (batchId & 0xFF);                // [4] batch_id
        f[p++] = (byte) (n & 0xFF);                      // [5] N
        f[p++] = (byte) (es & 0xFF);                     // [6] entry_size
        for (int i = 0; i < n; i++) {
            int bytes = sizes[i];
            int pkts = (bytes + PACKET_SIZE - 1) / PACKET_SIZE;   // ceil(bytes/190)
            int crc = crc16s[i] & 0xFFFF;
            f[p++] = (byte) ((bytes >> 24) & 0xFF);
            f[p++] = (byte) ((bytes >> 16) & 0xFF);
            f[p++] = (byte) ((bytes >> 8) & 0xFF);
            f[p++] = (byte) (bytes & 0xFF);
            f[p++] = (byte) ((pkts >> 8) & 0xFF);
            f[p++] = (byte) (pkts & 0xFF);
            f[p++] = (byte) ((crc >> 8) & 0xFF);
            f[p++] = (byte) (crc & 0xFF);
        }
        int sum = 0;
        for (int i = 3; i < p; i++) sum += (f[i] & 0xFF);         // Byte3..最后数据字节
        f[p++] = (byte) (sum & 0xFF);                            // CHK 单字节
        f[p] = TAIL;
        return f;
    }

    /** CRC-16/CCITT(init 0xFFFF, poly 0x1021, 不反射, 无 xorout)。三端必须一致。 */
    public static int crc16Ccitt(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) crc = (crc << 1) ^ 0x1021;
                else crc <<= 1;
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
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
