package com.rescue.sendimage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.docscanner.sdk.binarize.DocScannerBinarize;
import com.docscanner.sdk.correction.DocScannerCorrection;
import com.docscanner.sdk.jbig.DocScannerJBIG;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 图片处理(第一步,独立操作):读图 → 矫正(DocScannerCorrection)→ 二值化(DocScannerBinarize)
 * → JBIG压缩(DocScannerJBIG)→ 产物写成 .txt 到 /storage/emulated/0/ceshi/。
 * 1:1 复刻旧 App ffdagdfc 的 toTextByte 流程(含矫正,矫正会裁掉背景、显著减小 txt 体积)。
 *
 * 支持整批(processFolder)和单张(processOne)。
 */
public class ImageProcessor {

    /** 回退用的默认图片来源(自动识别失败时才用)。换卡后UUID会变,所以优先用 autoDetectSrcFolder。 */
    public static final String DEFAULT_SRC_FOLDER = "/storage/3054-9752/jpg";
    /** 旧 App 的中间产物输出文件夹 */
    public static final String CESHI_FOLDER = "/storage/emulated/0/ceshi";

    /**
     * v0.9.2:自动识别外置可移动卡的 jpg 目录(不再写死 TF 卡 UUID)。
     * getExternalFilesDirs:项[0]是内置存储,[1..]是可移动卡的"App专属目录"
     * (形如 /storage/XXXX-XXXX/Android/data/<包名>/files);取 /Android/ 前的卷根 + "/jpg"。
     * 找不到可移动卡时回退到 DEFAULT_SRC_FOLDER。
     */
    public static String autoDetectSrcFolder(Context ctx) {
        try {
            File[] dirs = ctx.getExternalFilesDirs(null);
            if (dirs != null) {
                for (int i = 1; i < dirs.length; i++) {     // 从1开始, 跳过内置存储[0]
                    File d = dirs[i];
                    if (d == null) continue;
                    String p = d.getAbsolutePath();          // /storage/3054-9752/Android/data/<pkg>/files
                    int idx = p.indexOf("/Android/");
                    if (idx > 0) {
                        return p.substring(0, idx) + "/jpg";  // /storage/3054-9752/jpg
                    }
                }
            }
        } catch (Throwable ignore) { }
        return DEFAULT_SRC_FOLDER;                            // 回退
    }

    public interface Callback {
        void log(String tag, String msg);
        void done(boolean ok, int okCount, int failCount);
    }

    private final Context context;
    private final Callback cb;
    private DocScannerCorrection correction;
    private DocScannerBinarize binarize;
    private DocScannerJBIG jbig;
    private volatile boolean stop = false;

    public ImageProcessor(Context ctx, Callback cb) {
        this.context = ctx.getApplicationContext();
        this.cb = cb;
    }

    public void stop() { stop = true; }

    /** 列文件夹里的 JPG/图片文件(排序) */
    public static List<File> listImages(String folder) {
        List<File> out = new ArrayList<>();
        File dir = new File(folder);
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".bmp")) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /** 确保引擎已初始化(opencv/onnx 首次加载较慢) */
    private boolean ensureInit() {
        if (binarize == null) {
            cb.log("INFO", "初始化矫正/二值化/JBIG 引擎(首次加载 opencv/onnx 稍慢)…");
            correction = new DocScannerCorrection();
            if (!correction.init(context)) { cb.log("ERR", "DocScannerCorrection 初始化失败(检查 carddetection.onnx 是否已打包)"); return false; }
            binarize = new DocScannerBinarize();
            if (!binarize.init(context)) { cb.log("ERR", "DocScannerBinarize 初始化失败"); return false; }
            jbig = new DocScannerJBIG();
            if (!jbig.init(context)) { cb.log("ERR", "DocScannerJBIG 初始化失败"); return false; }
            cb.log("OK", "引擎就绪");
        }
        return true;
    }

    /** 整批处理文件夹 */
    public void processFolder(String folder) {
        File dir = new File(folder);
        File[] all = dir.listFiles();
        cb.log("INFO", "图片夹: " + folder + "  存在=" + dir.exists() + " 可读=" + dir.canRead()
                + " 是目录=" + dir.isDirectory() + " listFiles=" + (all == null ? "null" : all.length + "项"));
        List<File> imgs = listImages(folder);
        if (imgs.isEmpty()) {
            if (all == null) {
                cb.log("ERR", "读不到该文件夹(listFiles=null)。外置TF卡(/storage/XXXX-XXXX)在 Android 11 可能需要授权:"
                        + "系统设置→应用→本App→权限→文件/存储,允许访问;或确认路径用「列存储」按钮核对。");
            } else {
                cb.log("ERR", "文件夹里没有 jpg/png(共 " + all.length + " 项,但没有图片): " + folder);
            }
            cb.done(false, 0, 0); return;
        }
        cb.log("INFO", "整批处理 " + imgs.size() + " 张,来源: " + folder);
        runBatch(imgs);
    }

    /** 单张处理 */
    public void processOne(File img) {
        if (img == null || !img.exists()) { cb.log("ERR", "图片不存在"); cb.done(false, 0, 0); return; }
        cb.log("INFO", "单张处理: " + img.getName());
        List<File> one = new ArrayList<>();
        one.add(img);
        runBatch(one);
    }

    private void runBatch(List<File> imgs) {
        if (!ensureInit()) { cb.done(false, 0, 0); return; }
        File ceshi = new File(CESHI_FOLDER);
        if (!ceshi.exists()) ceshi.mkdirs();

        int ok = 0, fail = 0;
        for (int i = 0; i < imgs.size() && !stop; i++) {
            File img = imgs.get(i);
            String base = img.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            File outTxt = new File(ceshi, base + ".txt");
            try {
                Bitmap src = BitmapFactory.decodeFile(img.getAbsolutePath());
                if (src == null) { cb.log("ERR", "[" + (i + 1) + "/" + imgs.size() + "] 解码失败: " + img.getName()); fail++; continue; }
                // 矫正(检测+裁剪到文档区域,旧App这一步把背景裁掉、体积变小)
                Bitmap corr = correction.correctDocument(src);
                src.recycle();
                if (corr == null) { cb.log("ERR", "[" + (i + 1) + "/" + imgs.size() + "] 矫正失败: " + img.getName()); fail++; continue; }
                Bitmap bin = binarize.binarize(corr);
                corr.recycle();
                if (bin == null) { cb.log("ERR", "[" + (i + 1) + "/" + imgs.size() + "] 二值化失败: " + img.getName()); fail++; continue; }
                boolean okc = jbig.compress(bin, outTxt.getAbsolutePath());
                bin.recycle();
                if (okc && outTxt.exists()) {
                    cb.log("OK", "[" + (i + 1) + "/" + imgs.size() + "] " + img.getName()
                            + " -> " + outTxt.getName() + " (" + outTxt.length() + " 字节)");
                    ok++;
                } else {
                    cb.log("ERR", "[" + (i + 1) + "/" + imgs.size() + "] JBIG压缩失败: " + img.getName());
                    fail++;
                }
            } catch (Throwable t) {
                cb.log("ERR", "[" + (i + 1) + "/" + imgs.size() + "] 异常: " + t.getClass().getSimpleName() + " " + t.getMessage());
                fail++;
            }
        }
        cb.log("INFO", "处理结束:成功 " + ok + ",失败 " + fail + ",产物在 " + CESHI_FOLDER);
        cb.done(fail == 0, ok, fail);
    }
}
