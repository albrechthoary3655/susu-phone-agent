package com.susu.phoneagent;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class BridgeApiClient {
    private static final String TAG = "BridgeApiClient";
    private static final int TIMEOUT_MS = 15000;

    public final String baseUrl;
    private final String token;

    /** 最近一次 getNextJob() 的 HTTP 状态码；-1=网络异常 */
    public volatile int lastCode = 0;

    public static class BridgeJob {
        public final String id;
        public final String action;
        public final JSONObject args;
        public BridgeJob(String id, String action, JSONObject args) {
            this.id = id; this.action = action; this.args = args;
        }
    }

    public BridgeApiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl; this.token = token;
    }

    /** 取下一个 job。204→null(idle)，200→BridgeJob，其他→null（lastCode 记录原因） */
    public BridgeJob getNextJob() {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", "/next");
            lastCode = conn.getResponseCode();
            if (lastCode == 204) return null;
            if (lastCode != 200) {
                Log.w(TAG, "next HTTP " + lastCode);
                return null;
            }
            JSONObject json = new JSONObject(readStream(conn));
            JSONObject args = json.optJSONObject("args");
            if (args == null) args = new JSONObject();
            return new BridgeJob(json.getString("id"), json.getString("action"), args);
        } catch (Exception e) {
            lastCode = -1;
            Log.e(TAG, "getNextJob: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        } finally { if (conn != null) conn.disconnect(); }
    }

    /** token 验证：返回 HTTP code（200/204=ok, 401=wrong token, -1=网络异常）
     *  注意：不输出 token 到日志 */
    public int testToken() {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", "/next");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            Log.i(TAG, "testToken → HTTP " + code);
            return code;
        } catch (Exception e) {
            Log.e(TAG, "testToken: " + e.getClass().getSimpleName());
            return -1;
        } finally { if (conn != null) conn.disconnect(); }
    }

    /** 回传执行结果；result 可以是 String 或 JSONObject */
    public boolean submitResult(String jobId, boolean success, Object result, String error) {
        HttpURLConnection conn = null;
        try {
            conn = open("POST", "/result/" + jobId);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            body.put("success", success);
            if (result != null) body.put("result", result);
            if (error  != null) body.put("error",  error);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            Log.e(TAG, "submitResult: " + e);
            return false;
        } finally { if (conn != null) conn.disconnect(); }
    }

    private HttpURLConnection open(String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private String readStream(HttpURLConnection conn) throws Exception {
        BufferedReader r = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }
}
