package com.eveningoutpost.dexdrip.camaps;

/**
 * Unified data model for all CamAPS extracted data.
 * Used by both V1 (AccessibilityService) and V2 (root/Frida) collectors.
 */
public class CamAPSData {

    // Glucose
    public double glucoseMmol = -1;
    public long glucoseTimestamp = 0;
    public String trend = "";            // DoubleUp, SingleUp, Flat, SingleDown, DoubleDown, etc.

    // Insulin
    public double iob = -1;              // Insulin On Board (units)
    public double basalRate = -1;        // Current basal rate (U/h)
    public double bolusDelivered = -1;   // Last bolus amount (units)
    public long bolusTimestamp = 0;

    // Carbs
    public double cob = -1;              // Carbs On Board (grams)
    public double carbsEntered = -1;     // Last carbs entry (grams)
    public long carbsTimestamp = 0;

    // Pump
    public double pumpBattery = -1;      // Battery percentage (0-100)
    public double pumpReservoir = -1;    // Reservoir remaining (units)

    // Status
    public boolean autoMode = false;
    public String cgmSource = "";        // "Dexcom G6", "Libre 3", etc.
    public String algorithmState = "";   // V2 only

    // Source identification
    public String sourceVersion = "V1";  // "V1" or "V2"
    public long collectedAt = System.currentTimeMillis();

    public boolean hasGlucose() {
        return glucoseMmol > 0 && glucoseTimestamp > 0;
    }

    public boolean hasIOB() {
        return iob >= 0;
    }

    public boolean hasCOB() {
        return cob >= 0;
    }

    public boolean hasBasalRate() {
        return basalRate > 0;
    }

    public boolean hasBolus() {
        return bolusDelivered > 0 && bolusTimestamp > 0;
    }

    public boolean hasCarbs() {
        return carbsEntered > 0 && carbsTimestamp > 0;
    }

    public boolean hasPumpBattery() {
        return pumpBattery >= 0;
    }

    public boolean hasPumpReservoir() {
        return pumpReservoir >= 0;
    }

    /** Convert mmol/L to mg/dL */
    public double getGlucoseMgdl() {
        if (glucoseMmol <= 0) return -1;
        return Math.round(glucoseMmol * 18.0 * 10.0) / 10.0;
    }

    /** Map trend arrow text to xDrip+ slope name */
    public String getTrendSlopeName() {
        if (trend == null || trend.isEmpty()) return "";
        String t = trend.toLowerCase();
        if (t.contains("double") && (t.contains("up") || t.contains("rising"))) return "DoubleUp";
        if (t.contains("double") && (t.contains("down") || t.contains("falling"))) return "DoubleDown";
        if (t.contains("single") && (t.contains("up") || t.contains("rising"))) return "SingleUp";
        if (t.contains("single") && (t.contains("down") || t.contains("falling"))) return "SingleDown";
        if (t.contains("fortyfive") && (t.contains("up") || t.contains("rising"))) return "FortyFiveUp";
        if (t.contains("fortyfive") && (t.contains("down") || t.contains("falling"))) return "FortyFiveDown";
        if (t.contains("flat") || t.contains("stable") || t.contains("steady")) return "Flat";
        if (t.contains("not comput") || t.contains("out of range") || t.contains("none")) return "NOT_COMPUTABLE";
        // Direct mappings
        if (t.equals("doubleup")) return "DoubleUp";
        if (t.equals("doubledown")) return "DoubleDown";
        if (t.equals("singleup")) return "SingleUp";
        if (t.equals("singledown")) return "SingleDown";
        if (t.equals("fortyfiveup")) return "FortyFiveUp";
        if (t.equals("fortyfivedown")) return "FortyFiveDown";
        return "";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CamAPSData[");
        if (hasGlucose()) sb.append("glucose=").append(glucoseMmol).append("mmol/L (").append(getGlucoseMgdl()).append("mg/dL) trend=").append(trend).append(" ");
        if (hasIOB()) sb.append("IOB=").append(iob).append("U ");
        if (hasCOB()) sb.append("COB=").append(cob).append("g ");
        if (hasBasalRate()) sb.append("basal=").append(basalRate).append("U/h ");
        if (hasPumpBattery()) sb.append("battery=").append(pumpBattery).append("% ");
        if (hasPumpReservoir()) sb.append("reservoir=").append(pumpReservoir).append("U ");
        sb.append("auto=").append(autoMode).append(" ");
        sb.append("v=").append(sourceVersion);
        sb.append("]");
        return sb.toString();
    }
}
