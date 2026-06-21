package com.rescue.sendimage;

/**
 * 字节 <-> hex 工具,以及写死的测试帧。
 */
public final class HexUtil {

    private HexUtil() { }

    /**
     * 测试帧:旧 App(ffdagdfc)真实抓到的控制帧,符合协议第2节 0x39 帧族。
     * 39 00 08 04 00 00 00 00 00 04 AA
     *  └头 └长度 └类型 └……载荷…… └校验 └尾
     * 校验 0x04 = sum(byte[3..N-2]) & 0xFF;本步只验证能否通上、板子回不回,故用原值。
     */
    public static final byte[] TEST_FRAME = new byte[] {
            (byte) 0x39, (byte) 0x00, (byte) 0x08, (byte) 0x04,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x04, (byte) 0xAA
    };

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 整个数组转 "39 00 08 ..." */
    public static String toHex(byte[] data) {
        return toHex(data, 0, data == null ? 0 : data.length);
    }

    /** 指定长度转 hex(中间用空格分隔) */
    public static String toHex(byte[] data, int off, int len) {
        if (data == null || len <= 0) return "";
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = off; i < off + len; i++) {
            int v = data[i] & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]).append(' ');
        }
        return sb.toString().trim();
    }
}
