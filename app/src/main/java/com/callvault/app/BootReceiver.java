package com.callvault.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootReceiver
 * Re-registers call monitoring after the device restarts,
 * so auto-recording works even after a reboot without opening the app.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("CallVault", "Boot completed — call monitoring active");
            // Call detection is handled via the manifest-registered receiver.
            // No explicit start needed — Android re-registers manifest receivers on boot.
        }
    }
}
