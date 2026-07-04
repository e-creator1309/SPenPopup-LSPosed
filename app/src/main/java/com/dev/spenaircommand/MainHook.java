package com.dev.spenaircommand;

import android.content.Context;

import com.dev.spenaircommand.hooks.AirCommandHook;
import com.dev.spenaircommand.hooks.BleSpenEnableHook;
import com.dev.spenaircommand.utils.Logger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TARGET_PKG = "com.samsung.android.service.aircommand";

    @Override
    public void initZygote(StartupParam startupParam) {
        Logger.log("initZygote — SPen module loaded (Popup + BLE Enable)");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        Logger.log("handleLoadPackage — hooking " + TARGET_PKG);

        // Feature 1: Open Air Command shortcuts as popup (not fullscreen)
        AirCommandHook.init(lpparam.classLoader);

        // Feature 2: Force-enable BLE S Pen Remote on custom ROMs
        // Bypasses the three gates in RemoteSpenService.j(Context):
        //   Gate 1 → r8.c.a(Context) — SemFloatingFeature BLE support check
        //   Gate 2 → z7.j.b()        — Settings.System spen_air_action == 1
        // Also patches SemFloatingFeature for the davinci (Note 10+) garage spec.
        Context appContext = null; // will be grabbed from classloader context
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object currentApp = activityThread.getMethod("currentApplication").invoke(null);
            if (currentApp instanceof Context) {
                appContext = (Context) currentApp;
            }
        } catch (Throwable ignored) {}
        BleSpenEnableHook.init(lpparam.classLoader, appContext);
    }
}
