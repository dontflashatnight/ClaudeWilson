package com.walter.claudewilson.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Java-side property reads; native property reads are a separate layer. */
final class SystemPropertiesHooks {
    private SystemPropertiesHooks() {}

    static void install(ClassLoader loader, HookConfig config) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", loader);
            XposedBridge.hookAllMethods(sp, "get",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0) return;
                    String key = String.valueOf(param.args[0]);
                    String value = config.props.get(key);
                    if (value != null) param.setResult(value);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook systemproperties: " + t);
        }
    }
}
