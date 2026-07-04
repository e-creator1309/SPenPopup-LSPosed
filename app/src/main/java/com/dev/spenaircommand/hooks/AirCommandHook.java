package com.dev.spenaircommand.hooks;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

import com.dev.spenaircommand.utils.Logger;
import com.dev.spenaircommand.utils.PopupUtils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks the Air Command launcher so every app shortcut opens in
 * Samsung Pop-up View (WINDOWING_MODE_FREEFORM = 5) instead of fullscreen.
 *
 * From decompiled APK (com.samsung.android.service.aircommand 7.6.34.7):
 *
 *   Obfuscated Launcher class  →  a5.i0   (compiled from: Launcher.java)
 *   App-shortcut launch path   →  a5.i0.A(Context, a, Intent)
 *                                    calls context.startActivity(intent)         [no opts]
 *                                 OR a5.i0.z(Context, Intent, UserHandle)
 *                                    calls startActivityAsUser via reflection
 *
 *   Hook strategy (two layers for full coverage):
 *   1. Hook a5.i0  method "A" — intercept BEFORE, inject ActivityOptions bundle
 *      carrying WINDOWING_MODE_FREEFORM into the intent (as a clip-data trick
 *      is not possible here, so we replace the call via XposedBridge).
 *   2. Fallback: hook android.content.ContextWrapper#startActivity(Intent,Bundle)
 *      — catches any launch path the obfuscated hook misses.
 */
public class AirCommandHook {

    /**
     * Obfuscated class name of the Launcher in APK version 7.6.34.7 (763407000).
     * If Samsung ships a new version with a different mapping, update this constant.
     * The fallback hook (layer 2) will keep working regardless.
     */
    private static final String LAUNCHER_CLASS = "a5.i0";

    /**
     * WINDOWING_MODE_FREEFORM = 5
     * This is the standard Android windowing mode that Samsung One UI maps
     * to its "Pop-up View" experience.
     */
    private static final int WINDOWING_MODE_FREEFORM = 5;

    public static void init(ClassLoader cl) {
        hookLauncherDirect(cl);
        hookContextStartActivity(cl);
        hookInstrumentation(cl);
        Logger.log("All hooks installed");
    }

    // ─────────────────────────────────────────────────────────────────
    // Layer 1 — Direct hook on obfuscated Launcher class
    // Intercepts: a5.i0.A(Context, Object/*shortcut*/, Intent)
    //             a5.i0.z(Context, Intent, UserHandle)
    // ─────────────────────────────────────────────────────────────────
    private static void hookLauncherDirect(ClassLoader cl) {
        try {
            Class<?> launcher = XposedHelpers.findClassIfExists(LAUNCHER_CLASS, cl);
            if (launcher == null) {
                Logger.log("Launcher class not found (" + LAUNCHER_CLASS + ") — relying on fallback hooks");
                return;
            }

            // Hook every static method that accepts an Intent parameter
            for (java.lang.reflect.Method m : launcher.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                boolean hasIntent = false;
                for (Class<?> p : params) {
                    if (p == Intent.class) { hasIntent = true; break; }
                }
                if (!hasIntent) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        injectPopupIntoIntentArgs(param.args);
                    }
                });
                Logger.log("Hooked Launcher." + m.getName());
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Layer 2 — Hook ContextWrapper.startActivity(Intent, Bundle)
    // Catches any launch path that reaches Context (including the
    // non-user-handle path  context.startActivity(intent)  in a5.i0.A)
    // ─────────────────────────────────────────────────────────────────
    private static void hookContextStartActivity(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    android.content.ContextWrapper.class,
                    "startActivity",
                    Intent.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Intent intent = (Intent) param.args[0];
                            if (intent == null) return;

                            // Only intercept NEW_TASK launches (Air Command pattern)
                            if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) return;

                            Bundle opts = (Bundle) param.args[1];
                            param.args[1] = PopupUtils.injectPopupMode(opts);
                            Logger.log("ContextWrapper.startActivity intercepted → popup mode");
                        }
                    }
            );
            Logger.log("Hook: ContextWrapper.startActivity(Intent, Bundle)");
        } catch (Throwable t) {
            Logger.error(t);
        }

        // Also hook the single-arg overload (no Bundle) → upgrade it
        try {
            XposedHelpers.findAndHookMethod(
                    android.content.ContextWrapper.class,
                    "startActivity",
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Intent intent = (Intent) param.args[0];
                            if (intent == null) return;
                            if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) return;

                            // Upgrade to two-arg call with popup bundle
                            Bundle popup = PopupUtils.injectPopupMode(null);
                            android.content.Context ctx = (android.content.Context) param.thisObject;
                            ctx.startActivity(intent, popup);
                            param.setResult(null); // skip original
                            Logger.log("ContextWrapper.startActivity(Intent) upgraded to popup");
                        }
                    }
            );
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Layer 3 — Hook Instrumentation.execStartActivity
    // This is the deepest reliable interception point before the
    // system receives the launch request.  Works even for obfuscated
    // or reflection-based callers (a5.i0.z → startActivityAsUser).
    // ─────────────────────────────────────────────────────────────────
    private static void hookInstrumentation(ClassLoader cl) {
        try {
            // execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)
            XposedHelpers.findAndHookMethod(
                    android.app.Instrumentation.class,
                    "execStartActivity",
                    android.content.Context.class,
                    android.os.IBinder.class,
                    android.os.IBinder.class,
                    android.app.Activity.class,
                    Intent.class,
                    int.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Intent intent = (Intent) param.args[4];
                            if (intent == null) return;
                            if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) return;

                            Bundle opts = (Bundle) param.args[6];
                            param.args[6] = PopupUtils.injectPopupMode(opts);
                            Logger.log("Instrumentation.execStartActivity → popup injected");
                        }
                    }
            );
            Logger.log("Hook: Instrumentation.execStartActivity");
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────
    private static void injectPopupIntoIntentArgs(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Intent) {
                Intent intent = (Intent) arg;
                intent.putExtra("android.activity.windowingMode", WINDOWING_MODE_FREEFORM);
            }
        }
    }
}
