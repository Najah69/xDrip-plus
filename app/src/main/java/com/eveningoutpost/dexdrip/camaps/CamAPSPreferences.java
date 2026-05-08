package com.eveningoutpost.dexdrip.camaps;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * SharedPreferences wrapper for CamAPS Bridge settings.
 */
public class CamAPSPreferences {

    private static final String PREF_ENABLED = "camaps_bridge_enabled";
    private static final String PREF_SCAN_INTERVAL = "camaps_scan_interval_seconds";
    private static final String PREF_SCRAPE_GLUCOSE = "camaps_scrape_glucose";
    private static final String PREF_SCRAPE_IOB = "camaps_scrape_iob";
    private static final String PREF_SCRAPE_COB = "camaps_scrape_cob";
    private static final String PREF_SCRAPE_BASAL = "camaps_scrape_basal";
    private static final String PREF_SCRAPE_PUMP = "camaps_scrape_pump";
    private static final String PREF_DEBUG_LOG = "camaps_debug_log";

    private static final int DEFAULT_SCAN_INTERVAL = 5; // seconds
    private static final long MIN_SCAN_INTERVAL_MS = 2000; // 2 seconds minimum

    private final SharedPreferences prefs;

    public CamAPSPreferences(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    /** Scan interval in milliseconds, clamped to minimum 2s */
    public long getScanIntervalMs() {
        int seconds = prefs.getInt(PREF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
        long ms = Math.max(seconds * 1000L, MIN_SCAN_INTERVAL_MS);
        return ms;
    }

    public boolean scrapeGlucose() {
        return prefs.getBoolean(PREF_SCRAPE_GLUCOSE, true);
    }

    public boolean scrapeIOB() {
        return prefs.getBoolean(PREF_SCRAPE_IOB, true);
    }

    public boolean scrapeCOB() {
        return prefs.getBoolean(PREF_SCRAPE_COB, true);
    }

    public boolean scrapeBasal() {
        return prefs.getBoolean(PREF_SCRAPE_BASAL, true);
    }

    public boolean scrapePump() {
        return prefs.getBoolean(PREF_SCRAPE_PUMP, true);
    }

    public boolean isDebugLogEnabled() {
        return prefs.getBoolean(PREF_DEBUG_LOG, false);
    }
}
