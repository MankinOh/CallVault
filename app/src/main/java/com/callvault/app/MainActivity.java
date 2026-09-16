package com.callvault.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.util.Log;

/**
 * MainActivity — hosts the CallVault WebView.
 *
 * Key fixes applied:
 *  1. Bottom nav overlap: root FrameLayout gets paddingBottom = nav bar inset,
 *     and we fire a JS event so React can adjust its own nav height.
 *     This matches the Claude app approach — the view sits above system buttons
 *     regardless of whether gesture nav or button nav is active.
 *  2. Permissions: removed deprecated PROCESS_OUTGOING_CALLS and storage perms.
 *     POST_NOTIFICATIONS requested separately on Android 13+.
 *  3. Battery optimization: re-prompts every launch until granted (not just once).
 *  4. Navigation bar color matches the app background so the system button area
 *     blends rather than clashing.
 */
public class MainActivity extends Activity {

    private static final String TAG          = "CallVault";
    private static final int    PERM_REQUEST       = 100;
    private static final int    PERM_NOTIF_REQUEST = 101;
    private static final int    BATTERY_REQ        = 200;

    private WebView    webView;
    private FrameLayout rootLayout;
    private int         navBarInset = 0; // pixels, set by inset listener

    // ── Recording saved broadcast ────────────────────────────────────────
    private final BroadcastReceiver recordingSavedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String contactName  = intent.getStringExtra("contactName");
            String number       = intent.getStringExtra("number");
            String platform     = intent.getStringExtra("platform");
            long   durationSecs = intent.getLongExtra("durationSecs", 0);
            String recordingId  = intent.getStringExtra("recordingId");

            if (contactName == null) contactName = "Unknown Caller";
            if (number      == null) number      = "";
            if (platform    == null) platform    = "phone";
            if (recordingId == null) recordingId = String.valueOf(System.currentTimeMillis());

            String minutes = String.valueOf(durationSecs / 60);
            String seconds = String.format("%02d", durationSecs % 60);
            String durStr  = minutes + ":" + seconds;

            final String jsContact  = contactName.replace("'", "\\'");
            final String jsNumber   = number.replace("'", "\\'");
            final String jsPlatform = platform.replace("'", "\\'");
            final String jsId       = recordingId.replace("'", "\\'");

            if (webView != null) {
                webView.post(() -> webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('callvault_recording_saved',{" +
                    "detail:{" +
                    "contactName:'" + jsContact  + "'," +
                    "number:'"      + jsNumber   + "'," +
                    "platform:'"    + jsPlatform + "'," +
                    "durationSecs:" + durationSecs + "," +
                    "duration:'"    + durStr + "'," +
                    "recordingId:'" + jsId + "'" +
                    "}}));", null
                ));
            }
        }
    };

    // ── MediaProjection result broadcast ────────────────────────────────
    private final BroadcastReceiver projectionResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int    resultCode = intent.getIntExtra("resultCode", 0);
            Intent projData   = intent.getParcelableExtra("data");
            boolean granted   = (resultCode == Activity.RESULT_OK);

            if (granted) {
                pendingProjectionResultCode = resultCode;
                pendingProjectionData       = projData;
            }

            if (webView != null) {
                webView.post(() -> webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('callvault_projection_result'," +
                    "{detail:{granted:" + granted + "}}));", null
                ));
            }
        }
    };

    private int    pendingProjectionResultCode = 0;
    private Intent pendingProjectionData       = null;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindowEdgeToEdge();
        setupWebView();
        requestCorePermissions();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (!"com.callvault.app.OPEN_RECORDING".equals(intent.getAction())) return;

        String contactName = intent.getStringExtra("contactName");
        String number      = intent.getStringExtra("number");
        String recordingId = intent.getStringExtra("recordingId");
        if (contactName == null) contactName = "Unknown";
        if (number      == null) number      = "";
        if (recordingId == null) recordingId = "";

        final String jsCN  = contactName.replace("'", "\\'");
        final String jsNum = number.replace("'", "\\'");
        final String jsId  = recordingId.replace("'", "\\'");

        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('callvault_open_recording',{" +
                "detail:{contactName:'" + jsCN + "',number:'" + jsNum + "',recordingId:'" + jsId + "'}}));",
                null
            ));
        }
    }

    // ── Window insets / edge-to-edge ──────────────────────────────────────

    /**
     * Configure edge-to-edge drawing so the app fills the entire screen.
     * The root view's paddingBottom will absorb the navigation bar height,
     * pushing all content — including the bottom nav bar — above the system
     * buttons. This is identical to how the Claude app handles it.
     *
     * Navigation bar background color is set to match the app so the system
     * button area blends seamlessly (no black gap, no clashing color).
     */
    private void setupWindowEdgeToEdge() {
        // Match nav bar color to app background
        getWindow().setStatusBarColor(Color.parseColor("#0D0E1A"));
        getWindow().setNavigationBarColor(Color.parseColor("#0D0E1A"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    // ── WebView setup ──────────────────────────────────────────────────────

    private void setupWebView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#0D0E1A"));
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(webView);
        setContentView(rootLayout);

        // Apply window insets to the ROOT layout, not the WebView.
        // This pushes the entire WebView (and its bottom nav) above the system buttons.
        // We also fire a JS event so React can read the exact inset value for its nav bar height.
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset    = 0;
            int bottomInset = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset    = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                topInset    = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }

            // Padding on root: top inset handled by status bar; bottom pushes content above nav
            rootLayout.setPadding(0, 0, 0, bottomInset);
            navBarInset = bottomInset;

            // Tell React the exact nav bar height in px so it can size its own nav bar
            final int finalBottom = bottomInset;
            if (webView != null) {
                webView.post(() -> webView.evaluateJavascript(
                    "window.cvNavInset=" + finalBottom + ";" +
                    "window.dispatchEvent(new CustomEvent('callvault_nav_inset',{detail:{inset:" + finalBottom + "}}));",
                    null
                ));
            }

            return insets;
        });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setBackgroundColor(Color.parseColor("#0D0E1A"));
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) { return false; }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        webView.addJavascriptInterface(new CallVaultBridge(), "CallVaultNative");
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // ── JavaScript Bridge ───────────────────────────────────────────────────

    private class CallVaultBridge {

        @JavascriptInterface
        public void setAutoRecordPhone(boolean enabled) {
            getSharedPreferences("callvault_prefs", MODE_PRIVATE)
                .edit().putBoolean("auto_record_phone", enabled).apply();
        }

        @JavascriptInterface
        public boolean getAutoRecordPhone() {
            return getSharedPreferences("callvault_prefs", MODE_PRIVATE)
                .getBoolean("auto_record_phone", true);
        }

        @JavascriptInterface
        public int getNavBarInsetPx() {
            return navBarInset;
        }

        @JavascriptInterface
        public void startRecording(String platform, String contactName, String number) {
            Intent svc = new Intent(MainActivity.this, RecordingService.class);
            svc.setAction(RecordingService.ACTION_START);
            svc.putExtra("direction",   "manual");
            svc.putExtra("platform",    platform    != null ? platform    : "manual");
            svc.putExtra("contactName", contactName != null ? contactName : "");
            svc.putExtra("number",      number      != null ? number      : "");
            startForegroundService(svc);
        }

        @JavascriptInterface
        public void stopRecording() {
            Intent svc = new Intent(MainActivity.this, RecordingService.class);
            svc.setAction(RecordingService.ACTION_STOP);
            startService(svc);
        }

        @JavascriptInterface
        public void requestSystemAudioCapture() {
            Intent req = new Intent(MainActivity.this, MediaProjectionRequestActivity.class);
            req.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(req);
        }

        @JavascriptInterface
        public void startFullDuplexRecording(String contactName, String number) {
            if (pendingProjectionResultCode == 0 || pendingProjectionData == null) {
                Log.w(TAG, "Full duplex: no projection permission yet");
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.w(TAG, "Full duplex requires Android 10+");
                return;
            }
            Intent svc = new Intent(MainActivity.this, FullDuplexRecordingService.class);
            svc.setAction(FullDuplexRecordingService.ACTION_START);
            svc.putExtra("resultCode",     pendingProjectionResultCode);
            svc.putExtra("projectionData", pendingProjectionData);
            svc.putExtra("contactName",    contactName != null ? contactName : "");
            svc.putExtra("number",         number      != null ? number      : "");
            startForegroundService(svc);
        }

        @JavascriptInterface
        public void stopFullDuplexRecording() {
            Intent svc = new Intent(MainActivity.this, FullDuplexRecordingService.class);
            svc.setAction(FullDuplexRecordingService.ACTION_STOP);
            startService(svc);
        }

        @JavascriptInterface
        public boolean supportsFullDuplex() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        }

        /** Returns a JSON string of current permission statuses for the status UI */
        @JavascriptInterface
        public String getPermissionStatus() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "{\"allGranted\":true}";
            boolean mic     = checkSelfPermission(Manifest.permission.RECORD_AUDIO)       == PackageManager.PERMISSION_GRANTED;
            boolean phone   = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)   == PackageManager.PERMISSION_GRANTED;
            boolean callLog = checkSelfPermission(Manifest.permission.READ_CALL_LOG)      == PackageManager.PERMISSION_GRANTED;
            boolean contacts= checkSelfPermission(Manifest.permission.READ_CONTACTS)      == PackageManager.PERMISSION_GRANTED;
            boolean allGranted = mic && phone && callLog && contacts;
            return "{\"mic\":" + mic + ",\"phone\":" + phone +
                   ",\"callLog\":" + callLog + ",\"contacts\":" + contacts +
                   ",\"allGranted\":" + allGranted + "}";
        }
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    private void requestCorePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            promptBatteryOptimization();
            return;
        }

        // Core permissions (never include deprecated storage or PROCESS_OUTGOING_CALLS)
        String[] corePerms = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        };

        boolean needRequest = false;
        for (String p : corePerms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            requestPermissions(corePerms, PERM_REQUEST);
        } else {
            // Core already granted — request notifications separately then battery
            requestNotificationPermission();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{ Manifest.permission.POST_NOTIFICATIONS },
                    PERM_NOTIF_REQUEST
                );
                return; // battery prompt follows in onRequestPermissionsResult
            }
        }
        promptBatteryOptimization();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);

        if (code == PERM_REQUEST) {
            boolean allGranted = true;
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            Log.d(TAG, "Core permissions result — all granted: " + allGranted);

            // Notify React of permission status
            if (webView != null) {
                final boolean granted = allGranted;
                webView.post(() -> webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('callvault_permissions'," +
                    "{detail:{granted:" + granted + "}}));", null
                ));
            }

            // Now request notifications (Android 13+), then battery
            requestNotificationPermission();

        } else if (code == PERM_NOTIF_REQUEST) {
            promptBatteryOptimization();
        }
    }

    /**
     * Battery optimization prompt.
     * Re-shown every launch until granted — unlike before where it was shown
     * only once even if the user tapped Later. Without this, the recording
     * service gets killed mid-call on Samsung, Xiaomi, Huawei, etc.
     */
    private void promptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return; // already whitelisted

        new AlertDialog.Builder(this)
            .setTitle("Keep recordings running")
            .setMessage(
                "To record calls reliably in the background — even when your screen is off " +
                "or the app is closed — please disable battery optimization for CallVault.\n\n" +
                "Tap 'Open Settings', then select 'Don't optimize'."
            )
            .setPositiveButton("Open Settings", (d, w) -> {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                try { startActivityForResult(i, BATTERY_REQ); }
                catch (Exception ex) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            })
            .setNegativeButton("Later", null)
            .show();
    }

    // ── Broadcast receivers ─────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();

        IntentFilter recFilter = new IntentFilter("com.callvault.app.RECORDING_SAVED");
        IntentFilter prjFilter = new IntentFilter("com.callvault.app.MEDIA_PROJECTION_RESULT");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingSavedReceiver, recFilter, RECEIVER_NOT_EXPORTED);
            registerReceiver(projectionResultReceiver, prjFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recordingSavedReceiver, recFilter);
            registerReceiver(projectionResultReceiver, prjFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        try { unregisterReceiver(recordingSavedReceiver);   } catch (Exception ignored) {}
        try { unregisterReceiver(projectionResultReceiver); } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
