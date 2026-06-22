package com.rescue.sendimage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 最小前台服务:长时间串口发送期间保活,避免进程被系统杀。
 * 实际发送逻辑在 ImageSender 线程里跑;本服务只提供前台通知 + 保活。
 * MainActivity 在开始发送时 start、结束时 stop。
 */
public class SendService extends Service {

    private static final String CH_ID = "send_ch";
    private static final int NOTIF_ID = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "图片发送", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle("SendImageNew")
         .setContentText("正在通过串口发送图片…")
         .setSmallIcon(android.R.drawable.stat_sys_upload)
         .setOngoing(true);
        return b.build();
    }

    public static void start(Context c) {
        Intent i = new Intent(c, SendService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, SendService.class));
    }
}
