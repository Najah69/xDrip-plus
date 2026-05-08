package com.eveningoutpost.dexdrip.camaps.access;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.eveningoutpost.dexdrip.camaps.CamAPSCollector;
import com.eveningoutpost.dexdrip.camaps.CamAPSData;
import com.eveningoutpost.dexdrip.camaps.CamAPSPreferences;
import com.eveningoutpost.dexdrip.camaps.CamAPSProcessor;

/**
 * AccessibilityService that scrapes the CamAPS FX UI for glucose, IOB, COB,
 * basal rate, bolus, and pump status data.
 *
 * This is the V1 (no-root) data collector. It reads the CamAPS screen content
 * that is visible to the accessibility system and extracts health data.
 *
 * The service only processes events from CamAPS packages and rate-limits
 * scraping to once per scan interval (default 5 seconds).
 */
public class CamAPSAccessibilityService extends AccessibilityService implements CamAPSCollector {

    private static final String TAG = "CamAPSAccessSvc";

    private CamAPSPreferences prefs;
    private CamAPSProcessor processor;
    private long lastScanTime = 0;
    private boolean active = false;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new CamAPSPreferences(this);
        processor = new CamAPSProcessor(this);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100; // ms

        // Target only CamAPS packages
        info.packageNames = new String[]{
            "com.camdiab.fx_alert.mmoll",
            "com.camdiab.fx_alert.mgdl",
            "com.camdiab.fx_alert.hx.mmoll",
            "com.camdiab.fx_alert.hx.mgdl",
            "com.camdiab.fx_alert.mmoll.ca"
        };

        setServiceInfo(info);
        Log.i(TAG, "AccessibilityService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!prefs.isEnabled()) return;

        // Always log that we received a CamAPS event (for debugging)
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "null";
        Log.i(TAG, "Event received: type=" + event.getEventType() + " pkg=" + pkg);

        // Rate limit: max one scrape per scan interval
        long now = System.currentTimeMillis();
        if (now - lastScanTime < prefs.getScanIntervalMs()) {
            Log.d(TAG, "Rate limited, skipping");
            return;
        }
        lastScanTime = now;

        try {
            if (event.getSource() != null) {
                CamAPSData data = CamAPSUIParser.parse(event.getSource());
                if (data != null) {
                    Log.i(TAG, "Data extracted: " + data);
                    onDataCollected(data);
                } else {
                    Log.w(TAG, "No data extracted from event source");
                }
                event.getSource().recycle();
            } else {
                Log.w(TAG, "Event source is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing accessibility event: " + e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted");
        active = false;
    }

    @Override
    public void onDestroy() {
        active = false;
        super.onDestroy();
    }

    // --- CamAPSCollector interface ---

    @Override
    public void onDataCollected(CamAPSData data) {
        if (prefs.isDebugLogEnabled()) {
            Log.d(TAG, "Data collected: " + data);
        }
        processor.process(data);
    }

    @Override
    public void start() {
        active = true;
        Log.i(TAG, "Collector started");
    }

    @Override
    public void stop() {
        active = false;
        Log.i(TAG, "Collector stopped");
    }

    @Override
    public boolean isActive() {
        return active;
    }
}
