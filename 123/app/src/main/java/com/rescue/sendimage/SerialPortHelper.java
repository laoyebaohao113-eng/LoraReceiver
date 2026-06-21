package com.rescue.sendimage;

import android.serialport.SerialPort;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 串口层:用 android-serialport-api(xmaihh)打开硬件 tty /dev/ttyS1。
 *
 * 设备事实(来自探测报告):MT8788 工控机 / Android 11,/dev/ttyS1 权限 rwxrwxrwx、
 * 本 App 可直接 open,无需 root/chmod —— 所以这里走"直接打开",不触发库的 su 提权。
 *
 * 这是硬件 tty,不是 USB,所以用这套库,而不是 android-B 的 usb-serial-for-android。
 */
public class SerialPortHelper {

    public static final String DEV_PATH = "/dev/ttyS1";
    public static final int BAUD = 921600;   // 8N1,见下

    public interface Listener {
        void onData(byte[] data, int len);   // 收到串口数据(板子回的字节)
        void onError(String msg);            // 读线程异常
    }

    private SerialPort serialPort;
    private InputStream in;
    private OutputStream out;
    private Thread readThread;
    private volatile boolean running = false;
    private Listener listener;

    public boolean isOpen() {
        return serialPort != null;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * 打开 /dev/ttyS1 @ 921600 8N1。
     * @throws Exception 打开失败(把原因抛给调用方显示)
     */
    public void open() throws Exception {
        if (serialPort != null) return;
        // 直接构造(随工程打包的 libserial_port.so),参数顺序:
        // device, baudrate, dataBits, parity, stopBits, flags  -> 8N1
        serialPort = new SerialPort(new File(DEV_PATH), BAUD, 8, 0, 1, 0);
        in = serialPort.getInputStream();
        out = serialPort.getOutputStream();
        startReadThread();
    }

    /** 发送一帧字节 */
    public void send(byte[] data) throws IOException {
        if (out == null) throw new IOException("串口未打开");
        out.write(data);
        out.flush();
    }

    private void startReadThread() {
        running = true;
        readThread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[1024];
                while (running) {
                    try {
                        int n = in.read(buf);   // 阻塞读
                        if (n > 0 && listener != null) {
                            byte[] copy = new byte[n];
                            System.arraycopy(buf, 0, copy, 0, n);
                            listener.onData(copy, n);
                        } else if (n < 0) {
                            break;   // 流结束
                        }
                    } catch (IOException e) {
                        if (running && listener != null) {
                            listener.onError("读串口异常: " + e.getMessage());
                        }
                        break;
                    }
                }
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
    }
}
