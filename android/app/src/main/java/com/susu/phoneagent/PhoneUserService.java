package com.susu.phoneagent;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shizuku UserService — Shizuku 以 uid=2000(shell) 身份运行此类。
 * 所有 shell 命令都继承 shell 权限，无需 root。
 */
public class PhoneUserService extends IPhoneService.Stub {
    private static final String TAG = "PhoneUserService";
    private static final Pattern PKG_RE =
        Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    @Override
    public String getForegroundApp() {
        // 优先 dumpsys window mCurrentFocus
        String win = runShell("dumpsys window");
        Pattern p1 = Pattern.compile("mCurrentFocus=Window\\{[^}]*?\\s([\\w.]+)/");
        Matcher m1 = p1.matcher(win);
        if (m1.find()) return m1.group(1);

        // fallback: dumpsys activity activities ResumedActivity 行
        String act = runShell("dumpsys activity activities");
        for (String line : act.split("\n")) {
            if (line.contains("ResumedActivity:") || line.contains("topResumedActivity=")) {
                Pattern p2 = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
                Matcher m2 = p2.matcher(line);
                while (m2.find()) {
                    String c = m2.group();
                    if (c.contains(".") && !c.startsWith("android.server")
                            && !c.equals("ActivityRecord")) return c;
                }
            }
        }
        return "unknown";
    }

    @Override
    public String pressHome() {
        ShellResult r = runShellFull("input keyevent 3");
        if (r.exit == 0) return "HOME pressed";
        return "FAIL:exit=" + r.exit + " err=" + trunc(r.err, 200);
    }

    @Override
    public String launchApp(String pkg) {
        if (pkg == null || !PKG_RE.matcher(pkg).matches() || pkg.length() > 200)
            return "FAIL:invalid package";
        // 优先 monkey
        ShellResult r = runShellFull("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
        if (r.exit == 0 && !r.out.contains("No activities found"))
            return "launched " + pkg;
        // fallback: am start
        ShellResult r2 = runShellFull(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + pkg);
        if (r2.exit == 0) return "launched " + pkg + " (am)";
        return "FAIL:monkey=" + r.exit + " am=" + r2.exit + " " + trunc(r2.err, 100);
    }

    @Override
    public String getDeviceStatus() {
        String fg = getForegroundApp();
        String bat  = runShell("dumpsys battery");
        String pwr  = runShell("dumpsys power");
        String level   = extract(bat, "level:\\s*(\\d+)", "0");
        String status  = extract(bat, "status:\\s*(\\d+)", "1");
        boolean charge = "2".equals(status);
        boolean screen = pwr.contains("mWakefulness=Awake") || pwr.contains("Display Power: state=ON");
        long ts = System.currentTimeMillis() / 1000;
        return "{\"battery\":" + level + ",\"charging\":" + charge
             + ",\"screen_on\":" + screen + ",\"foreground\":\"" + fg + "\""
             + ",\"ts\":" + ts + ",\"shizuku_status\":\"active\"}";
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static class ShellResult {
        int exit; String out, err;
        ShellResult(int exit, String out, String err) { this.exit=exit; this.out=out; this.err=err; }
    }

    private String runShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh","-c",cmd});
            String o = readAll(new BufferedReader(new InputStreamReader(p.getInputStream())));
            p.waitFor();
            return o;
        } catch (Exception e) { Log.e(TAG,"runShell: "+e); return ""; }
    }

    private ShellResult runShellFull(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh","-c",cmd});
            String o = readAll(new BufferedReader(new InputStreamReader(p.getInputStream())));
            String e = readAll(new BufferedReader(new InputStreamReader(p.getErrorStream())));
            int exit = p.waitFor();
            return new ShellResult(exit, o, e);
        } catch (Exception e) { Log.e(TAG,"runShellFull: "+e); return new ShellResult(-1,"",e.getMessage()); }
    }

    private String readAll(BufferedReader r) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private String extract(String s, String regex, String def) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : def;
    }

    private String trunc(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }
}
