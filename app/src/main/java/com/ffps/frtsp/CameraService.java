package com.ffps.frtsp;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
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
            int port = getSharedPreferences("settings", MODE_PRIVATE).getInt("http_port", 8554);
            String serverUrl = "http://" + getDeviceIp() + ":" + port + "/";
            Notification notification = new Notification.Builder(this)
                .setContentTitle("fRTSP server is running")
                .setContentText(serverUrl)
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


    private String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress() &&
                        address instanceof Inet4Address && address.getHostAddress() != null) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip != 0) return Formatter.formatIpAddress(ip);
        } catch (Exception ignored) { }
        return "DEVICE_IP";
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