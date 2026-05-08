package com.eveningoutpost.dexdrip.camaps;

/**
 * Interface for CamAPS data collectors.
 * V1: AccessibilityService scraping the CamAPS UI.
 * V2: Root-based direct database + JNI access.
 */
public interface CamAPSCollector {

    /** Called when new data has been collected. Implementations must be fast (no blocking I/O). */
    void onDataCollected(CamAPSData data);

    /** Start collecting. Called when xDrip+ starts or when user enables the bridge. */
    void start();

    /** Stop collecting. Called when user disables the bridge. */
    void stop();

    /** @return true if this collector is currently active */
    boolean isActive();
}
