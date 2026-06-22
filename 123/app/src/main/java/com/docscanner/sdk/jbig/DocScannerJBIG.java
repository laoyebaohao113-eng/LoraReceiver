package com.docscanner.sdk.jbig;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

/**
 * 照旧 App ffdagdfc 的 smali 严格复刻(包名/类名/native 签名/loadLibrary 必须一致,
 * 才能与打包进来的 libdocscanner-jbig.so 的 JNI 符号对得上)。不要改动签名。
 *
 * 流程:init(context) 置位 -> compress(bitmap, outPath) 把位图 JBIG 压缩写到 outPath。
 */
public class DocScannerJBIG {

    private static final String TAG = "DocScannerJBIG";

    private Context context;
    private boolean isInitialized = false;

    static {
        System.loadLibrary("docscanner-jbig");
    }

    public DocScannerJBIG() {
    }

    public boolean init(Context ctx) {
        if (isInitialized) return true;
        this.context = ctx.getApplicationContext();
        isInitialized = true;
        Log.i(TAG, "DocScannerJBIG initialized");
        return true;
    }

    /** 把位图 JBIG 压缩,结果写入 path(旧 App 用 .txt 后缀)。成功返回 true。 */
    public boolean compress(Bitmap bitmap, String path) {
        if (!isInitialized || bitmap == null) return false;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            return nativeCompress(0L, pixels, w, h, path);
        } catch (Exception e) {
            Log.e(TAG, "compress: " + e.getMessage());
            return false;
        }
    }

    public Bitmap decompress(String path) {
        if (!isInitialized) return null;
        try {
            return nativeDecompress(0L, path);
        } catch (Exception e) {
            Log.e(TAG, "decompress: " + e.getMessage());
            return null;
        }
    }

    public boolean isReady() {
        return isInitialized;
    }

    public void release() {
        isInitialized = false;
        Log.i(TAG, "DocScannerJBIG released");
    }

    protected void finalize() {
        release();
    }

    // 与 libdocscanner-jbig.so 对应的本地方法(签名不可改)
    private native boolean nativeCompress(long ptr, int[] pixels, int width, int height, String path);

    private native Bitmap nativeDecompress(long ptr, String path);
}
