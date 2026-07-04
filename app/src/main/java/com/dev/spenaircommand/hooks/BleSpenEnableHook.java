package com.dev.spenaircommand.hooks;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import com.dev.spenaircommand.utils.Logger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * BleSpenEnableHook — Force-enables BLE S Pen Remote on custom ROMs
 *
 * Root cause (jadx reverse engineering of com.samsung.android.service.aircommand v7.6.34.7):
 *
 *   RemoteSpenService.j(Context) has THREE gates before starting the BLE service:
 *
 *   Gate 1 → r8.c.a(Context)  [compiled from: ModelFeatures.java]
 *             → calls SemFloatingFeature.getBoolean("SEC_FLOATING_FEATURE_COMMON_SUPPORT_BLE_SPEN")
 *             → OR r8.c.k(Context): checks model feature list contains n.REMOTE
 *             On custom ROMs: floating_feature.xml missing this flag → false → service never starts
 *
 *   Gate 2 → z7.j.d(Context).b()  [compiled from: SettingsPreferenceManager.java]
 *             → reads Settings.System "spen_air_action" == 1
 *             On custom ROMs: Settings UI toggle hidden (because Gate 1 fails) → key = 0
 *
 *   Gate 3 → p7.e.h0(Context, boolean) [RemoteSpenMainController.canLaunch]
 *             → checks paired pen count (passes if pen was paired at least once)
 *
 *   Additionally:
 *   r8.c.k(Context) checks if ArrayList<n> from model config contains n.REMOTE
 *   This is parsed from SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_SPEN_GARAGE_SPEC
 *   On custom ROMs: this FloatingFeature string is null → model parse fails → no REMOTE
 *
 * Solution:
 *   1. Hook r8.c.a(Context)      → always return true  (Gate 1 bypass)
 *   2. Hook r8.c.j() / k(Context)→ always return true  (sub-checks)
 *   3. Hook z7.j.b()             → always return true  (Gate 2 bypass)
 *   4. Hook SemFloatingFeature.getBoolean(KEY_BLE_SPEN_SUPPORT) → true
 *   5. Hook SemFloatingFeature.getString(KEY_GARAGE_SPEC) → "davinci,button,airmotion,remote,..."
 *   6. Force Settings.System "spen_air_action" = 1 on app start
 *
 * Device: Samsung Galaxy Note 10+ (SM-N975F), S Pen model: davinci
 * Tested ROM: Custom Android 13 (Samsung OneUI base)
 */
public class BleSpenEnableHook {

    // Obfuscated class names — verified from jadx decompile of aircommand 7.6.34.7
    private static final String MODEL_FEATURES_CLASS    = "r8.c";  // ModelFeatures.java
    private static final String SETTINGS_PREF_MGR_CLASS = "z7.j";  // SettingsPreferenceManager.java
    private static final String FLOATING_FEATURE_CLASS  = "com.samsung.android.feature.SemFloatingFeature";

    // FloatingFeature keys that must return correct values
    private static final String KEY_BLE_SPEN_SUPPORT = "SEC_FLOATING_FEATURE_COMMON_SUPPORT_BLE_SPEN";
    private static final String KEY_SPEN_GARAGE_SPEC = "SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_SPEN_GARAGE_SPEC";

    // Note 10+ davinci full spec string:
    // Format parsed by r8.m.a(String): split(",")[0]=pen_name, contains("button"), contains("airmotion")
    // Format parsed by r8.g.a(String): also detects "remote" → adds n.REMOTE to feature ArrayList
    private static final String DAVINCI_GARAGE_SPEC = "davinci,button,airmotion,remote,attach_face";

    // Settings.System key checked by z7.j.b() → must be 1 for remote spen to be enabled
    private static final String SETTINGS_KEY = "spen_air_action";

    public static void init(ClassLoader cl, Context appContext) {
        hookGate1_ModelFeaturesTopLevel(cl);
        hookGate1_SubChecks(cl);
        hookGate2_SettingsPrefs(cl);
        hookSemFloatingFeature(cl);
        forceEnableSettingsKey(appContext);
        Logger.log("[BleSpenEnable] All BLE enable hooks installed — Note10+ remote should work now");
    }

    // ──────────────────────────────────────────────────────────────────
    // Gate 1 (top) — r8.c.a(Context) → always true
    // This is the first check in RemoteSpenService.j():
    //   if (!c.a(context)) return false; // "BLE SPen not supported"
    // ──────────────────────────────────────────────────────────────────
    private static void hookGate1_ModelFeaturesTopLevel(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(MODEL_FEATURES_CLASS, cl);
            if (cls == null) { Logger.log("[BleSpenEnable] r8.c not found"); return; }

            XposedHelpers.findAndHookMethod(cls, "a", Context.class,
                new XC_MethodReplacement() {
                    @Override protected Object replaceHookedMethod(MethodHookParam p) {
                        Logger.log("[BleSpenEnable] r8.c.a(Context) → true [Gate 1 bypassed]");
                        return true;
                    }
                });
            Logger.log("[BleSpenEnable] Hook: r8.c.a(Context) installed");
        } catch (Throwable t) { Logger.error(t); }
    }

    // ──────────────────────────────────────────────────────────────────
    // Gate 1 (sub) — r8.c.j() and r8.c.k(Context) → always true
    //   j() = SemFloatingFeature.getBoolean(KEY_BLE_SPEN_SUPPORT)
    //   k(Context) = model feature list contains n.REMOTE
    // ──────────────────────────────────────────────────────────────────
    private static void hookGate1_SubChecks(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(MODEL_FEATURES_CLASS, cl);
            if (cls == null) return;

            // Hook all static no-arg boolean methods in r8.c (covers j, m, l, etc.)
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam p) {
                            Logger.log("[BleSpenEnable] r8.c." + p.method.getName() + "() → true");
                            return true;
                        }
                    });
                }
            }

            // Hook r8.c.k(Context) specifically — model has REMOTE capability
            try {
                XposedHelpers.findAndHookMethod(cls, "k", Context.class,
                    new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam p) {
                            Logger.log("[BleSpenEnable] r8.c.k(Context) → true [REMOTE capability forced]");
                            return true;
                        }
                    });
            } catch (Throwable ignored) {}

            Logger.log("[BleSpenEnable] Hook: r8.c sub-checks installed");
        } catch (Throwable t) { Logger.error(t); }
    }

    // ──────────────────────────────────────────────────────────────────
    // Gate 2 — z7.j.b() → always true
    // SettingsPreferenceManager.isRemoteSpenEnabled()
    //   reads Settings.System.getInt(resolver, "spen_air_action") == 1
    // ──────────────────────────────────────────────────────────────────
    private static void hookGate2_SettingsPrefs(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(SETTINGS_PREF_MGR_CLASS, cl);
            if (cls == null) { Logger.log("[BleSpenEnable] z7.j not found"); return; }

            XposedHelpers.findAndHookMethod(cls, "b",
                new XC_MethodReplacement() {
                    @Override protected Object replaceHookedMethod(MethodHookParam p) {
                        Logger.log("[BleSpenEnable] z7.j.b() → true [Gate 2 bypassed]");
                        return true;
                    }
                });
            Logger.log("[BleSpenEnable] Hook: z7.j.b() installed");
        } catch (Throwable t) { Logger.error(t); }
    }

    // ──────────────────────────────────────────────────────────────────
    // System level — SemFloatingFeature hooks
    // Covers t8.b.a() and t8.b.e() which Samsung wraps around SemFloatingFeature
    //   getBoolean(KEY_BLE_SPEN_SUPPORT) → true
    //   getString(KEY_GARAGE_SPEC)       → "davinci,button,airmotion,remote,attach_face"
    // ──────────────────────────────────────────────────────────────────
    private static void hookSemFloatingFeature(ClassLoader cl) {
        try {
            Class<?> ffClass;
            try {
                ffClass = Class.forName(FLOATING_FEATURE_CLASS);
            } catch (ClassNotFoundException e) {
                ffClass = XposedHelpers.findClassIfExists(FLOATING_FEATURE_CLASS, cl);
            }
            if (ffClass == null) { Logger.log("[BleSpenEnable] SemFloatingFeature not found"); return; }

            final Class<?> ff = ffClass;

            // getBoolean(String) — return true for BLE support key
            try {
                XposedHelpers.findAndHookMethod(ff, "getBoolean", String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (KEY_BLE_SPEN_SUPPORT.equals(p.args[0])) {
                                p.setResult(true);
                                Logger.log("[BleSpenEnable] SemFF.getBoolean(BLE_SPEN_SUPPORT) → true");
                            }
                        }
                    });
            } catch (Throwable ignored) {}

            // getString(String) — return davinci spec if missing
            try {
                XposedHelpers.findAndHookMethod(ff, "getString", String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (KEY_SPEN_GARAGE_SPEC.equals(p.args[0])) {
                                String v = (String) p.getResult();
                                if (v == null || v.isEmpty()) {
                                    p.setResult(DAVINCI_GARAGE_SPEC);
                                    Logger.log("[BleSpenEnable] SemFF.getString(GARAGE_SPEC) → " + DAVINCI_GARAGE_SPEC);
                                }
                            }
                        }
                    });
            } catch (Throwable ignored) {}

            Logger.log("[BleSpenEnable] Hook: SemFloatingFeature installed");
        } catch (Throwable t) { Logger.error(t); }
    }

    // ──────────────────────────────────────────────────────────────────
    // Force-write Settings.System "spen_air_action" = 1 on app startup.
    // The Settings toggle normally writes this; on custom ROMs the toggle
    // is hidden (Gate 1 fails) so the key stays 0 and Gate 2 fails.
    // com.samsung.android.service.aircommand holds WRITE_SETTINGS permission.
    // ──────────────────────────────────────────────────────────────────
    private static void forceEnableSettingsKey(final Context ctx) {
        if (ctx == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                ContentResolver cr = ctx.getContentResolver();
                int cur = Settings.System.getInt(cr, SETTINGS_KEY, -1);
                if (cur != 1) {
                    Settings.System.putInt(cr, SETTINGS_KEY, 1);
                    Logger.log("[BleSpenEnable] Settings.System." + SETTINGS_KEY + " set to 1 (was " + cur + ")");
                } else {
                    Logger.log("[BleSpenEnable] Settings.System." + SETTINGS_KEY + " already 1, no change");
                }
            } catch (Throwable t) { Logger.error(t); }
        }, 2000);
    }
}
