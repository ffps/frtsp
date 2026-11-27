package com.ffps.frtsp;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MjpegHttpServer {
    private final Context context;
    private final int port;
    private volatile boolean running;
    private ServerSocket socket;
    private Thread acceptThread;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final long startedAt = System.currentTimeMillis();

    public MjpegHttpServer(Context context) {
        this.context = context.getApplicationContext();
        this.port = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("http_port", 8554);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(() -> {
            try {
                socket = new ServerSocket(port, 32, InetAddress.getByName("0.0.0.0"));
                while (running) {
                    final Socket client = socket.accept();
                    clients.execute(() -> handle(client));
                }
            } catch (Exception e) {
                if (running) android.util.Log.e("fRTSP", "HTTP server failed", e);
            } finally {
                running = false;
            }
        }, "fRTSP-HTTP");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        clients.shutdownNow();
    }

    private void handle(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "ISO-8859-1"));
             OutputStream out = c.getOutputStream()) {
            c.setSoTimeout(5000);
            String first = in.readLine();
            if (first == null) return;
            String[] request = first.split(" ");
            if (request.length < 2) { respond(out, 400, "Bad Request", jsonError("bad_request")); return; }
            String method = request[0];
            String path = request[1];
            String auth = null;
            String line;
            while ((line = in.readLine()) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Authorization"))
                    auth = line.substring(colon + 1).trim();
            }

            if (path.startsWith("/api/")) {
                if (!isLocal(c.getInetAddress()) && !authorized(auth)) {
                    out.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"fRTSP API\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    return;
                }
                if (!"GET".equals(method)) {
                    respond(out, 405, "Method Not Allowed", jsonError("method_not_allowed"));
                    return;
                }
                if ("/api/v1/status".equals(path)) respond(out, 200, "OK", statusJson());
                else if ("/api/v1/version".equals(path)) respond(out, 200, "OK", versionJson());
                else if ("/api/v1/config".equals(path)) respond(out, 200, "OK", configJson());
                else if ("/api/v1/stats".equals(path)) respond(out, 200, "OK", statsJson());
                else if ("/api/v1/logs".equals(path)) respond(out, 200, "OK", new JSONObject().put("items", new JSONArray()));
                else respond(out, 404, "Not Found", jsonError("not_found"));
                return;
            }

            if ("/".equals(path)) {
                respond(out, 200, "OK", new JSONObject().put("name", "fRTSP").put("api", "/api/v1/"));
                return;
            }
            if (path.matches("/camera/[0-9]+(\\.jpg)?")) {
                respond(out, 503, "Service Unavailable", jsonError("camera_stream_not_configured"));
                return;
            }
            respond(out, 404, "Not Found", jsonError("not_found"));
        } catch (Exception e) {
            android.util.Log.w("fRTSP", "HTTP client error", e);
        }
    }

    private boolean authorized(String header) {
        String expected = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("api_password", "");
        if (expected.length() == 0 || header == null || !header.startsWith("Basic ")) return false;
        try {
            String decoded = new String(android.util.Base64.decode(header.substring(6), android.util.Base64.DEFAULT), "UTF-8");
            return constantTimeEquals("api:" + expected, decoded);
        } catch (Exception e) { return false; }
    }

    private boolean isLocal(InetAddress address) {
        return address.isLoopbackAddress();
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(Charset.forName("UTF-8"));
        byte[] y = b.getBytes(Charset.forName("UTF-8"));
        int diff = x.length ^ y.length;
        int count = Math.max(x.length, y.length);
        for (int i = 0; i < count; i++) {
            int aByte = i < x.length ? x[i] : 0;
            int bByte = i < y.length ? y[i] : 0;
            diff |= aByte ^ bByte;
        }
        return diff == 0;
    }

    private JSONObject statusJson() throws JSONException {
        Runtime rt = Runtime.getRuntime();
        JSONObject server = new JSONObject().put("running", running).put("port", port);
        JSONObject memory = new JSONObject()
            .put("heap_used_mb", (rt.totalMemory() - rt.freeMemory()) / 1048576)
            .put("heap_max_mb", rt.maxMemory() / 1048576)
            .put("native_heap_mb", Debug.getNativeHeapAllocatedSize() / 1048576);
        JSONArray cameras = new JSONArray();
        return new JSONObject().put("server", server).put("uptime_seconds", (System.currentTimeMillis()-startedAt)/1000)
            .put("memory", memory).put("cameras", cameras);
    }

    private JSONObject statsJson() throws JSONException {
        return new JSONObject().put("uptime_seconds", (System.currentTimeMillis()-startedAt)/1000)
            .put("ffmpeg_processes", 0).put("jpeg_frames_total", 0);
    }

    private JSONObject configJson() throws JSONException {
        return new JSONObject().put("http_port", port).put("camera_count", 0)
            .put("api_password_configured", context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("api_password", "").length() > 0);
    }

    private JSONObject versionJson() throws JSONException {
        return new JSONObject().put("app", "fRTSP")
            .put("version", BuildConfig.VERSION_NAME).put("ffmpeg", "not bundled yet");
    }

    private JSONObject jsonError(String error) throws JSONException {
        return new JSONObject().put("error", error);
    }

    private void respond(OutputStream out, int code, String reason, JSONObject body) throws Exception {
        byte[] data = body.toString(2).getBytes("UTF-8");
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + data.length + "\r\n" +
            "Connection: close\r\n\r\n";
        out.write(headers.getBytes("ISO-8859-1"));
        out.write(data);
        out.flush();
    }
}
