package com.docscanner.sdk.binarize;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 照旧 App ffdagdfc 的 smali 严格复刻(包名/类名/native 签名/loadLibrary/模型拷贝逻辑必须一致,
 * 才能与打包进来的 libdocscanner-binarize.so + opencv + onnxruntime + unetv2.onnx 对得上)。不要改动签名。
 *
 * 流程:init(context) 把 assets/models/unetv2.onnx 拷到 filesDir/docscanner_binarize_models/,
 * 再 nativeInit(模型目录) 得到 nativePtr;binarize(bitmap) 返回二值化后的位图。
 */
public class DocScannerBinarize {

    private static final String MODEL_DIR = "docscanner_binarize_models";
    private static final String TAG = "DocScannerBinarize";

    private Context context;
    private boolean isInitialized = false;
    private long nativePtr = 0L;

    static {
        // 加载顺序与旧 App 一致:opencv 先,docscanner-binarize 后。
        // onnxruntime / c++_shared 由 docscanner-binarize.so 通过依赖自动加载(jniLibs 里已带)。
        System.loadLibrary("opencv_java4");
        System.loadLibrary("docscanner-binarize");
    }

    public DocScannerBinarize() {
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
            File model = new File(dir, "unetv2.onnx");
            if (!model.exists()) copyAssetFile("models/unetv2.onnx", model);
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

    /** 二值化:输入位图,返回二值化后的位图(失败返回 null)。 */
    public Bitmap binarize(Bitmap bitmap) {
        if (!isInitialized || bitmap == null) return null;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            return nativeBinarize(nativePtr, pixels, w, h);
        } catch (Exception e) {
            Log.e(TAG, "binarize: " + e.getMessage());
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

    // 与 libdocscanner-binarize.so 对应的本地方法(签名不可改)
    private native Bitmap nativeBinarize(long ptr, int[] pixels, int width, int height);

    private native long nativeInit(String modelDir);

    private native void nativeRelease(long ptr);
}
