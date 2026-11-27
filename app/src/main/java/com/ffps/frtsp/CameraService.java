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
    private static volatile CameraRuntime runtime;
    private static volatile CameraService instance;
    private MjpegHttpServer server;
    private MjpegHttpServer pendingOldServer;

    public static boolean isRunning() { return running; }
    public static String getLastError() { return lastError; }
    public static CameraRuntime getRuntime() { return runtime; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            installAdminPage();
            File ffmpeg = installFfmpeg();
            Intent launch = new Intent(this, MainActivity.class);
            PendingIntent pending = PendingIntent.getActivity(this, 0, launch, 0);
            int port = getSharedPreferences("settings", MODE_PRIVATE).getInt("http_port", 8554);
            String serverUrl = "http://" + getDeviceIp() + ":" + port + "/";
            Notification notification = new Notification.Builder(this)
                .setContentTitle("fRTSP server is running")
                .setContentText(serverUrl)
                .setSmallIcon(R.drawable.ic_stat_frtsp)
                .setContentIntent(pending)
                .getNotification();
            startForeground(1, notification);

            runtime = new CameraRuntime(this, ffmpeg);
            runtime.start();
            server = new MjpegHttpServer(this);
            server.start();
            running = true;
            lastError = null;
        } catch (Exception e) {
            running = false;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            if (runtime != null) runtime.stop();
            runtime = null;
            stopSelf();
        }
    }

    public static synchronized boolean prepareHttpServerSwitch(int oldPort) {
        CameraService service = instance;
        if (service == null || !running) return false;
        synchronized (service) {
            try {
                MjpegHttpServer newServer = new MjpegHttpServer(service);
                newServer.start();
                service.pendingOldServer = service.server;
                service.server = newServer;
                return true;
            } catch (Exception e) {
                service.getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("http_port", oldPort).commit();
                android.util.Log.e("fRTSP", "Cannot switch HTTP port; keeping " + oldPort, e);
                return false;
            }
        }
    }

    public static synchronized void finishHttpServerSwitch() {
        CameraService service = instance;
        if (service == null) return;
        synchronized (service) {
            MjpegHttpServer oldServer = service.pendingOldServer;
            service.pendingOldServer = null;
            if (oldServer != null) oldServer.stop();
            android.util.Log.i("fRTSP", "HTTP listener switched to port " +
                service.getSharedPreferences("settings", MODE_PRIVATE).getInt("http_port", 8554));
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

    private void installAdminPage() throws Exception {
        File target = new File(getFilesDir(), "index.html");
        long apkUpdateTime = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        android.content.SharedPreferences prefs = getSharedPreferences("admin_page", MODE_PRIVATE);
        if (target.exists() && prefs.getLong("apk_update_time", 0) == apkUpdateTime) return;
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = getAssets().open("index.html");
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
            prefs.edit().putLong("apk_update_time", apkUpdateTime).commit();
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (output != null) try { output.close(); } catch (Exception ignored) { }
        }
    }

    private File installFfmpeg() throws Exception {
        File target = new File(getFilesDir(), "ffmpeg");
        long apkUpdateTime = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        android.content.SharedPreferences prefs = getSharedPreferences("ffmpeg_binary", MODE_PRIVATE);
        if (target.exists() && target.length() > 0 && target.canExecute() &&
            prefs.getLong("apk_update_time", 0) == apkUpdateTime) return target;

        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = getAssets().open("ffmpeg-armv7/ffmpeg");
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (output != null) try { output.close(); } catch (Exception ignored) { }
        }
        if (!target.setReadable(true, true) || !target.setExecutable(true, true))
            throw new Exception("Cannot set FFmpeg permissions");
        if (!target.exists() || target.length() == 0 || !target.canExecute())
            throw new Exception("FFmpeg installation failed");

        if (!prefs.edit().putLong("apk_update_time", apkUpdateTime).commit())
            throw new Exception("Cannot save FFmpeg version");
        return target;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (instance == this) instance = null;
        if (runtime != null) runtime.stop();
        runtime = null;
        if (server != null) server.stop();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
