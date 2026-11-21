package com.walter.claudewilson;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;

import com.walter.claudewilson.model.AppVisibilityPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real-time HMA/HMAL device preset scanner (uses existing {@code QUERY_ALL_PACKAGES} permission,
 * zero {@code INTERNET}).
 *
 * <p>Inspects actual installed manifests ({@code GET_META_DATA}, {@code GET_PERMISSIONS}),
 * registered {@code AccessibilityService} and {@code InputMethod} components, known root/detector/
 * custom-ROM/suspicious signatures, and live {@code Settings.Global}/{@code Settings.Secure} state
 * for Developer Options, Accessibility, and Input Methods.
 */
final class HmaPresetScanner {
    static final String GROUP_XPOSED = "xposed";
    static final String GROUP_ROOT = "root";
    static final String GROUP_SHIZUKU = "shizuku";
    static final String GROUP_SUSPICIOUS = "suspicious";
    static final String GROUP_A11Y_APPS = "a11y_apps";
    static final String GROUP_DETECTORS = "detectors";
    static final String GROUP_CUSTOM_ROM = "custom_rom";
    static final String GROUP_IME_APPS = "ime_apps";

    static final Set<String> ALL_GROUPS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(java.util.Arrays.asList(
                    GROUP_XPOSED,
                    GROUP_ROOT,
                    GROUP_SHIZUKU,
                    GROUP_SUSPICIOUS,
                    GROUP_A11Y_APPS,
                    GROUP_DETECTORS,
                    GROUP_CUSTOM_ROM,
                    GROUP_IME_APPS)));

    private static final Object FAST_CACHE_LOCK = new Object();
    private static String fastCacheKey = "";
    private static Set<String> fastCacheResult = Collections.emptySet();

    private static final String SHIZUKU_PERM = "moe.shizuku.manager.permission.API_V23";
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    static final class MatchedApp {
        final String pkg;
        final String label;
        final boolean system;
        final boolean essential;
        final boolean forcedHidden;
        final Drawable icon;

        MatchedApp(String pkg, String label, boolean system, boolean essential,
                   boolean forcedHidden, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.essential = essential;
            this.forcedHidden = forcedHidden;
            this.icon = icon;
        }
    }

    static final class PresetGroup {
        final String id;
        final String title;
        final List<MatchedApp> apps;

        PresetGroup(String id, String title, List<MatchedApp> apps) {
            this.id = id;
            this.title = title;
            this.apps = apps;
        }

        Set<String> packageNames() {
            Set<String> out = new LinkedHashSet<String>();
            for (MatchedApp a : apps) {
                out.add(a.pkg);
            }
            return out;
        }
    }

    static final class ScanResult {
        final List<PresetGroup> groups;
        final boolean devOptionsOn;
        final boolean adbOn;
        final int enabledA11yCount;
        final String defaultImePkg;
        final boolean defaultImeThirdParty;

        ScanResult(List<PresetGroup> groups, boolean devOptionsOn, boolean adbOn,
                   int enabledA11yCount, String defaultImePkg, boolean defaultImeThirdParty) {
            this.groups = groups;
            this.devOptionsOn = devOptionsOn;
            this.adbOn = adbOn;
            this.enabledA11yCount = enabledA11yCount;
            this.defaultImePkg = defaultImePkg;
            this.defaultImeThirdParty = defaultImeThirdParty;
        }
    }

    private HmaPresetScanner() {
    }

    static ScanResult scan(Context ctx, String targetPkg) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, MatchedApp> byPkg = new HashMap<String, MatchedApp>();

        Set<String> xposedSet = new LinkedHashSet<String>();
        Set<String> rootSet = new LinkedHashSet<String>();
        Set<String> shizukuSet = new LinkedHashSet<String>();
        Set<String> suspiciousSet = new LinkedHashSet<String>();
        Set<String> a11ySet = new LinkedHashSet<String>();
        Set<String> detectorSet = new LinkedHashSet<String>();
        Set<String> customRomSet = new LinkedHashSet<String>();
        Set<String> imeSet = new LinkedHashSet<String>();

        // 1. Scan installed apps with meta-data for Xposed modules + static classifiers
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null || ai.packageName.isEmpty()) continue;
                String pkg = ai.packageName;
                boolean isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                boolean forcedHidden = AppVisibilityPolicy.isForcedHidden(pkg);
                boolean essential = !forcedHidden
                        && AppVisibilityPolicy.isEssentialPackage(pkg, targetPkg);
                CharSequence cs = pm.getApplicationLabel(ai);
                String label = cs == null || cs.length() == 0 ? pkg : cs.toString();
                Drawable icon = null;
                try {
                    icon = pm.getApplicationIcon(ai);
                } catch (Throwable ignored) {
                }
                byPkg.put(pkg, new MatchedApp(pkg, label, isSys, essential, forcedHidden, icon));

                if (hasXposedMetadata(ai.metaData)
                        || AppVisibilityPolicy.isXposedManagerPackage(pkg)) {
                    xposedSet.add(pkg);
                }
                if (AppVisibilityPolicy.isRootManagerPackage(pkg)) {
                    rootSet.add(pkg);
                }
                if (SHIZUKU_PKG.equals(pkg)) {
                    shizukuSet.add(pkg);
                }
                if (AppVisibilityPolicy.isDetectorPackage(pkg)) {
                    detectorSet.add(pkg);
                }
                if (AppVisibilityPolicy.isCustomRomPackage(pkg)) {
                    customRomSet.add(pkg);
                }
                if (!essential && AppVisibilityPolicy.isSuspiciousPackage(pkg)) {
                    suspiciousSet.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Scan requestedPermissions for Shizuku modules
        try {
            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            for (PackageInfo pi : pkgs) {
                if (pi == null || pi.packageName == null || pi.requestedPermissions == null) {
                    continue;
                }
                for (String perm : pi.requestedPermissions) {
                    if (SHIZUKU_PERM.equals(perm)) {
                        shizukuSet.add(pi.packageName);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 3. Scan registered AccessibilityService providers
        try {
            Intent a11yIntent = new Intent("android.accessibilityservice.AccessibilityService");
            List<ResolveInfo> ris = pm.queryIntentServices(a11yIntent, PackageManager.MATCH_ALL);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri != null && ri.serviceInfo != null && ri.serviceInfo.packageName != null) {
                        String pkg = ri.serviceInfo.packageName;
                        if (!AppVisibilityPolicy.isEssentialPackage(pkg, targetPkg)) {
                            a11ySet.add(pkg);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 4. Scan registered InputMethod services
        try {
            Intent imeIntent = new Intent("android.view.InputMethod");
            List<ResolveInfo> ris = pm.queryIntentServices(imeIntent, PackageManager.MATCH_ALL);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri != null && ri.serviceInfo != null && ri.serviceInfo.packageName != null) {
                        String pkg = ri.serviceInfo.packageName;
                        if (!AppVisibilityPolicy.isEssentialPackage(pkg, targetPkg)) {
                            imeSet.add(pkg);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Assemble ordered HMA preset groups
        Map<String, String> titles = new LinkedHashMap<String, String>();
        titles.put(GROUP_XPOSED, Lang.t("LSPosed / Xposed 模块", "LSPosed / Xposed Modules"));
        titles.put(GROUP_ROOT, Lang.t("Root 管理器", "Root Managers"));
        titles.put(GROUP_SHIZUKU, Lang.t("Shizuku 模块", "Shizuku Modules"));
        titles.put(GROUP_SUSPICIOUS, Lang.t("可疑应用", "Suspicious Apps"));
        titles.put(GROUP_A11Y_APPS, Lang.t("无障碍应用", "Accessibility Apps"));
        titles.put(GROUP_DETECTORS, Lang.t("检测器应用", "Detector Apps"));
        titles.put(GROUP_CUSTOM_ROM, Lang.t("自定义 ROM 应用", "Custom ROM Apps"));
        titles.put(GROUP_IME_APPS, Lang.t("输入法", "Input Methods"));

        Map<String, Set<String>> buckets = new LinkedHashMap<String, Set<String>>();
        buckets.put(GROUP_XPOSED, xposedSet);
        buckets.put(GROUP_ROOT, rootSet);
        buckets.put(GROUP_SHIZUKU, shizukuSet);
        buckets.put(GROUP_SUSPICIOUS, suspiciousSet);
        buckets.put(GROUP_A11Y_APPS, a11ySet);
        buckets.put(GROUP_DETECTORS, detectorSet);
        buckets.put(GROUP_CUSTOM_ROM, customRomSet);
        buckets.put(GROUP_IME_APPS, imeSet);

        List<PresetGroup> groups = new ArrayList<PresetGroup>();
        for (Map.Entry<String, String> e : titles.entrySet()) {
            Set<String> pkgs = buckets.get(e.getKey());
            List<MatchedApp> matched = new ArrayList<MatchedApp>();
            if (pkgs != null) {
                for (String p : pkgs) {
                    MatchedApp ma = byPkg.get(p);
                    if (ma != null) {
                        matched.add(ma);
                    }
                }
            }
            Collections.sort(matched, new Comparator<MatchedApp>() {
                @Override public int compare(MatchedApp a, MatchedApp b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            groups.add(new PresetGroup(e.getKey(), e.getValue(), matched));
        }

        // 5. Read live system-feature states (Developer Options, Accessibility, Default IME)
        boolean devOn = false;
        boolean adbOn = false;
        int a11yCount = 0;
        String defaultImePkg = "";
        boolean defaultIme3p = false;
        try {
            ContentResolver cr = ctx.getContentResolver();
            devOn = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
            adbOn = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0
                    || Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 0;
            String enabledA11y = Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledA11y != null && !enabledA11y.trim().isEmpty()) {
                for (String s : enabledA11y.split(":")) {
                    if (s != null && !s.trim().isEmpty()) a11yCount++;
                }
            }
            String defIme = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
            if (defIme != null && !defIme.trim().isEmpty()) {
                int slash = defIme.indexOf('/');
                defaultImePkg = slash > 0 ? defIme.substring(0, slash) : defIme;
                MatchedApp ma = byPkg.get(defaultImePkg);
                defaultIme3p = ma != null && !ma.system && !ma.essential;
            }
        } catch (Throwable ignored) {
        }

        return new ScanResult(groups, devOn, adbOn, a11yCount, defaultImePkg, defaultIme3p);
    }

    /**
     * Fast, icon-free resolver for {@code appVisExtraHidden} (system packages matched by active
     * HMA preset groups, e.g. Custom ROM / Accessibility / IME system components) so default
     * payloads include preset protection immediately without waiting for the sheet to open.
     */
    static Set<String> resolveSystemExtraHidden(Context ctx, String targetPkg,
                                                Set<String> activeGroups) {
        if (ctx == null || activeGroups == null || activeGroups.isEmpty()) {
            return Collections.emptySet();
        }
        String key = (targetPkg == null ? "" : targetPkg) + "|" + activeGroups.toString();
        synchronized (FAST_CACHE_LOCK) {
            if (key.equals(fastCacheKey)) {
                return fastCacheResult;
            }
        }
        Set<String> out = new LinkedHashSet<String>();
        try {
            PackageManager pm = ctx.getPackageManager();
            boolean wantXposed = activeGroups.contains(GROUP_XPOSED);
            boolean wantRoot = activeGroups.contains(GROUP_ROOT);
            boolean wantShizuku = activeGroups.contains(GROUP_SHIZUKU);
            boolean wantSusp = activeGroups.contains(GROUP_SUSPICIOUS);
            boolean wantA11y = activeGroups.contains(GROUP_A11Y_APPS);
            boolean wantDet = activeGroups.contains(GROUP_DETECTORS);
            boolean wantRom = activeGroups.contains(GROUP_CUSTOM_ROM);
            boolean wantIme = activeGroups.contains(GROUP_IME_APPS);

            Set<String> shizukuPkgs = new LinkedHashSet<String>();
            if (wantShizuku) {
                shizukuPkgs.add(SHIZUKU_PKG);
                try {
                    List<PackageInfo> pis = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
                    for (PackageInfo pi : pis) {
                        if (pi == null || pi.packageName == null || pi.requestedPermissions == null) {
                            continue;
                        }
                        for (String perm : pi.requestedPermissions) {
                            if (SHIZUKU_PERM.equals(perm)) {
                                shizukuPkgs.add(pi.packageName);
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Set<String> a11yPkgs = new LinkedHashSet<String>();
            if (wantA11y) {
                try {
                    List<ResolveInfo> ris = pm.queryIntentServices(
                            new Intent("android.accessibilityservice.AccessibilityService"),
                            PackageManager.MATCH_ALL);
                    if (ris != null) {
                        for (ResolveInfo ri : ris) {
                            if (ri != null && ri.serviceInfo != null
                                    && ri.serviceInfo.packageName != null) {
                                a11yPkgs.add(ri.serviceInfo.packageName);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Set<String> imePkgs = new LinkedHashSet<String>();
            if (wantIme) {
                try {
                    List<ResolveInfo> ris = pm.queryIntentServices(
                            new Intent("android.view.InputMethod"),
                            PackageManager.MATCH_ALL);
                    if (ris != null) {
                        for (ResolveInfo ri : ris) {
                            if (ri != null && ri.serviceInfo != null
                                    && ri.serviceInfo.packageName != null) {
                                imePkgs.add(ri.serviceInfo.packageName);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            List<ApplicationInfo> apps = pm.getInstalledApplications(
                    wantXposed ? PackageManager.GET_META_DATA : 0);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null || ai.packageName.isEmpty()) continue;
                boolean isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (!isSys) continue;
                String pkg = ai.packageName;
                if (AppVisibilityPolicy.isForcedHidden(pkg)
                        || AppVisibilityPolicy.isEssentialPackage(pkg, targetPkg)) {
                    continue;
                }
                boolean match = (wantRom && AppVisibilityPolicy.isCustomRomPackage(pkg))
                        || (wantA11y && a11yPkgs.contains(pkg))
                        || (wantIme && imePkgs.contains(pkg))
                        || (wantXposed && (hasXposedMetadata(ai.metaData)
                                || AppVisibilityPolicy.isXposedManagerPackage(pkg)))
                        || (wantRoot && AppVisibilityPolicy.isRootManagerPackage(pkg))
                        || (wantShizuku && shizukuPkgs.contains(pkg))
                        || (wantDet && AppVisibilityPolicy.isDetectorPackage(pkg))
                        || (wantSusp && AppVisibilityPolicy.isSuspiciousPackage(pkg));
                if (match) {
                    out.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }
        Set<String> frozen = Collections.unmodifiableSet(out);
        synchronized (FAST_CACHE_LOCK) {
            fastCacheKey = key;
            fastCacheResult = frozen;
        }
        return frozen;
    }

    private static boolean hasXposedMetadata(Bundle meta) {
        if (meta == null) return false;
        if (meta.containsKey("xposedmodule")) {
            Object v = meta.get("xposedmodule");
            if (v instanceof Boolean) {
                return ((Boolean) v).booleanValue();
            }
            return v != null && !"false".equalsIgnoreCase(String.valueOf(v));
        }
        return meta.containsKey("xposedminversion") || meta.containsKey("xposedscope");
    }
}
