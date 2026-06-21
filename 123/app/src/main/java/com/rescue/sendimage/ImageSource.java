package com.rescue.sendimage;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 取图:从指定文件夹列出 JPG;把图片压缩成 JPEG 字节(复刻旧 App 的"压缩成字节")。
 * 默认文件夹来自旧 App ffdagdfc:/storage/emulated/0/DCIM/Camera
 */
public final class ImageSource {

    private ImageSource() { }

    /** 旧 App ffdagdfc 写死的默认图片文件夹 */
    public static final String DEFAULT_FOLDER = "/storage/emulated/0/DCIM/Camera";

    /** 列出文件夹里的 JPG 文件(按名字排序) */
    public static List<File> listJpg(String folder) {
        List<File> out = new ArrayList<>();
        File dir = new File(folder);
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile()) {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) out.add(f);
                }
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /**
     * 读图片并压缩成 JPEG 字节。
     * @param quality 1~100,默认 80。
     */
    public static byte[] compressToJpeg(File file, int quality) throws Exception {
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp == null) throw new Exception("无法解码图片: " + file.getName());
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            return bos.toByteArray();
        } finally {
            bmp.recycle();
        }
    }
}
