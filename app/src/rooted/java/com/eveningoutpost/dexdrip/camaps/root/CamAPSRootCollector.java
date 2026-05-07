package com.eveningoutpost.dexdrip.camaps.root;

import android.content.Context;
import android.util.Log;

import com.eveningoutpost.dexdrip.camaps.CamAPSCollector;
import com.eveningoutpost.dexdrip.camaps.CamAPSData;
import com.eveningoutpost.dexdrip.camaps.CamAPSLogExporter;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * V2 Root-based data collector.
 *
 * Provides full access to CamAPS data via:
 * 1. Direct SQLCipher database reading (historical + real-time)
 * 2. Gson hook interception (live DTO capture before encryption)
 * 3. JNI native function hooking (getRecommendation, getTDD)
 *
 * REQUIREMENTS: Rooted device with:
 * - File system access to /data/data/com.camdiab.fx_alert.mmoll/
 * - SQLCipher library (included via xDrip+ dependencies)
 * - Optional: Frida server or Xposed framework for live hooks
 *
 * Implements CamAPSCollector for seamless integration with the V1/V2 bridge.
 */
public class CamAPSRootCollector implements CamAPSCollector {

    private static final String TAG = "CamAPSRootCollector";
    private static final long DB_POLL_INTERVAL_MS = 60_000; // Poll DB every 60 seconds

    private final CamAPSDatabaseHelper dbHelper;
    private final CamAPSJsonInterceptor jsonInterceptor;
    private final String dbKey;
    private ScheduledExecutorService scheduler;
    private volatile boolean active = false;
    // C3: volatile — written by scheduler thread, read by other contexts
    private volatile CamAPSData lastData;

    // Callback set by the bridge
    private OnDataCollectedListener listener;

    public interface OnDataCollectedListener {
        void onData(CamAPSData data);
        void onBatchData(List<CamAPSData> batch);
    }

    /**
     * @param context Android Context (required for SQLCipher.loadLibs)
     * @param dbKey The SQLCipher key extracted from CamAPS SharedPreferences.
     *              Use CamAPSDatabaseHelper.extractKeyFromPrefs() via root shell.
     */
    public CamAPSRootCollector(Context context, String dbKey) {
        this.dbKey = dbKey;
        this.dbHelper = new CamAPSDatabaseHelper(context);
        this.jsonInterceptor = new CamAPSJsonInterceptor();
    }

    public void setOnDataCollectedListener(OnDataCollectedListener listener) {
        this.listener = listener;
    }

    @Override
    public void start() {
        if (active) return;
        active = true;

        // Open the database
        if (dbKey != null && !dbKey.isEmpty()) {
            boolean opened = dbHelper.autoOpen(dbKey);
            if (opened) {
                Log.i(TAG, "Database opened successfully, starting poll");
                // M4: run backfill + polling in a dedicated thread — DB reads are blocking
                //     and must not execute on the UI thread
                new Thread(() -> {
                    backfillHistoricalData();
                    startPolling();
                }, "CamAPS-V2-init").start();
            } else {
                Log.e(TAG, "Failed to open database — check key and root access");
            }
        } else {
            Log.w(TAG, "No database key provided — database access disabled");
            Log.w(TAG, "Run: adb shell \"run-as com.camdiab.fx_alert.mmoll cat /data/data/com.camdiab.fx_alert.mmoll/shared_prefs/*.xml\" | grep -i key");
        }

        // Start JSON interceptor (Xposed or Frida-based)
        jsonInterceptor.start();
    }

    @Override
    public void stop() {
        active = false;
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        dbHelper.close();
        jsonInterceptor.stop();
        Log.i(TAG, "Collector stopped");
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void onDataCollected(CamAPSData data) {
        if (listener != null) {
            listener.onData(data);
        }
    }

    private void backfillHistoricalData() {
        try {
            // Extract all CGM readings (up to 24 hours)
            List<CamAPSData> cgmData = dbHelper.extractAllCGMReadings();
            if (listener != null && !cgmData.isEmpty()) {
                listener.onBatchData(cgmData);
            }

            // Extract all therapy events (up to 500)
            List<CamAPSData> therapyData = dbHelper.extractAllTherapyEvents();
            if (listener != null && !therapyData.isEmpty()) {
                listener.onBatchData(therapyData);
            }

            Log.i(TAG, "Backfill complete: " + cgmData.size() + " CGM + " + therapyData.size() + " therapies");
        } catch (Exception e) {
            Log.e(TAG, "Error during backfill: " + e.getMessage());
        }
    }

    private void startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            if (!active) return;
            try {
                // M1: incremental extraction — only rows newer than lastData
                //     avoids full 2880-row scan every 60 seconds
                long since = lastData != null ? lastData.glucoseTimestamp : 0;
                List<CamAPSData> latest = dbHelper.extractCGMReadingsSince(since);
                for (CamAPSData data : latest) {
                    onDataCollected(data);
                }
                if (!latest.isEmpty()) {
                    lastData = latest.get(0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Poll error: " + e.getMessage());
            }
        }, DB_POLL_INTERVAL_MS, DB_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // --- External hook integration points ---

    /**
     * Called by Frida/Xposed hook when a SyncDecryptedDTO is captured before encryption.
     * Contains all medical data that CamAPS is about to upload to MyLife Cloud.
     */
    public void onSyncDecryptedCaptured(String jsonPayload) {
        Log.d(TAG, "SyncDecryptedDTO captured: " + jsonPayload.substring(0, Math.min(200, jsonPayload.length())));
        // Parse the full JSON to extract CGM readings, therapy events, device info
        // This is the richest data source — all DTOs in clear text
        List<CamAPSData> batch = jsonInterceptor.parseSyncPayload(jsonPayload);
        if (listener != null && !batch.isEmpty()) {
            listener.onBatchData(batch);
        }
    }

    /**
     * Export all CamAPS debug logs (V1 + V2 tags) to a GitHub Gist.
     * Works for both the non-rooted and rooted APK — CamAPSLogExporter
     * captures all relevant tags from src/main/ (shared by both builds).
     *
     * @param context Android context
     */
    public static void exportDebugLogs(Context context) {
        CamAPSLogExporter.exportAndUpload(context);
    }

    /**
     * Called by Frida hook when getRecommendation() returns a result.
     * Captures the algorithm's insulin recommendation.
     */
    public void onRecommendationCaptured(String recommendation) {
        Log.d(TAG, "getRecommendation: " + recommendation);
        // Parse the recommendation JSON to extract suggested insulin dose
        CamAPSData data = jsonInterceptor.parseRecommendation(recommendation);
        if (data != null) {
            onDataCollected(data);
        }
    }
}
