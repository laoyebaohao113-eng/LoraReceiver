package com.rescue.sendimage;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** A 端图传 App v0.3.0 —— 处理(图→txt)/发送(txt→串口)两步分开,各支持整批/单张。 */
public class MainActivity extends Activity implements SendController.Ui {

    private static final String VERSION = "0.3.0";
    private static final String[] PORTS = {"/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3"};
    private static final int REQ_PERM = 1001, REQ_PICK = 2001;

    private Spinner spPort, spTxt;
    private EditText etBaud, etFolder, etTimeout, etRetries;
    private Button btnOpen, btnSendBatch, btnSendOne, btnProcBatch, btnProcOne;
    private TextView status, log;
    private ScrollView scroll;

    private LogPanel logp;
    private SendController ctrl;
    private ImageProcessor processor;
    private Thread procThread;
    private File pickedImage;
    private List<File> txtList = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(6); root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("A端图传 v" + VERSION + "  ceshi=/storage/emulated/0/ceshi");
        title.setTextSize(12); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);

        LinearLayout r1 = row(); r1.addView(label("串口"));
        spPort = new Spinner(this);
        spPort.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PORTS));
        r1.addView(spPort, lp(1)); r1.addView(label(" 波特率"));
        etBaud = numEdit(String.valueOf(SerialPortHelper.DEFAULT_BAUD)); r1.addView(etBaud, lp(1)); root.addView(r1);

        LinearLayout r2 = row(); r2.addView(label("图片夹"));
        etFolder = new EditText(this); etFolder.setText(ImageProcessor.DEFAULT_SRC_FOLDER); etFolder.setTextSize(11);
        r2.addView(etFolder, lp(3));
        r2.addView(button("选图", new View.OnClickListener() { public void onClick(View v) { onPick(); } }), lp(1)); root.addView(r2);

        LinearLayout r3 = row(); r3.addView(label("超时ms")); etTimeout = numEdit("1500"); r3.addView(etTimeout, lp(1));
        r3.addView(label(" 重试")); etRetries = numEdit("8"); r3.addView(etRetries, lp(1)); root.addView(r3);

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

        LinearLayout r7 = row();
        r7.addView(button("停止", new View.OnClickListener() { public void onClick(View v) { onStopClick(); } }), lp(1));
        r7.addView(button("清空日志", new View.OnClickListener() { public void onClick(View v) { logp.clear(); } }), lp(1));
        r7.addView(button("复制日志", new View.OnClickListener() { public void onClick(View v) { logp.copy(); } }), lp(1)); root.addView(r7);

        status = new TextView(this); status.setText("就绪"); status.setTextSize(12);
        status.setTextColor(Color.rgb(0, 100, 0)); root.addView(status);

        scroll = new ScrollView(this); log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE); log.setTextSize(12); log.setTextColor(Color.BLACK);
        log.setTextIsSelectable(true); log.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(log); root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        logp = new LogPanel(this, log, scroll, "serial_log.txt");
        ctrl = new SendController(this, this);

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        logp.line("INFO", "就绪。先「处理」(图→ceshi txt),再「发送」(txt→串口)。两步独立。");
        refreshTxt();
    }

    @Override public void log(String tag, String msg) { logp.line(tag, msg); }
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

    private void onPick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*");
        try { startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_PICK); }
        catch (Exception e) { logp.line("ERR", "无法打开选图器: " + e.getMessage()); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                File out = new File(getCacheDir(), "picked_src.jpg");
                InputStream in = getContentResolver().openInputStream(data.getData());
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                in.close(); fos.close(); pickedImage = out;
                logp.line("INFO", "已选图片(用于处理选定单张)");
            } catch (Exception e) { logp.line("ERR", "读所选图片失败: " + e.getMessage()); }
        }
    }

    private void onProcOne() {
        if (pickedImage == null || !pickedImage.exists()) { logp.line("ERR", "请先点选图选一张。"); return; }
        startProcess(true);
    }

    private void startProcess(final boolean single) {
        if (procThread != null && procThread.isAlive()) { logp.line("INFO", "正在处理中。"); return; }
        processor = new ImageProcessor(this, new ImageProcessor.Callback() {
            public void log(String t, String m) { logp.line(t, m); }
            public void done(boolean ok, final int okc, final int failc) {
                runOnUiThread(new Runnable() { public void run() {
                    status.setText("处理结束:成功 " + okc + " 失败 " + failc);
                    btnProcBatch.setEnabled(true); btnProcOne.setEnabled(true); refreshTxt();
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
        ctrl.send(files, parseInt(etTimeout, 1500, 100, 60000), parseInt(etRetries, 8, 0, 50));
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
