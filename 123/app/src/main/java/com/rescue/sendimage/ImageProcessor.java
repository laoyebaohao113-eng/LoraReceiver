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

    /** 旧 App 写死的图片来源文件夹 */
    public static final String DEFAULT_SRC_FOLDER = "/storage/emulated/0/DCIM/Camera";
    /** 旧 App 的中间产物输出文件夹 */
    public static final String CESHI_FOLDER = "/storage/emulated/0/ceshi";

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
        List<File> imgs = listImages(folder);
        if (imgs.isEmpty()) { cb.log("ERR", "文件夹无图片: " + folder); cb.done(false, 0, 0); return; }
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
