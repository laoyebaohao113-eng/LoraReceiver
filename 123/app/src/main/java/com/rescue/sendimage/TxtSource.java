package com.rescue.sendimage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 发送数据源:ceshi 文件夹里的 .txt(JBIG 压缩产物)。支持列出全部 / 读取字节。
 */
public final class TxtSource {

    private TxtSource() { }

    /** 列 ceshi 下的 .txt(排序) */
    public static List<File> listTxt(String folder) {
        List<File> out = new ArrayList<>();
        File dir = new File(folder);
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".txt")) out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /** 读整个文件为字节数组 */
    public static byte[] readBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
