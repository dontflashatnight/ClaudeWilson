package com.walter.claudewilson.hook;

import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Grants the page-level WebView geolocation prompt. The coordinates themselves come from the
 * same LocationManager/fused hooks as the native path: VirtualRegion's system-wide location
 * hooks also cover WebView, so there is no separate coordinate source to port.
 */
final class WebViewHooks {
    static volatile String status = "not-run";

    private WebViewHooks() {
    }

    static void install(ClassLoader loader, HookConfig config) {
        if (!config.hasLocation()) {
            status = "off";
            return;
        }
        try {
            Class<?> chrome = XposedHelpers.findClass(
                    "android.webkit.WebChromeClient", loader);
            XposedBridge.hookAllMethods(chrome, "onGeolocationPermissionsShowPrompt",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length < 2
                                    || !(param.args[1] instanceof GeolocationPermissions.Callback)) {
                                return;
                            }
                            String origin = param.args[0] == null
                                    ? "" : String.valueOf(param.args[0]);
                            try {
                                ((GeolocationPermissions.Callback) param.args[1])
                                        .invoke(origin, true, false);
                                status = "granted";
                            } catch (Throwable t) {
                                status = "callback-error";
                            }
                        }
                    });
            status = "ok";
            XposedBridge.log("ClaudeWilsonHook webview status=ok");
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
            XposedBridge.log("ClaudeWilsonHook webview: " + t);
        }
    }
}
