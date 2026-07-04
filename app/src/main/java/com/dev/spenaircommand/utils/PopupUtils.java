package com.dev.spenaircommand.utils;

import android.app.ActivityOptions;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * Builds an ActivityOptions bundle that tells Samsung One UI to open the
 * activity in WINDOWING_MODE_FREEFORM (Pop-up View = mode 5).
 *
 * setLaunchWindowingMode() and fromBundle() are hidden (@SystemApi) APIs,
 * so we reach them via reflection — the only approach that compiles against
 * the public SDK stub used in Xposed / LSPosed modules.
 */
public class PopupUtils {

    /** android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM */
    private static final int WINDOWING_MODE_FREEFORM = 5;

    /** Cached reflection handles — resolved once and reused */
    private static Method sSetLaunchWindowingMode;
    private static Method sFromBundle;
    private static boolean sInitDone = false;

    private static void init() {
        if (sInitDone) return;
        sInitDone = true;
        try {
            // ActivityOptions.setLaunchWindowingMode(int) — added in API 28
            sSetLaunchWindowingMode = ActivityOptions.class
                    .getDeclaredMethod("setLaunchWindowingMode", int.class);
            sSetLaunchWindowingMode.setAccessible(true);
        } catch (Throwable t) {
            Logger.error(t);
        }
        try {
            // ActivityOptions.fromBundle(Bundle) — hidden factory method
            sFromBundle = ActivityOptions.class
                    .getDeclaredMethod("fromBundle", Bundle.class);
            sFromBundle.setAccessible(true);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    /**
     * Takes an existing options bundle (may be null) and injects
     * WINDOWING_MODE_FREEFORM so the activity opens as a popup window.
     */
    public static Bundle injectPopupMode(Bundle existing) {
        init();

        try {
            ActivityOptions opts = null;

            // Try to restore existing options so we don't lose display-id etc.
            if (existing != null && sFromBundle != null) {
                try {
                    opts = (ActivityOptions) sFromBundle.invoke(null, existing);
                } catch (Throwable ignored) { }
            }
            if (opts == null) {
                opts = ActivityOptions.makeBasic();
            }

            // Inject freeform windowing mode via reflection
            if (sSetLaunchWindowingMode != null) {
                sSetLaunchWindowingMode.invoke(opts, WINDOWING_MODE_FREEFORM);
                Logger.log("setLaunchWindowingMode(5) injected via reflection");
            }

            Bundle result = opts.toBundle();

            // Samsung One UI extra keys for popup window
            result.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM);
            result.putBoolean("sem.activity.popupWindow", true);

            return result;

        } catch (Throwable t) {
            Logger.error(t);

            // Minimal fallback — at least try the Samsung extras
            Bundle fallback = (existing != null) ? existing : new Bundle();
            fallback.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM);
            fallback.putBoolean("sem.activity.popupWindow", true);
            return fallback;
        }
    }
}
