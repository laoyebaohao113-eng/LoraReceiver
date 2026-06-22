package com.docscanner.sdk.correction;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文档矫正(透视检测+裁剪),照旧 App ffdagdfc 的 smali 严格复刻——
 * 包名/类名/native 签名/loadLibrary/模型拷贝逻辑必须与旧 App 一致,
 * 才能与打包进来的 libdocscanner-correction.so + opencv + onnxruntime + carddetection.onnx 对得上。
 * 不要改动签名。
 *
 * 流程:init(context) 把 assets/models/carddetection.onnx 拷到 filesDir/docscanner_correction_models/,
 * 再 nativeInit(模型目录) 得到 nativePtr;correctDocument(bitmap) 返回矫正裁剪后的位图。
 *
 * 说明:so 内部还有可选的 uvdoc 展平模型,但旧 App 没有把 uvdoc.onnx 放进模型目录,
 * 运行时日志 "uvdoc.onnx not found, UVDoc will not be available" → 自动跳过,只做检测+裁剪。
 * 本工程为 1:1 对齐旧 App,同样只带 carddetection.onnx,不带 uvdoc.onnx。
 */
public class DocScannerCorrection {

    private static final String MODEL_DIR = "docscanner_correction_models";
    private static final String TAG = "DocScannerCorrection";

    private Context context;
    private boolean isInitialized = false;
    private long nativePtr = 0L;

    static {
        // 加载顺序与旧 App 一致:opencv 先,docscanner-correction 后。
        // onnxruntime / c++_shared 由 docscanner-correction.so 通过依赖自动加载(jniLibs 里已带)。
        System.loadLibrary("opencv_java4");
        System.loadLibrary("docscanner-correction");
    }

    public DocScannerCorrection() {
    }

    public boolean init(Context ctx) {
        if (isInitialized) return true;
        this.context = ctx.getApplicationContext();
        String modelPath = copyModelsToInternalStorage();
        if (modelPath == null) {
            Log.e(TAG, "Failed to copy models");
            return false;
        }
        nativePtr = nativeInit(modelPath);
        isInitialized = (nativePtr != 0L);
        return isInitialized;
    }

    private String copyModelsToInternalStorage() {
        try {
            File dir = new File(context.getFilesDir(), MODEL_DIR);
            if (!dir.exists()) dir.mkdirs();
            // 旧 App 只复制 carddetection.onnx(uvdoc 不复制 → UVDoc 跳过)
            File model = new File(dir, "carddetection.onnx");
            if (!model.exists()) copyAssetFile("models/carddetection.onnx", model);
            return dir.getAbsolutePath() + "/";
        } catch (Exception e) {
            Log.e(TAG, "copyModelsToInternalStorage failed: " + e.getMessage());
            return null;
        }
    }

    private void copyAssetFile(String assetName, File outFile) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = context.getAssets().open(assetName);
            out = new FileOutputStream(outFile);
            byte[] buf = new byte[0x2000];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy " + assetName + ": " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignore) { }
            try { if (out != null) out.close(); } catch (IOException ignore) { }
        }
    }

    /** 文档矫正:输入位图,返回检测裁剪矫正后的位图(失败返回 null)。 */
    public Bitmap correctDocument(Bitmap bitmap) {
        if (!isInitialized || bitmap == null) return null;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            return nativeCorrectDoc(nativePtr, pixels, w, h);
        } catch (Exception e) {
            Log.e(TAG, "correctDocument: " + e.getMessage());
            return null;
        }
    }

    public boolean isReady() {
        return isInitialized;
    }

    public void release() {
        if (nativePtr != 0L) {
            nativeRelease(nativePtr);
            nativePtr = 0L;
            isInitialized = false;
        }
    }

    protected void finalize() {
        release();
    }

    // 与 libdocscanner-correction.so 对应的本地方法(签名不可改)
    private native Bitmap nativeCorrectDoc(long ptr, int[] pixels, int width, int height);

    private native long nativeInit(String modelDir);

    private native void nativeRelease(long ptr);
}
