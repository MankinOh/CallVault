package com.callvault.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * RecordingService — foreground service for cellular call audio recording.
 *
 * KEY FIXES:
 *  1. startForeground() is called BEFORE AudioRecord initialization.
 *     Android requires foreground notification within 5s of onStartCommand.
 *     If AudioRecord init hangs (permission error, OEM lock), the service
 *     was previously killed before posting the notification.
 *
 *  2. Audio source strategy changed: MIC first, VOICE_COMMUNICATION as fallback.
 *     VOICE_COMMUNICATION initializes successfully on almost all devices but
 *     records silence on many OEMs in background services (Samsung, Xiaomi).
 *     MIC is more reliable for actual audio capture. We try VOICE_COMMUNICATION
 *     first (it may work on some devices), then fall back to MIC.
 *
 *  3. Broadcast uses RECEIVER_NOT_EXPORTED flag on Android 13+.
 */
public class RecordingService extends Service {

    private static final String TAG = "CallVault";
    public  static final String ACTION_START = "com.callvault.app.START_RECORDING";
    public  static final String ACTION_STOP  = "com.callvault.app.STOP_RECORDING";

    private static final String CHANNEL_RECORDING  = "callvault_recording";
    private static final String CHANNEL_SUMMARY    = "callvault_summary";
    private static final int    NOTIF_RECORDING    = 1001;
    private static final int    NOTIF_SUMMARY_BASE = 2000;

    private static final int SAMPLE_RATE    = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private Thread      recordingThread;
    private volatile boolean isRecording = false;
    private File        outputFile;

    private String contactName  = "Unknown Caller";
    private String phoneNumber  = "";
    private String direction    = "incoming";
    private String platform     = "phone";
    private long   startTimeMs  = 0;
    private long   durationSecs = 0;

    private static int summaryNotifId = NOTIF_SUMMARY_BASE;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_START.equals(action)) {
            contactName  = intent.getStringExtra("contactName");
            phoneNumber  = intent.getStringExtra("number");
            direction    = intent.getStringExtra("direction");
            platform     = intent.getStringExtra("platform");
            if (contactName == null) contactName = "Unknown Caller";
            if (phoneNumber == null) phoneNumber = "";
            if (direction   == null) direction   = "incoming";
            if (platform    == null) platform    = "phone";
            startRecording();

        } else if (ACTION_STOP.equals(action)) {
            durationSecs = intent.getLongExtra("durationSecs", 0);
            stopRecording();
        }

        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (isRecording) return;

        // ── Step 1: Post the foreground notification FIRST ────────────────
        // This must happen within 5 seconds of onStartCommand to avoid the
        // service being killed by the system on Android 8+.
        startForeground(NOTIF_RECORDING, buildRecordingNotification());

        startTimeMs = System.currentTimeMillis();

        // ── Step 2: Prepare output file ───────────────────────────────────
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safeName  = contactName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String filename  = "CallVault_" + platform + "_" + safeName + "_" + timestamp + ".pcm";
        File dir = new File(getExternalFilesDir(null), "CallVault/Recordings");
        if (!dir.exists()) dir.mkdirs();
        outputFile = new File(dir, filename);

        // ── Step 3: Initialize AudioRecord ────────────────────────────────
        // Strategy: try MIC first (more reliable for background recording on
        // most OEMs), fall back to VOICE_COMMUNICATION if MIC fails.
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize <= 0) bufferSize = SAMPLE_RATE * 2;
        bufferSize = Math.max(bufferSize, 8192);

        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "MIC source unavailable — trying VOICE_COMMUNICATION");
            audioRecord.release();
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            );
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not be initialized — aborting");
            stopForeground(true);
            stopSelf();
            return;
        }

        isRecording = true;
        audioRecord.startRecording();

        final int finalBufSize = bufferSize;
        recordingThread = new Thread(() -> {
            byte[] buf = new byte[finalBufSize];
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                while (isRecording) {
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0) fos.write(buf, 0, read);
                }
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
            Log.d(TAG, "Audio saved: " + outputFile.getAbsolutePath());
        });
        recordingThread.start();

        Log.d(TAG, "Recording started — " + direction + " call, " + contactName);
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;

        if (recordingThread != null) {
            try { recordingThread.join(3000); } catch (InterruptedException ignored) {}
            recordingThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }

        if (durationSecs == 0 && startTimeMs > 0) {
            durationSecs = (System.currentTimeMillis() - startTimeMs) / 1000;
        }

        stopForeground(true);

        String recordingId = outputFile != null ? outputFile.getName() : String.valueOf(System.currentTimeMillis());
        postSummaryNotification(recordingId);

        Intent broadcast = new Intent("com.callvault.app.RECORDING_SAVED");
        broadcast.putExtra("file",         outputFile != null ? outputFile.getAbsolutePath() : "");
        broadcast.putExtra("contactName",  contactName);
        broadcast.putExtra("number",       phoneNumber);
        broadcast.putExtra("platform",     platform);
        broadcast.putExtra("durationSecs", durationSecs);
        broadcast.putExtra("recordingId",  recordingId);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);

        Log.d(TAG, "Recording stopped. Duration: " + durationSecs + "s");
        stopSelf();
    }

    private Notification buildRecordingNotification() {
        String dirLabel = "incoming".equals(direction) ? "Incoming" : "outgoing".equals(direction) ? "Outgoing" : "Manual";
        String subtext  = dirLabel + " call · " + contactName;

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setContentTitle("🔴 CallVault — Recording")
            .setContentText(subtext)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void postSummaryNotification(String recordingId) {
        String durationStr = formatDuration(durationSecs);
        String dirLabel    = "incoming".equals(direction) ? "📞 Incoming" : "📤 Outgoing";
        String title       = "Call recorded — " + contactName;
        String body        = dirLabel + " · " + durationStr;
        if (!phoneNumber.isEmpty() && !phoneNumber.equals(contactName)) {
            body += " · " + phoneNumber;
        }

        Intent openRecording = new Intent(this, MainActivity.class);
        openRecording.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openRecording.setAction("com.callvault.app.OPEN_RECORDING");
        openRecording.putExtra("recordingId", recordingId);
        openRecording.putExtra("contactName", contactName);
        openRecording.putExtra("number",      phoneNumber);

        PendingIntent pi = PendingIntent.getActivity(this, summaryNotifId, openRecording,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_SUMMARY)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(summaryNotifId++, notif);
    }

    private String formatDuration(long secs) {
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private void createNotificationChannels() {
        NotificationChannel recChannel = new NotificationChannel(
            CHANNEL_RECORDING, "Active Recording", NotificationManager.IMPORTANCE_LOW);
        recChannel.setDescription("Shown while CallVault is recording a call");
        recChannel.setShowBadge(false);

        NotificationChannel sumChannel = new NotificationChannel(
            CHANNEL_SUMMARY, "Call Saved", NotificationManager.IMPORTANCE_DEFAULT);
        sumChannel.setDescription("Notifies when a call recording is saved");

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(recChannel);
        nm.createNotificationChannel(sumChannel);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (isRecording) stopRecording();
        super.onDestroy();
    }
}
