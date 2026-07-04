package com.dev.spenaircommand.hooks;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import com.dev.spenaircommand.utils.Logger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

/**
 * BleSpenEnableHook — Force-enables BLE S Pen Remote on custom ROMs
 *
 * Root cause (jadx reverse engineering of com.samsung.android.service.aircommand v7.6.34.7):
 *
 *   RemoteSpenService.j(Context) has THREE gates before starting the BLE service:
 *
 *   Gate 1 → r8.c.a(Context)  [ModelFeatures.java]
 *               public static boolean a(Context context) {
 *                   return j() || k(context);
 *               }
 *               j() = SemFloatingFeature.getBoolean("SEC_FLOATING_FEATURE_COMMON_SUPPORT_BLE_SPEN")
 *               k(Context) = d(context).features.contains(n.REMOTE)
 *               On custom ROMs: both return false → a(Context) = false → service never starts
 *
 *   Gate 2 → z7.j.d(Context).b()  [SettingsPreferenceManager.java]
 *               public boolean b() {
 *                   return (Integer) u8.b.SETTINGS_OBJ.c(this.context) == 1;
 *               }
 *               Reads Settings.System "spen_air_action" == 1
 *               On custom ROMs: toggle hidden → key = 0 → Gate 2 fails
 *
 *   Gate 3 → p7.e.h0(Context, boolean) — paired pen count check (usually passes)
 *
 * Solution (targeted — only the exact methods blocking startup):
 *   1. Hook r8.c.a(Context)  → always return true  (Gate 1 — covers j() AND k() together)
 *   2. Hook z7.j.b()         → always return true  (Gate 2)
 *   3. Hook SemFloatingFeature.getBoolean/getString for the config values
 *   4. Force Settings.System "spen_air_action" = 1 on app start
 *
 * ⚠ IMPORTANT: Do NOT hook r8.c.j() or r8.c.m() separately.
 *   r8.c.m() = isSpenAlertSupported() — forcing it true crashes AirCommandUiService
 *   because it enables SPen Alert initialization before V2.i singleton is ready.
 *   Since r8.c.a(Context) is hooked at the top level, sub-hooks are not needed.
 *
 * Device: Samsung Galaxy Note 10+ (SM-N975F), S Pen model: davinci
 */
public class BleSpenEnableHook {

    // Obfuscated class names — verified from jadx decompile of aircommand 7.6.34.7
    private static final String MODEL_FEATURES_CLASS    = "r8.c";  // ModelFeatures.java
    private static final String SETTINGS_PREF_MGR_CLASS = "z7.j";  // SettingsPreferenceManager.java
    private static final String FLOATING_FEATURE_CLASS  = "com.samsung.android.feature.SemFloatingFeature";

    // FloatingFeature keys
    private static final String KEY_BLE_SPEN_SUPPORT = "SEC_FLOATING_FEATURE_COMMON_SUPPORT_BLE_SPEN";
    private static final String KEY_SPEN_GARAGE_SPEC = "SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_SPEN_GARAGE_SPEC";

    // Note 10+ davinci garage spec — parsed by r8.m.a(String) and r8.g.a(String)
    // "remote" keyword → r8.g.a adds n.REMOTE to the feature ArrayList → k(Context) passes
    private static final String DAVINCI_GARAGE_SPEC = "davinci,button,airmotion,remote,attach_face";

    // Settings.System key checked by z7.j.b()
    private static final String SETTINGS_KEY = "spen_air_action";

    public static void init(ClassLoader cl, Context appContext) {
        hookGate1(cl);
        hookGate2(cl);
        hookSemFloatingFeature(cl);
        forceEnableSettingsKey(appContext);
        Logger.log("[BleSpenEnable] All hooks installed — BLE S Pen remote should start on next launch");
    }

    // ──────────────────────────────────────────────────────────────────
    // Gate 1 — r8.c.a(Context) → always true
    //
    // This is the ONLY r8.c hook we install.
    // a(Context) = j() || k(context), so hooking it at the top level
    // is sufficient. Do NOT hook j() or m() separately — m() is
    // isSpenAlertSupported() and forcing it true crashes AirCommandUiService.
    // ──────────────────────────────────────────────────────────────────
    private static void hookGate1(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(MODEL_FEATURES_CLASS, cl);
            if (cls == null) {
                Logger.log("[BleSpenEnable] r8.c not found — Gate 1 hook skipped");
                return;
            }
            XposedHelpers.findAndHookMethod(cls, "a", Context.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        Logger.log("[BleSpenEnable] r8.c.a(Context) → true  [Gate 1 bypassed]");
                        return true;
                    }
                });
            Logger.log("[BleSpenEnable] Hook: r8.c.a(Context) installed");
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Gate 2 — z7.j.b() → always true
    //
    // z7.j is SettingsPreferenceManager — b() reads Settings.System
    // "spen_air_action" and returns (value == 1).
    // On custom ROMs the Settings toggle is hidden so this key stays 0.
    // We hook b() AND write the key directly (forceEnableSettingsKey).
    // ──────────────────────────────────────────────────────────────────
    private static void hookGate2(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(SETTINGS_PREF_MGR_CLASS, cl);
            if (cls == null) {
                Logger.log("[BleSpenEnable] z7.j not found — Gate 2 hook skipped");
                return;
            }
            XposedHelpers.findAndHookMethod(cls, "b",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        Logger.log("[BleSpenEnable] z7.j.b() → true  [Gate 2 bypassed]");
                        return true;
                    }
                });
            Logger.log("[BleSpenEnable] Hook: z7.j.b() installed");
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // System level — SemFloatingFeature patches
    //
    // getBoolean(KEY_BLE_SPEN_SUPPORT) → true
    //   Used by r8.c.j() which feeds into Gate 1 (belt + suspenders).
    //
    // getString(KEY_SPEN_GARAGE_SPEC) → DAVINCI_GARAGE_SPEC
    //   Used by r8.c.e() → r8.c.d() → r8.g.a(String) which builds the
    //   feature ArrayList. Without it, n.REMOTE is absent → k(Context)=false.
    //   With Gate 1 hooked this is redundant, but we keep it so the feature
    //   list is correct for any other code that reads it.
    // ──────────────────────────────────────────────────────────────────
    private static void hookSemFloatingFeature(ClassLoader cl) {
        Class<?> ffClass = null;
        try {
            ffClass = Class.forName(FLOATING_FEATURE_CLASS);
        } catch (ClassNotFoundException ignored) {
            ffClass = XposedHelpers.findClassIfExists(FLOATING_FEATURE_CLASS, cl);
        }
        if (ffClass == null) {
            Logger.log("[BleSpenEnable] SemFloatingFeature not found — system hook skipped");
            return;
        }
        final Class<?> ff = ffClass;

        try {
            XposedHelpers.findAndHookMethod(ff, "getBoolean", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (KEY_BLE_SPEN_SUPPORT.equals(p.args[0])) {
                            p.setResult(true);
                            Logger.log("[BleSpenEnable] SemFF.getBoolean(BLE_SPEN_SUPPORT) → true");
                        }
                    }
                });
        } catch (Throwable t) {
            Logger.error(t);
        }

        try {
            XposedHelpers.findAndHookMethod(ff, "getString", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (KEY_SPEN_GARAGE_SPEC.equals(p.args[0])) {
                            String v = (String) p.getResult();
                            if (v == null || v.isEmpty()) {
                                p.setResult(DAVINCI_GARAGE_SPEC);
                                Logger.log("[BleSpenEnable] SemFF.getString(GARAGE_SPEC) → " + DAVINCI_GARAGE_SPEC);
                            }
                        }
                    }
                });
        } catch (Throwable t) {
            Logger.error(t);
        }

        Logger.log("[BleSpenEnable] Hook: SemFloatingFeature installed");
    }

    // ──────────────────────────────────────────────────────────────────
    // Force-write Settings.System "spen_air_action" = 1 on startup.
    //
    // Belt-and-suspenders alongside the z7.j.b() hook.
    // Runs 2 s after app starts to ensure the ContentResolver is ready.
    // com.samsung.android.service.aircommand holds WRITE_SETTINGS.
    // ──────────────────────────────────────────────────────────────────
    private static void forceEnableSettingsKey(final Context ctx) {
        if (ctx == null) {
            Logger.log("[BleSpenEnable] appContext null — skipping Settings.System write");
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                ContentResolver cr = ctx.getContentResolver();
                int cur = Settings.System.getInt(cr, SETTINGS_KEY, -1);
                if (cur != 1) {
                    Settings.System.putInt(cr, SETTINGS_KEY, 1);
                    Logger.log("[BleSpenEnable] Settings.System." + SETTINGS_KEY + " → 1  (was " + cur + ")");
                } else {
                    Logger.log("[BleSpenEnable] Settings.System." + SETTINGS_KEY + " already 1");
                }
            } catch (Throwable t) {
                Logger.error(t);
            }
        }, 2000);
    }
}
