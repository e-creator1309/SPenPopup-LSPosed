package com.dev.spenaircommand;

import com.dev.spenaircommand.hooks.AirCommandHook;
import com.dev.spenaircommand.utils.Logger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TARGET_PKG = "com.samsung.android.service.aircommand";

    @Override
    public void initZygote(StartupParam startupParam) {
        Logger.log("initZygote — SPen Popup module loaded");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        Logger.log("handleLoadPackage — hooking aircommand pkg");
        AirCommandHook.init(lpparam.classLoader);
    }
}
