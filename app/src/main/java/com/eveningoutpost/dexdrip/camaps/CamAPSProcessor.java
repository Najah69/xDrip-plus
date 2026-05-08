package com.eveningoutpost.dexdrip.camaps;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.utilitymodels.PumpStatus;

/**
 * Transforms CamAPSData into xDrip+ database calls.
 * Once data is in xDrip+ DB, the existing UploaderQueue → NightscoutUploader
 * pipeline automatically syncs it to Nightscout.
 */
public class CamAPSProcessor {

    private static final String TAG = "CamAPSProcessor";
    private static final String SOURCE_INFO = "CamAPS Bridge";
    private static final long DEDUP_MARGIN_MS = 240_000; // 4 minutes

    private final Context context;
    private final CamAPSPreferences prefs;
    private long lastGlucoseTimestamp = 0;
    private long lastIobTimestamp = 0;
    private long lastPumpTimestamp = 0;

    public CamAPSProcessor(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new CamAPSPreferences(context);
    }

    /** Process a full CamAPSData snapshot. Rate-limited per data type. */
    public void process(CamAPSData data) {
        if (data == null) return;

        if (prefs.isDebugLogEnabled()) {
            Log.d(TAG, "Processing: " + data);
        }

        if (data.hasGlucose() && prefs.scrapeGlucose()) {
            processGlucose(data);
        }
        if (data.hasIOB() && prefs.scrapeIOB()) {
            processIOB(data);
        }
        if ((data.hasCarbs() || data.hasCOB()) && prefs.scrapeCOB()) {
            processCarbs(data);
        }
        if (data.hasBolus()) {
            processBolus(data);
        }
        if (data.hasBasalRate() && prefs.scrapeBasal()) {
            processBasalRate(data);
        }
        if ((data.hasPumpBattery() || data.hasPumpReservoir()) && prefs.scrapePump()) {
            processPump(data);
        }
    }

    private void processGlucose(CamAPSData data) {
        // Rate limit: one glucose reading per DEDUP_MARGIN_MS
        if (data.glucoseTimestamp > 0 &&
            data.glucoseTimestamp - lastGlucoseTimestamp < DEDUP_MARGIN_MS) {
            return;
        }

        double mgdl = data.getGlucoseMgdl();
        if (mgdl <= 0) return;

        Sensor.createDefaultIfMissing();

        BgReading.bgReadingInsertFromInt(
            (int) Math.round(mgdl),
            data.glucoseTimestamp > 0 ? data.glucoseTimestamp : System.currentTimeMillis(),
            DEDUP_MARGIN_MS,
            true,
            SOURCE_INFO
        );

        lastGlucoseTimestamp = data.glucoseTimestamp;
        if (prefs.isDebugLogEnabled()) {
            Log.d(TAG, "Glucose injected: " + mgdl + " mg/dL at " + data.glucoseTimestamp);
        }
    }

    private void processIOB(CamAPSData data) {
        if (System.currentTimeMillis() - lastIobTimestamp < 10_000) return; // 10s rate limit

        PumpStatus.setBolusIoB(data.iob);

        // Also send as ExternalStatusline for AAPS-style integration
        if (data.hasBasalRate()) {
            broadcastStatusline(data);
        }

        lastIobTimestamp = System.currentTimeMillis();
        if (prefs.isDebugLogEnabled()) {
            Log.d(TAG, "IOB injected: " + data.iob + " U");
        }
    }

    private void processCarbs(CamAPSData data) {
        // FIX: carbsEntered ≠ cob
        // carbsEntered > 0 → actual carb entry by the user → create a Treatment (Nightscout treatment)
        // cob > 0 only  → active carbs remaining (decaying) → NOT a new entry, Nightscout
        //                  calculates COB from previous Treatment entries automatically
        if (data.carbsEntered > 0) {
            long ts = data.carbsTimestamp > 0 ? data.carbsTimestamp : System.currentTimeMillis();
            Treatments.create(data.carbsEntered, 0, ts, null);
            if (prefs.isDebugLogEnabled()) {
                Log.d(TAG, "Carbs entered injected: " + data.carbsEntered + "g at " + ts);
            }
        } else if (data.cob > 0 && prefs.isDebugLogEnabled()) {
            // COB is displayed in xDrip+ status line only — Nightscout derives it from entries
            Log.d(TAG, "COB observed: " + data.cob + "g (not creating Treatment — derived by Nightscout)");
        }
    }

    private void processBolus(CamAPSData data) {
        if (data.bolusDelivered > 0) {
            long ts = data.bolusTimestamp > 0 ? data.bolusTimestamp : System.currentTimeMillis();
            Treatments.create(0, data.bolusDelivered, ts, null);
            if (prefs.isDebugLogEnabled()) {
                Log.d(TAG, "Bolus injected: " + data.bolusDelivered + " U at " + ts);
            }
        }
    }

    private void processBasalRate(CamAPSData data) {
        // FIX: store basal rate in PumpStatus so it reaches Nightscout devicestatus
        // Previously only broadcast to ExternalStatusline (xDrip+ display only, never uploaded)
        PumpStatus.setBasal(data.basalRate);

        // Keep broadcast for xDrip+ status line display
        broadcastStatusline(data);

        if (prefs.isDebugLogEnabled()) {
            Log.d(TAG, "Basal rate: " + data.basalRate + " U/h → PumpStatus + statusline");
        }
    }

    private void processPump(CamAPSData data) {
        if (System.currentTimeMillis() - lastPumpTimestamp < 30_000) return; // 30s rate limit

        if (data.hasPumpBattery()) {
            PumpStatus.setBattery(data.pumpBattery);
        }
        if (data.hasPumpReservoir()) {
            PumpStatus.setReservoir(data.pumpReservoir);
        }

        lastPumpTimestamp = System.currentTimeMillis();
        if (prefs.isDebugLogEnabled()) {
            Log.d(TAG, "Pump injected: battery=" + data.pumpBattery + "% reservoir=" + data.pumpReservoir + " U");
        }
    }

    private void broadcastStatusline(CamAPSData data) {
        // Build status line in AAPS-compatible format
        // ExternalStatusBroadcastReceiver parses: "NN%" for TBR, "X.X U/h" for basal
        StringBuilder sb = new StringBuilder("CamAPS");
        if (data.hasBasalRate()) {
            sb.append(" ").append(String.format("%.2f", data.basalRate)).append(" U/h");
        }
        if (data.hasIOB()) {
            sb.append(" IOB:").append(String.format("%.2f", data.iob)).append("U");
        }

        Intent intent = new Intent("com.eveningoutpost.dexdrip.ExternalStatusline");
        intent.putExtra("com.eveningoutpost.dexdrip.Extras.Statusline", sb.toString());
        context.sendBroadcast(intent);
    }
}
