package com.eveningoutpost.dexdrip.camaps.root;

import android.util.Log;

import com.eveningoutpost.dexdrip.camaps.CamAPSData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Handles JSON interception for CamAPS data.
 *
 * In V2 (root), this is driven by:
 * - Xposed hook on Gson.toJson(Object) → captures SyncDecryptedDTO
 * - Frida script hook on the same method (see docs/points-interception-donnees.md)
 *
 * The intercepted JSON payloads are parsed here to extract CamAPSData,
 * which is then injected into xDrip+ via CamAPSProcessor.
 *
 * JSON structures documented in upload-medical-et-cloud.md:
 * - SyncDecryptedDTO → contains SyncInputObject[] with Payload (clear text)
 * - CGMReadingDTO → glucose reading with 14 fields
 * - TherapyEventDTO → 11 event types (bolus, carbs, basal, etc.)
 * - DeviceUploadDTO → pump/sensor configuration
 * - getRecommendation() → insulin recommendation
 */
public class CamAPSJsonInterceptor {

    private static final String TAG = "CamAPSJsonInterceptor";
    private boolean started = false;

    // C2: ISO-8601 date parsers compatible with all API levels (no java.time.*)
    //     CamAPS uses UTC timestamps in 3 observed formats
    private static final String[] ISO_FORMATS = {
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    };

    /** Parse an ISO-8601 timestamp string to epoch millis. Returns current time on failure. */
    private static long parseIsoTimestamp(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return 0;
        for (String fmt : ISO_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(dateStr).getTime();
            } catch (Exception ignored) {}
        }
        Log.w("CamAPSJsonInterceptor", "Cannot parse timestamp: " + dateStr);
        return System.currentTimeMillis();
    }

    /**
     * Start the interceptor. On a rooted device with Xposed:
     * - Hook Gson.toJson() to capture all DTOs before encryption
     * On a rooted device with Frida:
     * - The Frida script handles the hook externally and calls back via
     *   CamAPSRootCollector.onSyncDecryptedCaptured()
     */
    public void start() {
        if (started) return;
        started = true;
        Log.i(TAG, "JSON interceptor started (requires Xposed or Frida for actual hooks)");
        Log.i(TAG, "Frida script: see docs/points-interception-donnees.md Point 2");
    }

    public void stop() {
        started = false;
    }

    /**
     * Parse a SyncDecryptedDTO JSON payload into CamAPSData batch.
     * The payload contains SyncInputObject[] with individual medical data DTOs
     * serialized in their Payload fields.
     */
    public List<CamAPSData> parseSyncPayload(String json) {
        List<CamAPSData> results = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray objects = root.optJSONArray("syncInputObjects");
            if (objects == null) {
                // Try alternate field names (obfuscation resilience)
                objects = root.optJSONArray("items");
            }
            if (objects == null) {
                objects = root.optJSONArray("objects");
            }

            if (objects != null) {
                for (int i = 0; i < objects.length(); i++) {
                    JSONObject obj = objects.getJSONObject(i);
                    CamAPSData data = parseSyncInputObject(obj);
                    if (data != null) {
                        data.sourceVersion = "V2-JSON";
                        results.add(data);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing sync payload: " + e.getMessage());
        }
        return results;
    }

    private CamAPSData parseSyncInputObject(JSONObject obj) {
        CamAPSData data = new CamAPSData();
        try {
            // Try to parse the inner Payload JSON string
            String payload = obj.optString("payload");
            if (payload == null || payload.isEmpty()) {
                payload = obj.optString("data");
            }
            if (payload == null || payload.isEmpty()) {
                payload = obj.optString("content");
            }

            if (payload != null && !payload.isEmpty()) {
                JSONObject inner = new JSONObject(payload);

                // CGMReadingDTO fields
                double egvMmol = inner.optDouble("egvMmolL", -1);
                if (egvMmol <= 0) {
                    long egv = inner.optLong("egv", -1);
                    if (egv > 0) {
                        egvMmol = egv / 1000.0 / 18.0; // mg/dL*1000 → mmol/L
                    }
                }
                if (egvMmol > 0) {
                    data.glucoseMmol = egvMmol;
                }

                long egvTime = inner.optLong("egvSystemTime", 0);
                if (egvTime <= 0) {
                    egvTime = inner.optLong("recordedSystemTime", 0);
                }
                if (egvTime <= 0) {
                    // C2: replaced java.time.* (API 26+) with SimpleDateFormat-based helper
                    String dateStr = inner.optString("egvSystemTime", null);
                    if (dateStr != null && !dateStr.isEmpty()) {
                        egvTime = parseIsoTimestamp(dateStr);
                    }
                }
                data.glucoseTimestamp = egvTime > 0 ? egvTime : System.currentTimeMillis();

                // TherapyEventDTO fields
                int eventType = inner.optInt("eventTypeId", -1);
                if (eventType <= 0) {
                    String eventStr = inner.optString("eventType", null);
                    if (eventStr != null) {
                        if (eventStr.toLowerCase().contains("bolus")) eventType = 5;
                        else if (eventStr.toLowerCase().contains("carb")) eventType = 8;
                        else if (eventStr.toLowerCase().contains("basal")) eventType = 17;
                    }
                }

                double value = inner.optDouble("value", -1);
                if (value <= 0) {
                    value = inner.optDouble("amountOfBolus", -1);
                }
                if (value <= 0) {
                    value = inner.optDouble("carbs", -1);
                }

                // Map event type to data fields
                if (eventType == 5 || eventType == 6 || eventType == 7) {
                    data.bolusDelivered = value > 0 ? value : inner.optDouble("amountOfBolus", -1);
                    data.bolusTimestamp = data.glucoseTimestamp;
                } else if (eventType == 8) {
                    data.carbsEntered = value > 0 ? value : inner.optDouble("carbs", -1);
                    data.carbsTimestamp = data.glucoseTimestamp;
                } else if (eventType == 17) {
                    data.basalRate = value > 0 ? value : inner.optDouble("rate", -1);
                }

                // IOB/COB from bolus info
                JSONObject bolusInfo = inner.optJSONObject("bolusInfo");
                if (bolusInfo != null) {
                    data.iob = bolusInfo.optDouble("iob", -1);
                }

                // Pump device info
                JSONObject device = inner.optJSONObject("deviceSettings");
                if (device != null) {
                    // Device settings is typically a key-value list
                }

                // m4: only return data if at least one medical field was populated
                if (data.hasGlucose() || data.hasIOB() || data.hasCOB()
                        || data.hasBasalRate() || data.hasBolus()) {
                    return data;
                }
                Log.d(TAG, "parseSyncInputObject: payload recognized but no medical fields found");
                return null;
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not parse sync input object: " + e.getMessage());
        }
        return null;
    }

    /**
     * Parse the JSON string returned by getRecommendation() JNI call.
     * Format expected: {"recommendation": "...", "insulin": X.X, "confidence": X.X}
     */
    public CamAPSData parseRecommendation(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            CamAPSData data = new CamAPSData();
            data.sourceVersion = "V2-JNI";

            double insulin = obj.optDouble("insulin", -1);
            if (insulin <= 0) {
                insulin = obj.optDouble("recommendedDose", -1);
            }
            if (insulin <= 0) {
                // Try parsing from a text recommendation like "Deliver 2.5U"
                String rec = obj.optString("recommendation", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "([\\d.,]+)\\s*U").matcher(rec);
                if (m.find()) {
                    insulin = Double.parseDouble(m.group(1).replace(',', '.'));
                }
            }

            if (insulin > 0) {
                // M3: do NOT store as bolusDelivered — a recommendation ≠ a delivered bolus
                //     Storing as bolusDelivered would inject false therapy data into Nightscout
                //     TODO: add CamAPSData.suggestedBolus field when CamAPSProcessor supports it
                Log.d(TAG, "Recommendation captured: " + insulin + "U (not stored as bolus)");
                data.algorithmState = "recommendation:" + insulin + "U";
                data.bolusTimestamp = System.currentTimeMillis();
            }

            // Also capture algorithm state if present
            data.algorithmState = obj.optString("state", "");

            return data;
        } catch (Exception e) {
            Log.d(TAG, "Could not parse recommendation: " + e.getMessage());
        }
        return null;
    }
}
