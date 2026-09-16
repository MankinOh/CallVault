package com.callvault.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
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
 * FullDuplexRecordingService
 *
 * Records BOTH sides of a VoIP call simultaneously:
 *
 *   MIC TRACK   — AudioRecord with MIC source → captures your voice (outgoing audio)
 *   SPEAKER TRACK — AudioRecord with AudioPlaybackCaptureConfiguration →
 *                   captures the other person's voice coming through the speaker
 *                   (incoming audio). Requires MediaProjection permission + Android 10+.
 *
 * Both tracks are written to separate files and can be mixed/transcribed independently.
 * This method works for ANY app: WhatsApp, Messenger, Discord, Zoom, FaceTime,
 * Google Meet, Teams, or any other VoIP or audio application.
 *
 * IMPORTANT NOTE FOR USERS:
 * Android requires the user to tap "Allow" on a system confirmation dialog
 * before system audio capture can begin. This is an OS-level requirement
 * and cannot be bypassed. The prompt appears once per recording session.
 *
 * This service is started manually from the Record tab. The automatic
 * trigger (CallStateReceiver) only works for cellular phone calls.
 * For WhatsApp, Messenger, Discord, and other VoIP platforms, use this
 * Full Duplex mode — enable it in the Record tab before starting your call.
 */
 // Android 10+
public class FullDuplexRecordingService extends Service {

    private static final String TAG = "CallVault";
    public  static final String ACTION_START = "com.callvault.app.FULLDUPLEX_START";
    public  static final String ACTION_STOP  = "com.callvault.app.FULLDUPLEX_STOP";

    private static final String CHANNEL_ID  = "callvault_recording"; // reuse same channel
    private static final int    NOTIF_ID    = 1002;

    private static final int SAMPLE_RATE  = 44100;
    private static final int CHANNEL_MIC  = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_SPK  = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FMT    = AudioFormat.ENCODING_PCM_16BIT;

    // MediaProjection for speaker/system audio capture
    private MediaProjection mediaProjection;

    // Two AudioRecord instances — one per audio source
    private AudioRecord micRecord;
    private AudioRecord speakerRecord;

    // Two recording threads — run simultaneously
    private Thread micThread;
    private Thread speakerThread;

    private volatile boolean isRecording = false;

    private File micFile;
    private File speakerFile;

    private String contactName  = "Unknown";
    private String phoneNumber  = "";
    private long   startTimeMs  = 0;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_START.equals(action)) {
            int    resultCode    = intent.getIntExtra("resultCode", 0);
            Intent projData      = intent.getParcelableExtra("projectionData");
            contactName          = intent.getStringExtra("contactName");
            phoneNumber          = intent.getStringExtra("number");
            if (contactName == null) contactName = "Unknown";
            if (phoneNumber == null) phoneNumber = "";

            if (resultCode == 0 || projData == null) {
                Log.e(TAG, "FullDuplex: missing MediaProjection result — aborting");
                stopSelf();
                return START_NOT_STICKY;
            }

            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, projData);

            startFullDuplex();

        } else if (ACTION_STOP.equals(action)) {
            stopFullDuplex();
        }

        return START_NOT_STICKY;
    }

    private void startFullDuplex() {
        if (isRecording) return;
        startTimeMs = System.currentTimeMillis();

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(getExternalFilesDir(null), "CallVault/Recordings");
        if (!dir.exists()) dir.mkdirs();

        micFile     = new File(dir, "CallVault_mic_"     + timestamp + ".pcm");
        speakerFile = new File(dir, "CallVault_speaker_" + timestamp + ".pcm");

        // ── Mic AudioRecord ──────────────────────────────────────────────
        int micBuf = Math.max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MIC, AUDIO_FMT), 8192);
        micRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_MIC, AUDIO_FMT, micBuf);

        // ── Speaker AudioRecord via AudioPlaybackCapture ─────────────────
        int spkBuf = Math.max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_SPK, AUDIO_FMT), 8192);

        AudioPlaybackCaptureConfiguration captureConfig =
            new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        speakerRecord = new AudioRecord.Builder()
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AUDIO_FMT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_SPK)
                .build())
            .setBufferSizeInBytes(spkBuf)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build();

        if (micRecord.getState() != AudioRecord.STATE_INITIALIZED ||
            speakerRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "FullDuplex: AudioRecord init failed");
            cleanup();
            stopSelf();
            return;
        }

        isRecording = true;
        micRecord.startRecording();
        speakerRecord.startRecording();

        startForeground(NOTIF_ID, buildNotification());

        // ── Thread: mic ──────────────────────────────────────────────────
        final int mBuf = micBuf;
        micThread = new Thread(() -> {
            byte[] buf = new byte[mBuf];
            try (FileOutputStream fos = new FileOutputStream(micFile)) {
                while (isRecording) {
                    int r = micRecord.read(buf, 0, buf.length);
                    if (r > 0) fos.write(buf, 0, r);
                }
            } catch (IOException e) { Log.e(TAG, "Mic write error: " + e.getMessage()); }
        });

        // ── Thread: speaker ──────────────────────────────────────────────
        final int sBuf = spkBuf;
        speakerThread = new Thread(() -> {
            byte[] buf = new byte[sBuf];
            try (FileOutputStream fos = new FileOutputStream(speakerFile)) {
                while (isRecording) {
                    int r = speakerRecord.read(buf, 0, buf.length);
                    if (r > 0) fos.write(buf, 0, r);
                }
            } catch (IOException e) { Log.e(TAG, "Speaker write error: " + e.getMessage()); }
        });

        micThread.start();
        speakerThread.start();
        Log.d(TAG, "Full duplex recording started");
    }

    private void stopFullDuplex() {
        if (!isRecording) return;
        isRecording = false;

        try { if (micThread != null)     micThread.join(3000);    } catch (InterruptedException ignored) {}
        try { if (speakerThread != null) speakerThread.join(3000);} catch (InterruptedException ignored) {}

        cleanup();
        stopForeground(true);

        long durationSecs = startTimeMs > 0 ? (System.currentTimeMillis() - startTimeMs) / 1000 : 0;

        // Notify WebView
        Intent broadcast = new Intent("com.callvault.app.RECORDING_SAVED");
        broadcast.putExtra("file",         micFile != null ? micFile.getAbsolutePath() : "");
        broadcast.putExtra("speakerFile",  speakerFile != null ? speakerFile.getAbsolutePath() : "");
        broadcast.putExtra("contactName",  contactName);
        broadcast.putExtra("number",       phoneNumber);
        broadcast.putExtra("platform",     "voip_fullduplex");
        broadcast.putExtra("durationSecs", durationSecs);
        sendBroadcast(broadcast);

        // Post summary notification
        postSummaryNotification(durationSecs);
        Log.d(TAG, "Full duplex stopped. Duration: " + durationSecs + "s");
        stopSelf();
    }

    private void cleanup() {
        if (micRecord != null) {
            try { micRecord.stop(); } catch (Exception ignored) {}
            micRecord.release(); micRecord = null;
        }
        if (speakerRecord != null) {
            try { speakerRecord.stop(); } catch (Exception ignored) {}
            speakerRecord.release(); speakerRecord = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop(); mediaProjection = null;
        }
    }

    private void postSummaryNotification(long durationSecs) {
        String dur   = formatDuration(durationSecs);
        String title = "Call recorded — " + contactName;
        String body  = "VoIP call · Full Duplex · " + dur;

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, "callvault_summary")
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build();

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(2999, notif);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 CallVault — Full Duplex Recording")
            .setContentText("Capturing mic + speaker · " + contactName)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private String formatDuration(long secs) {
        long m = secs / 60, s = secs % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (isRecording) stopFullDuplex();
        super.onDestroy();
    }
}
