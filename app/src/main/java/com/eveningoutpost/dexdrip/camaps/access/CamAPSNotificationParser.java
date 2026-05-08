package com.eveningoutpost.dexdrip.camaps.access;

import android.util.Log;

import com.eveningoutpost.dexdrip.camaps.CamAPSData;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses CamAPS Android notification text to extract IOB, basal rate,
 * pump reservoir, pump battery, and bolus information.
 *
 * CamAPS notification format (from string resource 2131756446):
 * "Active insulin: %1$s, Insulin infusion rate: %2$s, Pump reservoir: %3$s,
 *  Pump battery: %4$s, Bolus: %5$s, Pump connect: %6$s, Sensor data: %7$s"
 *
 * Target glucose notification: "Target glucose: %s"
 * Daily summary: "Insulin today: %1$s, Insulin yesterday: %2$s, Glucose today: %3$s, Glucose yesterday: %4$s"
 */
public class CamAPSNotificationParser {

    private static final String TAG = "CamAPSNotifParser";

    // Match "Active insulin: 2.3 U" or "Active insulin: 2,3 U" (European locale)
    private static final Pattern ACTIVE_INSULIN = Pattern.compile(
        "Active insulin:\\s*([\\d.,]+)\\s*U", Pattern.CASE_INSENSITIVE);

    // Match "Insulin infusion rate: 1.05 U/h"
    private static final Pattern INFUSION_RATE = Pattern.compile(
        "Insulin infusion rate:\\s*([\\d.,]+)\\s*U/h", Pattern.CASE_INSENSITIVE);

    // Match "Pump reservoir: 120.5 U"
    private static final Pattern PUMP_RESERVOIR = Pattern.compile(
        "Pump reservoir:\\s*([\\d.,]+)\\s*U", Pattern.CASE_INSENSITIVE);

    // Match "Pump battery: 87 %"
    private static final Pattern PUMP_BATTERY = Pattern.compile(
        "Pump battery:\\s*([\\d.,]+)\\s*%", Pattern.CASE_INSENSITIVE);

    // Match "Bolus: 1.5 U"
    private static final Pattern BOLUS = Pattern.compile(
        "Bolus:\\s*([\\d.,]+)\\s*U", Pattern.CASE_INSENSITIVE);

    // Match "Sensor data: 5 min" (gives data freshness)
    private static final Pattern SENSOR_DATA = Pattern.compile(
        "Sensor data:\\s*(\\d+)\\s*min", Pattern.CASE_INSENSITIVE);

    /**
     * Attempt to parse a CamAPS notification text line.
     * @param text The notification text (can be multiline, from expanded notification)
     * @return CamAPSData with extracted fields, or null if nothing was extracted
     */
    public static CamAPSData parse(String text) {
        if (text == null || text.isEmpty()) return null;

        CamAPSData data = new CamAPSData();
        boolean found = false;

        // Replace European comma decimal separator
        String normalized = text.replace(',', '.');

        found |= tryExtractDouble(ACTIVE_INSULIN, normalized, v -> data.iob = v);
        found |= tryExtractDouble(INFUSION_RATE, normalized, v -> data.basalRate = v);
        found |= tryExtractDouble(PUMP_RESERVOIR, normalized, v -> data.pumpReservoir = v);
        found |= tryExtractDouble(PUMP_BATTERY, normalized, v -> data.pumpBattery = v);
        found |= tryExtractDouble(BOLUS, normalized, v -> data.bolusDelivered = v);

        if (!found) return null;

        data.collectedAt = System.currentTimeMillis();
        data.sourceVersion = "V1-Notif";

        Log.d(TAG, "Parsed notification: " + data);
        return data;
    }

    private interface DoubleSetter {
        void set(double value);
    }

    private static boolean tryExtractDouble(Pattern pattern, String text, DoubleSetter setter) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                double value = Double.parseDouble(m.group(1));
                setter.set(value);
                return true;
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse number: " + m.group(1));
            }
        }
        return false;
    }
}
