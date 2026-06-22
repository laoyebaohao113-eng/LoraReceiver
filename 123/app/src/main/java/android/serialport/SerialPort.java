package android.serialport;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * android-serialport-api 的 SerialPort 类。
 *
 * 重要:本类必须放在包 android.serialport、类名 SerialPort、native 方法 open 的签名
 * 必须与随工程打包的 libserial_port.so 完全一致(JNI 符号 Java_android_serialport_SerialPort_open)。
 * 这里的 .so 与签名取自旧 App ffdagdfc(已在本机 MT8788 验证),签名/参数顺序按其 smali 复刻:
 *     private native FileDescriptor open(String path, int baudrate, int dataBits, int parity, int stopBits, int flags)
 * 注意参数顺序是 baudrate, dataBits, parity, stopBits, flags(parity 在 stopBits 前)。
 *
 * /dev/ttyS1 已是 rwxrwxrwx 且可直接 open,所以这里直接 open,不做 su/chmod 提权。
 */
public class SerialPort {

    static {
        System.loadLibrary("serial_port");
    }

    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    /**
     * @param device   串口设备节点,如 /dev/ttyS1
     * @param baudrate 波特率,如 921600
     * @param dataBits 数据位,8
     * @param parity   校验,0=无
     * @param stopBits 停止位,1
     * @param flags    打开标志,0
     */
    public SerialPort(File device, int baudrate, int dataBits, int parity, int stopBits, int flags)
            throws IOException {
        mFd = open(device.getAbsolutePath(), baudrate, dataBits, parity, stopBits, flags);
        if (mFd == null) {
            throw new IOException("native open() 返回 null,打开失败: " + device.getAbsolutePath());
        }
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    // 与 libserial_port.so 对应的本地方法(签名不可改)
    private native FileDescriptor open(String path, int baudrate, int dataBits, int parity, int stopBits, int flags);

    public native void close();
}
