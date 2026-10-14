package com.subclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Runs while a match is running, so the alerts play on time with the screen
 * locked or while another app (Spotify) is in front.
 *
 * The page sends the upcoming events (heads-up and sub times, and exactly what
 * to say). This service holds a partial wake lock, checks the clock twice a
 * second, plays each event through {@link Announcer}, and keeps a notification
 * with a live countdown to the next sub.
 */
public class SubService extends Service {

    private static final String CHANNEL = "match_timer";
    private static final int NOTIFICATION_ID = 42;
    private static final String EXTRA_JSON = "schedule";
    private static final long LATE_LIMIT_MS = 60_000; // skip alerts more than a minute overdue

    private static SubService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Long> fired = new HashMap<>();
    private JSONObject audioCfg = new JSONObject();
    private boolean waitingForSubDone = false;
    private PowerManager.WakeLock wakeLock;

    private static final class Event {
        long at;
        String kind;     // "warn" or "sub"
        JSONArray parts; // sentences to speak
        int times;
        String names;    // who comes off, for the notification

        String key() {
            return kind + "@" + at;
        }
    }

    /** Called on the main thread whenever the page's schedule changes. */
    static void sync(Context ctx, String json) {
        boolean wantRunning;
        try {
            wantRunning = new JSONObject(json).optBoolean("running");
        } catch (JSONException e) {
            return;
        }
        if (running != null) {
            if (wantRunning) running.apply(json);
            else running.stopNow();
            return;
        }
        if (!wantRunning) return;
        Intent i = new Intent(ctx, SubService.class).putExtra(EXTRA_JSON, json);
        try {
            ContextCompat.startForegroundService(ctx, i);
        } catch (RuntimeException ignored) {
            // Android refuses to start it from the background; the page re-sends on its next change.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        createChannel();
        Announcer.get(this); // warm up the speech engine
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubClock:match");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(4 * 60 * 60 * 1000L); // a match never needs more than 4 hours
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String json = intent != null ? intent.getStringExtra(EXTRA_JSON) : null;
        if (json != null) {
            apply(json);
        } else if (events.isEmpty()) {
            stopNow(); // restarted by the system without a schedule
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void apply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject a = o.optJSONObject("audio");
            if (a != null) audioCfg = a;
            waitingForSubDone = o.optBoolean("due");
            events.clear();
            JSONArray arr = o.optJSONArray("events");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.getJSONObject(i);
                    Event ev = new Event();
                    ev.at = e.getLong("at");
                    ev.kind = e.optString("kind", "sub");
                    ev.parts = e.optJSONArray("parts");
                    ev.times = e.optInt("times", 1);
                    JSONArray names = e.optJSONArray("names");
                    ev.names = names == null ? "" : join(names);
                    events.add(ev);
                }
            }
        } catch (JSONException e) {
            return;
        }
        // Forget alerts from more than an hour ago; keep the rest so a re-sent schedule never repeats one.
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        for (Iterator<Map.Entry<String, Long>> it = fired.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < cutoff) it.remove();
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        updateNotification();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Event e : events) {
                if (e.at <= now && !fired.containsKey(e.key())) {
                    fired.put(e.key(), e.at);
                    changed = true;
                    if (now - e.at <= LATE_LIMIT_MS) play(e);
                }
            }
            if (changed) updateNotification();
            handler.postDelayed(this, 500);
        }
    };

    private void play(Event e) {
        try {
            JSONObject cfg = new JSONObject(audioCfg.toString());
            cfg.put("parts", e.parts == null ? new JSONArray() : e.parts);
            cfg.put("times", e.times);
            cfg.put("beep", e.kind);
            Announcer.get(this).play(cfg);
        } catch (JSONException ignored) {
        }
    }

    private Event nextSub() {
        long now = System.currentTimeMillis();
        Event best = null;
        for (Event e : events) {
            if ("sub".equals(e.kind) && e.at > now && !fired.containsKey(e.key())
                    && (best == null || e.at < best.at)) best = e;
        }
        return best;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setColor(0xFF2F7A45)
                .setContentIntent(tap)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        Event next = nextSub();
        if (waitingForSubDone) {
            b.setContentTitle("Sub now").setContentText("Open Sub Clock and tap Sub done to start the next timer");
        } else if (next != null) {
            b.setContentTitle("Next sub")
                    .setContentText(next.names.isEmpty() ? "Match running" : next.names + " off")
                    .setWhen(next.at)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true);
        } else {
            b.setContentTitle("Match running").setContentText("No subs scheduled");
        }
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification());
            } catch (SecurityException ignored) {
                // notifications turned off: the timer still runs, there's just no countdown shown
            }
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    void stopNow() {
        handler.removeCallbacksAndMessages(null);
        events.clear();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        running = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(a.optString(i));
        }
        return sb.toString();
    }
}
