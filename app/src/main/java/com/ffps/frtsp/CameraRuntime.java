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

    public CameraRuntime(Context context, File ffmpeg) {
        this.context = context.getApplicationContext();
        this.ffmpeg = ffmpeg;
    }

    public synchronized void start() throws Exception {
        if (running) return;
        running = true;
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
                .put("system_usage_percent", Math.round(readSystemCpuPercent() * 10.0) / 10.0);
            return new JSONObject().put("running", running).put("port", port)
                .put("uptime_seconds", (System.currentTimeMillis()-startedAt)/1000)
                .put("memory", memory).put("cpu", cpu).put("cameras", cameras);
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

    public JSONObject statsJson() {
        try {
            JSONArray cameras = new JSONArray();
            for (CameraWorker worker : workers.values()) {
                cameras.put(new JSONObject().put("name", worker.name).put("frames", worker.counters.frames.get())
                    .put("restarts", worker.restartCount).put("errors", worker.counters.errors.get())
                    .put("state", worker.state));
            }
            return new JSONObject().put("uptime_seconds", (System.currentTimeMillis()-startedAt)/1000)
                .put("ffmpeg_processes", activeProcesses()).put("jpeg_frames_total", totalFrames.get())
                .put("ffmpeg_restarts_total", totalRestarts.get()).put("camera_errors_total", totalErrors.get())
                .put("cameras", cameras);
        } catch (Exception e) { return new JSONObject(); }
    }

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
            return new JSONObject().put("http_port", context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("http_port", 8554)).put("camera_count", source.length())
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

    private static class CameraCounters {
        final AtomicLong frames = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
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
                    if (restartRequested) { restartRequested=false; continue; }
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
                    state="reconnecting"; restartCount++; totalRestarts.incrementAndGet();
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
                        if(lastFrameAt>0) lastFrameIntervalMs=now-lastFrameAt;
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
            return new JSONObject().put("name",name).put("state",state).put("fps",fps)
                .put("jpeg_quality",quality).put("source_width",sourceWidth).put("source_height",sourceHeight)
                .put("output_fps",lastFrameIntervalMs>0?Math.round(1000.0/lastFrameIntervalMs*100.0)/100.0:0)
                .put("frames_total",counters.frames.get()).put("frame_age_ms",age).put("restarts",restartCount)
                .put("errors",counters.errors.get()).put("last_error",lastError)
                .put("last_frame_at",lastFrameAt>0?iso8601Utc(lastFrameAt):JSONObject.NULL)
                .put("last_error_at",lastErrorAt>0?iso8601Utc(lastErrorAt):JSONObject.NULL);
        }
    }
}
