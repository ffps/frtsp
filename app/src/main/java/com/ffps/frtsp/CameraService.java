package com.ffps.frtsp;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class CameraService extends Service {
    private static volatile boolean running;
    private static volatile String lastError;
    private MjpegHttpServer server;

    public static boolean isRunning() { return running; }
    public static String getLastError() { return lastError; }

    @Override public void onCreate() {
        super.onCreate();
        try {
            installFfmpeg();
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
            running = true;
            lastError = null;
        } catch (Exception e) {
            running = false;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            stopSelf();
        }
    }

    private void installFfmpeg() throws Exception {
        File target = new File(getFilesDir(), "ffmpeg");
        if (target.exists() && target.length() > 0 && target.canExecute()) return;
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = getAssets().open("ffmpeg-armv7/ffmpeg");
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (output != null) try { output.close(); } catch (Exception ignored) { }
        }
        if (!target.setReadable(true, true) || !target.setExecutable(true, true)) {
            throw new Exception("Cannot set FFmpeg permissions");
        }
        if (!target.exists() || target.length() == 0 || !target.canExecute()) {
            throw new Exception("FFmpeg installation failed");
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (server != null) server.stop();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}