package com.rescue.sendimage;

import android.serialport.SerialPort;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 串口层:用 android-serialport-api(硬件 tty)打开指定串口节点。
 * 接收用独立线程死循环 in.read(),发送期间也一直在读;收到的字节原样回调。
 */
public class SerialPortHelper {

    public static final String DEFAULT_PATH = "/dev/ttyS0";
    public static final int DEFAULT_BAUD = 921600;

    public interface Listener {
        void onData(byte[] data, int len);   // 收到的原始字节
        void onError(String msg);            // 读线程异常
        void onInfo(String msg);             // 读线程生命周期/诊断信息
    }

    private SerialPort serialPort;
    private InputStream in;
    private OutputStream out;
    private Thread readThread;
    private volatile boolean running = false;
    private Listener listener;

    private String openedPath = "";
    private int openedBaud = 0;

    public boolean isOpen() { return serialPort != null; }
    public String getOpenedPath() { return openedPath; }
    public int getOpenedBaud() { return openedBaud; }
    public void setListener(Listener l) { this.listener = l; }

    public void open(String path, int baud) throws Exception {
        if (serialPort != null) return;
        // device, baudrate, dataBits, parity, stopBits, flags -> 8N1
        serialPort = new SerialPort(new File(path), baud, 8, 0, 1, 0);
        in = serialPort.getInputStream();
        out = serialPort.getOutputStream();
        openedPath = path;
        openedBaud = baud;
        startReadThread();
    }

    public void send(byte[] data) throws IOException {
        if (out == null) throw new IOException("串口未打开");
        out.write(data);
        out.flush();
    }

    private void startReadThread() {
        running = true;
        readThread = new Thread(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onInfo("接收线程已启动,持续监听 in.read()(发送期间也在读)");
                byte[] buf = new byte[1024];
                long total = 0;
                while (running) {
                    int n;
                    try {
                        n = in.read(buf);   // 阻塞读
                    } catch (IOException e) {
                        if (running && listener != null) listener.onError("读串口异常: " + e.getMessage());
                        break;
                    }
                    if (n < 0) {
                        if (listener != null) listener.onInfo("in.read() 返回 -1(流结束),接收线程退出");
                        break;
                    }
                    if (n > 0 && listener != null) {
                        total += n;
                        byte[] copy = new byte[n];
                        System.arraycopy(buf, 0, copy, 0, n);
                        // 监听器(打日志/喂解析器)的异常绝不允许搞死读线程
                        try { listener.onData(copy, n); }
                        catch (Throwable t) {
                            try { listener.onError("处理收到数据时异常(已忽略,继续读): " + t.getClass().getSimpleName() + " " + t.getMessage()); }
                            catch (Throwable ignore) { }
                        }
                    }
                }
                if (listener != null) listener.onInfo("接收线程结束,累计收到 " + total + " 字节");
            }
        }, "serial-read");
        readThread.start();
    }

    public void close() {
        running = false;
        try { if (in != null) in.close(); } catch (Throwable ignore) { }
        try { if (out != null) out.close(); } catch (Throwable ignore) { }
        try { if (serialPort != null) serialPort.close(); } catch (Throwable ignore) { }
        in = null;
        out = null;
        serialPort = null;
        readThread = null;
        openedPath = "";
        openedBaud = 0;
    }
}
