package com.rescue.sendimage;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.widget.ScrollView;
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
 * 日志面板:屏幕显示([TX]/[RX]/... 带时间戳)+ 写 /sdcard 文件 + 清空 + 复制。
 * 从 MainActivity 拆出来,保持 UI 类精简。
 */
public class LogPanel {

    private final Activity act;
    private final TextView log;
    private final ScrollView scroll;
    private final StringBuilder full = new StringBuilder();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final ExecutorService fileExec = Executors.newSingleThreadExecutor();
    private final File logFile;

    public LogPanel(Activity a, TextView log, ScrollView scroll, String fileName) {
        this.act = a; this.log = log; this.scroll = scroll;
        this.logFile = new File(Environment.getExternalStorageDirectory(), fileName);
    }

    public void line(final String tag, final String s) {
        final String line = "[" + fmt.format(new Date()) + "] [" + tag + "] " + s + "\n";
        act.runOnUiThread(new Runnable() { public void run() {
            full.append(line); log.append(line);
            scroll.post(new Runnable() { public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
        }});
        toFile(line);
    }

    private void toFile(final String line) {
        fileExec.execute(new Runnable() { public void run() {
            FileWriter w = null;
            try { w = new FileWriter(logFile, true); w.write(line); w.flush(); }
            catch (Throwable ignore) {} finally { if (w != null) try { w.close(); } catch (Throwable ignore) {} }
        }});
    }

    public void clear() {
        full.setLength(0); log.setText("");
        toFile("---- 屏幕日志已清空(文件继续记录)----\n");
        line("INFO", "屏幕日志已清空。");
    }

    public void copy() {
        try {
            ((ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("serial_log", full.toString()));
            Toast.makeText(act, "已复制全部日志", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) { Toast.makeText(act, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    public void shutdown() { fileExec.shutdown(); }
}
