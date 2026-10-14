package com.subclock.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plays the beep and speaks the announcement through Android's own text-to-speech.
 * While it talks it holds "transient, may duck" audio focus, the same thing a
 * navigation app does, so Spotify and other music apps drop their volume and
 * come back up as soon as the announcement ends.
 */
final class Announcer {

    private static Announcer instance;

    static synchronized Announcer get(Context c) {
        if (instance == null) instance = new Announcer(c.getApplicationContext());
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioAttributes attrs;
    private final AudioFocusRequest focus;
    private final TextToSpeech tts;
    private final List<Runnable> waitingForTts = new ArrayList<>();

    private volatile String state = "loading";
    private Runnable onStateChanged;
    private int token = 0;
    private boolean holdingFocus = false;

    private Announcer(Context c) {
        audio = c.getSystemService(AudioManager.class);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> { })
                .build();
        tts = new TextToSpeech(c, status -> main.post(() -> onTtsReady(status)));
    }

    private void onTtsReady(int status) {
        if (status == TextToSpeech.SUCCESS) {
            state = "ready";
            tts.setAudioAttributes(attrs);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    finished(id);
                }

                @SuppressWarnings("deprecation")
                @Override
                public void onError(String id) {
                    finished(id);
                }

                @Override
                public void onError(String id, int code) {
                    finished(id);
                }
            });
            for (Runnable r : waitingForTts) r.run();
        } else {
            state = "failed";
        }
        waitingForTts.clear();
        if (onStateChanged != null) onStateChanged.run();
    }

    private void finished(String id) {
        if (id != null && id.startsWith("end:")) {
            try {
                final int t = Integer.parseInt(id.substring(4));
                main.postDelayed(() -> releaseFocus(t), 250);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    String state() {
        return state;
    }

    void setOnStateChanged(Runnable r) {
        onStateChanged = r;
    }

    /**
     * cfg: parts (array of sentences), times, beep ("sub" | "warn" | "none"), rate, pitch,
     * volume (beep loudness 0..1), voice (engine voice name or ""), gapMs, speak (bool).
     * Must be called on the main thread.
     */
    void play(JSONObject cfg) {
        final int my = ++token;
        final JSONArray parts = cfg.optJSONArray("parts");
        final boolean speak = cfg.optBoolean("speak", true) && parts != null && parts.length() > 0;
        final String beep = cfg.optString("beep", "none");
        final double volume = cfg.optDouble("volume", 0.9);

        grabFocus();
        long beepMs = 0;
        if ("sub".equals(beep)) beepMs = Beeper.play(attrs, 1976, 140, volume, 1.0);
        else if ("warn".equals(beep)) beepMs = Beeper.play(attrs, 1319, 80, volume, 0.6);

        if (speak) {
            main.postDelayed(() -> {
                if (my == token) speak(cfg, parts, my);
            }, beepMs + 150);
        } else {
            main.postDelayed(() -> releaseFocus(my), beepMs + 300);
        }
        // Safety net: never leave the music turned down if the speech engine goes quiet.
        main.postDelayed(() -> releaseFocus(my), 25000);
    }

    private void speak(JSONObject cfg, JSONArray parts, int my) {
        if (!"ready".equals(state)) {
            if ("loading".equals(state)) waitingForTts.add(() -> speak(cfg, parts, my));
            else releaseFocus(my);
            return;
        }
        tts.setSpeechRate((float) cfg.optDouble("rate", 0.95));
        tts.setPitch((float) cfg.optDouble("pitch", 1.0));

        Voice chosen = findVoice(cfg.optString("voice", ""));
        if (chosen != null) tts.setVoice(chosen);
        else tts.setLanguage(Locale.US);

        int times = Math.max(1, cfg.optInt("times", 1));
        long gap = Math.max(0, cfg.optLong("gapMs", 300));
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);

        int n = parts.length();
        boolean first = true;
        for (int r = 0; r < times; r++) {
            for (int i = 0; i < n; i++) {
                boolean last = r == times - 1 && i == n - 1;
                String id = last ? "end:" + my : "part:" + my + ":" + r + ":" + i;
                tts.speak(parts.optString(i), first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, id);
                first = false;
                // A short pause after each sentence, a longer one before a repeat.
                if (!last) tts.playSilentUtterance(i == n - 1 ? gap * 2 : gap, TextToSpeech.QUEUE_ADD,
                        "gap:" + my + ":" + r + ":" + i);
            }
        }
    }

    private Voice findVoice(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            Set<Voice> all = tts.getVoices();
            if (all == null) return null;
            for (Voice v : all) if (name.equals(v.getName())) return v;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** English voices for the page's voice picker. */
    String voicesJson() {
        JSONArray out = new JSONArray();
        if (!"ready".equals(state)) return out.toString();
        try {
            Set<Voice> all = tts.getVoices();
            if (all != null) {
                for (Voice v : all) {
                    Locale l = v.getLocale();
                    if (l == null || !"en".equals(l.getLanguage())) continue;
                    Set<String> f = v.getFeatures();
                    boolean installed = f == null || !f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("lang", l.toLanguageTag());
                    o.put("local", !v.isNetworkConnectionRequired());
                    o.put("installed", installed);
                    o.put("quality", v.getQuality());
                    out.put(o);
                }
            }
        } catch (JSONException | RuntimeException ignored) {
        }
        return out.toString();
    }

    private void grabFocus() {
        if (!holdingFocus && audio != null) {
            audio.requestAudioFocus(focus);
            holdingFocus = true;
        }
    }

    private void releaseFocus(int t) {
        if (t == token && holdingFocus && audio != null) {
            audio.abandonAudioFocusRequest(focus);
            holdingFocus = false;
        }
    }

    /** Short square-wave beep, generated on the fly. */
    static final class Beeper {
        static long play(AudioAttributes attrs, int freq, int ms, double volume, double level) {
            final int rate = 44100;
            final int n = rate * ms / 1000;
            final int ramp = rate / 200; // 5 ms fade in and out, no clicks
            final double amp = Math.max(0.05, Math.min(1.0, volume)) * 0.6 * level * 32767;
            short[] buf = new short[n];
            for (int i = 0; i < n; i++) {
                double env = Math.min(1.0, Math.min(i / (double) ramp, (n - 1 - i) / (double) ramp));
                double phase = (i * (double) freq / rate) % 1.0;
                buf[i] = (short) ((phase < 0.5 ? 1 : -1) * amp * env);
            }
            try {
                final AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(n * 2)
                        .build();
                track.write(buf, 0, n);
                track.play();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        track.stop();
                    } catch (Exception ignored) {
                    }
                    track.release();
                }, ms + 300);
                return ms;
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
