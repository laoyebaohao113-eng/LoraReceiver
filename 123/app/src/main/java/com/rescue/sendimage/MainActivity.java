package com.rescue.sendimage;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A 端串口最小验证 App  v0.1.0
 *
 * 只做:打开 /dev/ttyS1(921600/8N1)-> 发一帧写死的测试控制帧 -> 持续读串口,
 * 把发出/收到的字节都以 hex 显示。先不做拍照/分包/传图。
 *
 * 串口层:android-serialport-api(硬件 tty,非 USB),见 SerialPortHelper。
 */
public class MainActivity extends Activity {

    private static final String VERSION = "0.1.0";

    private TextView log;
    private ScrollView scroll;
    private Button btnOpen;
    private Button btnSend;

    private final SerialPortHelper serial = new SerialPortHelper();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("A端串口最小验证  v" + VERSION + "\n" +
                SerialPortHelper.DEV_PATH + " @ " + SerialPortHelper.BAUD + " 8N1");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        btnOpen = new Button(this);
        btnOpen.setText("打开串口");
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onOpenClicked(); }
        });
        bar.addView(btnOpen, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnSend = new Button(this);
        btnSend.setText("发送测试帧");
        btnSend.setEnabled(false);
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSendClicked(); }
        });
        bar.addView(btnSend, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(Color.DKGRAY);
        log.setPadding(0, dp(8), 0, 0);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setText("就绪。先点「打开串口」。\n");
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // 串口收到数据 -> 显示 hex
        serial.setListener(new SerialPortHelper.Listener() {
            @Override public void onData(final byte[] data, final int len) {
                logLine("RX <- " + HexUtil.toHex(data, 0, len) + "  (" + len + " 字节)");
            }
            @Override public void onError(final String msg) {
                logLine("[ERR] " + msg);
            }
        });
    }

    private void onOpenClicked() {
        if (serial.isOpen()) {
            logLine("串口已打开,无需重复打开。");
            return;
        }
        try {
            serial.open();
            logLine("[OK] 已打开 " + SerialPortHelper.DEV_PATH + " @ "
                    + SerialPortHelper.BAUD + " 8N1,开始监听接收。");
            btnSend.setEnabled(true);
            btnOpen.setText("已打开");
        } catch (Throwable t) {
            logLine("[FAIL] 打开失败: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    private void onSendClicked() {
        if (!serial.isOpen()) {
            logLine("请先打开串口。");
            return;
        }
        try {
            serial.send(HexUtil.TEST_FRAME);
            logLine("TX -> " + HexUtil.toHex(HexUtil.TEST_FRAME)
                    + "  (" + HexUtil.TEST_FRAME.length + " 字节)");
        } catch (Throwable t) {
            logLine("[FAIL] 发送失败: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    // 线程安全地把一行追加到日志并滚到底
    private void logLine(final String s) {
        final String line = "[" + fmt.format(new Date()) + "] " + s + "\n";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                log.append(line);
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
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
    }
}
