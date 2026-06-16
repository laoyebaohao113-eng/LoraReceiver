package com.lora.receiver;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把单个UART分片上传到Win10服务器
 *
 * 接口约定：
 *   POST http://{host}:{port}/upload
 *   Header:
 *     X-Device-Name : 设备名
 *     X-File-No     : 文件编号
 *     X-Pkt-Total   : 包总数
 *     X-Pkt-Current : 当前包序号（从0开始）
 *     X-Data-Len    : 数据长度
 *   Body: 原始二进制数据
 *
 * 返回 null 表示成功，否则返回错误信息
 */
public class ServerUploader {

    private static final String TAG = "ServerUploader";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS    = 5000;

    public static String upload(String host, int port, String deviceName,
                                  int fileNo, int pktTotal, int pktCurrent,
                                  byte[] data, int dataLen) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + ":" + port + "/upload");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            conn.setRequestProperty("X-Device-Name", deviceName);
            conn.setRequestProperty("X-File-No", String.valueOf(fileNo));
            conn.setRequestProperty("X-Pkt-Total", String.valueOf(pktTotal));
            conn.setRequestProperty("X-Pkt-Current", String.valueOf(pktCurrent));
            conn.setRequestProperty("X-Data-Len", String.valueOf(dataLen));
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(dataLen);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(data, 0, dataLen);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                return null;
            } else {
                return "HTTP " + code;
            }

        } catch (IOException e) {
            Log.e(TAG, "上传失败: " + e.getMessage());
            return e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
