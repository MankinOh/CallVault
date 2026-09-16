package com.callvault.app;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * MediaProjectionRequestActivity
 *
 * A transparent, single-purpose activity whose only job is to show the
 * Android system dialog: "Allow CallVault to capture your screen and audio?"
 *
 * This dialog is required by Android OS — no app can capture system audio
 * without explicit user confirmation each session. It cannot be skipped.
 *
 * Flow:
 *   1. User taps "Full Duplex" or "Speaker Output" in the Record tab
 *   2. WebView calls CallVaultNative.requestSystemAudioCapture()
 *   3. MainActivity starts this transparent activity
 *   4. This activity immediately shows the system dialog
 *   5. User taps "Allow" or "Deny"
 *   6. Result is returned to MainActivity which starts the projection
 *   7. WebView is notified of the result
 *
 * The activity is transparent so the user never sees a blank screen —
 * just the system dialog appearing over whatever is currently on screen.
 */
public class MediaProjectionRequestActivity extends Activity {

    private static final String TAG = "CallVault";
    public  static final int    REQUEST_CODE = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Request the system audio capture permission dialog immediately
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE) {
            // Forward the result to MainActivity so it can start the projection
            Intent result = new Intent("com.callvault.app.MEDIA_PROJECTION_RESULT");
            result.putExtra("resultCode", resultCode);
            result.putExtra("data", data);
            sendBroadcast(result);
            Log.d(TAG, "MediaProjection result: " + (resultCode == RESULT_OK ? "GRANTED" : "DENIED"));
        }
        finish(); // close this transparent activity
    }
}
