package com.walter.claudewilson.hook;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** SSAID, locale, timezone and installer source. */
final class IdentityHooks {
    private IdentityHooks() {}

    static void install(ClassLoader loader, HookConfig config) {
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
            constant(pm, "getInstallerPackageName", installer);
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook installer-pm: " + t);
        }
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
            XposedBridge.hookAllMethods(config, "getLocale",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(value);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook config-locales: " + t);
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
