package com.ffps.frtsp;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
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
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        clients.shutdownNow();
    }

    private void handle(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "ISO-8859-1"));
             OutputStream out = c.getOutputStream()) {
            c.setSoTimeout(10000);
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
                handleApi(method, path, out);
                return;
            }

            if ("GET".equals(method) && path.startsWith("/cam/")) {
                String name = path.substring(5);
                if (name.endsWith(".jpg")) {
                    name = name.substring(0, name.length() - 4);
                    sendSnapshot(name, out);
                } else if (name.matches("[a-z][a-z0-9_]{0,31}")) {
                    streamMjpeg(name, c, out);
                } else {
                    respond(out, 404, "Not Found", jsonError("camera_not_found"));
                }
                return;
            }
            if ("/".equals(path) && "GET".equals(method)) {
                respond(out, 200, "OK", new JSONObject().put("name", "fRTSP")
                    .put("api", "/api/v1/").put("status", "/api/v1/status"));
                return;
            }
            respond(out, 404, "Not Found", jsonError("not_found"));
        } catch (Exception e) {
            android.util.Log.w("fRTSP", "HTTP client error", e);
        }
    }

    private void handleApi(String method, String path, OutputStream out) throws Exception {
        CameraRuntime runtime = CameraService.getRuntime();
        if ("GET".equals(method)) {
            if ("/api/v1/status".equals(path)) {
                respond(out, 200, "OK", runtime == null ? new JSONObject().put("running", false) : runtime.statusJson(port));
            } else if ("/api/v1/version".equals(path)) {
                respond(out, 200, "OK", runtime == null ? new JSONObject().put("app", "fRTSP")
                    .put("version", BuildConfig.VERSION_NAME).put("ffmpeg", "bundled") : runtime.versionJson());
            } else if ("/api/v1/config".equals(path)) {
                respond(out, 200, "OK", runtime == null ? configWithoutRuntime() : runtime.configJson());
            } else if ("/api/v1/stats".equals(path)) {
                respond(out, 200, "OK", runtime == null ? new JSONObject() : runtime.statsJson());
            } else if ("/api/v1/logs".equals(path)) {
                respond(out, 200, "OK", runtime == null ? new JSONObject().put("items", new JSONArray()) : runtime.logsJson(100));
            } else {
                respond(out, 404, "Not Found", jsonError("not_found"));
            }
            return;
        }

        if (!"POST".equals(method)) {
            respond(out, 405, "Method Not Allowed", jsonError("method_not_allowed"));
            return;
        }
        if (runtime == null || !runtime.isRunning()) {
            respond(out, 503, "Service Unavailable", jsonError("service_not_running"));
            return;
        }
        if ("/api/v1/cameras/restart".equals(path)) {
            runtime.restartAll();
            respond(out, 200, "OK", new JSONObject().put("ok", true).put("action", "restart_all"));
            return;
        }
        if ("/api/v1/service/restart".equals(path)) {
            runtime.restartAll();
            respond(out, 200, "OK", new JSONObject().put("ok", true).put("action", "restart_service_workers"));
            return;
        }
        String prefix = "/api/v1/camera/";
        if (path.startsWith(prefix)) {
            String tail = path.substring(prefix.length());
            int slash = tail.lastIndexOf('/');
            if (slash <= 0) { respond(out, 400, "Bad Request", jsonError("invalid_camera_action")); return; }
            String name = tail.substring(0, slash);
            String action = tail.substring(slash + 1);
            boolean ok = false;
            if ("restart".equals(action)) ok = runtime.startCamera(name);
            else if ("start".equals(action)) ok = runtime.startCamera(name);
            else if ("stop".equals(action)) ok = runtime.stopCamera(name);
            if (!ok) {
                respond(out, 404, "Not Found", jsonError("camera_not_found_or_action_failed"));
            } else {
                respond(out, 200, "OK", new JSONObject().put("ok", true)
                    .put("camera", name).put("action", action));
            }
            return;
        }
        respond(out, 404, "Not Found", jsonError("not_found"));
    }

    private JSONObject configWithoutRuntime() throws Exception {
        String raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("cameras_json", "[]");
        JSONArray source = new JSONArray(raw);
        JSONArray cameras = new JSONArray();
        for (int i=0; i<source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "").replaceAll("(://[^:/@]+):[^@/]*@", "$1:***@");
            cameras.put(new JSONObject().put("name", item.optString("name"))
                .put("url", url).put("enabled", item.optBoolean("enabled", true))
                .put("fps", item.optInt("fps", 1)).put("quality", item.optInt("quality", 75)));
        }
        return new JSONObject().put("http_port", port).put("camera_count", source.length())
            .put("cameras", cameras);
    }

    private void sendSnapshot(String name, OutputStream out) throws Exception {
        CameraRuntime runtime = CameraService.getRuntime();
        CameraRuntime.CameraWorker camera = runtime == null ? null : runtime.getCamera(name);
        if (camera == null) { respond(out, 404, "Not Found", jsonError("camera_not_found")); return; }
        byte[] jpeg = camera.getLatestFrame();
        if (jpeg == null) { respond(out, 503, "Service Unavailable", jsonError("frame_not_ready")); return; }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length +
            "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.write(jpeg);
        out.flush();
    }

    private void streamMjpeg(String name, Socket socket, OutputStream out) throws Exception {
        CameraRuntime runtime = CameraService.getRuntime();
        CameraRuntime.CameraWorker camera = runtime == null ? null : runtime.getCamera(name);
        if (camera == null) { respond(out, 404, "Not Found", jsonError("camera_not_found")); return; }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
            "Cache-Control: no-cache, no-store, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\n\r\n")
            .getBytes("ISO-8859-1"));
        out.flush();
        long sentSequence = -1;
        while (running && !socket.isClosed()) {
            byte[] jpeg = camera.getLatestFrame();
            long sequence = camera.getFrameSequence();
            if (jpeg == null || sequence == sentSequence) {
                synchronized (camera.getFrameLock()) {
                    try { camera.getFrameLock().wait(15000); } catch (InterruptedException e) { return; }
                }
                continue;
            }
            sentSequence = sequence;
            out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n")
                .getBytes("ISO-8859-1"));
            out.write(jpeg);
            out.write("\r\n".getBytes("ISO-8859-1"));
            out.flush();
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

    private boolean isLocal(InetAddress address) { return address.isLoopbackAddress(); }

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

    private JSONObject jsonError(String error) throws Exception {
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
