package com.rescue.sendimage;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** A 端图传 App —— 处理(图→txt)/发送(txt→串口),各支持整批/单张。
 *  v0.9.1:处理完成后自动进发送队列(串口已开立即发整批;未开则打开串口后自动发)。 */
public class MainActivity extends Activity implements SendController.Ui {

    private static final String VERSION = "0.9.6";
    private static final String[] PORTS = {"/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3"};
    private static final int REQ_PERM = 1001, REQ_PICK = 2001;

    private Spinner spPort, spTxt;
    private EditText etBaud, etFolder, etTimeout, etRetries;
    private EditText etSettle, etCtrlGap, etChunkSize, etChunkDelay;
    private CheckBox cbChunk;
    private Button btnOpen, btnSendBatch, btnSendOne, btnProcBatch, btnProcOne, btnAdv;
    private TextView status, log, tvPicked;
    private ScrollView scroll;
    private LinearLayout advPanel;   // 高级设置面板(可折叠)

    private LogPanel logp;
    private SendController ctrl;
    private ImageProcessor processor;
    private Thread procThread;
    private File pickedImage;
    /** v0.9.1:处理完成后自动进发送队列。串口已开→立即发整批;未开→置位, 打开串口后自动发。 */
    private boolean pendingAutoSend = false;
    private List<File> txtList = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashLogger();   // v0.9.5:崩溃黑匣子,把未捕获异常(含OOM)栈+内存写进 sendimage_crash.txt
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(6); root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("A端图传 v" + VERSION + "  ceshi=/storage/emulated/0/ceshi");
        title.setTextSize(12); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);

        // ========== 常用控件(始终可见)==========
        LinearLayout r1 = row(); r1.addView(label("串口"));
        spPort = new Spinner(this);
        spPort.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PORTS));
        spPort.setSelection(1); // 默认 /dev/ttyS1(仍可下拉切换)
        r1.addView(spPort, lp(1)); r1.addView(label(" 波特率"));
        etBaud = numEdit(String.valueOf(SerialPortHelper.DEFAULT_BAUD)); r1.addView(etBaud, lp(1)); root.addView(r1);

        LinearLayout r2 = row(); r2.addView(label("图片夹"));
        etFolder = new EditText(this); etFolder.setText(ImageProcessor.autoDetectSrcFolder(this)); etFolder.setTextSize(11);
        r2.addView(etFolder, lp(3));
        r2.addView(button("选图", new View.OnClickListener() { public void onClick(View v) { onPick(); } }), lp(1)); root.addView(r2);

        LinearLayout r2b = row();
        tvPicked = new TextView(this); tvPicked.setText("已选图: (无)"); tvPicked.setTextSize(11);
        tvPicked.setTextColor(Color.rgb(0, 0, 160)); tvPicked.setPadding(0, dp(8), 0, 0);
        r2b.addView(tvPicked, lp(3));
        r2b.addView(button("列存储", new View.OnClickListener() { public void onClick(View v) { listStorage(); } }), lp(1));
        root.addView(r2b);

        LinearLayout r4 = row();
        btnProcBatch = button("处理整批", new View.OnClickListener() { public void onClick(View v) { startProcess(false); } });
        btnProcOne = button("处理选定单张", new View.OnClickListener() { public void onClick(View v) { onProcOne(); } });
        r4.addView(btnProcBatch, lp(1)); r4.addView(btnProcOne, lp(1)); root.addView(r4);

        LinearLayout r5 = row(); r5.addView(label("待发txt")); spTxt = new Spinner(this); r5.addView(spTxt, lp(3));
        r5.addView(button("刷新", new View.OnClickListener() { public void onClick(View v) { refreshTxt(); } }), lp(1)); root.addView(r5);

        LinearLayout r6 = row();
        btnOpen = button("打开串口", new View.OnClickListener() { public void onClick(View v) { onOpen(); } });
        btnSendBatch = button("发送整批", new View.OnClickListener() { public void onClick(View v) { onSend(false); } });
        btnSendOne = button("发送选定txt", new View.OnClickListener() { public void onClick(View v) { onSend(true); } });
        btnSendBatch.setEnabled(false); btnSendOne.setEnabled(false);
        r6.addView(btnOpen, lp(1)); r6.addView(btnSendBatch, lp(1)); r6.addView(btnSendOne, lp(1)); root.addView(r6);

        LinearLayout rAct = row();
        btnAdv = button("高级设置▼", new View.OnClickListener() { public void onClick(View v) { toggleAdv(); } });
        rAct.addView(btnAdv, lp(1));
        rAct.addView(button("停止", new View.OnClickListener() { public void onClick(View v) { onStopClick(); } }), lp(1));
        rAct.addView(button("清空", new View.OnClickListener() { public void onClick(View v) { logp.clear(); } }), lp(1));
        rAct.addView(button("复制", new View.OnClickListener() { public void onClick(View v) { logp.copy(); } }), lp(1));
        root.addView(rAct);

        // ========== 高级设置(默认收起,把空间让给日志)==========
        advPanel = new LinearLayout(this); advPanel.setOrientation(LinearLayout.VERTICAL);
        advPanel.setVisibility(View.GONE);
        LinearLayout a1 = row(); a1.addView(label("超时ms")); etTimeout = numEdit("1500"); a1.addView(etTimeout, lp(1));
        a1.addView(label(" 重试")); etRetries = numEdit("8"); a1.addView(etRetries, lp(1)); advPanel.addView(a1);
        LinearLayout a2 = row(); a2.addView(label("安定ms")); etSettle = numEdit("1500"); a2.addView(etSettle, lp(1));
        a2.addView(label(" 控制间隔ms")); etCtrlGap = numEdit("400"); a2.addView(etCtrlGap, lp(1)); advPanel.addView(a2);
        LinearLayout a3 = row(); cbChunk = new CheckBox(this); cbChunk.setText("分段写"); cbChunk.setTextSize(11); a3.addView(cbChunk);
        a3.addView(label(" 段")); etChunkSize = numEdit("48"); a3.addView(etChunkSize, lp(1));
        a3.addView(label(" 段延ms")); etChunkDelay = numEdit("3"); a3.addView(etChunkDelay, lp(1)); advPanel.addView(a3);
        LinearLayout a4 = row(); a4.addView(label("短包"));
        a4.addView(button("发10", new View.OnClickListener() { public void onClick(View v) { onShortTest(10); } }), lp(1));
        a4.addView(button("发64", new View.OnClickListener() { public void onClick(View v) { onShortTest(64); } }), lp(1));
        a4.addView(button("发190", new View.OnClickListener() { public void onClick(View v) { onShortTest(190); } }), lp(1));
        a4.addView(button("测试帧", new View.OnClickListener() { public void onClick(View v) { ctrl.sendTestFrame(); } }), lp(1));
        advPanel.addView(a4);
        root.addView(advPanel);

        status = new TextView(this); status.setText("就绪"); status.setTextSize(12);
        status.setTextColor(Color.rgb(0, 100, 0)); root.addView(status);

        // ========== 日志区(占满剩余高度,可滚动)==========
        scroll = new ScrollView(this); log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE); log.setTextSize(12); log.setTextColor(Color.BLACK);
        log.setTextIsSelectable(true); log.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        logp = new LogPanel(this, log, scroll, "serial_log.txt");
        ctrl = new SendController(this, this);

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        logp.line("INFO", "就绪。先「处理」(图→ceshi txt),再「发送」(txt→串口)。调试参数在「高级设置」里。");
        refreshTxt();
    }

    @Override public void log(String tag, String msg) { logp.line(tag, msg); }

    /** v0.9.5:全局崩溃黑匣子。任何线程未捕获的异常(NPE/OOM/…)在进程被杀前,
     *  把"时间+线程+内存(max/total/free)+完整栈"追加到 /sdcard/sendimage_crash.txt,
     *  然后交回系统默认处理(应用照常退出)。注:native 段错误(SIGSEGV)走不到这里,需配合 logcat。 */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    Runtime rt = Runtime.getRuntime();
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String info = "==== CRASH " + new java.util.Date() + "  thread=" + t.getName()
                        + "  mem(MB) max=" + (rt.maxMemory() / 1048576)
                        + " total=" + (rt.totalMemory() / 1048576)
                        + " free=" + (rt.freeMemory() / 1048576) + " ====\n"
                        + sw.toString() + "\n\n";
                    FileWriter w = new FileWriter(new File(Environment.getExternalStorageDirectory(), "sendimage_crash.txt"), true);
                    w.write(info); w.flush(); w.close();
                } catch (Throwable ignore) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
    @Override public void progress(final int i, final int it, final int pk, final int pt, final int rt) {
        final String s = "进度: 图 " + (i + 1) + "/" + it + "  包 " + (pk + 1) + "/" + pt + (rt > 0 ? "  重试" + rt : "");
        runOnUiThread(new Runnable() { public void run() { status.setText(s); } });
    }
    @Override public void sendDone(final boolean ok, final String msg) {
        runOnUiThread(new Runnable() { public void run() {
            logp.line(ok ? "OK" : "ERR", "发送结束: " + msg); status.setText(msg);
            btnSendBatch.setEnabled(ctrl.isOpen()); btnSendOne.setEnabled(ctrl.isOpen());
        }});
    }

    /** 折叠/展开高级设置面板 */
    private void toggleAdv() {
        boolean show = advPanel.getVisibility() != View.VISIBLE;
        advPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        btnAdv.setText(show ? "高级设置▲" : "高级设置▼");
    }

    private void onPick() {
        // 优先用 SAF 文档选择器(Android 11 上最稳,返回真实可读 URI)
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            try {
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.addCategory(Intent.CATEGORY_OPENABLE); g.setType("image/*");
                startActivityForResult(Intent.createChooser(g, "选择图片"), REQ_PICK);
            } catch (Exception e2) { logp.line("ERR", "无法打开选图器: " + e2.getMessage()); }
        }
    }

    /** 查所选 URI 的真实文件名(查不到返回 null) */
    private String queryName(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignore) { }
        return null;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        // 不管成不成,都打印回调情况,便于排查"选完没反应"
        logp.line("DBG", "选图回调: resultCode=" + res + (res == RESULT_OK ? "(OK)" : "(非OK,可能取消)"));
        if (res != RESULT_OK) { logp.line("INFO", "未选择图片(取消或无结果)。"); return; }

        // 取 URI:优先 getData,兜底 clipData(部分相册/文件器走 clipData)
        android.net.Uri uri = (data != null) ? data.getData() : null;
        if (uri == null && data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            uri = data.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) { logp.line("ERR", "选图返回里没有图片URI(data/clipData都为空)。"); return; }
        final String realName = queryName(uri);
        logp.line("DBG", "选中URI=" + uri + "  文件名=" + (realName != null ? realName : "(未知)"));

        try {
            File out = new File(getCacheDir(), "picked_src.jpg");
            if (out.exists()) out.delete();   // 删旧的,避免读不到时残留上一张造成"假尺寸"
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) { logp.line("ERR", "打不开所选图片输入流: " + uri); return; }
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
            in.close(); fos.close();
            if (total <= 0 || !out.exists()) { logp.line("ERR", "所选图片内容为空,选取失败。"); pickedImage = null; return; }
            pickedImage = out;
            // 只读尺寸(不解整张,避免大图OOM),作为"确实拿到这张图"的真实证据
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(out.getAbsolutePath(), o);
            final String info = "已选图: " + (realName != null ? realName + "  " : "")
                    + (o.outWidth > 0 ? (o.outWidth + "x" + o.outHeight + "  ") : "(非图片?)")
                    + (total / 1024) + "KB  → 可点「处理选定单张」";
            runOnUiThread(new Runnable() { public void run() { tvPicked.setText(info); } });
            logp.line("OK", info);
        } catch (Exception e) {
            pickedImage = null;
            logp.line("ERR", "读所选图片失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** 扫描存储卷,找含 jpg 子文件夹的卷,帮你确认 SD 卡 jpg 的真实路径 */
    private void listStorage() {
        StringBuilder sb = new StringBuilder("存储卡扫描(找含 jpg 子夹的卷):\n");
        List<String> cand = new ArrayList<>();
        cand.add("/storage/emulated/0");           // 内置存储(多数文件器叫"SD卡"/"内部存储")
        File[] roots = new File("/storage").listFiles();
        if (roots != null) for (File r : roots) {
            String n = r.getName();
            if (n.equals("emulated") || n.equals("self")) continue;
            cand.add(r.getAbsolutePath());          // 外置SD,形如 /storage/1234-5678
        }
        for (String base : cand) {
            File jpg = new File(base, "jpg");
            if (jpg.exists() && jpg.isDirectory()) {
                int c = 0; File[] js = jpg.listFiles();
                if (js != null) for (File f : js) {
                    String nn = f.getName().toLowerCase();
                    if (nn.endsWith(".jpg") || nn.endsWith(".jpeg")) c++;
                }
                sb.append("  ✓ ").append(jpg.getAbsolutePath()).append("  (jpg ").append(c).append(" 张) ← 填到上面「图片夹」\n");
            } else {
                sb.append("  ✗ ").append(base).append("/jpg 不存在\n");
            }
        }
        logp.line("INFO", sb.toString());
    }

    private void onProcOne() {
        if (pickedImage == null || !pickedImage.exists()) { logp.line("ERR", "请先点选图选一张。"); return; }
        startProcess(true);
    }

    private void startProcess(final boolean single) {
        if (procThread != null && procThread.isAlive()) { logp.line("INFO", "正在处理中。"); return; }
        if (single && (pickedImage == null || !pickedImage.exists())) { logp.line("ERR", "未选图,无法处理单张。请先点「选图」。"); return; }
        processor = new ImageProcessor(this, new ImageProcessor.Callback() {
            public void log(String t, String m) { logp.line(t, m); }
            public void done(boolean ok, final int okc, final int failc) {
                runOnUiThread(new Runnable() { public void run() {
                    status.setText("处理结束:成功 " + okc + " 失败 " + failc);
                    btnProcBatch.setEnabled(true); btnProcOne.setEnabled(true); refreshTxt();
                    /* v0.9.1:处理完自动进发送队列 */
                    if (ok && okc > 0) {
                        if (ctrl.isOpen()) {
                            logp.line("INFO", "处理完成,自动发送整批…");
                            onSend(false);
                        } else {
                            pendingAutoSend = true;
                            logp.line("INFO", "处理完成(" + okc + "个txt),串口未打开;打开串口后将自动发送整批。");
                        }
                    }
                }});
            }
        });
        final String folder = etFolder.getText().toString().trim();
        btnProcBatch.setEnabled(false); btnProcOne.setEnabled(false);
        procThread = new Thread(new Runnable() { public void run() {
            if (single) processor.processOne(pickedImage); else processor.processFolder(folder);
        }}, "img-proc");
        procThread.start();
    }

    private void onOpen() {
        if (ctrl.isOpen()) {
            ctrl.close(); btnOpen.setText("打开串口");
            btnSendBatch.setEnabled(false); btnSendOne.setEnabled(false);
            spPort.setEnabled(true); etBaud.setEnabled(true); logp.line("INFO", "串口已关闭。"); return;
        }
        int baud;
        try { baud = Integer.parseInt(etBaud.getText().toString().trim()); }
        catch (Exception e) { logp.line("ERR", "波特率无效。"); return; }
        if (ctrl.open((String) spPort.getSelectedItem(), baud)) {
            btnOpen.setText("关闭串口"); btnSendBatch.setEnabled(true); btnSendOne.setEnabled(true);
            spPort.setEnabled(false); etBaud.setEnabled(false);
            /* v0.9.1:若有"处理完待发"的批次, 串口一开就自动发 */
            if (pendingAutoSend) {
                pendingAutoSend = false;
                logp.line("INFO", "串口已开,自动发送上次处理的整批…");
                onSend(false);
            }
        }
    }

    private void onSend(boolean single) {
        List<File> files;
        if (single) {
            int idx = spTxt.getSelectedItemPosition();
            if (idx < 0 || idx >= txtList.size()) { logp.line("ERR", "请先在待发txt里选一个。"); return; }
            files = SendController.singleList(txtList.get(idx));
        } else {
            files = TxtSource.listTxt(ImageProcessor.CESHI_FOLDER);
            if (files.isEmpty()) { logp.line("ERR", "ceshi 无 txt,请先处理。"); return; }
        }
        btnSendBatch.setEnabled(false); btnSendOne.setEnabled(false);
        applyChunk();
        ctrl.send(files, parseInt(etTimeout, 1500, 100, 60000), parseInt(etRetries, 8, 0, 50),
                parseInt(etCtrlGap, 400, 0, 5000), parseInt(etSettle, 1500, 0, 30000));
    }

    /** 把界面分段写设置应用到串口层 */
    private void applyChunk() {
        ctrl.setChunk(cbChunk.isChecked(), parseInt(etChunkSize, 48, 1, 208), parseInt(etChunkDelay, 3, 0, 100));
    }

    /** B类对照实验:发合法短数据帧 */
    private void onShortTest(int n) {
        if (!ctrl.isOpen()) { logp.line("ERR", "请先打开串口。"); return; }
        applyChunk();
        ctrl.sendShortDataTest(n, parseInt(etCtrlGap, 400, 0, 5000), parseInt(etSettle, 1500, 0, 30000));
    }

    private void onStopClick() {
        ctrl.stop(); if (processor != null) processor.stop(); logp.line("INFO", "已请求停止…");
    }

    private void refreshTxt() {
        txtList = TxtSource.listTxt(ImageProcessor.CESHI_FOLDER);
        List<String> names = new ArrayList<>();
        for (File f : txtList) names.add(f.getName());
        if (names.isEmpty()) names.add("(ceshi无txt)");
        spTxt.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        logp.line("INFO", "ceshi 现有 " + txtList.size() + " 个 txt");
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private TextView label(String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(12); t.setPadding(0, dp(8), 0, 0); return t; }
    private EditText numEdit(String d) { EditText e = new EditText(this); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setText(d); e.setTextSize(12); return e; }
    private Button button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setOnClickListener(l); b.setTextSize(12); return b; }
    private LinearLayout.LayoutParams lp(float w) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w); }
    private int parseInt(EditText e, int def, int min, int max) {
        try { int v = Integer.parseInt(e.getText().toString().trim()); return v < min ? min : (v > max ? max : v); }
        catch (Exception ex) { return def; }
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (ctrl != null) ctrl.shutdown();
        if (processor != null) processor.stop();
        if (logp != null) logp.shutdown();
    }
}
