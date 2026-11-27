package com.ffps.frtsp;

import android.content.Context;
import android.os.Debug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class CameraRuntime {
    private final Context context;
    private final File ffmpeg;
    private final Map<String, CameraWorker> workers = new ConcurrentHashMap<String, CameraWorker>();
    private final Map<String, CameraCounters> cameraCounters = new ConcurrentHashMap<String, CameraCounters>();
    private final ArrayDeque<String> logBuffer = new ArrayDeque<String>();
    private final AtomicLong totalFrames = new AtomicLong();
    private final AtomicLong totalRestarts = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean running;
    private long cpuTotalPrevious = -1;
    private long cpuIdlePrevious = -1;
    private volatile double latestCpuUsage = -1;
    private volatile long latestCpuSampleAt;
    private long cpuUsageSamples;
    private double cpuUsageSum;
    private volatile Thread cpuSamplerThread;

    public CameraRuntime(Context context, File ffmpeg) {
        this.context = context.getApplicationContext();
        this.ffmpeg = ffmpeg;
    }

    public synchronized void start() throws Exception {
        if (running) return;
        running = true;
        startCpuSampler();
        JSONArray cameras = readConfig();
        for (int i = 0; i < cameras.length(); i++) {
            JSONObject item = cameras.optJSONObject(i);
            if (item == null || !item.optBoolean("enabled", true)) continue;
            String name = item.optString("name", "");
            if (name.length() == 0) continue;
            CameraWorker worker = new CameraWorker(item);
            workers.put(name, worker);
            worker.start();
        }
        addLog("INFO", "Camera runtime started; configured cameras: " + cameras.length());
    }

    public synchronized void stop() {
        running = false;
        stopCpuSampler();
        for (CameraWorker worker : workers.values()) worker.stop();
        workers.clear();
        addLog("INFO", "Camera runtime stopped");
    }

    public boolean isRunning() { return running; }
    public CameraWorker getCamera(String name) { return workers.get(name); }

    public synchronized boolean restartAll() {
        if (!running) return false;
        for (CameraWorker worker : workers.values()) worker.restart();
        addLog("INFO", "Restart requested for all cameras");
        return true;
    }

    public synchronized boolean startCamera(String name) {
        if (!running) return false;
        CameraWorker current = workers.get(name);
        if (current != null) { current.restart(); return true; }
        JSONObject item = findConfig(name);
        if (item == null) return false;
        CameraWorker worker = new CameraWorker(item);
        workers.put(name, worker);
        worker.start();
        addLog("INFO", "Camera start requested: " + name);
        return true;
    }

    public synchronized boolean stopCamera(String name) {
        CameraWorker worker = workers.remove(name);
        if (worker == null) return false;
        worker.stop();
        addLog("INFO", "Camera stopped: " + name);
        return true;
    }

    public synchronized boolean setCameraEnabled(String name, boolean enabled) {
        if (!running) return false;
        JSONObject item = findConfig(name);
        if (item == null) return false;
        CameraWorker current = workers.get(name);
        if (enabled) {
            if (current != null) return true;
            CameraWorker worker = new CameraWorker(item);
            workers.put(name, worker);
            worker.start();
            addLog("INFO", "Camera enabled: " + name);
            return true;
        }
        if (current != null) {
            workers.remove(name);
            current.stop();
            addLog("INFO", "Camera disabled: " + name);
        }
        return true;
    }

    private JSONArray readConfig() {
        String raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("cameras_json", "[]");
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    private JSONObject findConfig(String name) {
        JSONArray array = readConfig();
        for (int i=0; i<array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && name.equals(item.optString("name"))) return item;
        }
        return null;
    }

    public JSONObject cameraConfigJson(String name) {
        try {
            JSONObject item = findConfig(name);
            if (item == null) return null;
            return new JSONObject().put("name", item.optString("name"))
                .put("url", redact(item.optString("url", "")))
                .put("enabled", item.optBoolean("enabled", true))
                .put("fps", item.optInt("fps", 1))
                .put("quality", item.optInt("quality", 75));
        } catch (Exception e) { return null; }
    }

    // 1=success, 0=not found, -1=already exists, -2=invalid configuration.
    public synchronized int saveCameraConfig(String name, JSONObject supplied, boolean createOnly) {
        if (!running || name == null || !name.matches("[a-z][a-z0-9_]{0,31}") || supplied == null)
            return -2;
        String suppliedName = supplied.optString("name", name);
        if (!name.equals(suppliedName)) return -2;
        JSONArray source = readConfig();
        int index = -1;
        JSONObject previous = null;
        for (int i=0; i<source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null && name.equals(item.optString("name"))) {
                index = i; previous = item; break;
            }
        }
        if (createOnly && previous != null) return -1;
        if (!createOnly && previous == null) return 0;

        try {
            JSONObject merged = previous == null
                ? new JSONObject().put("name", name).put("url", "").put("enabled", true).put("fps", 1).put("quality", 75)
                : new JSONObject(previous.toString());
            if (supplied.has("url")) {
                String url = supplied.optString("url", "").trim();
                if (url.length() > 0 && !url.matches("(?i)^rtsp://.*")) url = "rtsp://" + url;
                if (previous != null && url.contains(":***@")) {
                    String oldUrl = previous.optString("url", "");
                    int oldAt = oldUrl.indexOf('@');
                    int newAt = url.indexOf('@');
                    if (oldAt > 0 && newAt > 0) url = oldUrl.substring(0, oldAt) + url.substring(newAt);
                }
                if (!url.matches("(?i)^rtsp://[^/]+.*")) return -2;
                merged.put("url", url);
            }
            if (supplied.has("enabled")) {
                if (!(supplied.opt("enabled") instanceof Boolean)) return -2;
                merged.put("enabled", supplied.getBoolean("enabled"));
            }
            if (supplied.has("fps")) {
                Object value = supplied.opt("fps");
                if (!(value instanceof Number)) return -2;
                double numeric = ((Number) value).doubleValue();
                int fps = (int) numeric;
                if (numeric != fps || fps < 1 || fps > 60) return -2;
                merged.put("fps", fps);
            }
            if (supplied.has("quality")) {
                Object value = supplied.opt("quality");
                if (!(value instanceof Number)) return -2;
                double numeric = ((Number) value).doubleValue();
                int quality = (int) numeric;
                if (numeric != quality || quality < 1 || quality > 100) return -2;
                merged.put("quality", quality);
            }
            if (merged.optString("url", "").length() == 0) return -2;
            if (index >= 0) source.put(index, merged); else source.put(merged);
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("cameras_json", source.toString()).commit();
            countersFor(name);

            CameraWorker current = workers.remove(name);
            if (current != null) current.stop();
            if (merged.optBoolean("enabled", true)) {
                CameraWorker worker = new CameraWorker(merged);
                workers.put(name, worker);
                worker.start();
            }
            addLog("INFO", (previous == null ? "Camera added: " : "Camera configuration updated: ") + name);
            return 1;
        } catch (Exception e) {
            addLog("ERROR", "Camera configuration rejected for " + name + ": " + e.getMessage());
            return -2;
        }
    }

    // 1=success, 0=not found, -2=invalid configuration.
    public synchronized int deleteCamera(String name) {
        if (!running || name == null || !name.matches("[a-z][a-z0-9_]{0,31}")) return -2;
        JSONArray source = readConfig();
        JSONArray updated = new JSONArray();
        boolean found = false;
        for (int i=0; i<source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null && name.equals(item.optString("name"))) found = true;
            else if (item != null) updated.put(item);
        }
        if (!found) return 0;
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("cameras_json", updated.toString()).commit();
        CameraWorker current = workers.remove(name);
        if (current != null) current.stop();
        cameraCounters.remove(name);
        addLog("INFO", "Camera deleted: " + name);
        return 1;
    }

    // Validates and stores only the fields supplied by the caller.
    public synchronized int updateGlobalConfig(JSONObject supplied) {
        if (!running || supplied == null) return -2;
        android.content.SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        try {
            if (supplied.has("port")) {
                Object value = supplied.opt("port");
                if (!(value instanceof Number)) return -2;
                double numeric = ((Number) value).doubleValue();
                int port = (int) numeric;
                if (numeric != port || port < 1 || port > 65535) return -2;
                editor.putInt("http_port", port);
            }
            if (supplied.has("password")) {
                if (!(supplied.opt("password") instanceof String)) return -2;
                editor.putString("api_password", supplied.getString("password"));
            }
            editor.commit();
            return 1;
        } catch (Exception e) { return -2; }
    }

    public JSONObject statusJson(int port) {
        try {
            JSONArray cameras = new JSONArray();
            for (CameraWorker worker : workers.values()) cameras.put(worker.toJson());
            Runtime rt = Runtime.getRuntime();
            android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo systemMemory = new android.app.ActivityManager.MemoryInfo();
            if (activityManager != null) activityManager.getMemoryInfo(systemMemory);
            JSONObject memory = new JSONObject()
                .put("heap_used_mb", (rt.totalMemory()-rt.freeMemory())/1048576)
                .put("heap_max_mb", rt.maxMemory()/1048576)
                .put("native_heap_mb", Debug.getNativeHeapAllocatedSize()/1048576)
                .put("system_available_mb", systemMemory.availMem/1048576)
                .put("system_total_mb", systemMemory.totalMem/1048576)
                .put("system_low_memory", systemMemory.lowMemory);
            JSONObject cpu = new JSONObject().put("process_id", android.os.Process.myPid())
                .put("available_processors", rt.availableProcessors())
                .put("system_usage_percent", latestCpuUsage < 0 ? JSONObject.NULL : Math.round(latestCpuUsage * 10.0) / 10.0)
                .put("sample_age_ms", latestCpuSampleAt > 0 ? System.currentTimeMillis()-latestCpuSampleAt : JSONObject.NULL);
            return new JSONObject().put("running", running).put("port", port)
                .put("uptime_seconds", (System.currentTimeMillis()-startedAt)/1000)
                .put("memory", memory).put("cpu", cpu)
                .put("ffmpeg_processes", activeProcesses()).put("cameras", cameras);
        } catch (Exception e) { return new JSONObject(); }
    }

    private synchronized double readSystemCpuPercent() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();
            if (line == null || !line.startsWith("cpu ")) return -1;
            String[] values = line.trim().split("\\s+");
            long total = 0;
            for (int i=1; i<values.length; i++) total += Long.parseLong(values[i]);
            long idle = Long.parseLong(values[4]) + (values.length > 5 ? Long.parseLong(values[5]) : 0);
            double result = -1;
            if (cpuTotalPrevious >= 0 && total > cpuTotalPrevious) {
                long totalDelta = total - cpuTotalPrevious;
                long idleDelta = idle - cpuIdlePrevious;
                result = Math.max(0, Math.min(100, (totalDelta-idleDelta)*100.0/totalDelta));
            }
            cpuTotalPrevious = total;
            cpuIdlePrevious = idle;
            return result;
        } catch (Exception e) { return -1; }
    }

    private void startCpuSampler() {
        Thread thread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                double value = readSystemCpuPercent();
                if (value >= 0) {
                    latestCpuUsage = value;
                    latestCpuSampleAt = System.currentTimeMillis();
                    synchronized (this) {
                        cpuUsageSum += value;
                        cpuUsageSamples++;
                    }
                }
                try { Thread.sleep(1000L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "fRTSP-cpu");
        thread.setDaemon(true);
        cpuSamplerThread = thread;
        thread.start();
    }

    private void stopCpuSampler() {
        Thread thread = cpuSamplerThread;
        cpuSamplerThread = null;
        if (thread != null) thread.interrupt();
    }

    public JSONObject statsJson() {
        try {
            JSONArray cameras = new JSONArray();
            for (Map.Entry<String, CameraCounters> entry : cameraCounters.entrySet()) {
                String cameraName = entry.getKey();
                CameraCounters c = entry.getValue();
                long intervals = c.intervalCount.get();
                long minInterval = c.minIntervalMs.get();
                long maxInterval = c.maxIntervalMs.get();
                JSONObject item = new JSONObject().put("name", cameraName)
                    .put("frames", c.frames.get()).put("restarts", c.restarts.get())
                    .put("errors", c.errors.get())
                    .put("average_output_fps", intervals > 0 ? round2(1000.0 * intervals / c.intervalSumMs.get()) : JSONObject.NULL)
                    .put("min_output_fps", maxInterval > 0 ? round2(1000.0 / maxInterval) : JSONObject.NULL)
                    .put("max_output_fps", minInterval > 0 && minInterval != Long.MAX_VALUE ? round2(1000.0 / minInterval) : JSONObject.NULL)
                    .put("average_frame_interval_ms", intervals > 0 ? c.intervalSumMs.get() / intervals : JSONObject.NULL)
                    .put("max_frame_interval_ms", maxInterval > 0 ? maxInterval : JSONObject.NULL);
                cameras.put(item);
            }
            double averageCpu;
            long cpuSamples;
            synchronized (this) { cpuSamples = cpuUsageSamples; averageCpu = cpuSamples > 0 ? cpuUsageSum / cpuSamples : -1; }
            JSONObject cpu = new JSONObject().put("average_usage_percent", averageCpu >= 0 ? Math.round(averageCpu * 10.0) / 10.0 : JSONObject.NULL)
                .put("samples", cpuSamples);
            JSONObject totals = new JSONObject().put("jpeg_frames", totalFrames.get())
                .put("ffmpeg_restarts", totalRestarts.get()).put("camera_errors", totalErrors.get());
            return new JSONObject().put("uptime_seconds", (System.currentTimeMillis()-startedAt)/1000)
                .put("cpu", cpu).put("totals", totals).put("cameras", cameras);
        } catch (Exception e) { return new JSONObject(); }
    }

    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }

    public JSONObject configJson() {
        try {
            JSONArray source = readConfig();
            JSONArray cameras = new JSONArray();
            for (int i=0; i<source.length(); i++) {
                JSONObject item = source.optJSONObject(i);
                if (item == null) continue;
                String url = redact(item.optString("url", ""));
                cameras.put(new JSONObject().put("name", item.optString("name"))
                    .put("url", url).put("enabled", item.optBoolean("enabled", true))
                    .put("fps", item.optInt("fps", 1)).put("quality", item.optInt("quality", 75)));
            }
            boolean password = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("api_password", "").length() > 0;
            int configuredPort = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("http_port", 8554);
            return new JSONObject().put("port", configuredPort).put("http_port", configuredPort).put("camera_count", source.length())
                .put("api_password_configured", password).put("cameras", cameras);
        } catch (Exception e) { return new JSONObject(); }
    }

    public JSONObject versionJson() {
        try { return new JSONObject().put("app", "fRTSP").put("version", BuildConfig.VERSION_NAME)
            .put("ffmpeg", ffmpeg.exists() ? "bundled ARMv7" : "missing").put("ffmpeg_path", ffmpeg.getAbsolutePath()); }
        catch (Exception e) { return new JSONObject(); }
    }

    public JSONObject logsJson(int limit) {
        try {
            JSONArray items = new JSONArray();
            synchronized (logBuffer) {
                int skip = Math.max(0, logBuffer.size()-Math.max(1, Math.min(200, limit)));
                int i=0;
                for (String line : logBuffer) if (i++ >= skip) items.put(line);
            }
            return new JSONObject().put("items", items);
        } catch (Exception e) { return new JSONObject(); }
    }

    private static String iso8601Utc(long timestamp) {
        if (timestamp <= 0) return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }

    private int activeProcesses() {
        int count=0;
        for (CameraWorker worker : workers.values()) if (worker.process != null) count++;
        return count;
    }

    private void addLog(String level, String message) {
        String line = System.currentTimeMillis()+" "+level+" "+redact(message);
        synchronized (logBuffer) {
            while (logBuffer.size() >= 300) logBuffer.removeFirst();
            logBuffer.addLast(line);
        }
        android.util.Log.i("fRTSP", line);
    }

    private String redact(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(rtsp://[^:/@]+):[^@/]*@", "$1:***@");
    }

    private static void updateMin(AtomicLong target, long value) {
        long current;
        do { current = target.get(); if (value >= current) return; }
        while (!target.compareAndSet(current, value));
    }

    private static void updateMax(AtomicLong target, long value) {
        long current;
        do { current = target.get(); if (value <= current) return; }
        while (!target.compareAndSet(current, value));
    }

    private static class CameraCounters {
        final AtomicLong frames = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong restarts = new AtomicLong();
        final AtomicLong intervalSumMs = new AtomicLong();
        final AtomicLong intervalCount = new AtomicLong();
        final AtomicLong minIntervalMs = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong maxIntervalMs = new AtomicLong();
    }

    private synchronized CameraCounters countersFor(String name) {
        CameraCounters counters = cameraCounters.get(name);
        if (counters == null) {
            counters = new CameraCounters();
            cameraCounters.put(name, counters);
        }
        return counters;
    }

    public class CameraWorker {
        public final String name;
        private final String url;
        private final int fps;
        private final int quality;
        private volatile String state="starting";
        private volatile String lastError="";
        private volatile java.lang.Process process;
        private volatile boolean wanted=true;
        private volatile boolean restartRequested;
        private volatile byte[] latestFrame;
        private volatile long lastFrameAt;
        private volatile long lastFrameIntervalMs;
        private volatile int restartCount;
        private final CameraCounters counters;
        private volatile long lastErrorAt;
        private volatile int sourceWidth;
        private volatile int sourceHeight;
        private volatile long frameSequence;
        private volatile Thread workerThread;
        private final Object frameLock = new Object();

        CameraWorker(JSONObject config) {
            name=config.optString("name","");
            counters=countersFor(name);
            url=config.optString("url","");
            fps=Math.max(1,Math.min(60,config.optInt("fps",1)));
            quality=Math.max(1,Math.min(100,config.optInt("quality",75)));
        }

        void start() {
            workerThread = new Thread(this::runLoop,"fRTSP-"+name);
            workerThread.start();
        }


        void stop() {
            wanted=false; state="stopping";
            java.lang.Process p=process;
            if (p!=null) p.destroy();
            Thread t = workerThread;
            if (t != null) t.interrupt();
            synchronized(frameLock) { frameLock.notifyAll(); }
        }

        void restart() {
            restartRequested=true;
            java.lang.Process p=process;
            if (p!=null) p.destroy();
            Thread t = workerThread;
            if (t != null) t.interrupt();
            synchronized(frameLock) { frameLock.notifyAll(); }
        }

        public byte[] getLatestFrame() { return latestFrame; }
        public long getLastFrameAt() { return lastFrameAt; }
        public long getFrameSequence() { return frameSequence; }
        public Object getFrameLock() { return frameLock; }
        public String getState() { return state; }

        private void runLoop() {
            int delay=2;
            while(wanted && running) {
                try {
                    state="connecting"; lastError="";
                    ArrayList<String> command=new ArrayList<String>();
                    command.add(ffmpeg.getAbsolutePath());
                    Collections.addAll(command,"-hide_banner","-loglevel","info","-nostats",
                        "-rtsp_transport","tcp","-stimeout","5000000","-i",url,
                        "-an","-vf","fps="+fps,"-q:v",String.valueOf(31-Math.round(quality*0.29f)),
                        "-f","image2pipe","-vcodec","mjpeg","pipe:1");
                    java.lang.ProcessBuilder builder=new java.lang.ProcessBuilder(command);
                    builder.redirectErrorStream(false);
                    process=builder.start();
                    final java.lang.Process current=process;
                    Thread errors=new Thread(() -> readErrors(current),"fRTSP-err-"+name);
                    errors.setDaemon(true); errors.start();
                    final long processStartedAt = System.currentTimeMillis();
                    Thread watchdog = new Thread(() -> {
                        while (wanted && running && process == current) {
                            try { Thread.sleep(5000L); } catch (InterruptedException ignored) { return; }
                            long reference = Math.max(processStartedAt, lastFrameAt);
                            if (System.currentTimeMillis() - reference > 30000L && process == current) {
                                lastError = "No JPEG frames received for 30 seconds";
                                addLog("WARN", name + ": " + lastError);
                                current.destroy();
                                return;
                            }
                        }
                    }, "fRTSP-watch-" + name);
                    watchdog.setDaemon(true); watchdog.start();
                    state="connecting";
                    readJpegs(new BufferedInputStream(current.getInputStream(), 65536));
                    if (!wanted || !running) break;
                    int exit=current.waitFor();
                    if (restartRequested) {
                        restartRequested=false;
                        restartCount++;
                        counters.restarts.incrementAndGet();
                        totalRestarts.incrementAndGet();
                        continue;
                    }
                    counters.errors.incrementAndGet();
                    totalErrors.incrementAndGet();
                    lastErrorAt=System.currentTimeMillis();
                    if (lastError.length() == 0) lastError="FFmpeg exited with code "+exit;
                    addLog("WARN",name+": "+lastError+" (exit "+exit+")");
                } catch(Exception e) {
                    if (!wanted || !running) break;
                    counters.errors.incrementAndGet();
                    totalErrors.incrementAndGet();
                    lastErrorAt=System.currentTimeMillis();
                    lastError=redact(e.getMessage()==null?e.toString():e.getMessage());
                    addLog("ERROR",name+": "+lastError);
                } finally {
                    java.lang.Process p=process; process=null;
                    if(p!=null) p.destroy();
                }
                if(wanted && running) {
                    if (System.currentTimeMillis()-lastFrameAt < 10000) delay=2;
                    state="reconnecting"; restartCount++; counters.restarts.incrementAndGet(); totalRestarts.incrementAndGet();
                    try { Thread.sleep(delay*1000L); } catch(InterruptedException ignored) { }
                    delay=Math.min(30,delay*2);
                }
            }
            state="stopped";
            synchronized(frameLock) { frameLock.notifyAll(); }
        }

        private void readErrors(java.lang.Process p) {
            try {
                BufferedReader reader=new BufferedReader(new InputStreamReader(p.getErrorStream(),"UTF-8"));
                String line;
                while((line=reader.readLine())!=null) {
                    String safe=redact(line);
                    if(safe.length()>500) safe=safe.substring(0,500);
                    if(safe.trim().length()>0) addLog("FFMPEG",name+": "+safe);
                    if(line.contains("Video:")) {
                        java.util.regex.Matcher m=Pattern.compile("(\\d{2,5})x(\\d{2,5})").matcher(line);
                        if(m.find()) try { sourceWidth=Integer.parseInt(m.group(1)); sourceHeight=Integer.parseInt(m.group(2)); } catch(Exception ignored) { }
                    }
                }
            } catch(Exception ignored) { }
        }

        private void readJpegs(InputStream input) throws Exception {
            ByteArrayOutputStream frame=null;
            int previous=-1, value;
            while(wanted && running && (value=input.read())!=-1) {
                if(frame==null) {
                    if(previous==0xff && value==0xd8) {
                        frame=new ByteArrayOutputStream(65536);
                        frame.write(0xff); frame.write(0xd8);
                    }
                } else {
                    frame.write(value);
                    if(frame.size()>20*1024*1024) frame=null;
                    else if(previous==0xff && value==0xd9) {
                        latestFrame=frame.toByteArray();
                        long now=System.currentTimeMillis();
                        if(lastFrameAt>0) {
                            lastFrameIntervalMs=now-lastFrameAt;
                            counters.intervalSumMs.addAndGet(lastFrameIntervalMs);
                            counters.intervalCount.incrementAndGet();
                            updateMin(counters.minIntervalMs, lastFrameIntervalMs);
                            updateMax(counters.maxIntervalMs, lastFrameIntervalMs);
                        }
                        lastFrameAt=now;
                        frameSequence++; counters.frames.incrementAndGet(); totalFrames.incrementAndGet();
                        state="streaming";
                        synchronized(frameLock) { frameLock.notifyAll(); }
                        frame=null;
                    }
                }
                previous=value;
            }
        }

        JSONObject toJson() throws Exception {
            long age=lastFrameAt==0?-1:System.currentTimeMillis()-lastFrameAt;
            return new JSONObject().put("name",name).put("state",state)
                .put("source_width",sourceWidth).put("source_height",sourceHeight)
                .put("output_fps",lastFrameIntervalMs>0?Math.round(1000.0/lastFrameIntervalMs*100.0)/100.0:0)
                .put("frame_age_ms",age).put("last_error",lastError)
                .put("last_frame_at",lastFrameAt>0?iso8601Utc(lastFrameAt):JSONObject.NULL)
                .put("last_error_at",lastErrorAt>0?iso8601Utc(lastErrorAt):JSONObject.NULL);
        }
    }
}
