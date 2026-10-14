package com.subclock.app;

import android.os.PowerManager;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * window.SubNative in the page. JavaScript calls arrive on a background thread,
 * so anything that touches the service, audio or UI hops to the main thread.
 */
public final class Bridge {

    private final MainActivity act;

    Bridge(MainActivity act) {
        this.act = act;
    }

    /** The page's upcoming schedule: who comes off and when, plus the voice settings. */
    @JavascriptInterface
    public void sync(String json) {
        act.runOnUiThread(() -> SubService.sync(act, json));
    }

    /** Play an alert right now (Sub now, Test the alert, Hear it). */
    @JavascriptInterface
    public void announceNow(String json) {
        act.runOnUiThread(() -> {
            try {
                Announcer.get(act).play(new JSONObject(json));
            } catch (JSONException ignored) {
            }
        });
    }

    @JavascriptInterface
    public String voices() {
        return Announcer.get(act).voicesJson();
    }

    /** "loading", "ready" or "failed" (no speech engine on the phone). */
    @JavascriptInterface
    public String ttsState() {
        return Announcer.get(act).state();
    }

    @JavascriptInterface
    public void openVoiceSettings() {
        act.runOnUiThread(act::openVoiceSettings);
    }

    @JavascriptInterface
    public boolean canRunInBackground() {
        PowerManager pm = act.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(act.getPackageName());
    }

    @JavascriptInterface
    public void requestBackgroundRun() {
        act.runOnUiThread(act::requestBackgroundRun);
    }
}
