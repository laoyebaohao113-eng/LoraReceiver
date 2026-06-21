package com.rescue.sendimage;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
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
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A 端图传 App  v0.2.0 —— 完整复刻(干净版)
 *
 * 取图(SD卡文件夹/选图)→ JPEG压缩 → 按 PX30协议分包组帧 → 串口逐包发 → 收ACK流控/超时重发。
 * 串口/波特率界面可切换;TX/RX 带时间戳 hex;写 /sdcard/serial_log.txt;前台服务保活。
 */
public class MainActivity extends Activity {

    private static final String VERSION = "0.2.0";
    private static final String[] PORTS = {"/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3"};
    private static final String LOG_FILE = "serial_log.txt";
    private static final int REQ_PERM = 1001;
    private static final int REQ_PICK = 2001;

    private Spinner spPort;
    private EditText etBaud, etFolder, etQuality, etTimeout, etRetries;
    private Button btnOpen, btnPick, btnStart, btnStop, btnClear, btnCopy;
    private TextView status, log;
    private ScrollView scroll;

    private final SerialPortHelper serial = new SerialPortHelper();
    private RxFrameParser rxParser;
    private ImageSender sender;
    private Thread senderThread;
    private File pickedFile;   // “选图”选中的单张图(优先于文件夹)

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
        int pad = dp(6);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("A端图传 v" + VERSION + "  日志: /sdcard/" + LOG_FILE);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // 行:串口 + 波特率
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(label("串口"));
        spPort = new Spinner(this);
        spPort.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PORTS));
        spPort.setSelection(0);
        row1.addView(spPort, lp(1));
        row1.addView(label(" 波特率"));
        etBaud = numEdit(String.valueOf(SerialPortHelper.DEFAULT_BAUD));
        row1.addView(etBaud, lp(1));
        root.addView(row1);

        // 行:图片文件夹 + 选图
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(label("文件夹"));
        etFolder = new EditText(this);
        etFolder.setText(ImageSource.DEFAULT_FOLDER);
        etFolder.setTextSize(12);
        row2.addView(etFolder, lp(3));
        btnPick = new Button(this);
        btnPick.setText("选图");
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPickClicked(); }
        });
        row2.addView(btnPick, lp(1));
        root.addView(row2);

        // 行:JPEG质量 + 超时 + 重试
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(label("质量"));
        etQuality = numEdit("80");
        row3.addView(etQuality, lp(1));
        row3.addView(label(" 超时ms"));
        etTimeout = numEdit("1500");
        row3.addView(etTimeout, lp(1));
        row3.addView(label(" 重试"));
        etRetries = numEdit("8");
        row3.addView(etRetries, lp(1));
        root.addView(row3);

        // 行:打开 + 开始 + 停止
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        btnOpen = button("打开串口", new View.OnClickListener() {
            @Override public void onClick(View v) { onOpenClicked(); }
        });
        row4.addView(btnOpen, lp(1));
        btnStart = button("开始发送", new View.OnClickListener() {
            @Override public void onClick(View v) { onStartClicked(); }
        });
        btnStart.setEnabled(false);
        row4.addView(btnStart, lp(1));
        btnStop = button("停止", new View.OnClickListener() {
            @Override public void onClick(View v) { onStopClicked(); }
        });
        btnStop.setEnabled(false);
        row4.addView(btnStop, lp(1));
        root.addView(row4);

        // 行:清空 + 复制
        LinearLayout row5 = new LinearLayout(this);
        row5.setOrientation(LinearLayout.HORIZONTAL);
        row5.addView(button("清空日志", new View.OnClickListener() {
            @Override public void onClick(View v) { onClearClicked(); }
        }), lp(1));
        row5.addView(button("复制全部日志", new View.OnClickListener() {
            @Override public void onClick(View v) { onCopyClicked(); }
        }), lp(1));
        root.addView(row5);

        status = new TextView(this);
        status.setText("就绪");
        status.setTextSize(12);
        status.setTextColor(Color.rgb(0, 100, 0));
        root.addView(status);

        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(Color.BLACK);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        rxParser = new RxFrameParser(new RxFrameParser.AckListener() {
            @Override public void onAck(Protocol.Ack ack) {
                if (sender != null) sender.offerAck(ack);
            }
        });
        serial.setListener(new SerialPortHelper.Listener() {
            @Override public void onData(byte[] data, int len) {
                logLine("RX", HexUtil.toHex(data, 0, len) + "  (" + len + "B)");
                rxParser.feed(data, len);
            }
            @Override public void onError(String msg) { logLine("ERR", msg); }
        });

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        }
        logLine("INFO", "就绪。默认串口 " + PORTS[0] + " @ " + SerialPortHelper.DEFAULT_BAUD
                + ",图片文件夹 " + ImageSource.DEFAULT_FOLDER);
    }

    // ---------------- 按钮 ----------------

    private void onOpenClicked() {
        if (serial.isOpen()) {
            serial.close();
            btnOpen.setText("打开串口");
            btnStart.setEnabled(false);
            spPort.setEnabled(true);
            etBaud.setEnabled(true);
            logLine("INFO", "串口已关闭。");
            return;
        }
        String path = (String) spPort.getSelectedItem();
        int baud;
        try { baud = Integer.parseInt(etBaud.getText().toString().trim()); }
        catch (Exception e) { logLine("ERR", "波特率无效。"); return; }
        try {
            serial.open(path, baud);
            logLine("OK", "已打开 " + path + " @ " + baud + " 8N1。");
            btnOpen.setText("关闭串口");
            btnStart.setEnabled(true);
            spPort.setEnabled(false);
            etBaud.setEnabled(false);
        } catch (Throwable t) {
            logLine("ERR", "打开失败: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    private void onPickClicked() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try { startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_PICK); }
        catch (Exception e) { logLine("ERR", "无法打开选图器: " + e.getMessage()); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                File out = new File(getCacheDir(), "picked.jpg");
                InputStream in = getContentResolver().openInputStream(uri);
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                in.close(); fos.close();
                pickedFile = out;
                logLine("INFO", "已选图片:" + uri + "(将只发这一张)");
            } catch (Exception e) {
                logLine("ERR", "读取所选图片失败: " + e.getMessage());
            }
        }
    }

    private void onStartClicked() {
        if (!serial.isOpen()) { logLine("ERR", "请先打开串口。"); return; }
        if (senderThread != null && senderThread.isAlive()) { logLine("INFO", "正在发送中。"); return; }

        // 收集待发图片
        List<File> imgs = new ArrayList<>();
        if (pickedFile != null && pickedFile.exists()) {
            imgs.add(pickedFile);
        } else {
            String folder = etFolder.getText().toString().trim();
            imgs = ImageSource.listJpg(folder);
            if (imgs.isEmpty()) { logLine("ERR", "文件夹无 JPG: " + folder); return; }
        }
        final int quality = parseInt(etQuality, 80, 1, 100);
        final int timeout = parseInt(etTimeout, 1500, 100, 60000);
        final int retries = parseInt(etRetries, 8, 0, 50);

        sender = new ImageSender(imgs, quality, timeout, retries,
                new ImageSender.Tx() {
                    @Override public void send(byte[] frame) throws Exception { serial.send(frame); }
                },
                new ImageSender.Callback() {
                    @Override public void log(String tag, String msg) { logLine(tag, msg); }
                    @Override public void progress(int imgIdx, int imgTotal, int pktIdx, int pktTotal, int retry) {
                        final String s = "进度: 图 " + (imgIdx + 1) + "/" + imgTotal
                                + "  包 " + (pktIdx + 1) + "/" + pktTotal
                                + (retry > 0 ? "  重试" + retry : "");
                        runOnUiThread(new Runnable() { @Override public void run() { status.setText(s); } });
                    }
                    @Override public void done(final boolean ok, final String msg) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                logLine(ok ? "OK" : "ERR", "发送结束: " + msg);
                                status.setText(msg);
                                btnStart.setEnabled(true);
                                btnStop.setEnabled(false);
                                SendService.stop(MainActivity.this);
                            }
                        });
                    }
                });

        SendService.start(this);
        senderThread = new Thread(sender, "img-sender");
        senderThread.start();
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        logLine("INFO", "开始发送,待发 " + imgs.size() + " 张。");
    }

    private void onStopClicked() {
        if (sender != null) sender.stop();
        logLine("INFO", "已请求停止…");
    }

    private void onClearClicked() {
        fullLog.setLength(0);
        log.setText("");
        appendToFile("[" + fmt.format(new Date()) + "] ---- 屏幕日志已清空(文件继续记录)----\n");
        logLine("INFO", "屏幕日志已清空。");
    }

    private void onCopyClicked() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("serial_log", fullLog.toString()));
            Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ---------------- 日志 ----------------

    private void logLine(final String tag, final String s) {
        final String line = "[" + fmt.format(new Date()) + "] [" + tag + "] " + s + "\n";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                fullLog.append(line);
                log.append(line);
                scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
            }
        });
        appendToFile(line);
    }

    private void appendToFile(final String line) {
        fileExec.execute(new Runnable() {
            @Override public void run() {
                FileWriter w = null;
                try { w = new FileWriter(logFile, true); w.write(line); w.flush(); }
                catch (Throwable ignore) { }
                finally { if (w != null) try { w.close(); } catch (Throwable ignore) { } }
            }
        });
    }

    // ---------------- 小工具 ----------------

    private TextView label(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(12);
        t.setPadding(0, dp(8), 0, 0); return t;
    }
    private EditText numEdit(String def) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(def); e.setTextSize(12); return e;
    }
    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setOnClickListener(l); return b;
    }
    private LinearLayout.LayoutParams lp(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }
    private int parseInt(EditText e, int def, int min, int max) {
        try { int v = Integer.parseInt(e.getText().toString().trim());
            if (v < min) v = min; if (v > max) v = max; return v;
        } catch (Exception ex) { return def; }
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sender != null) sender.stop();
        serial.close();
        SendService.stop(this);
        fileExec.shutdown();
    }
}
