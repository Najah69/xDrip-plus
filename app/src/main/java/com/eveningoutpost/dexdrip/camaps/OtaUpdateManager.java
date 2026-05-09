package com.eveningoutpost.dexdrip.camaps;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.eveningoutpost.dexdrip.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OTA (Over-The-Air) update via GitHub Releases for xDrip+ CamAPS Bridge.
 *
 * Vérifie la dernière release sur Najah69/xDrip-plus (repo public, pas de token),
 * compare avec le buildVersion actuel, télécharge l'APK et lance l'installation.
 *
 * Usage:
 *   OtaUpdateManager manager = new OtaUpdateManager(context);
 *   manager.checkForUpdate(result -> { ... });
 *   manager.downloadAndInstall(apkUrl, versionName, success -> { ... });
 */
public class OtaUpdateManager {

    private static final String TAG = "OtaUpdateManager";
    private static final String GITHUB_API = "https://api.github.com/repos/Najah69/xDrip-plus/releases/latest";

    private final Context context;

    public OtaUpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Result types ─────────────────────────────────────────────────────────

    public static class UpdateResult {
        public final boolean updateAvailable;
        public final String versionName;
        public final int versionCode;
        public final String releaseNotes;
        public final String apkUrl;
        public final String errorMessage;

        private UpdateResult(boolean updateAvailable, String versionName, int versionCode,
                             String releaseNotes, String apkUrl, String errorMessage) {
            this.updateAvailable = updateAvailable;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.releaseNotes = releaseNotes;
            this.apkUrl = apkUrl;
            this.errorMessage = errorMessage;
        }

        public static UpdateResult upToDate() {
            return new UpdateResult(false, null, 0, null, null, null);
        }

        public static UpdateResult available(String versionName, int versionCode,
                                              String releaseNotes, String apkUrl) {
            return new UpdateResult(true, versionName, versionCode, releaseNotes, apkUrl, null);
        }

        public static UpdateResult error(String message) {
            return new UpdateResult(false, null, 0, null, null, message);
        }
    }

    // ── Callback interface ───────────────────────────────────────────────────

    public interface UpdateCallback {
        void onResult(UpdateResult result);
    }

    public interface DownloadCallback {
        void onResult(boolean success);
    }

    // ── Check for update ─────────────────────────────────────────────────────

    /**
     * Vérifie si une mise à jour est disponible sur GitHub Releases.
     * xDrip+ est un repo public → pas besoin de token.
     */
    public void checkForUpdate(final UpdateCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "xDrip-CamAPS-OTA/" + BuildConfig.VERSION_NAME);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.e(TAG, "GitHub API returned HTTP " + code);
                    callback.onResult(UpdateResult.error("GitHub API error: HTTP " + code));
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();

                UpdateResult result = parseReleaseResponse(body.toString());
                callback.onResult(result);

            } catch (java.net.UnknownHostException e) {
                callback.onResult(UpdateResult.error("No internet connection"));
            } catch (Exception e) {
                Log.e(TAG, "Check update error: " + e.getMessage());
                callback.onResult(UpdateResult.error(e.getMessage() != null
                        ? e.getMessage() : "Unknown error"));
            }
        }, "OTA-CheckUpdate").start();
    }

    // ── Download & install ───────────────────────────────────────────────────

    /**
     * Télécharge l'APK depuis l'URL GitHub et lance l'installation.
     */
    public void downloadAndInstall(final String apkUrl, final String versionName,
                                   final DownloadCallback callback) {
        new Thread(() -> {
            try {
                File apkFile = new File(
                        context.getExternalCacheDir() != null
                                ? context.getExternalCacheDir()
                                : context.getCacheDir(),
                        "xdrip_camaps_update_" + versionName + ".apk");

                Log.i(TAG, "Downloading APK: " + apkUrl + " -> " + apkFile.getAbsolutePath());

                HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/octet-stream");
                conn.setRequestProperty("User-Agent", "xDrip-CamAPS-OTA/" + BuildConfig.VERSION_NAME);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(300_000); // 5 min for large APK

                if (conn.getResponseCode() != 200) {
                    Log.e(TAG, "Download failed: HTTP " + conn.getResponseCode());
                    callback.onResult(false);
                    return;
                }

                java.io.InputStream input = conn.getInputStream();
                FileOutputStream output = new FileOutputStream(apkFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                output.close();
                input.close();

                Log.i(TAG, "APK downloaded: " + apkFile.length() + " bytes");
                triggerInstall(apkFile);
                callback.onResult(true);

            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage());
                callback.onResult(false);
            }
        }, "OTA-Download").start();
    }

    // ── Install trigger ──────────────────────────────────────────────────────

    private void triggerInstall(File apkFile) {
        // Check INSTALL_PACKAGES permission (required on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Log.w(TAG, "INSTALL_PACKAGES permission not granted — opening settings");
                // Show toast on UI thread
                android.os.Handler mainHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                mainHandler.post(() -> {
                    Toast.makeText(context,
                            "Enable 'Install unknown apps' for xDrip+ to install updates",
                            Toast.LENGTH_LONG).show();
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + BuildConfig.APPLICATION_ID));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settingsIntent);
                });
                return;
            }
        }

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(context,
                    BuildConfig.APPLICATION_ID + ".provider", apkFile);
        } else {
            uri = Uri.fromFile(apkFile);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
        Log.i(TAG, "Installation triggered for " + apkFile.getName());
    }

    // ── Parse GitHub release ─────────────────────────────────────────────────

    private UpdateResult parseReleaseResponse(String body) {
        try {
            JSONObject release = new JSONObject(body);

            String tagName = release.optString("tag_name", "");
            String releaseNotes = release.optString("body", "");
            if (releaseNotes.length() > 500) releaseNotes = releaseNotes.substring(0, 500);

            // Chercher l'asset APK
            String apkUrl = null;
            org.json.JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "");
                        break;
                    }
                }
            }

            if (apkUrl == null || apkUrl.isEmpty()) {
                Log.w(TAG, "No APK asset in release " + tagName);
                return UpdateResult.upToDate();
            }

            // Extraire versionCode du tag (format: v1.4-12345 ou v1.4)
            int versionCodeFromTag;
            String versionNameFromTag = tagName.replaceFirst("^v", "");
            String[] parts = versionNameFromTag.split("-");
            if (parts.length > 1) {
                try {
                    versionCodeFromTag = Integer.parseInt(parts[parts.length - 1]);
                } catch (NumberFormatException e) {
                    versionCodeFromTag = 1;
                }
            } else {
                versionCodeFromTag = 1;
            }

            int currentVersion = BuildConfig.VERSION_CODE;
            Log.i(TAG, "Release: " + tagName + " (code=" + versionCodeFromTag
                    + "), current buildVersion=" + currentVersion + ", APK: " + apkUrl);

            if (versionCodeFromTag > currentVersion) {
                return UpdateResult.available(versionNameFromTag, versionCodeFromTag,
                        releaseNotes, apkUrl);
            } else {
                return UpdateResult.upToDate();
            }

        } catch (Exception e) {
            Log.e(TAG, "Parse release error: " + e.getMessage());
            return UpdateResult.error("Failed to parse release: " + e.getMessage());
        }
    }
}
