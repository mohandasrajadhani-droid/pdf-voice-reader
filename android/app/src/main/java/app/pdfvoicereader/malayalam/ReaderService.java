package app.pdfvoicereader.malayalam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service that keeps the app alive while reading aloud with the
 * screen off, shows the "Reading" notification with Pause/Resume and Stop,
 * and holds a partial wake lock so the phone doesn't sleep mid-book.
 */
public class ReaderService extends Service {

    static final String CHANNEL = "reading";
    static final int NOTIFICATION_ID = 7301;
    static final String ACTION_SHOW = "app.pdfvoicereader.SHOW";
    static final String ACTION_PAUSE = "app.pdfvoicereader.PAUSE";
    static final String ACTION_RESUME = "app.pdfvoicereader.RESUME";
    static final String ACTION_STOP = "app.pdfvoicereader.STOP";

    private static ReaderService running;
    private static String lastTitle = "PDF Voice Reader";
    private static String lastText = "Reading";
    private static boolean lastPlaying = true;

    private PowerManager.WakeLock wakeLock;

    /** Starts the service (call while the app is on screen, e.g. when Play is tapped). */
    public static void begin(Context c, String title, String text) {
        lastTitle = title;
        lastText = text;
        lastPlaying = true;
        Intent i = new Intent(c, ReaderService.class);
        i.setAction(ACTION_SHOW);
        try {
            ContextCompat.startForegroundService(c, i);
        } catch (Exception ignored) {
            // Android refused to start it (rare); reading still works while the screen is on.
        }
    }

    /** Updates the notification and wake lock without restarting the service. */
    public static void setPlaying(Context c, String title, String text, boolean playing) {
        lastTitle = title;
        lastText = text;
        lastPlaying = playing;
        ReaderService s = running;
        if (s == null) return;
        s.holdWakeLock(playing);
        try {
            NotificationManagerCompat.from(c).notify(NOTIFICATION_ID, s.build());
        } catch (SecurityException ignored) {
            // notifications not allowed; the service keeps running regardless
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, ReaderService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows which page is being read, with pause and stop buttons");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        // Always enter the foreground first: Android requires it soon after startForegroundService.
        Notification n = build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        ReaderEngine engine = ReaderEngine.get(this);
        if (ACTION_PAUSE.equals(action)) {
            engine.pause();
        } else if (ACTION_RESUME.equals(action)) {
            engine.resume();
        } else if (ACTION_STOP.equals(action)) {
            engine.stop();
            stopSelf();
            return START_NOT_STICKY;
        } else {
            holdWakeLock(lastPlaying);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        holdWakeLock(false);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void holdWakeLock(boolean on) {
        try {
            if (on) {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm == null) return;
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PdfVoiceReader:reading");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L); // at most 6 hours
            } else if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private PendingIntent serviceAction(String action, int code) {
        Intent i = new Intent(this, ReaderService.class);
        i.setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    Notification build() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(lastPlaying ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
            .setContentTitle(lastTitle)
            .setContentText(lastText)
            .setContentIntent(contentIntent)
            .setOngoing(lastPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        if (lastPlaying) {
            b.addAction(android.R.drawable.ic_media_pause, "Pause", serviceAction(ACTION_PAUSE, 2));
        } else {
            b.addAction(android.R.drawable.ic_media_play, "Resume", serviceAction(ACTION_RESUME, 3));
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceAction(ACTION_STOP, 4));
        return b.build();
    }
}
