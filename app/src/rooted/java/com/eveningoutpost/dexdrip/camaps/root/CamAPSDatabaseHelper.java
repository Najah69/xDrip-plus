package com.eveningoutpost.dexdrip.camaps.root;

import android.content.Context;
import android.util.Log;

import net.sqlcipher.database.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.eveningoutpost.dexdrip.camaps.CamAPSData;

// M5: load SQLCipher native lib once at class load time, not on every open() call

/**
 * Opens the CamAPS Room/SQLCipher database and extracts all medical data.
 *
 * REQUIREMENTS (V2 - ROOTED DEVICE):
 * - Root access to read /data/data/com.camdiab.fx_alert.mmoll/
 * - SQLCipher library (net.sqlcipher.database)
 * - Key extracted from CamAPS SharedPreferences XML
 *
 * Database schema (Room entities, obfuscated names mapped to known tables):
 * - glucose_readings / CGMReadingEntity → CGM readings with timestamps
 * - therapies / TherapyEventEntity → Bolus, carbs, basals
 * - accounts / AccountsEntity → Encrypted credentials (AES-256-GCM)
 *
 * Key storage: /data/data/com.camdiab.fx_alert.mmoll/shared_prefs/*.xml
 * The SQLCipher key is stored as a SharedPreference value (typically 32+ chars).
 */
public class CamAPSDatabaseHelper {

    private static final String TAG = "CamAPSDBHelper";

    // M5: native lib loaded once — System.loadLibrary() is safe to call multiple times
    //     but involves a JNI lookup on every call; static block guarantees single load
    static {
        System.loadLibrary("sqlcipher");
    }

    // CamAPS package — use the primary mmoll variant
    public static final String CAMAPS_PACKAGE = "com.camdiab.fx_alert.mmoll";
    public static final String CAMAPS_DATA_DIR = "/data/data/" + CAMAPS_PACKAGE;
    public static final String CAMAPS_DB_DIR = CAMAPS_DATA_DIR + "/databases";
    public static final String CAMAPS_PREFS_DIR = CAMAPS_DATA_DIR + "/shared_prefs";

    // Common database file names (try multiple, Room auto-generates names)
    private static final String[] DB_CANDIDATES = {
        "app_database.db",
        "camaps_db.db",
        "room_db.db",
        "my_life_db.db"
    };

    // Known table names (deobfuscated from Room @Entity annotations)
    // These are identified from the CamAPS decompiled source
    private static final String[] CGM_TABLES = {
        "CGMReadingEntity", "cgm_reading_entity", "glucose_readings",
        "glucose", "cgm_readings", "cgm"
    };

    private static final String[] THERAPY_TABLES = {
        "TherapyEventEntity", "therapy_event_entity", "therapies",
        "therapy", "events", "therapy_events"
    };
    // m1: ACCOUNT_TABLES removed — declared but never used (dead code)

    private SQLiteDatabase db;
    private String activeDbPath;
    private final Context context;

    public CamAPSDatabaseHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Open the CamAPS database. Requires root: the database file must be copied
     * to an accessible location first.
     *
     * @param dbPath Full path to the .db file (must be readable)
     * @param key SQLCipher key extracted from SharedPreferences
     * @return true if database opened successfully
     */
    public boolean open(String dbPath, String key) {
        try {
            // M2: use OPEN_READONLY — openOrCreateDatabase would silently create
            //     an empty file on a wrong path, making all reads return 0 results
            db = SQLiteDatabase.openDatabase(dbPath, key, null,
                    SQLiteDatabase.OPEN_READONLY);
            activeDbPath = dbPath;
            Log.i(TAG, "SQLCipher database opened: " + dbPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open database: " + e.getMessage());
            return false;
        }
    }

    /**
     * Attempt to locate and open the CamAPS database using an extracted key.
     * Scans known database file names.
     */
    public boolean autoOpen(String key) {
        for (String candidate : DB_CANDIDATES) {
            String path = CAMAPS_DB_DIR + "/" + candidate;
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                return open(path, key);
            }
        }
        // Try listing the directory for any .db file
        File dir = new File(CAMAPS_DB_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".db"));
            if (files != null) {
                for (File file : files) {
                    if (open(file.getAbsolutePath(), key)) {
                        return true;
                    }
                }
            }
        }
        Log.e(TAG, "No readable database found in " + CAMAPS_DB_DIR);
        return false;
    }

    /**
     * Extract all CGM readings from the database.
     * Maps to CGMReadingDTO fields: Egv (mg/dL*1000), EgvMmolL (mmol/L),
     * EgvSystemTime (timestamp), Rate, PredictedEgv, AlgorithmState.
     */
    public List<CamAPSData> extractAllCGMReadings() {
        List<CamAPSData> results = new ArrayList<>();
        if (db == null || !db.isOpen()) return results;

        String table = findTable(CGM_TABLES);
        if (table == null) {
            Log.w(TAG, "No CGM table found");
            return results;
        }

        try {
            String[] columnSets = {
                "egvMmolL, egvSystemTime",
                "egv_mmol_l, egv_system_time",
                "value, timestamp",
                "glucoseValue, recordedAt",
                "reading, time"
            };

            String columns = null;
            for (String cols : columnSets) {
                try {
                    db.rawQuery("SELECT " + cols + " FROM " + table + " LIMIT 1", null).close();
                    columns = cols;
                    break;
                } catch (Exception ignored) {}
            }
            if (columns == null) columns = "*";

            // m3: ORDER BY explicit column name when known — positional ORDER BY 2 is fragile
            String orderBy = columns.equals("*") ? "2" : columns.split(",")[1].trim();

            android.database.Cursor cursor = db.rawQuery(
                "SELECT " + columns + " FROM " + table + " ORDER BY " + orderBy + " DESC LIMIT 2880", null);

            // C1: try-finally guarantees cursor.close() even if an exception is thrown inside the loop
            try {
                while (cursor.moveToNext()) {
                    CamAPSData data = new CamAPSData();
                    data.sourceVersion = "V2-DB";
                    if (columns.equals("*")) {
                        data = extractFromWildcardCursor(cursor);
                    } else {
                        data.glucoseMmol = cursor.getDouble(0);
                        try {
                            data.glucoseTimestamp = cursor.getLong(1);
                        } catch (Exception e) {
                            data.glucoseTimestamp = System.currentTimeMillis();
                        }
                    }
                    if (data.hasGlucose()) results.add(data);
                }
            } finally {
                cursor.close();
            }
            Log.i(TAG, "Extracted " + results.size() + " CGM readings");
        } catch (Exception e) {
            Log.e(TAG, "Error extracting CGM readings: " + e.getMessage());
        }
        return results;
    }

    /**
     * M1: Incremental CGM extraction — only rows newer than sinceTimestamp.
     * Use this in polling loops instead of extractAllCGMReadings() to avoid
     * full table scans every 60 seconds.
     *
     * @param sinceTimestamp epoch millis of the last known reading (0 = all)
     */
    public List<CamAPSData> extractCGMReadingsSince(long sinceTimestamp) {
        List<CamAPSData> results = new ArrayList<>();
        if (db == null || !db.isOpen()) return results;

        String table = findTable(CGM_TABLES);
        if (table == null) return results;

        try {
            String where = sinceTimestamp > 0
                    ? " WHERE egvSystemTime > " + sinceTimestamp : "";
            android.database.Cursor cursor = db.rawQuery(
                "SELECT egvMmolL, egvSystemTime FROM " + table
                    + where + " ORDER BY egvSystemTime DESC LIMIT 50", null);
            try {
                while (cursor.moveToNext()) {
                    CamAPSData data = new CamAPSData();
                    data.sourceVersion = "V2-DB";
                    data.glucoseMmol = cursor.getDouble(0);
                    data.glucoseTimestamp = cursor.getLong(1);
                    if (data.hasGlucose()) results.add(data);
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            // Column names may differ — fall back to full extraction with limit
            Log.d(TAG, "extractCGMReadingsSince fallback: " + e.getMessage());
            return extractAllCGMReadings();
        }
        return results;
    }

    /**
     * Extract therapy events (bolus, carbs, basal rate changes).
     * Maps to TherapyEventDTO fields: eventTypeId, value, eventDateTime.
     */
    public List<CamAPSData> extractAllTherapyEvents() {
        List<CamAPSData> results = new ArrayList<>();
        if (db == null || !db.isOpen()) return results;

        String table = findTable(THERAPY_TABLES);
        if (table == null) {
            Log.w(TAG, "No therapy table found");
            return results;
        }

        try {
            android.database.Cursor cursor = db.rawQuery(
                "SELECT * FROM " + table + " ORDER BY eventDateTime DESC LIMIT 500", null);

            // C1: try-finally guarantees cursor.close() even on exception
            try {
                while (cursor.moveToNext()) {
                    CamAPSData data = parseTherapyRow(cursor);
                    if (data != null) {
                        data.sourceVersion = "V2-DB";
                        results.add(data);
                    }
                }
            } finally {
                cursor.close();
            }
            Log.i(TAG, "Extracted " + results.size() + " therapy events");
        } catch (Exception e) {
            Log.e(TAG, "Error extracting therapy events: " + e.getMessage());
        }
        return results;
    }

    /**
     * Extract the SQLCipher key from CamAPS SharedPreferences XML.
     * On a rooted device, cat the XML file and grep for the key.
     *
     * Typical format: <string name="db_password">base64orhexkey</string>
     * or: <string name="cipher_key">...</string>
     */
    public static String extractKeyFromPrefs() {
        // This is run via root shell. The key is found by reading all XML files.
        // Common key names: "db_password", "cipher_key", "database_key", "passphrase"
        String[] keyNames = {"db_password", "cipher_key", "database_key",
                              "passphrase", "db_key", "sqlcipher_key", "room_key"};

        // In practice, the key extraction is done by reading the XML files:
        // adb shell "run-as com.camdiab.fx_alert.mmoll cat /data/data/com.camdiab.fx_alert.mmoll/shared_prefs/*.xml"
        // Then regex for: <string name="KEY_NAME">VALUE</string>

        Log.i(TAG, "Key extraction requires adb shell or root file access");
        Log.i(TAG, "Look for keys named: " + java.util.Arrays.toString(keyNames));
        Log.i(TAG, "Prefs path: " + CAMAPS_PREFS_DIR);
        return null; // Must be supplied externally
    }

    public void close() {
        if (db != null && db.isOpen()) {
            db.close();
            Log.i(TAG, "Database closed");
        }
    }

    // --- Internal helpers ---

    private String findTable(String[] candidates) {
        for (String table : candidates) {
            try {
                db.rawQuery("SELECT COUNT(*) FROM " + table, null).close();
                Log.d(TAG, "Found table: " + table);
                return table;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private CamAPSData extractFromWildcardCursor(android.database.Cursor cursor) {
        CamAPSData data = new CamAPSData();
        String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) {
            String col = columns[i].toLowerCase();
            if (col.contains("egvmmol") || col.contains("egv_mmol")) {
                data.glucoseMmol = cursor.getDouble(i);
            } else if (col.contains("egvsystemtime") || col.contains("system_time") || col.contains("timestamp")) {
                data.glucoseTimestamp = cursor.getLong(i);
            } else if ((col.contains("glucose") || col.contains("value") || col.contains("reading")) && data.glucoseMmol <= 0) {
                double val = cursor.getDouble(i);
                if (val > 2 && val < 25) data.glucoseMmol = val;
            }
        }
        return data;
    }

    private CamAPSData parseTherapyRow(android.database.Cursor cursor) {
        CamAPSData data = new CamAPSData();
        String[] columns = cursor.getColumnNames();

        for (int i = 0; i < columns.length; i++) {
            String col = columns[i].toLowerCase();
            int type = cursor.getType(i);

            if (col.contains("eventtype") || col.contains("event_type")) {
                try {
                    int eventTypeId = cursor.getInt(i);
                    parseEventType(eventTypeId, cursor, data);
                } catch (Exception e) {
                    // String type
                    parseEventType(cursor.getString(i), cursor, data);
                }
            } else if (col.contains("value")) {
                try { data.basalRate = cursor.getDouble(i); } catch (Exception ignored) {}
            } else if (col.contains("timestamp") || col.contains("datetime") || col.contains("time")) {
                try { data.glucoseTimestamp = cursor.getLong(i); } catch (Exception ignored) {}
            }
        }

        if (data.hasGlucose() || data.hasIOB() || data.hasCOB() || data.hasBasalRate() || data.hasBolus()) {
            return data;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private void parseEventType(int eventTypeId, android.database.Cursor cursor, CamAPSData data) {
        // CamAPS therapy event type IDs:
        // 2=ManualBG, 5=Bolus, 6=ExtendedBolus, 7=CombinedBolus,
        // 8=Carbohydrates, 17=BasalRate, 19=InfusionSetChange,
        // 21=Rewind, 28=EaseOff, 29=Boost, 30=AutoMode
        if (eventTypeId == 5 || eventTypeId == 6 || eventTypeId == 7) {
            extractBolusValue(cursor, data);
        } else if (eventTypeId == 8) {
            extractCarbsValue(cursor, data);
        }
    }

    private void parseEventType(String eventType, android.database.Cursor cursor, CamAPSData data) {
        if (eventType == null) return;
        String t = eventType.toLowerCase();
        if (t.contains("bolus")) extractBolusValue(cursor, data);
        else if (t.contains("carb") || t.contains("meal")) extractCarbsValue(cursor, data);
        else if (t.contains("basal")) extractBasalValue(cursor, data);
    }

    private void extractBolusValue(android.database.Cursor cursor, CamAPSData data) {
        String[] cols = cursor.getColumnNames();
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].toLowerCase();
            if ((c.contains("amount") || c.contains("value") || c.contains("dose") || c.contains("units"))) {
                try {
                    double val = cursor.getDouble(i);
                    if (val > 0 && val < 100) {
                        data.bolusDelivered = val;
                        data.bolusTimestamp = data.glucoseTimestamp;
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void extractCarbsValue(android.database.Cursor cursor, CamAPSData data) {
        String[] cols = cursor.getColumnNames();
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].toLowerCase();
            if (c.contains("carbs") || c.contains("grams") || c.contains("cho") || c.contains("amount")) {
                try {
                    double val = cursor.getDouble(i);
                    if (val > 0 && val < 500) {
                        data.carbsEntered = val;
                        data.carbsTimestamp = data.glucoseTimestamp;
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void extractBasalValue(android.database.Cursor cursor, CamAPSData data) {
        String[] cols = cursor.getColumnNames();
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].toLowerCase();
            if (c.contains("rate") || c.contains("basal") || c.contains("value")) {
                try {
                    double val = cursor.getDouble(i);
                    if (val > 0 && val < 50) {
                        data.basalRate = val;
                    }
                } catch (Exception ignored) {}
            }
        }
    }
}
