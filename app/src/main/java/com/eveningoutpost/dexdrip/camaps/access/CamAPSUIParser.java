package com.eveningoutpost.dexdrip.camaps.access;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.eveningoutpost.dexdrip.camaps.CamAPSData;

/**
 * Parses CamAPS AccessibilityNodeInfo tree into CamAPSData.
 *
 * Scrapes the CamAPS main screen for:
 * - Glucose value + trend arrow
 * - IOB (Insulin On Board)
 * - COB (Carbs On Board)
 * - Basal rate
 * - Bolus info
 * - Pump battery/reservoir
 * - Auto mode status
 */
public class CamAPSUIParser {

    private static final String TAG = "CamAPSUIParser";

    // CamAPS resource IDs (from decompiled layouts)
    private static final String RES_GLUCOSE_VALUE = "com.camdiab.fx_alert.mmoll:id/2131296807";
    private static final String RES_TREND_ARROW = "com.camdiab.fx_alert.mmoll:id/2131297274";
    private static final String RES_AUTO_MODE = "com.camdiab.fx_alert.mmoll:id/2131296378";
    private static final String RES_IOB_VALUE = "com.camdiab.fx_alert.mmoll:id/2131297486";
    private static final String RES_BASAL_COB_VALUE = "com.camdiab.fx_alert.mmoll:id/2131297482";
    private static final String RES_UNIT_LABEL = "com.camdiab.fx_alert.mmoll:id/2131296808";

    // Fallback: scan all TextViews for recognizable patterns
    private static final String[] GLUCOSE_CONTAINER_IDS = {
        RES_GLUCOSE_VALUE,
        "com.camdiab.fx_alert.mmoll:id/2131297074"  // widget glucose value
    };

    private static final String[] TREND_IDS = {
        RES_TREND_ARROW
    };

    private static final String[] IOB_CONTAINER_IDS = {
        RES_IOB_VALUE
    };

    private static final String[] BASAL_COB_IDS = {
        RES_BASAL_COB_VALUE
    };

    /**
     * Parse the AccessibilityNodeInfo tree starting from root.
     */
    public static CamAPSData parse(AccessibilityNodeInfo root) {
        if (root == null) return null;

        // First try: known resource IDs
        CamAPSData data = new CamAPSData();
        boolean found = false;

        found |= findGlucoseValue(root, data);
        found |= findTrend(root, data);
        found |= findIOB(root, data);
        found |= findBasalOrCOB(root, data);
        found |= findAutoMode(root, data);

        // Second try: full tree scan as fallback (catches custom UI / different IDs)
        if (!found) {
            found = fullTreeScan(root, data);
        }

        if (!found) return null;

        data.collectedAt = System.currentTimeMillis();
        data.sourceVersion = "V1-Access";

        Log.i(TAG, "Parsed UI: glucose=" + data.glucoseMmol + " mmol, IOB=" + data.iob
            + " U, basal=" + data.basalRate + " U/h, auto=" + data.autoMode);
        return data;
    }

    /** Full recursive tree scan — collects ALL text nodes to find glucose/IOB/COB patterns. */
    private static boolean fullTreeScan(AccessibilityNodeInfo root, CamAPSData data) {
        java.util.List<String> allTexts = new java.util.ArrayList<>();
        java.util.List<String> allDescs = new java.util.ArrayList<>();
        collectAllTexts(root, allTexts, allDescs);

        boolean found = false;

        for (String text : allTexts) {
            if (text == null || text.isEmpty()) continue;
            String t = text.trim();

            // Glucose: "5.4" or "5,4" in mmol range, or "98" in mg/dL range
            if (t.matches("^\\d{1,2}[.,]\\d$")) {
                double v = parseDouble(t);
                if (v >= 2.0 && v <= 22.0 && data.glucoseMmol <= 0) {
                    data.glucoseMmol = v;
                    data.glucoseTimestamp = System.currentTimeMillis();
                    found = true;
                    continue;
                }
            }
            // Glucose mg/dL: e.g. "98", "145", "250"
            if (t.matches("^\\d{2,3}$")) {
                double v = parseDouble(t);
                if (v >= 40 && v <= 500 && data.glucoseMmol <= 0) {
                    data.glucoseMmol = v / 18.0;
                    data.glucoseTimestamp = System.currentTimeMillis();
                    found = true;
                    continue;
                }
            }
            // IOB: "2.3 U" or "2,3 U"
            if ((t.toLowerCase().contains("u") || t.toLowerCase().contains("iob") || t.toLowerCase().contains("active insulin"))
                && data.iob <= 0) {
                double v = parseDouble(t.replaceAll("[^\\d.,]", ""));
                if (v >= 0 && v < 50) {
                    data.iob = v;
                    found = true;
                    continue;
                }
            }
            // COB: "15 g" or "15g"
            if ((t.toLowerCase().contains("g") || t.toLowerCase().contains("cob") || t.toLowerCase().contains("carbs"))
                && data.cob <= 0 && data.carbsEntered <= 0) {
                double v = parseDouble(t.replaceAll("[^\\d.,]", ""));
                if (v > 0 && v < 500) {
                    data.cob = v;
                    found = true;
                    continue;
                }
            }
            // Basal: "1.05 U/h"
            if ((t.toLowerCase().contains("u/h") || t.toLowerCase().contains("basal"))
                && data.basalRate <= 0) {
                double v = parseDouble(t.replaceAll("[^\\d.,]", ""));
                if (v > 0 && v < 50) {
                    data.basalRate = v;
                    found = true;
                    continue;
                }
            }
            // Auto mode indicator
            if (t.toLowerCase().contains("auto") && (t.toLowerCase().contains("mode") || t.length() <= 6)) {
                data.autoMode = true;
                found = true;
            }
        }

        // Also check content descriptions (for trend arrows etc.)
        for (String desc : allDescs) {
            if (desc == null) continue;
            String d = desc.trim().toLowerCase();
            if (d.contains("trend") || d.contains("arrow") || d.contains("rising") ||
                d.contains("falling") || d.contains("stable") || d.contains("flat")) {
                data.trend = desc.trim();
                found = true;
                break;
            }
        }

        return found;
    }

    /** Recursively collect all text and contentDescription from the tree. */
    private static void collectAllTexts(AccessibilityNodeInfo node,
                                         java.util.List<String> texts,
                                         java.util.List<String> descs) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            texts.add(text.toString());
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            descs.add(desc.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectAllTexts(node.getChild(i), texts, descs);
        }
    }

    private static boolean findGlucoseValue(AccessibilityNodeInfo root, CamAPSData data) {
        for (String resId : GLUCOSE_CONTAINER_IDS) {
            AccessibilityNodeInfo node = findByViewId(root, resId);
            if (node != null) {
                String text = getText(node);
                if (text != null && !text.equals("---") && !text.isEmpty()) {
                    // Check if it's mmol/L or mg/dL by looking at unit label
                    AccessibilityNodeInfo unitNode = findByViewId(root, RES_UNIT_LABEL);
                    boolean isMmol = unitNode != null && getText(unitNode).contains("mmol/L");

                    double value = parseDouble(text);
                    if (value > 0) {
                        if (isMmol) {
                            data.glucoseMmol = value;
                        } else {
                            data.glucoseMmol = value / 18.0;
                        }
                        data.glucoseTimestamp = System.currentTimeMillis();
                        return true;
                    }
                }
            }
        }
        // Fallback: scan for glucose pattern in any TextView
        return findGlucoseByPattern(root, data);
    }

    private static boolean findGlucoseByPattern(AccessibilityNodeInfo root, CamAPSData data) {
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            String text = getText(child);
            if (text != null && text.matches("^\\d{1,2}[.,]\\d$")) {
                // Looks like a glucose value (e.g., "5.4" or "10,2")
                double value = parseDouble(text);
                if (value > 2.0 && value < 25.0) {
                    data.glucoseMmol = value;
                    data.glucoseTimestamp = System.currentTimeMillis();
                    return true;
                }
            }
            if (findGlucoseByPattern(child, data)) return true;
        }
        return false;
    }

    private static boolean findTrend(AccessibilityNodeInfo root, CamAPSData data) {
        for (String resId : TREND_IDS) {
            AccessibilityNodeInfo node = findByViewId(root, resId);
            if (node != null) {
                String desc = getContentDescription(node);
                if (desc != null && !desc.isEmpty()) {
                    data.trend = desc;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean findIOB(AccessibilityNodeInfo root, CamAPSData data) {
        for (String resId : IOB_CONTAINER_IDS) {
            AccessibilityNodeInfo node = findByViewId(root, resId);
            if (node != null) {
                String text = getText(node);
                if (text != null) {
                    // Parse "2.3 U" or "2,3 U"
                    String cleaned = text.replace(',', '.').replaceAll("[^\\d.]", "");
                    double value = parseDouble(cleaned);
                    if (value >= 0) {
                        data.iob = value;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean findBasalOrCOB(AccessibilityNodeInfo root, CamAPSData data) {
        for (String resId : BASAL_COB_IDS) {
            AccessibilityNodeInfo node = findByViewId(root, resId);
            if (node != null) {
                String text = getText(node);
                if (text != null) {
                    String cleaned = text.replace(',', '.').replaceAll("[^\\d.]", "");
                    double value = parseDouble(cleaned);
                    if (value > 0) {
                        // Heuristic: if value > 10, it's likely reservoir; if < 10, it's basal rate or COB
                        // This text field can show basal rate (U/h) or COB (g)
                        if (text.toLowerCase().contains("u/h") || text.toLowerCase().contains("u\\h")) {
                            data.basalRate = value;
                        } else if (text.toLowerCase().contains("g") || text.toLowerCase().contains("cob")) {
                            data.cob = value;
                        } else if (value < 15) {
                            // Could be basal rate
                            data.basalRate = value;
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean findAutoMode(AccessibilityNodeInfo root, CamAPSData data) {
        AccessibilityNodeInfo node = findByViewId(root, RES_AUTO_MODE);
        if (node != null) {
            String text = getText(node);
            if (text != null && text.toLowerCase().contains("auto")) {
                data.autoMode = true;
                return true;
            }
        }
        return false;
    }

    // --- Tree navigation helpers ---

    private static AccessibilityNodeInfo findByViewId(AccessibilityNodeInfo root, String viewId) {
        return root.findAccessibilityNodeInfosByViewId(viewId).isEmpty()
            ? null
            : root.findAccessibilityNodeInfosByViewId(viewId).get(0);
    }

    private static String getText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cs = node.getText();
        return cs != null ? cs.toString().trim() : null;
    }

    private static String getContentDescription(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cs = node.getContentDescription();
        return cs != null ? cs.toString().trim() : null;
    }

    private static double parseDouble(String text) {
        if (text == null || text.isEmpty()) return -1;
        try {
            return Double.parseDouble(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
