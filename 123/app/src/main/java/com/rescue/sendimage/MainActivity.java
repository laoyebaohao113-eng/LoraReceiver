package com.rescue.sendimage;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A 端串口调试 App  v0.1.1
 *
 * 功能:选择串口(默认 /dev/ttyS0)+ 波特率(默认 921600,可改)-> 打开 -> 发测试帧
 * -> 持续读串口。TX/RX 均以 [TX]/[RX] + hex + 时间戳显示,日志同时写 /sdcard/serial_log.txt。
 * 提供「清空日志」「复制全部日志」。
 *
 * 串口层:android-serialport-api(硬件 tty,非 USB),见 SerialPortHelper / android.serialport.SerialPort。
 */
public class MainActivity extends Activity {

    private static final String VERSION = "0.1.1";
    private static final String[] PORTS = {
            "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3"
    };
    private static final String LOG_FILE = "serial_log.txt";
    private static final int REQ_PERM = 1001;

    private Spinner spPort;
    private EditText etBaud;
    private Button btnOpen, btnSend, btnClear, btnCopy;
    private TextView log;
    private ScrollView scroll;

    private final SerialPortHelper serial = new SerialPortHelper();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final StringBuilder fullLog = new StringBuilder();
    private final ExecutorService fileExec = Executors.newSingleThreadExecutor();
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        logFile = new File(Environment.getExternalStorageDirectory(), LOG_FILE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("A端串口调试  v" + VERSION + "   日志文件: /sdcard/" + LOG_FILE);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // ---- 第一行:串口选择 + 波特率 ----
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        TextView lblPort = new TextView(this);
        lblPort.setText("串口 ");
        row1.addView(lblPort);

        spPort = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, PORTS);
        spPort.setAdapter(adapter);
        spPort.setSelection(0);   // 默认 /dev/ttyS0
        row1.addView(spPort, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView lblBaud = new TextView(this);
        lblBaud.setText("  波特率 ");
        row1.addView(lblBaud);

        etBaud = new EditText(this);
        etBaud.setInputType(InputType.TYPE_CLASS_NUMBER);
        etBaud.setText(String.valueOf(SerialPortHelper.DEFAULT_BAUD));  // 921600
        etBaud.setMinEms(5);
        row1.addView(etBaud, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row1);

        // ---- 第二行:打开 / 发送 ----
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        btnOpen = new Button(this);
        btnOpen.setText("打开串口");
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onOpenClicked(); }
        });
        row2.addView(btnOpen, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnSend = new Button(this);
        btnSend.setText("发送测试帧");
        btnSend.setEnabled(false);
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSendClicked(); }
        });
        row2.addView(btnSend, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row2);

        // ---- 第三行:清空 / 复制 ----
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);

        btnClear = new Button(this);
        btnClear.setText("清空日志");
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClearClicked(); }
        });
        row3.addView(btnClear, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnCopy = new Button(this);
        btnCopy.setText("复制全部日志");
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onCopyClicked(); }
        });
        row3.addView(btnCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row3);

        // ---- 大日志区(占满剩余,可滚动)----
        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(13);
        log.setTextColor(Color.BLACK);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setPadding(dp(4), dp(6), dp(4), dp(6));
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        serial.setListener(new SerialPortHelper.Listener() {
            @Override public void onData(byte[] data, int len) {
                logLine("[RX] " + HexUtil.toHex(data, 0, len) + "  (" + len + "B)");
            }
            @Override public void onError(String msg) {
                logLine("[ERR] " + msg);
            }
        });

        // 申请存储权限(为了写 /sdcard/serial_log.txt)
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            }
        }

        logLine("[INFO] 就绪。默认串口 " + PORTS[0] + " @ " + SerialPortHelper.DEFAULT_BAUD
                + "。选好串口/波特率后点「打开串口」。");
    }

    private void onOpenClicked() {
        if (serial.isOpen()) {
            // 已打开 -> 关闭
            serial.close();
            btnOpen.setText("打开串口");
            btnSend.setEnabled(false);
            spPort.setEnabled(true);
            etBaud.setEnabled(true);
            logLine("[INFO] 串口已关闭。");
            return;
        }
        String path = (String) spPort.getSelectedItem();
        int baud;
        try {
            baud = Integer.parseInt(etBaud.getText().toString().trim());
        } catch (Exception e) {
            logLine("[ERR] 波特率无效,请输入数字。");
            return;
        }
        try {
            serial.open(path, baud);
            logLine("[OK] 已打开 " + path + " @ " + baud + " 8N1,开始监听接收。");
            btnOpen.setText("关闭串口");
            btnSend.setEnabled(true);
            spPort.setEnabled(false);
            etBaud.setEnabled(false);
        } catch (Throwable t) {
            logLine("[FAIL] 打开失败: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    private void onSendClicked() {
        if (!serial.isOpen()) {
            logLine("[INFO] 请先打开串口。");
            return;
        }
        try {
            serial.send(HexUtil.TEST_FRAME);
            logLine("[TX] " + HexUtil.toHex(HexUtil.TEST_FRAME)
                    + "  (" + HexUtil.TEST_FRAME.length + "B)");
        } catch (Throwable t) {
            logLine("[FAIL] 发送失败: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    private void onClearClicked() {
        fullLog.setLength(0);
        log.setText("");
        // 文件保留完整历史,写个分隔标记
        appendToFile("[" + fmt.format(new Date()) + "] ---- 屏幕日志已清空(文件继续记录)----\n");
        logLine("[INFO] 屏幕日志已清空。");
    }

    private void onCopyClicked() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("serial_log", fullLog.toString()));
            Toast.makeText(this, "已复制全部日志到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 线程安全:屏幕追加一行 + 滚到底 + 写文件 */
    private void logLine(final String s) {
        final String line = "[" + fmt.format(new Date()) + "] " + s + "\n";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                fullLog.append(line);
                log.append(line);
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
        appendToFile(line);
    }

    /** 把一行追加到 /sdcard/serial_log.txt(后台线程,避免卡 UI) */
    private void appendToFile(final String line) {
        fileExec.execute(new Runnable() {
            @Override public void run() {
                FileWriter w = null;
                try {
                    w = new FileWriter(logFile, true);   // append
                    w.write(line);
                    w.flush();
                } catch (Throwable ignore) {
                    // 没权限/写失败就算了,屏幕日志仍在
                } finally {
                    if (w != null) { try { w.close(); } catch (Throwable ignore) { } }
                }
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        serial.close();
        fileExec.shutdown();
    }
}
