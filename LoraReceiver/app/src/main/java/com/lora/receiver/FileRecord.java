package com.lora.receiver;

/**
 * 单条文件接收记录
 */
public class FileRecord {
    public String  fileName;       // 文件名，如 001.txt
    public long    fileSize;       // 文件大小（字节）
    public String  receiveTime;    // 接收完成时间
    public boolean emailSent;      // 邮件是否发送成功
    public String  emailError;     // 邮件失败原因（null表示成功）
    public String  filePath;       // 文件绝对路径

    public FileRecord(String fileName, long fileSize, String receiveTime, String filePath) {
        this.fileName    = fileName;
        this.fileSize    = fileSize;
        this.receiveTime = receiveTime;
        this.filePath    = filePath;
        this.emailSent   = false;
        this.emailError  = null;
    }

    public String getSizeStr() {
        if (fileSize < 1024) return fileSize + " B";
        else if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        else return String.format("%.1f MB", fileSize / 1024.0 / 1024.0);
    }
}
