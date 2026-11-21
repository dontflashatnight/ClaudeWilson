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
    static volatile boolean debugLog;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if ("android".equals(param.packageName)
                && (param.processName == null || "android".equals(param.processName))) {
            SystemLocationHooks.install(param.classLoader);
            SystemBodyguardHooks.install(param.classLoader);
            SystemAppVisHooks.install(param.classLoader);
            return;
        }
        TargetSpec spec = TargetRegistry.byPackage(param.packageName);
        if (spec == null || !spec.hooksEnabled()) {
            HookConfig companion = HookConfig.loadForCompanion(param.packageName);
            if (companion == null) {
                return;
            }
            installCompanionBrowser(param, companion);
            return;
        }
        try {
            HookConfig config = HookConfig.load(spec);
            if (config == null) {
                // Keep ExitInfo stealth active and fail-closed if Bodyguard is armed in BLOCK_KILL
                // (prevents target apps from running naked when no region persona is active).
                BodyguardHooks.enforceMissingPersona(
                        param.classLoader,
                        HookConfig.readGuardMode(),
                        new java.io.File(spec.configPath()).exists());
                return;
            }
            debugLog = config.debugLog;
            HookContext.init();
            BuildHooks.install(param.classLoader, config);
            TelephonyHooks.install(param.classLoader, config);
            IdentityHooks.install(param.classLoader, config, param.packageName);
            LocationHooks.install(param.classLoader, config);
            if (config.debugTelemetry) {
                TelemetryHooks.install(param.classLoader);
            }
            WifiHooks.install(param.classLoader, config);
            BleHooks.install(param.classLoader, config);
            SensorHooks.install(param.classLoader, config);
            SystemPropertiesHooks.install(param.classLoader, config);
            RootArtifactHooks.install(param.classLoader, config, param.packageName);
            NetworkHooks.install(param.classLoader, config);
            WebViewHooks.install(param.classLoader, config);
            BodyguardHooks.install(param.classLoader, config);
            NativeHooks.install(config);
            if (config.debugSelfCheck) {
                SelfCheck.schedule(spec, config);
            }
            if (debugLog) {
                XposedBridge.log(LOG + "[" + spec.id() + "]: armed " + config.simOperator + "/"
                        + config.simCountryIso + " pid=" + android.os.Process.myPid());
            }
        } catch (Throwable t) {
            if (debugLog) {
                XposedBridge.log(LOG + "[" + spec.id() + "]: " + t);
            }
        }
    }

    /**
     * Companion protection for the device's default / Custom Tabs browser (OAuth &amp; Persona KYC):
     * aligns device identity, timezone, locale, GPS, Java &amp; native IPv6/tunnel hiding without
     * arming Bodyguard kill gates that could interrupt normal web browsing.
     */
    private static void installCompanionBrowser(XC_LoadPackage.LoadPackageParam param,
                                                HookConfig config) {
        try {
            debugLog = config.debugLog;
            HookContext.init();
            BuildHooks.install(param.classLoader, config);
            TelephonyHooks.install(param.classLoader, config);
            IdentityHooks.installCompanionBrowser(param.classLoader, config);
            LocationHooks.install(param.classLoader, config);
            SystemPropertiesHooks.install(param.classLoader, config);
            NetworkHooks.install(param.classLoader, config);
            NativeHooks.install(config);
            if (debugLog) {
                XposedBridge.log(LOG + "[browser:" + param.packageName + "]: armed "
                        + config.simCountryIso + " pid=" + android.os.Process.myPid());
            }
        } catch (Throwable t) {
            if (debugLog) {
                XposedBridge.log(LOG + "[browser:" + param.packageName + "]: " + t);
            }
        }
    }
}
