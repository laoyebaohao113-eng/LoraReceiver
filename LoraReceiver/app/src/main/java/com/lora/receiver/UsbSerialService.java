package com.lora.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务 - 保证锁屏后USB串口持续运行
 * 通知栏常驻，系统不会杀死此服务
 */
public class UsbSerialService extends Service {

    private static final String TAG         = "UsbSerialService";
    private static final String CHANNEL_ID  = "lora_service";
    private static final int    NOTIF_ID    = 1001;

    // Binder供Activity绑定
    public class LocalBinder extends Binder {
        UsbSerialService getService() { return UsbSerialService.this; }
    }
    private final IBinder mBinder = new LocalBinder();

    // 串口逻辑委托给MainActivity持有的对象（通过回调通信）
    public interface ServiceCallback {
        void onServiceReady();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("LoRa接收器运行中"));
        Log.d(TAG, "前台服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;  // 系统杀死后自动重启
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "前台服务已停止");
    }

    public void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        // 点击通知跳转到密码锁界面
        Intent intent = new Intent(this, LockActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LoRa 接收器")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "LoRa接收服务",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持USB串口后台运行");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
