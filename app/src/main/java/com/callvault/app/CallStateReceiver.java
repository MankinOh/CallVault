package com.callvault.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * CallStateReceiver
 *
 * Detects incoming cellular phone call state changes.
 *
 * KEY CHANGES from previous version:
 *  - ACTION_NEW_OUTGOING_CALL removed: deprecated and blocked on Android 10+.
 *    Outgoing calls are detected by checking the call log when OFFHOOK fires
 *    and the previous state was IDLE (i.e. we went straight to OFFHOOK without
 *    RINGING first — that's an outgoing call).
 *  - State persisted in SharedPreferences instead of static fields.
 *    BroadcastReceivers are re-instantiated on every broadcast. If the process
 *    is killed between RINGING and OFFHOOK, static fields reset. SharedPrefs
 *    survive process death.
 *  - startForegroundService called only when the app process is alive.
 *    On Android 12+ this can throw ForegroundServiceStartNotAllowedException
 *    from a background context. We wrap it in try/catch and log the failure
 *    gracefully rather than crashing.
 */
public class CallStateReceiver extends BroadcastReceiver {

    private static final String TAG        = "CallVault";
    private static final String PREFS      = "callvault_prefs";
    private static final String KEY_STATE  = "call_last_state";
    private static final String KEY_NUMBER = "call_pending_number";
    private static final String KEY_START  = "call_start_ms";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean autoRecord = prefs.getBoolean("auto_record_phone", true);
        if (!autoRecord) return;

        String state    = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String lastState= prefs.getString(KEY_STATE, TelephonyManager.EXTRA_STATE_IDLE);
        if (state == null) return;

        Log.d(TAG, "Phone state: " + lastState + " → " + state);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            // Incoming call ringing — capture number while we have it
            String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            prefs.edit()
                .putString(KEY_STATE, state)
                .putString(KEY_NUMBER, number != null ? number : "")
                .apply();

        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            // Call answered or outgoing call started
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(lastState)) {
                // Incoming call that was just answered
                String number = prefs.getString(KEY_NUMBER, "");
                String contactName = lookupContact(context, number);
                Log.d(TAG, "Incoming answered: " + contactName + " (" + number + ")");
                prefs.edit()
                    .putString(KEY_STATE, state)
                    .putLong(KEY_START, System.currentTimeMillis())
                    .apply();
                startRecording(context, "incoming", number, contactName);

            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(lastState)) {
                // Went straight from IDLE to OFFHOOK = outgoing call
                // Read the number from the call log (requires READ_CALL_LOG)
                String number = getLastOutgoingNumber(context);
                String contactName = lookupContact(context, number);
                Log.d(TAG, "Outgoing call: " + contactName + " (" + number + ")");
                prefs.edit()
                    .putString(KEY_STATE, state)
                    .putLong(KEY_START, System.currentTimeMillis())
                    .apply();
                startRecording(context, "outgoing", number, contactName);
            } else {
                prefs.edit().putString(KEY_STATE, state).apply();
            }

        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            // Call ended
            if (!TelephonyManager.EXTRA_STATE_IDLE.equals(lastState)) {
                long startMs     = prefs.getLong(KEY_START, 0);
                long durationSecs= startMs > 0 ? (System.currentTimeMillis() - startMs) / 1000 : 0;
                Log.d(TAG, "Call ended, duration: " + durationSecs + "s");
                stopRecording(context, durationSecs);
            }
            prefs.edit()
                .putString(KEY_STATE, TelephonyManager.EXTRA_STATE_IDLE)
                .putString(KEY_NUMBER, "")
                .putLong(KEY_START, 0)
                .apply();
        }
    }

    /**
     * Read the most recent outgoing call number from the call log.
     * Returns empty string if READ_CALL_LOG is not granted or log is empty.
     */
    private String getLastOutgoingNumber(Context context) {
        try {
            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{ CallLog.Calls.NUMBER },
                CallLog.Calls.TYPE + " = " + CallLog.Calls.OUTGOING_TYPE,
                null,
                CallLog.Calls.DATE + " DESC LIMIT 1"
            );
            if (cursor != null && cursor.moveToFirst()) {
                String number = cursor.getString(0);
                cursor.close();
                return number != null ? number : "";
            }
            if (cursor != null) cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "Call log read failed: " + e.getMessage());
        }
        return "";
    }

    private String lookupContact(Context context, String number) {
        if (number == null || number.isEmpty()) return "Unknown Caller";
        try {
            Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            );
            Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{ ContactsContract.PhoneLookup.DISPLAY_NAME },
                null, null, null
            );
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    cursor.close();
                    return (name != null && !name.isEmpty()) ? name : number;
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Contact lookup failed: " + e.getMessage());
        }
        return number;
    }

    private void startRecording(Context context, String direction, String number, String contactName) {
        try {
            Intent svc = new Intent(context, RecordingService.class);
            svc.setAction(RecordingService.ACTION_START);
            svc.putExtra("direction",   direction);
            svc.putExtra("platform",    "phone");
            svc.putExtra("number",      number      != null ? number      : "");
            svc.putExtra("contactName", contactName != null ? contactName : "Unknown Caller");
            context.startForegroundService(svc);
        } catch (Exception e) {
            // Android 12+ may throw ForegroundServiceStartNotAllowedException
            // if the app is in a deep background state. Log and move on.
            Log.e(TAG, "Could not start recording service: " + e.getMessage());
        }
    }

    private void stopRecording(Context context, long durationSecs) {
        try {
            Intent svc = new Intent(context, RecordingService.class);
            svc.setAction(RecordingService.ACTION_STOP);
            svc.putExtra("durationSecs", durationSecs);
            context.startService(svc);
        } catch (Exception e) {
            Log.e(TAG, "Could not stop recording service: " + e.getMessage());
        }
    }
}
