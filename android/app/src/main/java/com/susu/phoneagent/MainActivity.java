package com.susu.phoneagent;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.widget.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String TAG = "PhoneAgentUI";
    private static final int REQ_SHIZUKU = 1001;
    private static final int REQ_NOTIFY  = 1002;

    // Bridge status views
    private TextView tvShizuku, tvService, tvBridge, tvHeartbeat;
    // Guard status views
    private TextView tvGuardStatus;
    private CheckBox chkGuard;
    // Inputs
    private EditText etToken, etUrl, etGuardToken;
    private Handler handler;
    private Runnable ticker;

    private final Shizuku.OnBinderReceivedListener onBinderReceived = () -> {
        if (handler != null) handler.post(this::refreshStatus);
    };
    private final Shizuku.OnBinderDeadListener onBinderDead = () -> {
        if (handler != null) handler.post(this::refreshStatus);
    };
    private final Shizuku.OnRequestPermissionResultListener permResult =
        (code, grant) -> { if (code == REQ_SHIZUKU && handler != null) handler.post(this::refreshStatus); };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        handler = new Handler(Looper.getMainLooper());

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        scroll.addView(root);
        setContentView(scroll);

        addTitle(root, "🤖 Susu Phone Agent");

        // ── Bridge Status ─────────────────────────────────────────────────────
        tvShizuku   = addMono(root, "Shizuku:   —");
        tvService   = addMono(root, "Service:   —");
        tvBridge    = addMono(root, "Bridge:    —");
        tvHeartbeat = addMono(root, "Last poll: —");

        addSep(root);

        // ── Sleep Guard Status ────────────────────────────────────────────────
        addLabel(root, "─── Sleep Guard ───");
        tvGuardStatus = addMono(root, "Guard:     Disabled");

        // CheckBox: off by default — user must explicitly enable
        chkGuard = new CheckBox(this);
        chkGuard.setText("Enable Sleep Guard  ⚠ 默认关闭，锁机保护");
        chkGuard.setChecked(false);  // 硬编码 default OFF
        chkGuard.setPadding(0, 8, 0, 4);
        chkGuard.setOnCheckedChangeListener((btn, checked) -> {
            // 本地开关立即生效 + 持久化
            PhoneBridgeService.guardEnabled = checked;
            prefs().edit().putBoolean(PhoneBridgeService.K_GUARD_ENABLED, checked).apply();
            Log.i(TAG, "guardEnabled → " + checked);
            refreshStatus();
        });
        root.addView(chkGuard);

        addSep(root);

        // ── Bridge Config ─────────────────────────────────────────────────────
        addLabel(root, "Phone Bridge Token:");
        etToken = addInput(root, "粘贴或手动输入 Bridge Token");
        addButton(root, "📋 Paste Bridge Token", v -> paste(etToken));

        addLabel(root, "Bridge URL（留空用默认）:");
        etUrl = addInput(root, "https://your-domain.example/phone-bridge");

        // ── Sleep Guard Config ────────────────────────────────────────────────
        addLabel(root, "Sleep Guard Token（独立 token）:");
        etGuardToken = addInput(root, "粘贴或手动输入 Guard Token");
        addButton(root, "📋 Paste Guard Token", v -> paste(etGuardToken));

        LinearLayout cfgRow = hRow(root, 2f);
        addWeightBtn(cfgRow, "💾 Save All Config", 1f, v -> saveConfig());
        addWeightBtn(cfgRow, "🔍 Test Bridge",     1f, v -> testBridge());

        addSep(root);

        // ── Controls ──────────────────────────────────────────────────────────
        addButton(root, "🔑 Request Shizuku Permission", v -> requestShizuku());
        LinearLayout ctrlRow = hRow(root, 2f);
        addWeightBtn(ctrlRow, "▶ Start Agent", 1f, v -> startAgent());
        addWeightBtn(ctrlRow, "■ Stop Agent",  1f, v -> stopAgent());

        addSep(root);

        // OEM background-survival hints (Honor / MIUI / ColorOS have aggressive app killers)
        addLabel(root,
            "⚠ Background keep-alive (Honor / MIUI / ColorOS):\n" +
            "1. Settings → Apps → Phone Agent → Battery → No restrictions\n" +
            "2. Settings → Battery → App battery management → Phone Agent → No restrictions\n" +
            "3. Enable auto-start for Phone Agent in system settings\n\n" +
            "Sleep Guard: requires guard_enabled=true AND server active=true to act.\n" +
            "Sleep Guard is off by default. Enable it only after verifying your setup.");

        // 加载已保存配置
        SharedPreferences p = prefs();
        String tok      = p.getString(PhoneBridgeService.K_TOKEN,        "");
        String url      = p.getString(PhoneBridgeService.K_URL,          "");
        String guardTok = p.getString(PhoneBridgeService.K_GUARD_TOKEN,  "");
        boolean gEnabled = p.getBoolean(PhoneBridgeService.K_GUARD_ENABLED, false);

        if (!tok.isEmpty())      etToken.setText(tok);
        if (!url.isEmpty() && !url.equals(PhoneBridgeService.DEF_URL)) etUrl.setText(url);
        if (!guardTok.isEmpty()) etGuardToken.setText(guardTok);
        chkGuard.setChecked(gEnabled);
        // 同步到 static（Service 可能还没起来）
        PhoneBridgeService.guardEnabled = gEnabled;

        Shizuku.addBinderReceivedListenerSticky(onBinderReceived);
        Shizuku.addBinderDeadListener(onBinderDead);
        Shizuku.addRequestPermissionResultListener(permResult);

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }

        ticker = new Runnable() {
            @Override public void run() { refreshStatus(); handler.postDelayed(this, 1000); }
        };
        handler.post(ticker);
    }

    @Override protected void onResume()  { super.onResume(); refreshStatus(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(permResult);
    }

    // ── Logic ─────────────────────────────────────────────────────────────────
    private void saveConfig() {
        String tok      = etToken.getText().toString().trim();
        String url      = etUrl.getText().toString().trim();
        String guardTok = etGuardToken.getText().toString().trim();
        if (url.isEmpty()) url = PhoneBridgeService.DEF_URL;

        prefs().edit()
            .putString(PhoneBridgeService.K_TOKEN,       tok)
            .putString(PhoneBridgeService.K_URL,         url)
            .putString(PhoneBridgeService.K_GUARD_TOKEN, guardTok)
            .apply();
        Log.i(TAG, "config saved bridge.len=" + tok.length() + " guard.len=" + guardTok.length());
        toast("✓ Saved — 重启 Agent 使新 token 生效");
    }

    private void paste(EditText et) {
        try {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                CharSequence text = item.getText();
                if (text != null && text.length() > 0) {
                    et.setText(text.toString().trim());
                    et.setSelection(et.getText().length());
                    toast("✓ 已粘贴，确认后 Save");
                } else { toast("剪贴板为空"); }
            } else { toast("剪贴板无内容"); }
        } catch (Exception e) { toast("粘贴失败: " + e.getMessage()); }
    }

    private void testBridge() {
        String tok = etToken.getText().toString().trim();
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) url = PhoneBridgeService.DEF_URL;
        if (tok.isEmpty()) { toast("请先填写 Bridge Token"); return; }
        tvBridge.setText("Bridge:    Testing…"); tvBridge.setTextColor(0xFF757575);
        final String fu = url;
        new Thread(() -> {
            BridgeApiClient c = new BridgeApiClient(fu, tok);
            int code = c.testToken();
            String msg; int col;
            if (code==200||code==204)   { msg="✓ Token OK (HTTP "+code+")"; col=0xFF2E7D32; }
            else if (code==401)          { msg="✗ Token 错误 (401)";          col=0xFFC62828; }
            else if (code==-1)           { msg="✗ 网络不通";                   col=0xFFE65100; }
            else                         { msg="? HTTP "+code;                  col=0xFFE65100; }
            final String fm=msg; final int fc=col;
            handler.post(() -> { toast(fm); tvBridge.setText("Bridge:    "+fm); tvBridge.setTextColor(fc); });
        }, "Tester").start();
    }

    private void requestShizuku() {
        if (!Shizuku.pingBinder()) { toast("Shizuku not running"); return; }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { toast("Already granted ✓"); return; }
        Shizuku.requestPermission(REQ_SHIZUKU);
    }

    private void startAgent() {
        if (prefs().getString(PhoneBridgeService.K_TOKEN, "").isEmpty()) {
            toast("先 Save Config 填写 Bridge Token"); return;
        }
        PhoneBridgeService.start(this);
        toast("Starting…");
        handler.postDelayed(this::refreshStatus, 800);
    }

    private void stopAgent() {
        PhoneBridgeService.stop(this);
        PhoneBridgeService.bridgeStatus = "";
        PhoneBridgeService.lastPollMs   = 0;
        PhoneBridgeService.guardStatus  = "Disabled";
        toast("Stopped");
        handler.postDelayed(this::refreshStatus, 500);
    }

    // ── Status display ────────────────────────────────────────────────────────
    private void refreshStatus() {
        // Shizuku
        boolean ping    = Shizuku.pingBinder();
        boolean granted = ping && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        if (!ping)         setTv(tvShizuku, "Shizuku:   ✗ Not running",    0xFFC62828);
        else if (!granted) setTv(tvShizuku, "Shizuku:   ⚠ Need permission", 0xFFE65100);
        else               setTv(tvShizuku, "Shizuku:   ✓ Connected",       0xFF2E7D32);

        // Service
        boolean svcRunning = false;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo si : am.getRunningServices(100))
                if (PhoneBridgeService.class.getName().equals(si.service.getClassName()))
                    { svcRunning = true; break; }
        } catch (Exception ignored) {}
        setTv(tvService, "Service:   " + (svcRunning ? "✓ Running" : "◌ Stopped"),
              svcRunning ? 0xFF2E7D32 : 0xFF757575);

        // Bridge
        String bs = PhoneBridgeService.bridgeStatus;
        if (!svcRunning || bs.isEmpty()) setTv(tvBridge, "Bridge:    ◌ Not started", 0xFF757575);
        else if (bs.contains("401") || bs.contains("Unauthorized"))
            setTv(tvBridge, "Bridge:    ✗ " + bs, 0xFFC62828);
        else if (bs.contains("Offline") || bs.contains("error") || bs.contains("stopped"))
            setTv(tvBridge, "Bridge:    ⚠ " + bs, 0xFFE65100);
        else setTv(tvBridge, "Bridge:    ✓ " + bs, 0xFF2E7D32);

        // Heartbeat
        long lp = PhoneBridgeService.lastPollMs;
        if (lp == 0 || !svcRunning) { setTv(tvHeartbeat, "Last poll: —", 0xFF757575); }
        else {
            long s = (System.currentTimeMillis() - lp) / 1000;
            int c = s < 10 ? 0xFF2E7D32 : s < 30 ? 0xFFE65100 : 0xFFC62828;
            setTv(tvHeartbeat, "Last poll: " + s + "s ago", c);
        }

        // Guard status
        boolean ge = PhoneBridgeService.guardEnabled;
        String gs = PhoneBridgeService.guardStatus;
        if (!svcRunning) {
            setTv(tvGuardStatus, "Guard:     ◌ Service stopped", 0xFF757575);
        } else if (!ge) {
            setTv(tvGuardStatus, "Guard:     ◌ Disabled (local)", 0xFF757575);
        } else if (gs.contains("Active")) {
            setTv(tvGuardStatus, "Guard:     🔴 " + gs, 0xFFC62828);
        } else if (gs.contains("Ready")) {
            setTv(tvGuardStatus, "Guard:     🟢 " + gs, 0xFF2E7D32);
        } else if (gs.contains("Offline") || gs.contains("Error")) {
            setTv(tvGuardStatus, "Guard:     ⚠ " + gs, 0xFFE65100);
        } else {
            setTv(tvGuardStatus, "Guard:     " + gs, 0xFF757575);
        }

        // 同步 checkbox 状态（不触发 listener）
        chkGuard.setOnCheckedChangeListener(null);
        chkGuard.setChecked(ge);
        chkGuard.setOnCheckedChangeListener((btn, checked) -> {
            PhoneBridgeService.guardEnabled = checked;
            prefs().edit().putBoolean(PhoneBridgeService.K_GUARD_ENABLED, checked).apply();
            Log.i(TAG, "guardEnabled → " + checked);
            refreshStatus();
        });
    }

    private void setTv(TextView tv, String text, int color) { tv.setText(text); tv.setTextColor(color); }
    private SharedPreferences prefs() { return getSharedPreferences(PhoneBridgeService.PREFS, MODE_PRIVATE); }

    // ── Layout helpers ────────────────────────────────────────────────────────
    private TextView addTitle(LinearLayout p, String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(21); tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0,0,0,20); p.addView(tv); return tv;
    }
    private TextView addLabel(LinearLayout p, String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(13); tv.setPadding(0,6,0,2); p.addView(tv); return tv;
    }
    private TextView addMono(LinearLayout p, String t) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(0,2,0,2); p.addView(tv); return tv;
    }
    private EditText addInput(LinearLayout p, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,2,0,4); et.setLayoutParams(lp); p.addView(et); return et;
    }
    private Button addButton(LinearLayout p, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,4,0,4); b.setLayoutParams(lp); p.addView(b); return b;
    }
    private LinearLayout hRow(LinearLayout p, float total) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setWeightSum(total);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,4,0,4); row.setLayoutParams(lp); p.addView(row); return row;
    }
    private Button addWeightBtn(LinearLayout p, String label, float weight, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(4,0,4,0); b.setLayoutParams(lp); p.addView(b); return b;
    }
    private void addSep(LinearLayout p) {
        View v = new View(this); v.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0,14,0,14); v.setLayoutParams(lp); p.addView(v);
    }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
}
