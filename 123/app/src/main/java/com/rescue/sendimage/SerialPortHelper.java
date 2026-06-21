package com.rescue.sendimage;

import android.serialport.SerialPort;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 串口层:用 android-serialport-api(硬件 tty)打开指定串口节点。
 *
 * 设备事实(探测报告):MT8788 工控机 / Android 11,串口节点权限 rwxrwxrwx、可直接 open,
 * 无需 root/chmod。硬件 tty(非 USB),所以用这套库而不是 usb-serial。
 *
 * v0.1.1:串口路径与波特率改为运行时传入(界面可切换),默认 /dev/ttyS0 @ 921600。
 */
public class SerialPortHelper {

    public static final String DEFAULT_PATH = "/dev/ttyS0";   // 工程师确认板子接的是串口0
    public static final int DEFAULT_BAUD = 921600;

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

    private String openedPath = "";
    private int openedBaud = 0;

    public boolean isOpen() {
        return serialPort != null;
    }

    public String getOpenedPath() { return openedPath; }
    public int getOpenedBaud() { return openedBaud; }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * 打开指定串口 @ 指定波特率(8N1)。
     * @throws Exception 打开失败(把原因抛给调用方显示)
     */
    public void open(String path, int baud) throws Exception {
        if (serialPort != null) return;
        // 直接构造(随工程打包的 libserial_port.so),参数顺序:
        // device, baudrate, dataBits, parity, stopBits, flags  -> 8N1
        serialPort = new SerialPort(new File(path), baud, 8, 0, 1, 0);
        in = serialPort.getInputStream();
        out = serialPort.getOutputStream();
        openedPath = path;
        openedBaud = baud;
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
        openedPath = "";
        openedBaud = 0;
    }
}
