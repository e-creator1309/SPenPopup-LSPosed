package com.dev.spenaircommand.utils;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

public class Logger {

    private static final String TAG = "SPenPopup";

    public static void log(String msg) {
        XposedBridge.log("[SPenPopup] " + msg);
        Log.d(TAG, msg);
    }

    public static void error(Throwable t) {
        XposedBridge.log("[SPenPopup][ERROR] " + t.getMessage());
        Log.e(TAG, "Hook error", t);
    }
}
