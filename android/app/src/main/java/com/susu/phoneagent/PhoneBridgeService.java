package com.susu.phoneagent;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground Service：并行运行两个完全独立的轮询循环：
 *
 *   ① Phone Bridge poller  — 3s 间隔，执行 Claude 下发的手机操作
 *   ② Sleep Guard poller   — 4s 间隔，读取服务器 guard state，按条件 pressHome
 *
 * 两个循环各用独立的 ScheduledExecutorService，catch(Throwable) + finally 重调度，
 * 任意一个崩溃都不影响另一个。
 *
 * ════ 双保险安全条件 ════
 *   pressHome 只在同时满足以下两个条件时执行：
 *     1) 本地 guardEnabled == true  （默认 false，必须用户手动开启）
 *     2) 服务器 active == true
 *   任意一个为 false → 绝对不 pressHome。
 */
public class PhoneBridgeService extends Service {
    private static final String TAG = "PhoneBridgeService";
    private static final String CH  = "phone_agent";
    private static final int    NID = 1001;

    // ── SharedPreferences keys ────────────────────────────────────────────────
    static final String PREFS          = "phone_agent_prefs";
    static final String K_TOKEN        = "bridge_token";
    static final String K_URL          = "bridge_url";
    static final String DEF_URL        = "https://your-domain.example/phone-bridge";
    static final String K_GUARD_TOKEN  = "guard_token";
    static final String K_GUARD_URL    = "guard_url";
    static final String DEF_GUARD_URL  = "https://your-domain.example";
    static final String K_GUARD_ENABLED = "guard_enabled"; // boolean, 默认 false

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final long POLL_MS       = 3_000;  // Bridge 轮询间隔
    private static final long GUARD_POLL_MS = 4_000;  // Guard 轮询间隔（错开避免同时网络）
    private static final long BACKOFF_MAX   = 30_000;

    // ── 供 MainActivity 实时读取的公共状态（static volatile，无 Binder 开销）────
    public static volatile String  bridgeStatus  = "";
    public static volatile long    lastPollMs    = 0;
    public static volatile boolean guardEnabled  = false; // 默认 false（双保险）
    public static volatile String  guardStatus   = "Disabled";

    // ── Bridge poller 内部 ────────────────────────────────────────────────────
    private final AtomicBoolean pollerActive = new AtomicBoolean(false);
    private ScheduledExecutorService executor;
    private volatile BridgeApiClient client;
    private volatile long bridgeBackoff = POLL_MS;

    // ── Sleep Guard poller 内部 ───────────────────────────────────────────────
    private final AtomicBoolean guardPollerActive = new AtomicBoolean(false);
    private ScheduledExecutorService guardExecutor;
    private volatile SleepGuardClient guardClient;
    private volatile long guardBackoff = GUARD_POLL_MS;

    // ── Shizuku 共享 ─────────────────────────────────────────────────────────
    private volatile IPhoneService phoneService = null;
    private PowerManager.WakeLock wakeLock;

    private final Shizuku.UserServiceArgs userServiceArgs =
        new Shizuku.UserServiceArgs(new ComponentName("com.susu.phoneagent",
            "com.susu.phoneagent.PhoneUserService"))
            .daemon(false).processNameSuffix("user_service").version(1);

    private final ServiceConnection userConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            phoneService = IPhoneService.Stub.asInterface(b);
            Log.i(TAG, "UserService connected");
            updateNotif(bridgeStatus.isEmpty() ? "● Connected" : bridgeStatus);
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            phoneService = null;
            Log.w(TAG, "UserService disconnected");
            updateNotif("Waiting for Shizuku · still polling");
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> {
        Log.i(TAG, "Shizuku binder received");
        bindUserSvc();
    };
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        phoneService = null;
        Log.w(TAG, "Shizuku binder dead");
    };

    // ─────────────────────────────────────────────────────────────────────────
    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, PhoneBridgeService.class));
    }
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, PhoneBridgeService.class));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NID, buildNotif("Starting…"));
        bridgeStatus = "Starting…";
        guardStatus  = "Disabled";

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SusuAgent:Poller");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(24 * 60 * 60 * 1000L);

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);

        initClientAndPoller();
        initGuardPoller();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand bridge=" + pollerActive.get() + " guard=" + guardPollerActive.get());
        if (!pollerActive.get())      initClientAndPoller();
        if (!guardPollerActive.get()) initGuardPoller();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        pollerActive.set(false);
        guardPollerActive.set(false);
        bridgeStatus = "";
        guardStatus  = "Disabled";
        lastPollMs   = 0;

        if (executor      != null) { executor.shutdownNow();      executor      = null; }
        if (guardExecutor != null) { guardExecutor.shutdownNow();  guardExecutor = null; }

        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        try { Shizuku.unbindUserService(userServiceArgs, userConn, true); } catch (Exception ignored) {}

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ── Bridge poller ─────────────────────────────────────────────────────────
    private void initClientAndPoller() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = p.getString(K_TOKEN, "");
        String url   = p.getString(K_URL,   DEF_URL);
        if (url == null || url.isEmpty()) url = DEF_URL;

        if (token == null || token.isEmpty()) {
            bridgeStatus = "No token";
            updateNotif("⚠ No Bridge token — open app");
            return;
        }
        client = new BridgeApiClient(url, token);
        startBridgePoller();
    }

    private void startBridgePoller() {
        if (!pollerActive.compareAndSet(false, true)) { Log.w(TAG, "bridge poller already active"); return; }
        bridgeBackoff = POLL_MS;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BridgePoller");
                t.setDaemon(false); return t;
            });
        }
        executor.schedule(this::pollOnce, 0, TimeUnit.MILLISECONDS);
        bridgeStatus = "Connecting…";
        updateNotif("Connecting to bridge…");
        Log.i(TAG, "bridge poller started");
    }

    /**
     * Bridge 单次轮询任务。
     * Throwable 不会杀死循环——finally 无条件重调度。
     */
    private void pollOnce() {
        long nextDelay = POLL_MS;
        try {
            lastPollMs = System.currentTimeMillis();
            BridgeApiClient c = client;
            if (c == null) { nextDelay = BACKOFF_MAX; return; }

            BridgeApiClient.BridgeJob job = c.getNextJob();
            int code = c.lastCode;

            if (code == 401) {
                bridgeStatus = "Unauthorized (HTTP 401)";
                updateNotif("⚠ Bridge: Unauthorized — check token");
                nextDelay = BACKOFF_MAX; return;
            }
            if (code == -1) {
                bridgeStatus = "Offline";
                updateNotif("Bridge offline — retrying");
                nextDelay = bridgeBackoff;
                bridgeBackoff = Math.min(bridgeBackoff * 2, BACKOFF_MAX); return;
            }
            if (code >= 500) {
                bridgeStatus = "Server error (" + code + ")";
                nextDelay = bridgeBackoff;
                bridgeBackoff = Math.min(bridgeBackoff * 2, BACKOFF_MAX); return;
            }

            bridgeBackoff = POLL_MS;
            if (job == null) { bridgeStatus = "Connected · Idle"; nextDelay = POLL_MS; return; }

            bridgeStatus = "Working · " + job.action;
            updateNotif("● " + job.action);
            Log.i(TAG, "bridge job " + job.id + " " + job.action);
            safeExecJob(c, job);
            bridgeStatus = "Connected · Idle";
            updateNotif("● Connected · Polling");

        } catch (Throwable t) {
            Log.e(TAG, "pollOnce FATAL: " + t.getClass().getSimpleName() + " " + t.getMessage());
            bridgeStatus = "Loop error: " + t.getClass().getSimpleName();
            nextDelay = bridgeBackoff;
            bridgeBackoff = Math.min(bridgeBackoff * 2, BACKOFF_MAX);
        } finally {
            if (pollerActive.get() && executor != null && !executor.isShutdown()) {
                executor.schedule(this::pollOnce, nextDelay, TimeUnit.MILLISECONDS);
            } else {
                pollerActive.set(false);
                bridgeStatus = "Poller stopped";
            }
        }
    }

    private void safeExecJob(BridgeApiClient c, BridgeApiClient.BridgeJob job) {
        try {
            IPhoneService svc = phoneService;
            if (svc == null) { c.submitResult(job.id, false, null, "Shizuku UserService not connected"); return; }
            switch (job.action) {
                case "get_foreground_app":
                    c.submitResult(job.id, true, svc.getForegroundApp(), null); break;
                case "press_home": {
                    String r = svc.pressHome(); boolean ok = !r.startsWith("FAIL");
                    c.submitResult(job.id, ok, ok ? r : null, ok ? null : r); break; }
                case "launch_app": {
                    String pkg = job.args.optString("package_name","");
                    if (pkg.isEmpty()) { c.submitResult(job.id, false, null, "missing package_name"); break; }
                    String r = svc.launchApp(pkg); boolean ok = !r.startsWith("FAIL");
                    c.submitResult(job.id, ok, ok ? r : null, ok ? null : r); break; }
                case "get_device_status": {
                    String json = svc.getDeviceStatus();
                    Object res; try { res = new JSONObject(json); } catch (Exception e) { res = json; }
                    c.submitResult(job.id, true, res, null); break; }
                default:
                    c.submitResult(job.id, false, null, "unknown action: " + job.action);
            }
        } catch (Throwable t) {
            Log.e(TAG, "execJob: " + t);
            try { c.submitResult(job.id, false, null, "exec: " + t.getClass().getSimpleName()); } catch (Throwable ignored) {}
        }
    }

    // ── Sleep Guard poller ────────────────────────────────────────────────────
    private void initGuardPoller() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 读取本地开关（默认 false）
        guardEnabled = p.getBoolean(K_GUARD_ENABLED, false);

        String token = p.getString(K_GUARD_TOKEN, "");
        String url   = p.getString(K_GUARD_URL,   DEF_GUARD_URL);
        if (url == null || url.isEmpty()) url = DEF_GUARD_URL;

        if (token == null || token.isEmpty()) {
            guardStatus = "No token";
            Log.i(TAG, "no guard token, guard poller not started");
            return;
        }
        guardClient = new SleepGuardClient(url, token);
        startGuardPoller();
    }

    private void startGuardPoller() {
        if (!guardPollerActive.compareAndSet(false, true)) { Log.w(TAG, "guard poller already active"); return; }
        guardBackoff = GUARD_POLL_MS;
        if (guardExecutor == null || guardExecutor.isShutdown()) {
            guardExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GuardPoller");
                t.setDaemon(false); return t;
            });
        }
        guardExecutor.schedule(this::guardPollOnce, 1_000, TimeUnit.MILLISECONDS); // 1s 延迟启动，错开 bridge
        Log.i(TAG, "guard poller started");
    }

    /**
     * Sleep Guard 单次轮询。
     *
     * ════ 双保险安全逻辑 ════
     *   SAFETY 1: guardEnabled（本地开关，默认 false）→ 不 pressHome
     *   SAFETY 2: state.active（服务器开关）→ 不 pressHome
     *   SAFETY 3: state == null（网络失败）→ 不 pressHome
     *   SAFETY 4: phoneService == null（Shizuku 不可用）→ 不 pressHome
     *   两个条件都必须为 true 才执行封锁。
     *
     * Throwable 不会杀循环——finally 无条件重调度。
     * 本方法完全独立于 Bridge poller，互不影响。
     */
    private void guardPollOnce() {
        long nextDelay = GUARD_POLL_MS;
        try {
            // ── SAFETY 1: 本地开关 ─────────────────────────────────────────
            if (!guardEnabled) {
                guardStatus = "Disabled";
                nextDelay = GUARD_POLL_MS;
                return;
            }

            SleepGuardClient gc = guardClient;
            if (gc == null) {
                guardStatus = "No token";
                nextDelay = GUARD_POLL_MS;
                return;
            }

            // ── Fetch state（网络失败 → null → 不锁）──────────────────────
            SleepGuardClient.GuardState state = gc.fetchState();

            // ── SAFETY 3: 网络失败 → fail-safe ────────────────────────────
            if (state == null) {
                guardStatus = "Offline";
                nextDelay = guardBackoff;
                guardBackoff = Math.min(guardBackoff * 2, BACKOFF_MAX);
                return;
            }

            guardBackoff = GUARD_POLL_MS;

            // ── SAFETY 2: 服务器开关 ───────────────────────────────────────
            if (!state.active) {
                guardStatus = "Ready (server inactive)";
                return;
            }

            // 两个开关都开了
            guardStatus = "Active · Monitoring";

            // ── SAFETY 4: Shizuku 不可用 ───────────────────────────────────
            IPhoneService svc = phoneService;
            if (svc == null) {
                // 继续监控但无法执行，记录不锁
                return;
            }

            // 获取前台 App
            String fg;
            try {
                fg = svc.getForegroundApp();
            } catch (Throwable t) {
                return; // fail-safe
            }

            if (fg == null || fg.isEmpty() || fg.equals("unknown")) return;

            // 检查是否属于封锁列表
            if (!state.blockedApps.contains(fg)) return;

            // 检查临时豁免
            long nowSecs = System.currentTimeMillis() / 1000;
            if (!state.allowPkg.isEmpty()
                    && fg.equals(state.allowPkg)
                    && nowSecs < state.allowUntil) {
                return; // 在豁免窗口内，不锁
            }

            // ── 两保险都满足，执行 pressHome ───────────────────────────────
            Log.i(TAG, "Guard: blocking " + fg + " → pressHome");
            guardStatus = "Active · Blocking " + fg;
            try {
                String result = svc.pressHome();
                Log.i(TAG, "Guard: pressHome result: " + result);
            } catch (Throwable t) {
                Log.e(TAG, "Guard: pressHome failed: " + t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "guardPollOnce FATAL: " + t.getClass().getSimpleName() + " " + t.getMessage());
            guardStatus = "Error: " + t.getClass().getSimpleName();
            nextDelay = guardBackoff;
            guardBackoff = Math.min(guardBackoff * 2, BACKOFF_MAX);
        } finally {
            // 无条件重调度：Guard poller 独立于 Bridge poller，不会互相影响
            if (guardPollerActive.get() && guardExecutor != null && !guardExecutor.isShutdown()) {
                guardExecutor.schedule(this::guardPollOnce, nextDelay, TimeUnit.MILLISECONDS);
            } else {
                guardPollerActive.set(false);
                guardStatus = "Poller stopped";
            }
        }
    }

    // ── Shizuku binding ───────────────────────────────────────────────────────
    private void bindUserSvc() {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { return; }
            Shizuku.bindUserService(userServiceArgs, userConn);
        } catch (Exception e) { Log.e(TAG, "bindUserSvc: " + e); }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    void updateNotif(String txt) {
        try { getSystemService(NotificationManager.class).notify(NID, buildNotif(txt)); }
        catch (Exception ignored) {}
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
            CH, "Phone Agent", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Susu Phone Agent polling");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotif(String status) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
            .setContentTitle("Susu Phone Agent")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
