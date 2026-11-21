package com.walter.claudewilson.hook;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** SSAID, locale, timezone and installer source. */
final class IdentityHooks {
    private IdentityHooks() {}

    static void install(ClassLoader loader, HookConfig config) {
        install(loader, config, "");
    }

    /**
     * Chromium-safe subset for companion browsers (Chrome Custom Tabs / default browser):
     * spoofs {@code android_id} and {@code TimeZone.getDefault()} without overriding Java
     * {@code Locale.getDefault()} / {@code Resources}, avoiding Chromium's native ICU vs Java
     * locale {@code CHECK()} abort ({@code SIGTRAP} in {@code BrowserStartupControllerImpl}).
     * Browser locale is aligned cleanly via Android per-app locale ({@code cmd locale}).
     */
    static void installCompanionBrowser(ClassLoader loader, HookConfig config) {
        try {
            Class<?> secure = XposedHelpers.findClass("android.provider.Settings$Secure", loader);
            XposedBridge.hookAllMethods(secure, "getString", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2
                            && "android_id".equals(String.valueOf(param.args[1]))) {
                        param.setResult(config.androidId);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        if (!config.tz.isEmpty()) {
            try {
                Class<?> tzc = XposedHelpers.findClass("java.util.TimeZone", loader);
                final TimeZone value = TimeZone.getTimeZone(config.tz);
                XposedBridge.hookAllMethods(tzc, "getDefault",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult((TimeZone) value.clone());
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    static void install(ClassLoader loader, HookConfig config, String targetPackage) {
        try {
            Class<?> secure = XposedHelpers.findClass("android.provider.Settings$Secure", loader);
            XposedBridge.hookAllMethods(secure, "getString", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2
                            && "android_id".equals(String.valueOf(param.args[1]))) {
                        param.setResult(config.androidId);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook aid: " + t);
        }

        if (!config.locale.isEmpty()) {
            final Locale value = Locale.forLanguageTag(config.locale.replace('_', '-'));
            final android.os.LocaleList locales = localeList(config, value);
            try {
                Class<?> locale = XposedHelpers.findClass("java.util.Locale", loader);
                XposedBridge.hookAllMethods(locale, "getDefault",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(value);
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook locale: " + t);
            }
            applyResourcesLocale(loader, value, locales);
        }
        if (!config.tz.isEmpty()) {
            try {
                Class<?> tzc = XposedHelpers.findClass("java.util.TimeZone", loader);
                final TimeZone value = TimeZone.getTimeZone(config.tz);
                XposedBridge.hookAllMethods(tzc, "getDefault",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult((TimeZone) value.clone());
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook tz: " + t);
            }
        }

        String installer = "com.android.vending";
        try {
            Class<?> info = XposedHelpers.findClass(
                    "android.content.pm.InstallSourceInfo", loader);
            String[] getters = {"getInstallingPackageName", "getInitiatingPackageName",
                    "getOriginatingPackageName"};
            for (String getter : getters) {
                constant(info, getter, installer);
            }
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook installer: " + t);
        }
        try {
            Class<?> pm = XposedHelpers.findClass(
                    "android.app.ApplicationPackageManager", loader);
            enforcePackageVisibility(loader, pm, config, targetPackage, installer);
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook installer-pm: " + t);
            }
        }
        hookAdvertisingId(loader, config);
        hookInstallReferrer(loader);
    }

    /** Aligns GMS Advertising ID with the active persona seed so personas do not share an ad ID. */
    private static void hookAdvertisingId(ClassLoader loader, HookConfig config) {
        final String adId = com.walter.claudewilson.model.PersonaSynth.advertisingId(
                config.androidId);
        if (adId.isEmpty()) {
            return;
        }
        try {
            Class<?> info = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info", loader);
            if (info == null) {
                return;
            }
            XposedBridge.hookAllMethods(info, "getId",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(adId);
                }
            });
            XposedBridge.hookAllMethods(info, "isLimitAdTrackingEnabled",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook adid: " + t);
            }
        }
    }

    /** Aligns Play Install Referrer with organic Google Play store attribution. */
    private static void hookInstallReferrer(ClassLoader loader) {
        try {
            Class<?> details = XposedHelpers.findClassIfExists(
                    "com.android.installreferrer.api.ReferrerDetails", loader);
            if (details == null) {
                return;
            }
            constant(details, "getInstallReferrer", "utm_source=google-play&utm_medium=organic");
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook referrer: " + t);
            }
        }
    }

    private static final ThreadLocal<Boolean> IN_SYS_LOOKUP = new ThreadLocal<Boolean>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SYS_PKG_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    private static volatile java.util.Set<String> activeExtraHidden =
            java.util.Collections.emptySet();

    /**
     * HMA-grade target-process package visibility enforcement ({@code default / allowlist / denylist},
     * {@code excludeSystemApps}, {@code extraHidden}, and strict/feature side-channel shields across
     * PackageManager, LauncherApps, PackageInstaller, AccessibilityManager, InputMethodManager, and
     * Settings.Secure).
     */
    private static void enforcePackageVisibility(ClassLoader loader, Class<?> pm,
                                                 final HookConfig config,
                                                 final String targetPackage,
                                                 final String spoofInstaller) {
        final String mode = config.appVisMode;
        final java.util.Set<String> selected = config.appVisPackages;
        activeExtraHidden = config.appVisExtraHidden;
        final boolean exclSys = config.appVisExcludeSystem;
        final boolean strict = config.appVisStrict;

        // 1. Installer source: hide hidden packages with IllegalArgumentException (never leak "vending"
        //    for hidden/non-existent packages), and spoof Play Store for the target package itself.
        try {
            XposedBridge.hookAllMethods(pm, "getInstallerPackageName",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                    if (param.args != null && param.args.length > 0
                            && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (isPkgHidden(param.thisObject, mode, selected, exclSys, pkg, targetPackage)) {
                            param.setThrowable(new IllegalArgumentException("Unknown package: " + pkg));
                        }
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) return;
                    if (param.args != null && param.args.length > 0
                            && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (targetPackage == null || targetPackage.isEmpty()
                                || targetPackage.equals(pkg)) {
                            param.setResult(spoofInstaller);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // 2. Single-package queries that throw NameNotFoundException when absent.
        XC_MethodHook throwNotFound = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                if (param.args != null && param.args.length > 0
                        && param.args[0] instanceof String) {
                    String pkg = (String) param.args[0];
                    if (isPkgHidden(param.thisObject, mode, selected, exclSys, pkg, targetPackage)) {
                        param.setThrowable(
                                new android.content.pm.PackageManager.NameNotFoundException(pkg));
                    }
                }
            }
        };
        for (String method : new String[]{
                "getPackageInfo", "getPackageInfoAsUser",
                "getApplicationInfo", "getApplicationInfoAsUser",
                "getPackageUid", "getPackageUidAsUser", "getPackageGids",
                "getResourcesForApplication", "getApplicationEnabledSetting",
                "getInstallSourceInfo", "getApplicationIcon",
                "getApplicationBanner", "getApplicationLogo"}) {
            try {
                XposedBridge.hookAllMethods(pm, method, throwNotFound);
            } catch (Throwable ignored) {
            }
        }

        // 3. ComponentName queries (getActivityInfo / getServiceInfo / getReceiverInfo / getProviderInfo).
        XC_MethodHook throwComponentNotFound = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                if (param.args != null && param.args.length > 0
                        && param.args[0] instanceof android.content.ComponentName) {
                    String pkg = ((android.content.ComponentName) param.args[0]).getPackageName();
                    if (isPkgHidden(param.thisObject, mode, selected, exclSys, pkg, targetPackage)) {
                        param.setThrowable(
                                new android.content.pm.PackageManager.NameNotFoundException(pkg));
                    }
                }
            }
        };
        for (String method : new String[]{
                "getActivityInfo", "getServiceInfo", "getReceiverInfo", "getProviderInfo"}) {
            try {
                XposedBridge.hookAllMethods(pm, method, throwComponentNotFound);
            } catch (Throwable ignored) {
            }
        }
        try {
            XposedBridge.hookAllMethods(pm, "getComponentEnabledSetting", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                    if (param.args != null && param.args.length > 0
                            && param.args[0] instanceof android.content.ComponentName) {
                        String pkg = ((android.content.ComponentName) param.args[0]).getPackageName();
                        if (isPkgHidden(param.thisObject, mode, selected, exclSys, pkg, targetPackage)) {
                            param.setThrowable(new IllegalArgumentException("Unknown component: " + pkg));
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // 4. Launch intent queries returning null when absent.
        XC_MethodHook returnNull = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                if (param.args != null && param.args.length > 0
                        && param.args[0] instanceof String
                        && isPkgHidden(param.thisObject, mode, selected, exclSys,
                                (String) param.args[0], targetPackage)) {
                    param.setResult(null);
                }
            }
        };
        for (String method : new String[]{
                "getLaunchIntentForPackage", "getLeanbackLaunchIntentForPackage"}) {
            try {
                XposedBridge.hookAllMethods(pm, method, returnNull);
            } catch (Throwable ignored) {
            }
        }

        // 5. Single-item resolve queries (resolveActivity / resolveService / resolveContentProvider).
        XC_MethodHook filterSingle = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                Object res = param.getResult();
                if (res == null) return;
                String pkg = extractPackageName(res);
                if (pkg != null && isItemHidden(param.thisObject, res, mode, selected,
                        exclSys, pkg, targetPackage)) {
                    param.setResult(null);
                }
            }
        };
        for (String method : new String[]{
                "resolveActivity", "resolveActivityAsUser",
                "resolveService", "resolveContentProvider", "resolveContentProviderAsUser"}) {
            try {
                XposedBridge.hookAllMethods(pm, method, filterSingle);
            } catch (Throwable ignored) {
            }
        }

        // 6. List enumeration & Intent/Provider query methods.
        XC_MethodHook filterList = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                Object res = param.getResult();
                if (!(res instanceof java.util.List<?>)) {
                    return;
                }
                java.util.List<?> list = (java.util.List<?>) res;
                java.util.List<Object> kept = null;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    String pkg = extractPackageName(item);
                    if (pkg != null && isItemHidden(param.thisObject, item, mode, selected,
                            exclSys, pkg, targetPackage)) {
                        if (kept == null) {
                            kept = new java.util.ArrayList<Object>(list.size());
                            for (int j = 0; j < i; j++) {
                                kept.add(list.get(j));
                            }
                        }
                    } else if (kept != null) {
                        kept.add(item);
                    }
                }
                if (kept != null) {
                    param.setResult(kept);
                }
            }
        };
        for (String method : new String[]{
                "getInstalledPackages", "getInstalledPackagesAsUser",
                "getInstalledApplications", "getInstalledApplicationsAsUser",
                "getPackagesHoldingPermissions",
                "queryIntentActivities", "queryIntentActivitiesAsUser",
                "queryIntentServices", "queryIntentServicesAsUser",
                "queryIntentContentProviders", "queryIntentContentProvidersAsUser",
                "queryBroadcastReceivers", "queryBroadcastReceiversAsUser",
                "queryContentProviders"}) {
            try {
                XposedBridge.hookAllMethods(pm, method, filterList);
            } catch (Throwable ignored) {
            }
        }

        // 7. UID & permission probes (getPackagesForUid / getNameForUid / checkPermission).
        try {
            XposedBridge.hookAllMethods(pm, "getPackagesForUid", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                    Object res = param.getResult();
                    if (!(res instanceof String[])) {
                        return;
                    }
                    String[] pkgs = (String[]) res;
                    java.util.List<String> kept = null;
                    for (int i = 0; i < pkgs.length; i++) {
                        String p = pkgs[i];
                        if (p != null && isPkgHidden(param.thisObject, mode, selected,
                                exclSys, p, targetPackage)) {
                            if (kept == null) {
                                kept = new java.util.ArrayList<String>(pkgs.length);
                                for (int j = 0; j < i; j++) {
                                    kept.add(pkgs[j]);
                                }
                            }
                        } else if (kept != null) {
                            kept.add(p);
                        }
                    }
                    if (kept != null) {
                        param.setResult(kept.isEmpty() ? null : kept.toArray(new String[0]));
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(pm, "getNameForUid", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                    Object res = param.getResult();
                    if (!(res instanceof String)) return;
                    String name = (String) res;
                    int colon = name.indexOf(':');
                    String pkg = colon > 0 ? name.substring(0, colon) : name;
                    if (isPkgHidden(param.thisObject, mode, selected, exclSys, pkg, targetPackage)) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(pm, "checkPermission", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_SYS_LOOKUP.get())) return;
                    if (param.args != null && param.args.length >= 2
                            && param.args[1] instanceof String
                            && isPkgHidden(param.thisObject, mode, selected, exclSys,
                                    (String) param.args[1], targetPackage)) {
                        param.setResult(android.content.pm.PackageManager.PERMISSION_DENIED);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // 8. HMA Strict & Feature Side-Channel Shields: LauncherApps, PackageInstaller,
        //    AccessibilityManager, InputMethodManager, and Settings.Secure.
        hookStrictSideChannels(loader, mode, selected, exclSys, targetPackage,
                strict, config.appVisHideA11y, config.appVisHideIme);
    }

    private static void hookStrictSideChannels(ClassLoader loader, final String mode,
                                               final java.util.Set<String> selected,
                                               final boolean exclSys,
                                               final String targetPackage,
                                               final boolean strict,
                                               final boolean hideA11y,
                                               final boolean hideIme) {
        // 8a. LauncherApps
        if (strict) try {
            Class<?> la = XposedHelpers.findClassIfExists("android.content.pm.LauncherApps", loader);
            if (la != null) {
                XC_MethodHook filterLauncherList = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (!(res instanceof java.util.List<?>)) return;
                        java.util.List<?> list = (java.util.List<?>) res;
                        java.util.List<Object> kept = null;
                        for (int i = 0; i < list.size(); i++) {
                            Object item = list.get(i);
                            String pkg = extractPackageName(item);
                            if (pkg != null && isItemHidden(null, item, mode, selected,
                                    exclSys, pkg, targetPackage)) {
                                if (kept == null) {
                                    kept = new java.util.ArrayList<Object>(list.size());
                                    for (int j = 0; j < i; j++) kept.add(list.get(j));
                                }
                            } else if (kept != null) {
                                kept.add(item);
                            }
                        }
                        if (kept != null) param.setResult(kept);
                    }
                };
                XposedBridge.hookAllMethods(la, "getActivityList", filterLauncherList);
                XposedBridge.hookAllMethods(la, "getShortcutConfigActivityList", filterLauncherList);
                XposedBridge.hookAllMethods(la, "isPackageEnabled", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof String
                                && isPkgHidden(null, mode, selected, exclSys,
                                        (String) param.args[0], targetPackage)) {
                            param.setResult(Boolean.FALSE);
                        }
                    }
                });
                XposedBridge.hookAllMethods(la, "getApplicationInfo", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof String
                                && isPkgHidden(null, mode, selected, exclSys,
                                        (String) param.args[0], targetPackage)) {
                            param.setThrowable(new android.content.pm.PackageManager.NameNotFoundException(
                                    (String) param.args[0]));
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }

        // 8b. PackageInstaller sessions
        if (strict) try {
            Class<?> pi = XposedHelpers.findClassIfExists("android.content.pm.PackageInstaller", loader);
            if (pi != null) {
                XC_MethodHook filterSessions = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (!(res instanceof java.util.List<?>)) return;
                        java.util.List<?> list = (java.util.List<?>) res;
                        java.util.List<Object> kept = null;
                        for (int i = 0; i < list.size(); i++) {
                            Object item = list.get(i);
                            String pkg = null;
                            if (item instanceof android.content.pm.PackageInstaller.SessionInfo) {
                                pkg = ((android.content.pm.PackageInstaller.SessionInfo) item).getAppPackageName();
                            }
                            if (pkg != null && isPkgHidden(null, mode, selected, exclSys, pkg, targetPackage)) {
                                if (kept == null) {
                                    kept = new java.util.ArrayList<Object>(list.size());
                                    for (int j = 0; j < i; j++) kept.add(list.get(j));
                                }
                            } else if (kept != null) {
                                kept.add(item);
                            }
                        }
                        if (kept != null) param.setResult(kept);
                    }
                };
                XposedBridge.hookAllMethods(pi, "getAllSessions", filterSessions);
                XposedBridge.hookAllMethods(pi, "getMySessions", filterSessions);
            }
        } catch (Throwable ignored) {
        }

        // 8c. AccessibilityManager + Settings.Secure (HMA Accessibility Feature Shield)
        if (hideA11y || strict) try {
            Class<?> am = XposedHelpers.findClassIfExists(
                    "android.view.accessibility.AccessibilityManager", loader);
            if (am != null) {
                XC_MethodHook filterA11y = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (!(res instanceof java.util.List<?>)) return;
                        java.util.List<?> list = (java.util.List<?>) res;
                        java.util.List<Object> kept = null;
                        for (int i = 0; i < list.size(); i++) {
                            Object item = list.get(i);
                            String pkg = extractPackageName(item);
                            if (pkg != null && isItemHidden(null, item, mode, selected,
                                    exclSys, pkg, targetPackage)) {
                                if (kept == null) {
                                    kept = new java.util.ArrayList<Object>(list.size());
                                    for (int j = 0; j < i; j++) kept.add(list.get(j));
                                }
                            } else if (kept != null) {
                                kept.add(item);
                            }
                        }
                        if (kept != null) param.setResult(kept);
                    }
                };
                XposedBridge.hookAllMethods(am, "getInstalledAccessibilityServiceList", filterA11y);
                if (hideA11y) {
                    XC_MethodHook emptyList = new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(java.util.Collections.emptyList());
                        }
                    };
                    XC_MethodHook alwaysFalse = new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.FALSE);
                        }
                    };
                    XposedBridge.hookAllMethods(am, "getEnabledAccessibilityServiceList", emptyList);
                    XposedBridge.hookAllMethods(am, "isEnabled", alwaysFalse);
                    XposedBridge.hookAllMethods(am, "isTouchExplorationEnabled", alwaysFalse);
                } else {
                    XposedBridge.hookAllMethods(am, "getEnabledAccessibilityServiceList", filterA11y);
                }
            }
        } catch (Throwable ignored) {
        }

        // 8d. InputMethodManager (hides third-party IME packages hidden by policy)
        if (hideIme || strict) try {
            Class<?> imm = XposedHelpers.findClassIfExists(
                    "android.view.inputmethod.InputMethodManager", loader);
            if (imm != null) {
                XC_MethodHook filterIme = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object res = param.getResult();
                        if (!(res instanceof java.util.List<?>)) return;
                        java.util.List<?> list = (java.util.List<?>) res;
                        java.util.List<Object> kept = null;
                        for (int i = 0; i < list.size(); i++) {
                            Object item = list.get(i);
                            String pkg = extractPackageName(item);
                            if (pkg != null && isItemHidden(null, item, mode, selected,
                                    exclSys, pkg, targetPackage)) {
                                if (kept == null) {
                                    kept = new java.util.ArrayList<Object>(list.size());
                                    for (int j = 0; j < i; j++) kept.add(list.get(j));
                                }
                            } else if (kept != null) {
                                kept.add(item);
                            }
                        }
                        if (kept != null) param.setResult(kept);
                    }
                };
                XposedBridge.hookAllMethods(imm, "getInputMethodList", filterIme);
                XposedBridge.hookAllMethods(imm, "getEnabledInputMethodList", filterIme);
            }
        } catch (Throwable ignored) {
        }

        // 8e. Settings.Secure shields for Accessibility & Input Method feature leaks
        if (hideA11y || hideIme) try {
            Class<?> secure = XposedHelpers.findClassIfExists(
                    "android.provider.Settings$Secure", loader);
            if (secure != null) {
                XC_MethodHook secureStr = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2 || param.args[1] == null) {
                            return;
                        }
                        String key = String.valueOf(param.args[1]);
                        if (hideA11y) {
                            if ("enabled_accessibility_services".equals(key)) {
                                param.setResult("");
                                return;
                            }
                            if ("accessibility_enabled".equals(key)
                                    || "touch_exploration_enabled".equals(key)) {
                                param.setResult("0");
                                return;
                            }
                        }
                        if (hideIme) {
                            Object res = param.getResult();
                            String val = res instanceof String ? (String) res : null;
                            if ("enabled_input_methods".equals(key)) {
                                param.setResult(com.walter.claudewilson.model.AppVisibilityPolicy
                                        .filterEnabledInputMethods(val, mode, selected,
                                                activeExtraHidden, exclSys, targetPackage));
                            } else if ("default_input_method".equals(key)) {
                                param.setResult(com.walter.claudewilson.model.AppVisibilityPolicy
                                        .sanitizeDefaultInputMethod(val, mode, selected,
                                                activeExtraHidden, exclSys, targetPackage));
                            }
                        }
                    }
                };
                XposedBridge.hookAllMethods(secure, "getString", secureStr);
                XposedBridge.hookAllMethods(secure, "getStringForUser", secureStr);
                if (hideA11y) {
                    XC_MethodHook secureInt = new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 2 || param.args[1] == null) {
                                return;
                            }
                            String key = String.valueOf(param.args[1]);
                            if ("accessibility_enabled".equals(key)
                                    || "touch_exploration_enabled".equals(key)) {
                                param.setResult(0);
                            }
                        }
                    };
                    XposedBridge.hookAllMethods(secure, "getInt", secureInt);
                    XposedBridge.hookAllMethods(secure, "getIntForUser", secureInt);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isItemHidden(Object pmObj, Object item, String mode,
                                        java.util.Set<String> selected, boolean exclSys,
                                        String pkg, String targetPackage) {
        boolean isSys = false;
        if (exclSys && com.walter.claudewilson.model.AppVisibilityPolicy.MODE_ALLOWLIST.equals(mode)) {
            Boolean fromItem = extractIsSystemFromItem(item);
            isSys = fromItem != null ? fromItem.booleanValue() : isSystemPackage(pmObj, pkg);
        }
        return com.walter.claudewilson.model.AppVisibilityPolicy.isHidden(
                mode, selected, activeExtraHidden, exclSys, pkg, isSys, targetPackage);
    }

    private static boolean isPkgHidden(Object pmObj, String mode, java.util.Set<String> selected,
                                       boolean exclSys, String pkg, String targetPackage) {
        boolean isSys = false;
        if (exclSys && com.walter.claudewilson.model.AppVisibilityPolicy.MODE_ALLOWLIST.equals(mode)) {
            isSys = isSystemPackage(pmObj, pkg);
        }
        return com.walter.claudewilson.model.AppVisibilityPolicy.isHidden(
                mode, selected, activeExtraHidden, exclSys, pkg, isSys, targetPackage);
    }

    private static boolean isSystemPackage(Object pmObj, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Boolean cached = SYS_PKG_CACHE.get(pkg);
        if (cached != null) return cached.booleanValue();
        if (!(pmObj instanceof android.content.pm.PackageManager)) {
            return false;
        }
        IN_SYS_LOOKUP.set(Boolean.TRUE);
        try {
            android.content.pm.ApplicationInfo ai =
                    ((android.content.pm.PackageManager) pmObj).getApplicationInfo(pkg, 0);
            boolean sys = ai != null
                    && (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            SYS_PKG_CACHE.put(pkg, Boolean.valueOf(sys));
            return sys;
        } catch (Throwable ignored) {
            return false;
        } finally {
            IN_SYS_LOOKUP.remove();
        }
    }

    private static Boolean extractIsSystemFromItem(Object item) {
        android.content.pm.ApplicationInfo ai = null;
        if (item instanceof android.content.pm.ApplicationInfo) {
            ai = (android.content.pm.ApplicationInfo) item;
        } else if (item instanceof android.content.pm.PackageInfo) {
            ai = ((android.content.pm.PackageInfo) item).applicationInfo;
        } else if (item instanceof android.content.pm.ProviderInfo) {
            ai = ((android.content.pm.ProviderInfo) item).applicationInfo;
        } else if (item instanceof android.content.pm.ResolveInfo) {
            android.content.pm.ResolveInfo ri = (android.content.pm.ResolveInfo) item;
            if (ri.activityInfo != null) ai = ri.activityInfo.applicationInfo;
            else if (ri.serviceInfo != null) ai = ri.serviceInfo.applicationInfo;
            else if (ri.providerInfo != null) ai = ri.providerInfo.applicationInfo;
        } else if (item instanceof android.content.pm.LauncherActivityInfo) {
            ai = ((android.content.pm.LauncherActivityInfo) item).getApplicationInfo();
        } else if (item instanceof android.accessibilityservice.AccessibilityServiceInfo) {
            android.content.pm.ResolveInfo ri =
                    ((android.accessibilityservice.AccessibilityServiceInfo) item).getResolveInfo();
            if (ri != null && ri.serviceInfo != null) ai = ri.serviceInfo.applicationInfo;
        } else if (item instanceof android.view.inputmethod.InputMethodInfo) {
            android.content.pm.ServiceInfo si =
                    ((android.view.inputmethod.InputMethodInfo) item).getServiceInfo();
            if (si != null) ai = si.applicationInfo;
        }
        if (ai == null) return null;
        boolean sys = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        if (ai.packageName != null && !ai.packageName.isEmpty()) {
            SYS_PKG_CACHE.put(ai.packageName, Boolean.valueOf(sys));
        }
        return Boolean.valueOf(sys);
    }

    private static String extractPackageName(Object item) {
        if (item instanceof android.content.pm.PackageInfo) {
            return ((android.content.pm.PackageInfo) item).packageName;
        }
        if (item instanceof android.content.pm.ApplicationInfo) {
            return ((android.content.pm.ApplicationInfo) item).packageName;
        }
        if (item instanceof android.content.pm.ProviderInfo) {
            return ((android.content.pm.ProviderInfo) item).packageName;
        }
        if (item instanceof android.content.pm.ResolveInfo) {
            android.content.pm.ResolveInfo ri = (android.content.pm.ResolveInfo) item;
            if (ri.activityInfo != null) {
                return ri.activityInfo.packageName;
            }
            if (ri.serviceInfo != null) {
                return ri.serviceInfo.packageName;
            }
            if (ri.providerInfo != null) {
                return ri.providerInfo.packageName;
            }
        }
        if (item instanceof android.content.pm.LauncherActivityInfo) {
            android.content.ComponentName cn =
                    ((android.content.pm.LauncherActivityInfo) item).getComponentName();
            return cn != null ? cn.getPackageName() : null;
        }
        if (item instanceof android.accessibilityservice.AccessibilityServiceInfo) {
            android.content.pm.ResolveInfo ri =
                    ((android.accessibilityservice.AccessibilityServiceInfo) item).getResolveInfo();
            return ri != null && ri.serviceInfo != null ? ri.serviceInfo.packageName : null;
        }
        if (item instanceof android.view.inputmethod.InputMethodInfo) {
            return ((android.view.inputmethod.InputMethodInfo) item).getPackageName();
        }
        return null;
    }

    private static void applyResourcesLocale(ClassLoader loader, final Locale value,
            final android.os.LocaleList locales) {
        try {
            Class<?> localeList = XposedHelpers.findClass("android.os.LocaleList", loader);
            XposedBridge.hookAllMethods(localeList, "getDefault",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(locales);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook locale-list: " + t);
        }
        try {
            Class<?> config = XposedHelpers.findClass(
                    "android.content.res.Configuration", loader);
            XposedBridge.hookAllMethods(config, "getLocales",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(locales);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook config-locales: " + t);
        }
        if (HookContext.HOST_SDK_INT >= 33) {
            try {
                Class<?> localeManager = XposedHelpers.findClassIfExists(
                        "android.app.LocaleManager", loader);
                if (localeManager != null) {
                    XC_MethodHook listResult = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(locales);
                        }
                    };
                    XposedBridge.hookAllMethods(localeManager, "getSystemLocales", listResult);
                    XposedBridge.hookAllMethods(localeManager, "getApplicationLocales", listResult);
                }
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook locale-manager: " + t);
            }
        }
        XC_MethodHook constructor = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                for (Object arg : param.args) {
                    if (arg instanceof android.content.res.Configuration) {
                        ((android.content.res.Configuration) arg).setLocales(locales);
                    }
                }
            }
        };
        try {
            XposedHelpers.findAndHookConstructor("android.content.res.Resources", loader,
                    android.content.res.AssetManager.class,
                    android.util.DisplayMetrics.class,
                    android.content.res.Configuration.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            for (Object arg : param.args) {
                                if (arg instanceof android.content.res.Configuration) {
                                    ((android.content.res.Configuration) arg).setLocales(locales);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook resources-ctor: " + t);
        }
        try {
            Class<?> impl = XposedHelpers.findClass("android.content.res.ResourcesImpl", loader);
            XposedBridge.hookAllConstructors(impl, constructor);
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook resourcesimpl-ctor: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.content.res.Resources", loader,
                    "getConfiguration", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result instanceof android.content.res.Configuration) {
                                android.content.res.Configuration copy =
                                        new android.content.res.Configuration(
                                                (android.content.res.Configuration) result);
                                copy.setLocales(locales);
                                param.setResult(copy);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook resources-config: " + t);
        }
        try {
            Class<?> context = XposedHelpers.findClass("android.app.ContextImpl", loader);
            XposedBridge.hookAllMethods(context, "createConfigurationContext",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            applyContextLocale(param.getResult(), value);
                        }
                    });
            XposedBridge.hookAllMethods(context, "createDisplayContext",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            applyContextLocale(param.getResult(), value);
                        }
                    });
            XposedBridge.hookAllMethods(context, "applyOverrideConfiguration",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            for (Object arg : param.args) {
                                if (arg instanceof android.content.res.Configuration) {
                                    ((android.content.res.Configuration) arg).setLocales(locales);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook context-locale: " + t);
        }
        try {
            Class<?> system = XposedHelpers.findClass(
                    "android.provider.Settings$System", loader);
            XposedBridge.hookAllMethods(system, "getString", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && "system_locales".equals(
                            String.valueOf(param.args[1]))) {
                        param.setResult(locales.toLanguageTags());
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook settings-locale: " + t);
        }
        try {
            Class<?> ulocale = XposedHelpers.findClass("android.icu.util.ULocale", loader);
            XposedBridge.hookAllMethods(ulocale, "getDefault", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        param.setResult(XposedHelpers.callStaticMethod(ulocale, "forLocale",
                                value));
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook icu-locale: " + t);
        }
        XC_MethodHook apply = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                applyContextLocale(param.thisObject, value);
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", loader,
                    "attach", android.content.Context.class, apply);
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook app-resources: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", loader,
                    "attachBaseContext", android.content.Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            applyContextLocale(param.thisObject, value);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook activity-resources: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", loader,
                    "onConfigurationChanged", android.content.res.Configuration.class, apply);
            XposedHelpers.findAndHookMethod("android.app.Activity", loader,
                    "onConfigurationChanged", android.content.res.Configuration.class, apply);
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook config-resources: " + t);
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyContextLocale(Object object, Locale value) {
        if (!(object instanceof android.content.Context)) {
            return;
        }
        try {
            android.content.Context context = (android.content.Context) object;
            android.content.res.Resources resources = context.getResources();
            android.content.res.Configuration config =
                    new android.content.res.Configuration(resources.getConfiguration());
            config.setLocales(new android.os.LocaleList(value));
            resources.updateConfiguration(config, resources.getDisplayMetrics());
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook apply-locale: " + t);
        }
    }

    private static android.os.LocaleList localeList(HookConfig config, Locale value) {
        java.util.List<Locale> values = new java.util.ArrayList<>();
        for (String tag : config.localeList) {
            try {
                Locale parsed = Locale.forLanguageTag(tag.replace('_', '-'));
                if (!parsed.getLanguage().isEmpty() && !values.contains(parsed)) {
                    values.add(parsed);
                }
            } catch (Throwable ignored) {
            }
        }
        if (values.isEmpty()) {
            values.add(value);
        }
        return new android.os.LocaleList(values.toArray(new Locale[0]));
    }

    private static void constant(Class<?> clazz, String name, String value) {
        try {
            XposedBridge.hookAllMethods(clazz, name,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Class<?> type = param.method instanceof Method
                            ? ((Method) param.method).getReturnType() : String.class;
                    param.setResult(type == void.class ? null : value);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook " + name + ": " + t);
        }
    }
}
