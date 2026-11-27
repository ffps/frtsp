package com.ffps.frtsp;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class CameraService extends Service {
    private MjpegHttpServer server;

    @Override public void onCreate() {
        super.onCreate();
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch, 0);
        Notification notification = new Notification.Builder(this)
            .setContentTitle("fRTSP")
            .setContentText("HTTP server :8554")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pending)
            .getNotification();
        startForeground(1, notification);
        server = new MjpegHttpServer(this);
        server.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
