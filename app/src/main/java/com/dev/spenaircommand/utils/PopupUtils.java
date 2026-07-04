package com.dev.spenaircommand.utils;

import android.app.ActivityOptions;
import android.os.Bundle;

/**
 * Builds an ActivityOptions bundle that tells the system to open the
 * activity in WINDOWING_MODE_FREEFORM (Samsung Pop-up View).
 *
 * Samsung One UI maps WINDOWING_MODE_FREEFORM (5) to its floating
 * "Pop-up View" window — the same mode triggered when the user drags
 * an app icon from the taskbar or uses the three-dot pop-up shortcut.
 */
public class PopupUtils {

    /** android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM */
    public static final int WINDOWING_MODE_FREEFORM = 5;

    /**
     * Takes an existing ActivityOptions bundle (may be null) and injects
     * the freeform windowing mode into it, returning the updated bundle.
     */
    public static Bundle injectPopupMode(Bundle existingOpts) {
        try {
            ActivityOptions opts;
            if (existingOpts != null) {
                opts = ActivityOptions.fromBundle(existingOpts);
            } else {
                opts = ActivityOptions.makeBasic();
            }

            // Standard API 28+ — sets the windowing mode for the launched activity
            opts.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

            Bundle result = opts.toBundle();

            // Samsung-specific extra recognised by One UI's window manager
            result.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM);
            // Samsung SemActivityOptions compat key
            result.putBoolean("sem.activity.popupWindow", true);

            return result;
        } catch (Throwable t) {
            Logger.error(t);
            // Return a minimal bundle that at least tries the Samsung key
            Bundle fallback = new Bundle();
            fallback.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM);
            fallback.putBoolean("sem.activity.popupWindow", true);
            return fallback;
        }
    }
}
