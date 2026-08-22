package com.susu.phoneagent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i("BootReceiver", "Boot completed — starting PhoneBridgeService");
            // Service 会注册 addBinderReceivedListenerSticky，
            // 等 Shizuku 重启后自动 bind UserService，无需用户操作
            try { PhoneBridgeService.start(ctx); }
            catch (Exception e) { Log.e("BootReceiver", "start failed: " + e); }
        }
    }
}
