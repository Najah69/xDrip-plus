package com.eveningoutpost.dexdrip.camaps;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.eveningoutpost.dexdrip.BuildConfig;
import com.eveningoutpost.dexdrip.models.JoH;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CamAPS Bridge — Debug Log Exporter
 *
 * Captures logcat filtered on CamAPS tags, saves to Downloads and optionally
 * uploads to a GitHub Gist for easy sharing during debugging sessions.
 *
 * Usage:
 *   CamAPSLogExporter.exportAndUpload(context);     // capture + upload to Gist
 *   CamAPSLogExporter.exportToFile(context);         // capture + save locally only
 *   CamAPSLogExporter.captureLogcat(maxLines)        // raw capture, returns String
 *
 * GitHub Gist token (optional — for secret gists):
 *   Settings → CamAPS Bridge → GitHub Token
 *   SharedPreferences key: camaps_github_gist_token
 *   Without token: public gist (still shareable via URL)
 *
 * Relevant logcat tags captured:
 *   CamAPSAccessSvc · CamAPSUIParser · CamAPSNotificationParser
 *   CamAPSProcessor · CamAPSDBHelper · CamAPSRootCollector
 *   CamAPSJsonInterceptor · CamAPSPreferences
 */
public class CamAPSLogExporter {

    private static final String TAG = "CamAPSLogExporter";

    // All CamAPS-related logcat tags to capture
    private static final String[] CAMAPS_TAGS = {
        "CamAPSAccessSvc",
        "CamAPSUIParser",
        "CamAPSNotificationParser",
        "CamAPSProcessor",
        "CamAPSDBHelper",
        "CamAPSRootCollector",
        "CamAPSJsonInterceptor",
        "CamAPSPreferences"
    };

    private static final int DEFAULT_MAX_LINES = 2000;
    private static final String GIST_API_URL = "https://api.github.com/gists";
    private static final String PREFS_TOKEN_KEY = "camaps_github_gist_token";

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    /**
     * Full export: capture logcat → save to Downloads → upload to GitHub Gist.
     * Runs on a background thread. Shows a toast with the Gist URL on completion.
     *
     * @param context Android context
     */
    public static void exportAndUpload(final Context context) {
        new Thread(() -> {
            try {
                String logContent = captureLogcat(DEFAULT_MAX_LINES);
                if (logContent.isEmpty()) {
                    JoH.static_toast_long("No CamAPS logs found — is the bridge active?");
                    return;
                }

                File savedFile = saveToFile(context, logContent);
                Log.i(TAG, "Logcat saved to: " + savedFile.getAbsolutePath());

                String token = getGistToken(context);
                // If token is set → secret Gist (URL-only access)
                // If no token    → public Gist (anonymous, still requires URL to find)
                boolean isSecret = token != null && !token.isEmpty();
                String gistUrl = uploadToGist(logContent, token, isSecret);

                if (gistUrl != null) {
                    Log.i(TAG, "Gist uploaded (" + (isSecret ? "secret" : "public") + "): " + gistUrl);
                    copyToClipboard(context, gistUrl);
                    JoH.static_toast_long("Log uploaded (" + (isSecret ? "secret" : "public")
                            + ")!\nURL copied to clipboard:\n" + gistUrl);
                } else {
                    // Fallback: share the local file
                    JoH.static_toast_long("Upload failed. File saved:\n" + savedFile.getName());
                    shareFile(context, savedFile);
                }
            } catch (Exception e) {
                Log.e(TAG, "Export error: " + e.getMessage());
                JoH.static_toast_long("Log export error: " + e.getMessage());
            }
        }, "CamAPS-LogExport").start();
    }

    /**
     * Local export only: capture logcat → save to Downloads → share via Android intent.
     *
     * @param context Android context
     */
    public static void exportToFile(final Context context) {
        new Thread(() -> {
            try {
                String logContent = captureLogcat(DEFAULT_MAX_LINES);
                if (logContent.isEmpty()) {
                    JoH.static_toast_long("No CamAPS logs found — is the bridge active?");
                    return;
                }
                File savedFile = saveToFile(context, logContent);
                JoH.static_toast_long("Log saved: " + savedFile.getName());
                shareFile(context, savedFile);
            } catch (Exception e) {
                Log.e(TAG, "Export to file error: " + e.getMessage());
                JoH.static_toast_long("Export error: " + e.getMessage());
            }
        }, "CamAPS-LogExport").start();
    }

    /**
     * Capture logcat filtered on all CamAPS tags.
     *
     * @param maxLines Maximum number of log lines to capture (0 = all available)
     * @return Captured log content as a String, or empty string if capture failed
     */
    public static String captureLogcat(int maxLines) {
        try {
            // Build logcat command: -d = dump and exit, -s = silent (tags only)
            StringBuilder cmd = new StringBuilder("logcat -d -s");
            for (String tag : CAMAPS_TAGS) {
                cmd.append(" ").append(tag).append(":V");
            }

            Process process = Runtime.getRuntime().exec(cmd.toString());
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder sb = new StringBuilder();
            sb.append("=== xDrip+ CamAPS Bridge Log ===\n");
            sb.append("Version: ").append(BuildConfig.VERSION_NAME).append("\n");
            sb.append("Build: ").append(BuildConfig.buildVersion).append("\n");
            sb.append("Captured: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
            sb.append("Tags: ").append(String.join(", ", CAMAPS_TAGS)).append("\n");
            sb.append("================================\n\n");

            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                lineCount++;
                if (maxLines > 0 && lineCount >= maxLines) {
                    sb.append("\n... (truncated at ").append(maxLines).append(" lines)");
                    break;
                }
            }
            reader.close();
            process.waitFor();

            Log.d(TAG, "Captured " + lineCount + " log lines");
            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "logcat capture error: " + e.getMessage());
            return "";
        }
    }

    // ── INTERNAL ──────────────────────────────────────────────────────────────

    private static File saveToFile(Context context, String content) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "camaps_debug_" + timestamp + ".txt";

        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(dir, filename);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        return file;
    }

    /**
     * Upload content to GitHub Gist API.
     *
     * @param content  Log content to upload
     * @param token    GitHub PAT with 'gist' scope — null for anonymous public gist
     * @param isSecret true = secret gist (URL-only access), false = public
     * @return Gist HTML URL, or null if upload failed
     */
    static String uploadToGist(String content, String token, boolean isSecret) {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            String filename = "camaps_debug_" + timestamp + ".txt";

            // Build JSON payload manually (avoid Gson dependency in main build)
            String escapedContent = content
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            String jsonPayload = "{"
                    + "\"description\":\"xDrip+ CamAPS Bridge Debug Log — " + timestamp + "\","
                    + "\"public\":" + !isSecret + ","
                    + "\"files\":{"
                    +   "\"" + filename + "\":{"
                    +     "\"content\":\"" + escapedContent + "\""
                    +   "}"
                    + "}"
                    + "}";

            URL url = new URL(GIST_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "xDrip-CamAPS-Bridge/" + BuildConfig.VERSION_NAME);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 201) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                return json.optString("html_url", null);
            } else {
                Log.e(TAG, "Gist API returned HTTP " + responseCode);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Gist upload error: " + e.getMessage());
            return null;
        }
    }

    private static void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("CamAPS Log URL", text));
            }
        } catch (Exception e) {
            Log.w(TAG, "Clipboard copy failed: " + e.getMessage());
        }
    }

    private static void shareFile(Context context, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    BuildConfig.APPLICATION_ID + ".provider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "xDrip+ CamAPS Debug Log");
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(shareIntent, "Share CamAPS Log")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.e(TAG, "Share error: " + e.getMessage());
        }
    }

    private static String getGistToken(Context context) {
        try {
            android.content.SharedPreferences prefs =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(context);
            return prefs.getString(PREFS_TOKEN_KEY, null);
        } catch (Exception e) {
            return null;
        }
    }
}
