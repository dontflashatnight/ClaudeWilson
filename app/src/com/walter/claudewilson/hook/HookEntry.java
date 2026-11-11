package com.walter.claudewilson.hook;

import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Vector entry point. Loaded when a registered target process is specialized, before the
 * target's Application code runs; no external launcher or per-process attach is involved.
 *
 * <p>Package routing is data-driven: only a hooks-enabled {@link TargetSpec} is armed. A target
 * that is still diagnostic-only reaches this class and returns without installing anything.
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String LOG = "ClaudeWilsonHook";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        TargetSpec spec = TargetRegistry.byPackage(param.packageName);
        if (spec == null || !spec.hooksEnabled()) {
            return;
        }
        try {
            HookConfig config = HookConfig.load(spec);
            if (config == null) {
                XposedBridge.log(LOG + "[" + spec.id() + "]: no valid persona at "
                        + spec.configPath());
                return;
            }
            BuildHooks.install(param.classLoader, config);
            TelephonyHooks.install(param.classLoader, config);
            IdentityHooks.install(param.classLoader, config);
            LocationHooks.install(param.classLoader, config);
            TelemetryHooks.install(param.classLoader);
            WifiHooks.install(param.classLoader, config);
            BleHooks.install(param.classLoader, config);
            SensorHooks.install(param.classLoader, config);
            WebViewHooks.install(param.classLoader, config);
            SystemPropertiesHooks.install(param.classLoader, config);
            RootArtifactHooks.install(param.classLoader);
            NetworkHooks.install(param.classLoader);
            NativeHooks.install(config);
            SelfCheck.schedule(spec, config);
            XposedBridge.log(LOG + "[" + spec.id() + "]: armed " + config.simOperator + "/"
                    + config.simCountryIso + " pid=" + android.os.Process.myPid());
        } catch (Throwable t) {
            XposedBridge.log(LOG + "[" + spec.id() + "]: " + t);
        }
    }
}
