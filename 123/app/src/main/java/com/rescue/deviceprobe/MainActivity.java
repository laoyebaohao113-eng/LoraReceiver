package com.rescue.deviceprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 设备信息探测 App —— 只读探测,不修改设备。
 * 版本 0.1.0
 * 结果显示在屏幕,并写入 /sdcard/device_probe_result.txt
 */
public class MainActivity extends Activity {

    private static final String VERSION = "0.1.0";
    private static final String RESULT_FILE = "device_probe_result.txt";
    private static final int REQ_PERM = 1001;

    private Button button;
    private TextView output;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ---- 纯代码构建 UI:一个按钮 + 可滚动文本框 ----
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("设备信息探测  v" + VERSION + "(只读)");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        button = new Button(this);
        button.setText("开始探测");
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startProbe(); }
        });
        root.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextColor(Color.DKGRAY);
        output.setPadding(0, dp(8), 0, 0);
        output.setText("点击「开始探测」。\n结果会写入 /sdcard/" + RESULT_FILE);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- 按钮:先要存储权限,再后台探测 ----
    private void startProbe() {
        if (running) return;
        if (Build.VERSION.SDK_INT >= 23) {
            boolean w = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!w) {
                requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
                return;
            }
        }
        runProbeThread();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            // 不管授没授权都跑(报告里会写清写文件成功/失败)
            runProbeThread();
        }
    }

    private void runProbeThread() {
        running = true;
        button.setEnabled(false);
        button.setText("探测中…");
        output.setText("探测中,请稍候…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String report = buildReport();
                final String writeStatus = writeResultFile(report);
                final String full = report + "\n" + writeStatus + "\n";
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        output.setText(full);
                        button.setEnabled(true);
                        button.setText("重新探测");
                        running = false;
                    }
                });
            }
        }, "probe").start();
    }

    // ================= 探测主体 =================
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 设备信息探测 v").append(VERSION).append(" =====\n");
        sb.append("时间(开机以来ms): ").append(System.currentTimeMillis()).append("\n\n");

        sectionDevice(sb);
        sectionRoot(sb);
        sectionSerial(sb);
        sectionUsb(sb);
        sectionStorage(sb);
        return sb.toString();
    }

    // 1) 系统/机型/ABI
    private void sectionDevice(StringBuilder sb) {
        sb.append("【1. 系统与机型】\n");
        sb.append("Android 版本: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("API 级别    : ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("型号 MODEL   : ").append(Build.MODEL).append("\n");
        sb.append("厂商 MANUF   : ").append(Build.MANUFACTURER).append("\n");
        sb.append("品牌 BRAND   : ").append(Build.BRAND).append("\n");
        sb.append("设备 DEVICE  : ").append(Build.DEVICE).append("\n");
        sb.append("硬件 HARDWARE: ").append(Build.HARDWARE).append("\n");
        StringBuilder abis = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null) {
            for (String a : Build.SUPPORTED_ABIS) { if (abis.length() > 0) abis.append(", "); abis.append(a); }
        }
        sb.append("CPU 架构 ABI : ").append(abis).append("\n\n");
    }

    // root 检测(只探测,不获取)
    private void sectionRoot(StringBuilder sb) {
        sb.append("【1b. Root / su 检测】\n");
        String[] suPaths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/vendor/bin/su", "/system/sbin/su", "/data/local/xbin/su",
                "/data/local/bin/su", "/magisk/.core/bin/su"
        };
        boolean found = false;
        for (String p : suPaths) {
            File f = new File(p);
            if (f.exists()) {
                found = true;
                sb.append("  发现 su: ").append(p)
                  .append("  可执行=").append(f.canExecute()).append("\n");
            }
        }
        if (!found) sb.append("  常见路径未发现 su 文件\n");

        // 试跑 su -c id(3秒超时,不挂死)
        String r = execWithTimeout(new String[]{"su", "-c", "id"}, 3000);
        sb.append("  执行 `su -c id` 结果: ").append(r).append("\n");
        sb.append("  判定: ").append(r.contains("uid=0") ? "可获得 root(su 可用)"
                : "无法确认 root(su 不可用或被拒)").append("\n\n");
    }

    // 2) 串口节点扫描
    private void sectionSerial(StringBuilder sb) {
        sb.append("【2. 串口节点扫描(重点 /dev/ttyS1)】\n");

        Set<String> paths = new LinkedHashSet<>();
        for (int i = 0; i <= 9; i++) paths.add("/dev/ttyS" + i);
        for (int i = 0; i <= 7; i++) paths.add("/dev/ttyUSB" + i);
        for (int i = 0; i <= 3; i++) paths.add("/dev/ttyACM" + i);
        // 再扫一遍 /dev,补充其它 tty*
        try {
            String[] devs = new File("/dev").list();
            if (devs != null) {
                for (String n : devs) {
                    if (n.startsWith("tty") && !n.equals("tty")) paths.add("/dev/" + n);
                }
            }
        } catch (Throwable ignore) { }

        for (String path : paths) {
            File f = new File(path);
            boolean exists = f.exists();
            if (!exists) {
                // 固定候选项才打印"不存在",动态扫到的空项跳过
                if (path.matches("/dev/tty(S[0-9]|USB[0-7]|ACM[0-3])")) {
                    String tag = path.equals("/dev/ttyS1") ? "  >>> " : "  - ";
                    sb.append(tag).append(path).append(" : 不存在\n");
                }
                continue;
            }
            String tag = path.equals("/dev/ttyS1") ? "  >>> " : "  - ";
            sb.append(tag).append(path).append(" : 存在");
            // 权限/属主
            try {
                StructStat st = Os.stat(path);
                sb.append("  权限=").append(permToString(st.st_mode))
                  .append("  uid=").append(st.st_uid).append(" gid=").append(st.st_gid);
            } catch (ErrnoException e) {
                sb.append("  stat失败=").append(OsConstants.errnoName(e.errno));
            } catch (Throwable t) {
                sb.append("  stat异常=").append(t.getClass().getSimpleName());
            }
            sb.append("  本App可读=").append(f.canRead()).append(" 可写=").append(f.canWrite());
            // 尝试 open(非阻塞,不会挂死)
            sb.append("  open=").append(tryOpen(path)).append("\n");
        }
        sb.append("\n");
    }

    // 非阻塞打开,返回结果说明
    private String tryOpen(String path) {
        int flags = OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_NOCTTY;
        try {
            FileDescriptor fd = Os.open(path, flags, 0);
            Os.close(fd);
            return "成功(O_RDWR)";
        } catch (ErrnoException e) {
            // 读写不行,试只读
            try {
                FileDescriptor fd = Os.open(path,
                        OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_NOCTTY, 0);
                Os.close(fd);
                return "仅只读成功(O_RDONLY);读写失败=" + OsConstants.errnoName(e.errno);
            } catch (ErrnoException e2) {
                return "失败=" + OsConstants.errnoName(e2.errno) + "(" + e2.getMessage() + ")";
            } catch (Throwable t2) {
                return "失败=" + t2.getClass().getSimpleName();
            }
        } catch (Throwable t) {
            return "失败=" + t.getClass().getSimpleName();
        }
    }

    // 3) USB 设备列表
    private void sectionUsb(StringBuilder sb) {
        sb.append("【3. 已连接 USB 设备】\n");
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um == null) { sb.append("  无 UsbManager\n\n"); return; }
            Map<String, UsbDevice> map = um.getDeviceList();
            if (map == null || map.isEmpty()) {
                sb.append("  (未发现 USB 设备)\n\n");
                return;
            }
            for (UsbDevice d : map.values()) {
                sb.append("  - ").append(d.getDeviceName())
                  .append("  VID=").append(String.format("%04X", d.getVendorId()))
                  .append(" PID=").append(String.format("%04X", d.getProductId()));
                if (Build.VERSION.SDK_INT >= 21) {
                    String pn = d.getProductName();
                    String mn = d.getManufacturerName();
                    if (pn != null) sb.append("  名称=").append(pn);
                    if (mn != null) sb.append("  厂商=").append(mn);
                }
                sb.append("\n");
            }
        } catch (Throwable t) {
            sb.append("  读取USB异常: ").append(t.getClass().getSimpleName())
              .append(" ").append(t.getMessage()).append("\n");
        }
        sb.append("\n");
    }

    // 4) 外部存储与读写
    private void sectionStorage(StringBuilder sb) {
        sb.append("【4. 外部存储与读写】\n");
        File ext = Environment.getExternalStorageDirectory();
        sb.append("  外部存储路径: ").append(ext == null ? "null" : ext.getAbsolutePath()).append("\n");
        sb.append("  状态 state   : ").append(Environment.getExternalStorageState()).append("\n");
        if (ext != null) {
            sb.append("  目录可读=").append(ext.canRead()).append(" 可写=").append(ext.canWrite()).append("\n");
            File test = new File(ext, "device_probe_write_test.txt");
            try {
                FileOutputStream fos = new FileOutputStream(test);
                fos.write("ok".getBytes("UTF-8"));
                fos.close();
                boolean readable = test.exists() && test.length() > 0;
                sb.append("  写测试: 成功 -> ").append(test.getAbsolutePath())
                  .append("  回读=").append(readable ? "OK" : "失败").append("\n");
                test.delete();
            } catch (Throwable t) {
                sb.append("  写测试: 失败 = ").append(t.getClass().getSimpleName())
                  .append(" ").append(t.getMessage()).append("\n");
            }
        }
        sb.append("\n");
    }

    // 把报告写到 /sdcard/device_probe_result.txt
    private String writeResultFile(String report) {
        File ext = Environment.getExternalStorageDirectory();
        File out = new File(ext, RESULT_FILE);
        try {
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(report.getBytes("UTF-8"));
            fos.close();
            return "===== 结果已写入: " + out.getAbsolutePath() + " (" + out.length() + " 字节) =====";
        } catch (Throwable t) {
            return "===== 写结果文件失败: " + t.getClass().getSimpleName() + " " + t.getMessage()
                    + "(可能未授予存储权限)=====";
        }
    }

    // ============ 工具方法 ============

    // st_mode -> "rwxr-xr-x"
    private String permToString(int mode) {
        char[] c = new char[9];
        int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        char[] rwx = {'r', 'w', 'x'};
        for (int i = 0; i < 9; i++) {
            c[i] = (mode & bits[i]) != 0 ? rwx[i % 3] : '-';
        }
        return new String(c);
    }

    // 执行外部命令,超时则杀掉,返回输出(单行)
    private String execWithTimeout(String[] cmd, final long ms) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            final Process fp = p;
            Thread killer = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
                    try { fp.destroy(); } catch (Throwable ignore) { }
                }
            });
            killer.start();
            String out = readStream(p.getInputStream());
            String err = readStream(p.getErrorStream());
            try { p.waitFor(); } catch (InterruptedException ignore) { }
            killer.interrupt();
            String r = (out + " " + err).trim().replace('\n', ' ');
            return r.isEmpty() ? "(无输出)" : r;
        } catch (Throwable t) {
            return "无法执行(" + t.getClass().getSimpleName() + ":无 su 或被拒)";
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignore) { } }
        }
    }

    private String readStream(InputStream is) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                if (sb.length() > 2000) break;
            }
        } catch (Throwable ignore) { }
        return sb.toString();
    }
}
