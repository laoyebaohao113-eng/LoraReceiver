package com.lora.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG             = "LoraMain";
    private static final String ACTION_USB_PERM = "com.lora.receiver.USB_PERM";
    private static final int    BAUD_RATE       = 460800;
    private static final int    READ_TIMEOUT_MS = 100;
    private static final int    MAX_DATA_BYTES  = 3990;

    // -------- UI --------
    private TextView    tvStatus, tvDeviceInfo, tvLog;
    private TextView    tvFilesCount, tvEmailsCount, tvBytesCount;
    private TextView    tvPktProgress, tvFileInfo;
    private ProgressBar pbPacket;
    private Button      btnConnect, btnClear;
    private ImageView   ivStatusDot;
    private ScrollView  svLog;

    // -------- USB --------
    private UsbManager    mUsbManager;
    private UsbSerialPort mPort;
    private boolean       mConnected = false;
    private volatile boolean mReading = false;

    // -------- 解析 --------
    private final FrameParser mParser = new FrameParser();

    // -------- 当前文件缓冲 --------
    private int    mCurrentFileNo   = -1;
    private int    mCurrentPktTotal = 0;
    private byte[] mFileBuf         = null;
    private int    mFileBufUsed     = 0;

    // -------- 统计 --------
    private int  mFilesReceived = 0;
    private int  mEmailsSent   = 0;
    private long mTotalBytes   = 0;

    // -------- 存储 --------
    private File mSaveDir;

    // -------- 历史记录 --------
    public static final List<FileRecord> sHistory = new ArrayList<>();

    // -------- 线程 --------
    private final Handler         mH       = new Handler(Looper.getMainLooper());
    private final ExecutorService mIoExec     = Executors.newSingleThreadExecutor();
    private final ExecutorService mEmailEx    = Executors.newSingleThreadExecutor();
    private final ExecutorService mUploadExec = Executors.newFixedThreadPool(2);

    // -------- Prefs --------
    private Prefs mPrefs;

    // -------- 前台服务 --------
    private UsbSerialService mService;
    private boolean          mServiceBound = false;

    private final ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService      = ((UsbSerialService.LocalBinder) binder).getService();
            mServiceBound = true;
            Log.d(TAG, "服务已绑定");
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound = false;
        }
    };

    // -------- 密码锁：后台恢复时需要重新验证 --------
    private boolean mUnlocked = false;

    //==========================================================
    // USB广播接收器
    //==========================================================
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERM.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && dev != null) openPort(dev);
                else log("USB权限被拒绝");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                disconnect("设备已拔出");
            }
        }
    };

    //==========================================================
    // 生命周期
    //==========================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查是否从LockActivity跳转过来（已通过密码）
        mUnlocked = true;

        setContentView(R.layout.activity_main);

        mPrefs      = new Prefs(this);
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mSaveDir    = getExternalFilesDir("LoraFiles");
        if (mSaveDir == null) mSaveDir = new File(getFilesDir(), "LoraFiles");
        if (!mSaveDir.exists()) mSaveDir.mkdirs();

        bindViews();
        registerUsbReceiver();
        startAndBindService();

        UsbDevice dev = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (dev != null) requestPermission(dev);

        log("应用启动，存储目录：" + mSaveDir.getAbsolutePath());
        if (!mPrefs.isConfigured()) {
            log("⚠ 尚未配置邮箱，请点击右上角设置");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从后台恢复时跳回密码锁
        if (!mUnlocked) {
            goToLock();
            return;
        }
        mUnlocked = false; // 重置，下次恢复需要重新验证
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 进入后台，标记需要重新验证
        mUnlocked = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mReading = false;
        closePort();
        try { unregisterReceiver(mUsbReceiver); } catch (Exception ignored) {}
        if (mServiceBound) {
            unbindService(mServiceConn);
            mServiceBound = false;
        }
        mIoExec.shutdownNow();
        mEmailEx.shutdownNow();
        mUploadExec.shutdownNow();
    }

    private void goToLock() {
        startActivity(new Intent(this, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    //==========================================================
    // 启动并绑定前台服务
    //==========================================================
    private void startAndBindService() {
        Intent serviceIntent = new Intent(this, UsbSerialService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, mServiceConn, Context.BIND_AUTO_CREATE);
    }

    //==========================================================
    // 绑定控件
    //==========================================================
    private void bindViews() {
        tvStatus      = findViewById(R.id.tv_status);
        tvDeviceInfo  = findViewById(R.id.tv_device_info);
        tvLog         = findViewById(R.id.tv_log);
        tvFilesCount  = findViewById(R.id.tv_files_count);
        tvEmailsCount = findViewById(R.id.tv_emails_count);
        tvBytesCount  = findViewById(R.id.tv_bytes_count);
        tvPktProgress = findViewById(R.id.tv_pkt_progress);
        tvFileInfo    = findViewById(R.id.tv_file_info);
        pbPacket      = findViewById(R.id.pb_packet);
        ivStatusDot   = findViewById(R.id.iv_status_dot);
        svLog         = findViewById(R.id.sv_log);
        btnConnect    = findViewById(R.id.btn_connect);
        btnClear      = findViewById(R.id.btn_clear);

        btnConnect.setOnClickListener(v -> {
            if (mConnected) disconnect("手动断开");
            else            connectUsb();
        });

        btnClear.setOnClickListener(v -> tvLog.setText(""));

        ImageButton btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        ImageButton btnHistory = findViewById(R.id.btn_history);
        btnHistory.setOnClickListener(v ->
            startActivity(new Intent(this, HistoryActivity.class)));
    }

    //==========================================================
    // USB 操作
    //==========================================================
    private void registerUsbReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, f, RECEIVER_NOT_EXPORTED);
    }

    private void connectUsb() {
        List<UsbSerialDriver> drivers =
            UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);
        if (drivers.isEmpty()) {
            log("未找到USB串口设备");
            Toast.makeText(this, "未找到设备", Toast.LENGTH_SHORT).show();
            return;
        }
        UsbDevice dev = drivers.get(0).getDevice();
        if (mUsbManager.hasPermission(dev)) openPort(dev);
        else requestPermission(dev);
    }

    private void requestPermission(UsbDevice dev) {
        PendingIntent pi = PendingIntent.getBroadcast(
            this, 0, new Intent(ACTION_USB_PERM),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        mUsbManager.requestPermission(dev, pi);
        log("正在申请USB权限...");
    }

    private void openPort(UsbDevice dev) {
        List<UsbSerialDriver> drivers =
            UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);
        if (drivers.isEmpty()) { log("驱动未找到"); return; }

        UsbSerialDriver driver = drivers.get(0);
        mPort = driver.getPorts().get(0);

        try {
            mPort.open(mUsbManager.openDevice(dev));
            mPort.setParameters(BAUD_RATE, 8,
                UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            mConnected = true;
            mParser.reset();
            resetCurrentFile();

            String devName = dev.getDeviceName();
            String chip    = driver.getClass().getSimpleName()
                               .replace("SerialDriver", "");

            mH.post(() -> {
                tvStatus.setText("已连接  " + BAUD_RATE + " bps");
                tvDeviceInfo.setText(devName + "  芯片: " + chip);
                ivStatusDot.setImageResource(R.drawable.ic_dot_green);
                btnConnect.setText("断开连接");
            });

            log("已连接: " + devName + " (" + chip + ")");
            if (mServiceBound) mService.updateNotification("已连接 " + chip);
            startReading();

        } catch (IOException e) {
            log("打开串口失败: " + e.getMessage());
        }
    }

    private void disconnect(String reason) {
        mReading = false;
        closePort();
        mConnected = false;
        mH.post(() -> {
            tvStatus.setText("未连接");
            tvDeviceInfo.setText("");
            ivStatusDot.setImageResource(R.drawable.ic_dot_gray);
            btnConnect.setText("连接设备");
        });
        log("已断开: " + reason);
        if (mServiceBound) mService.updateNotification("LoRa接收器运行中（未连接）");
    }

    private void closePort() {
        if (mPort != null) {
            try { mPort.close(); } catch (Exception ignored) {}
            mPort = null;
        }
    }

    //==========================================================
    // 串口读取线程
    //==========================================================
    private void startReading() {
        mReading = true;
        mIoExec.execute(() -> {
            byte[] buf = new byte[4096];
            while (mReading && mPort != null) {
                try {
                    int n = mPort.read(buf, READ_TIMEOUT_MS);
                    if (n > 0) {
                        List<FrameParser.Frame> frames = mParser.feed(buf, n);
                        for (FrameParser.Frame f : frames) processFrame(f);
                    }
                } catch (Exception e) {
                    if (mReading) {
                        Log.e(TAG, "读取错误: " + e.getMessage());
                        disconnect("读取异常: " + e.getMessage());
                    }
                    break;
                }
            }
        });
    }

    //==========================================================
    // 帧处理
    //==========================================================
    private void processFrame(FrameParser.Frame frame) {
        if (!frame.crcOK) {
            Log.w(TAG, "CRC错误");
            sendAck(FrameParser.NAK);
            return;
        }

        int fileNo   = frame.fileNo;
        int pktCur   = frame.pktCurrent;
        int pktTotal = frame.pktTotal;

        if (pktCur == 0 || fileNo != mCurrentFileNo) {
            mCurrentFileNo   = fileNo;
            mCurrentPktTotal = pktTotal;
            int bufSize = pktTotal * MAX_DATA_BYTES + MAX_DATA_BYTES;
            mFileBuf    = new byte[bufSize];
            mFileBufUsed = 0;
            log("开始接收文件 #" + String.format("%03d", fileNo) +
                "  共 " + pktTotal + " 包");
        }

        if (mFileBuf == null) { sendAck(FrameParser.NAK); return; }

        int offset = pktCur * MAX_DATA_BYTES;
        if (offset + frame.dataLen <= mFileBuf.length) {
            System.arraycopy(frame.data, 0, mFileBuf, offset, frame.dataLen);
            mFileBufUsed = offset + frame.dataLen;
        }

        final int c = pktCur + 1, t = pktTotal, fn = fileNo;
        mH.post(() -> updateProgress(fn, c, t));

        sendAck(FrameParser.ACK);

        // 异步上传本分片到服务器（不影响主流程，失败仅记录日志）
        if (mPrefs.isUploadEnabled() && mPrefs.isServerConfigured()) {
            final byte[] dataCopy = Arrays.copyOf(frame.data, frame.dataLen);
            final int    fFileNo  = fileNo, fPktTotal = pktTotal,
                         fPktCur  = pktCur, fDataLen  = frame.dataLen;
            mUploadExec.execute(() -> uploadPacket(fFileNo, fPktTotal, fPktCur, dataCopy, fDataLen));
        }

        if (pktCur == pktTotal - 1) {
            int totalLen = mFileBufUsed;
            byte[] fileData = Arrays.copyOf(mFileBuf, totalLen);
            mFileBuf    = null;
            mFileBufUsed = 0;
            saveAndEmail(fileNo, fileData);
        }
    }

    private void resetCurrentFile() {
        mCurrentFileNo   = -1;
        mCurrentPktTotal = 0;
        mFileBuf         = null;
        mFileBufUsed     = 0;
    }

    //==========================================================
    // 上传单个分片到Win10服务器
    //==========================================================
    private static final int UPLOAD_RETRY_MAX = 2;

    private void uploadPacket(int fileNo, int pktTotal, int pktCur,
                              byte[] data, int dataLen) {
        String host = mPrefs.getServerHost();
        int    port = mPrefs.getServerPort();
        String dev  = mPrefs.getDeviceName();

        String err = null;
        for (int retry = 0; retry <= UPLOAD_RETRY_MAX; retry++) {
            err = ServerUploader.upload(host, port, dev, fileNo, pktTotal, pktCur, data, dataLen);
            if (err == null) break;
        }

        if (err == null) {
            log("☁ 已上传 File#" + String.format("%03d", fileNo) +
                " Pkt" + pktCur + "/" + (pktTotal - 1));
        } else {
            log("☁❌ 上传失败 File#" + String.format("%03d", fileNo) +
                " Pkt" + pktCur + ": " + err);
        }
    }

    private void sendAck(byte ack) {
        if (mPort == null) return;
        try { mPort.write(new byte[]{ack}, 500); }
        catch (IOException e) { Log.e(TAG, "ACK失败: " + e.getMessage()); }
    }

    //==========================================================
    // 保存文件 + 发邮件
    //==========================================================
    private void saveAndEmail(int fileNo, byte[] data) {
        mEmailEx.execute(() -> {
            String  fileName = String.format(Locale.US, "%03d.txt", fileNo);
            File    file     = new File(mSaveDir, fileName);
            String  timeStr  = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                   Locale.getDefault()).format(new Date());

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            } catch (IOException e) {
                log("❌ 文件保存失败: " + e.getMessage());
                return;
            }

            mFilesReceived++;
            mTotalBytes += data.length;
            log("✔ 文件已保存: " + fileName + " (" + data.length + " 字节)");

            FileRecord record = new FileRecord(fileName, data.length, timeStr,
                                               file.getAbsolutePath());
            sHistory.add(0, record);
            mH.post(this::refreshStats);

            if (!mPrefs.isConfigured()) {
                log("⚠ 邮箱未配置，跳过: " + fileName);
                record.emailError = "未配置邮箱";
                return;
            }

            log("📧 发送邮件: " + fileName + " → " + mPrefs.getTo());
            GmailSender sender = new GmailSender(
                mPrefs.getFrom(), mPrefs.getPassword(), mPrefs.getTo());

            String subject = String.format(Locale.US, "[LoRa] 数据文件 #%03d", fileNo);
            String body = String.format(Locale.US,
                "LoRa 接收数据\n\n文件编号：%03d\n大小：%d 字节\n时间：%s\n路径：%s",
                fileNo, data.length, timeStr, file.getAbsolutePath());

            String err = sender.send(subject, body, file);
            if (err == null) {
                mEmailsSent++;
                record.emailSent = true;
                log("✔ 邮件发送成功 (第" + mEmailsSent + "封)");
            } else {
                record.emailError = err;
                log("❌ 邮件失败: " + err);
            }
            mH.post(this::refreshStats);
        });
    }

    //==========================================================
    // UI
    //==========================================================
    private void updateProgress(int fileNo, int pktCur, int pktTotal) {
        int pct = (pktTotal > 0) ? (pktCur * 100 / pktTotal) : 0;
        pbPacket.setProgress(pct);
        tvPktProgress.setText(pktCur + " / " + pktTotal);
        tvFileInfo.setText(String.format(Locale.US,
            "文件 #%03d  当前包 %d / %d  (%d%%)", fileNo, pktCur, pktTotal, pct));
    }

    private void refreshStats() {
        tvFilesCount.setText(String.valueOf(mFilesReceived));
        tvEmailsCount.setText(String.valueOf(mEmailsSent));
        tvBytesCount.setText(String.valueOf(mTotalBytes / 1024));
    }

    private void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + time + "] " + msg + "\n";
        Log.d(TAG, msg);
        mH.post(() -> {
            tvLog.append(line);
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
