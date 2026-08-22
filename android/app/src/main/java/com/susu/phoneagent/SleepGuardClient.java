package com.susu.phoneagent;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sleep Guard state 轮询客户端。
 *
 * 所有网络异常均返回 null。调用方必须把 null 视为"不锁机"（fail-safe）。
 * token 不输出到任何日志。
 */
public class SleepGuardClient {
    private static final String TAG = "SleepGuardClient";
    private static final int TIMEOUT_MS = 8000;

    private final String baseUrl;
    private final String token;

    public static class GuardState {
        public boolean active     = false;
        public List<String> blockedApps = new ArrayList<>();
        public String allowPkg   = "";
        public long   allowUntil = 0;
    }

    public SleepGuardClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token   = token;
    }

    /**
     * GET /sleep-guard/state
     * 成功 → GuardState
     * 任意异常 / 非 200 → null（调用方 fail-safe: 不锁）
     */
    public GuardState fetchState() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/sleep-guard/state").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + token);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "fetchState HTTP " + code);
                return null;
            }

            BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');

            JSONObject json = new JSONObject(sb.toString());
            GuardState s = new GuardState();
            s.active     = json.optBoolean("active",      false);
            s.allowPkg   = json.optString("allow_pkg",    "");
            s.allowUntil = json.optLong("allow_until",    0);

            JSONArray arr = json.optJSONArray("blocked_apps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    s.blockedApps.add(arr.getString(i));
                }
            }
            return s;
        } catch (Exception e) {
            Log.e(TAG, "fetchState: " + e.getClass().getSimpleName());
            return null; // fail-safe: caller must treat null as "don't lock"
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
