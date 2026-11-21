package com.walter.claudewilson.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TimeZone;

import com.walter.claudewilson.model.TargetSpec;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Writes what the target process actually sees into its own files dir. */
final class SelfCheck {
    /** The ten su paths the target telemetry checks, in its own file view. */
    private static final String[] SU_PATHS = {
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/su/bin/su"
    };
    /** The seventeen package names in the target telemetry root-package list. */
    private static final String[] ROOT_PACKAGES = {
            "com.noshufou.android.su", "com.noshufou.android.su.elite",
            "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.koushikdutta.rommanager", "com.thirdparty.superuser",
            "com.yellowes.su", "com.dimonvideo.luckypatcher", "com.chelpus.lackypatch",
            "com.ramdroid.appquarantine", "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus", "de.robv.android.xposed.installer",
            "com.saurik.substrate", "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot", "com.formyhm.hideroot"
    };
    /** The seven directories the target telemetry checks for a writable mount. */
    private static final String[] RW_DIRS = {
            "/system", "/system/bin", "/system/sbin", "/system/xbin",
            "/vendor/bin", "/sbin", "/etc"
    };

    private SelfCheck() {}

    static void schedule(TargetSpec target, HookConfig config) {
        Thread worker = new Thread(() -> {
            try { Thread.sleep(2500L); } catch (InterruptedException ignored) {}
            Context context = null;
            for (int i = 0; i < 20 && context == null; i++) {
                try {
                    Class<?> activityThread = Class.forName("android.app.ActivityThread");
                    Object app = XposedHelpers.callStaticMethod(
                            activityThread, "currentApplication");
                    if (app instanceof Context) context = (Context) app;
                } catch (Throwable ignored) {
                    // Application may not be attached yet.
                }
                if (context == null) {
                    try { Thread.sleep(250L); } catch (InterruptedException ignored) {}
                }
            }
            if (context == null) {
                XposedBridge.log("ClaudeWilsonHook selfcheck: no context");
                return;
            }
            String body = collect(context, target, config);
            try {
                write(new File(context.getFilesDir(), "hookcheck.txt"), body);
                write(new File(context.getFilesDir(), "hookcheck." + processTag() + ".txt"),
                        body);
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook selfcheck write: " + t);
            }
            // Also publish a shell-readable copy under the target's own external files dir.
            // A non-root Shizuku shell can read this path, while it cannot read files/.
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir != null) {
                    dir.mkdirs();
                    write(new File(dir, "cw-hookcheck.txt"), body);
                    write(new File(dir, "cw-hookcheck." + processTag() + ".txt"), body);
                }
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook selfcheck external write: " + t);
            }
            // AppOps reports a runtime permission as "foreground" only after the process reaches
            // the foreground state, which can be several seconds after handleLoadPackage. A second
            // pass keeps the location line from freezing at the startup value.
            try { Thread.sleep(8000L); } catch (InterruptedException ignored) {}
            body = collect(context, target, config);
            try {
                write(new File(context.getFilesDir(), "hookcheck.txt"), body);
                write(new File(context.getFilesDir(), "hookcheck." + processTag() + ".txt"), body);
                File dir = context.getExternalFilesDir(null);
                if (dir != null) {
                    dir.mkdirs();
                    write(new File(dir, "cw-hookcheck.txt"), body);
                    write(new File(dir, "cw-hookcheck." + processTag() + ".txt"), body);
                }
            } catch (Throwable t) {
                if (HookEntry.debugLog) {
                    XposedBridge.log("ClaudeWilsonHook selfcheck refresh: " + t);
                }
            }
        }, "AsyncTask #9");
        worker.setDaemon(true);
        worker.start();
    }

    private static String processName() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] data = new byte[256];
            int count = in.read(data);
            if (count > 0) {
                int end = 0;
                while (end < count && data[end] != 0) {
                    end++;
                }
                if (end > 0) {
                    return new String(data, 0, end, "UTF-8");
                }
            }
        } catch (Throwable ignored) {
        }
        return "main";
    }

    private static String processTag() {
        String name = processName();
        int colon = name.indexOf(':');
        String tag = colon >= 0 ? name.substring(colon + 1) : "main";
        tag = tag.replaceAll("[^A-Za-z0-9_.-]", "_");
        return tag.isEmpty() ? "main" : tag;
    }

    private static String collect(Context context, TargetSpec target, HookConfig config) {
        StringBuilder out = new StringBuilder();
        out.append("engine=vector\n");
        out.append("target=").append(target.id()).append('\n');
        out.append("target_pkg=").append(target.packageName()).append('\n');
        out.append("process=").append(processName()).append('\n');
        out.append("cc_hint=").append(config.countryCodeHint).append('\n');
        out.append("armed=1\n");
        out.append("selfcheck=2\n");
        out.append("pid=").append(android.os.Process.myPid()).append('\n');
        out.append("config=").append(config.signature()).append('\n');
        out.append("telemetry=").append(TelephonyHooks.status).append('\n');
        LocationHooks.refreshStatus();
        out.append("location=").append(LocationHooks.status).append('\n');
        out.append("location_served=").append(LocationHooks.served()).append('\n');
        out.append("location_last=").append(LocationHooks.lastServedAgeMs()).append('\n');
        out.append("wifi=").append(WifiHooks.status).append('\n');
        out.append("telemetry=").append(TelemetryHooks.status).append('\n');
        out.append("wifi_scan=").append(WifiHooks.scanServed.get()).append('\n');
        out.append("wifi_connection=").append(WifiHooks.connectionServed.get()).append('\n');
        out.append("ble=").append(BleHooks.status).append('\n');
        out.append("ble_scan=").append(BleHooks.scanServed.get()).append('\n');
        out.append("ble_bonded=").append(BleHooks.bondedServed.get()).append('\n');
        out.append("sensors=").append(SensorHooks.status).append('\n');
        out.append("sensor_events=").append(SensorHooks.events.get()).append('\n');
        out.append("webview=").append(WebViewHooks.status).append('\n');
        out.append("simop=").append(value(() -> {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getSimOperator();
        })).append('\n');
        out.append("simiso=").append(value(() -> {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getSimCountryIso();
        })).append('\n');
        out.append("simname=").append(value(() -> {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getSimOperatorName();
        })).append('\n');
        out.append("netop=").append(value(() -> {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getNetworkOperator();
        })).append('\n');
        out.append("netiso=").append(value(() -> {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getNetworkCountryIso();
        })).append('\n');
        out.append("netname=").append(value(() -> {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getNetworkOperatorName();
        })).append('\n');
        out.append("roaming=").append(value(() -> {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            return tm == null ? null : String.valueOf(tm.isNetworkRoaming());
        })).append('\n');
        out.append("sub=").append(value(() -> {
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return null;
            java.util.List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.isEmpty()) return null;
            SubscriptionInfo info = list.get(0);
            return info.getMccString() + info.getMncString() + "/"
                    + info.getCountryIso() + "/" + info.getCarrierName();
        })).append('\n');
        out.append("aid=").append(Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID)).append('\n');
        out.append("manufacturer=").append(Build.MANUFACTURER).append('\n');
        out.append("brand=").append(Build.BRAND).append('\n');
        out.append("device=").append(Build.DEVICE).append('\n');
        out.append("product=").append(Build.PRODUCT).append('\n');
        out.append("hardware=").append(Build.HARDWARE).append('\n');
        out.append("model=").append(Build.MODEL).append('\n');
        out.append("fp=").append(Build.FINGERPRINT).append('\n');
        out.append("sdk_int=").append(Build.VERSION.SDK_INT).append('\n');
        int sdkIntFull = readSdkIntFull();
        if (sdkIntFull > 0) {
            out.append("sdk_int_full=").append(sdkIntFull).append('\n');
        }
        out.append("release=").append(Build.VERSION.RELEASE).append('\n');
        out.append("sdk_prop=").append(value(() -> prop("ro.build.version.sdk"))).append('\n');
        String sdkFullProp = prop("ro.build.version.sdk_full");
        if (sdkFullProp != null && !sdkFullProp.isEmpty()) {
            out.append("sdk_full_prop=").append(sdkFullProp.replace('\n', ' ')).append('\n');
        }
        out.append("su_paths=").append(suPaths()).append('\n');
        out.append("su_packages=").append(suPackages(context)).append('\n');
        out.append("su_props=").append(suProps()).append('\n');
        out.append("su_mounts=").append(suMounts()).append('\n');
        // Keep the legacy three-field view for the ChatGPT checklist and evidence collector.
        boolean suFile = new File("/system/bin/su").exists();
        boolean suOs = accessible("/system/bin/su");
        out.append("su_file=").append(suFile ? "visible" : "hidden").append('\n');
        out.append("su_os=").append(suOs ? "visible" : "hidden").append('\n');
        out.append("su_system=").append(suFile || suOs ? "visible" : "hidden").append('\n');
        out.append("locale=").append(Locale.getDefault()).append('\n');
        out.append("res_locale=").append(value(() -> {
            if (context.getResources().getConfiguration().getLocales().isEmpty()) return null;
            return context.getResources().getConfiguration().getLocales().get(0).toString();
        })).append('\n');
        out.append("locale_list=").append(android.os.LocaleList.getDefault()).append('\n');
        out.append("tz=").append(TimeZone.getDefault().getID()).append('\n');
        out.append("ifaces=").append(value(() -> {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return null;
            StringBuilder names = new StringBuilder();
            while (all.hasMoreElements()) {
                names.append(all.nextElement().getName()).append(',');
            }
            return names.toString();
        })).append('\n');
        out.append("iface_ips=").append(value(() -> {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return null;
            StringBuilder addresses = new StringBuilder();
            while (all.hasMoreElements()) {
                NetworkInterface face = all.nextElement();
                if (face.isLoopback() || !face.isUp()) continue;
                Enumeration<InetAddress> list = face.getInetAddresses();
                while (list.hasMoreElements()) {
                    InetAddress address = list.nextElement();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    addresses.append(address.getHostAddress()).append(',');
                }
            }
            return addresses.toString();
        })).append('\n');
        out.append("ipv6_spoof=");
        InetAddress sv6 = NetworkHooks.spoofedAddress();
        out.append(config.ipv6Disabled ? "disabled" : (sv6 != null ? sv6.getHostAddress() : "")).append('\n');
        out.append("link_addrs=").append(value(() -> {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.Network active = cm.getActiveNetwork();
            if (active == null) return "";
            android.net.LinkProperties lp = cm.getLinkProperties(active);
            if (lp == null) return "";
            StringBuilder sb = new StringBuilder();
            String ifn = lp.getInterfaceName();
            sb.append(ifn != null ? ifn : "").append('|');
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                if (la != null && la.getAddress() != null) {
                    sb.append(la.getAddress().getHostAddress()).append('/');
                    sb.append(la.getPrefixLength()).append(',');
                }
            }
            return sb.toString();
        })).append('\n');
        out.append("link_dns=").append(value(() -> {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.Network active = cm.getActiveNetwork();
            if (active == null) return "";
            android.net.LinkProperties lp = cm.getLinkProperties(active);
            if (lp == null) return "";
            StringBuilder sb = new StringBuilder();
            for (InetAddress d : lp.getDnsServers()) {
                if (d != null) sb.append(d.getHostAddress()).append(',');
            }
            return sb.toString();
        })).append('\n');
        out.append("link_mtu=").append(value(() -> {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.Network active = cm.getActiveNetwork();
            if (active == null) return "";
            android.net.LinkProperties lp = cm.getLinkProperties(active);
            return lp == null ? "" : String.valueOf(lp.getMtu());
        })).append('\n');
        out.append("link_routes=").append(value(() -> {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.Network active = cm.getActiveNetwork();
            if (active == null) return "";
            android.net.LinkProperties lp = cm.getLinkProperties(active);
            if (lp == null) return "";
            StringBuilder sb = new StringBuilder();
            for (android.net.RouteInfo r : lp.getRoutes()) {
                if (r != null && r.getDestination() != null) {
                    sb.append(r.getDestination()).append('@')
                            .append(r.getInterface()).append(',');
                }
            }
            return sb.toString();
        })).append('\n');
        out.append("net_vpn=").append(value(() -> {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.Network active = cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = active != null
                    ? cm.getNetworkCapabilities(active) : null;
            boolean hasVpn = nc != null && nc.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_VPN);
            boolean notVpn = nc != null && nc.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
            Object ti = nc != null ? nc.getTransportInfo() : null;
            String tiName = ti == null ? "null" : ti.getClass().getSimpleName();
            boolean ncStrHasVpn = nc != null && (nc.toString().contains("|VPN")
                    || nc.toString().contains("VpnTransportInfo"));
            @SuppressWarnings("deprecation")
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            @SuppressWarnings("deprecation")
            String niType = ni == null ? "none" : ni.getTypeName();
            @SuppressWarnings("deprecation")
            String niExtra = ni == null || ni.getExtraInfo() == null ? "null" : ni.getExtraInfo();
            return "hasVpn=" + hasVpn + ",notVpn=" + notVpn + ",ti=" + tiName
                    + ",ncStrVpn=" + ncStrHasVpn + ",ni=" + niType + ",extra=" + niExtra;
        })).append('\n');
        out.append("proxy=").append(value(() -> {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.getDefaultProxy() == null ? "none" : "present";
        })).append('\n');
        out.append("native=").append(NativeHooks.isReady() ? 1 : 0).append('\n');
        return out.toString();
    }

    private static void write(File out, String body) throws Exception {
        FileOutputStream stream = new FileOutputStream(out);
        stream.write(body.getBytes("UTF-8"));
        stream.close();
    }

    private static String suPaths() {
        int count = 0;
        for (String path : SU_PATHS) {
            try {
                if (new File(path).exists()) {
                    count++;
                }
            } catch (Throwable ignored) {
                // A path that cannot be checked is not a positive hit.
            }
        }
        return count + "/" + SU_PATHS.length;
    }

    private static String suPackages(Context context) {
        PackageManager manager = context.getPackageManager();
        if (manager == null) {
            return "err";
        }
        int count = 0;
        for (String pkg : ROOT_PACKAGES) {
            try {
                manager.getPackageInfo(pkg, 0);
                count++;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not visible to the target process.
            } catch (Throwable ignored) {
                // Unknown visibility is not a positive hit.
            }
        }
        return count + "/" + ROOT_PACKAGES.length;
    }

    private static String suProps() {
        String debug = prop("ro.debuggable");
        String secure = prop("ro.secure");
        if (debug == null && secure == null) {
            return "err";
        }
        return "1".equals(debug) || "0".equals(secure) ? "danger" : "clean";
    }

    private static int readSdkIntFull() {
        try {
            return Build.VERSION.class.getField("SDK_INT_FULL").getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String prop(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, key);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String suMounts() {
        int count = 0;
        boolean read = false;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\s+");
                if (fields.length < 4 || !writableDir(fields[1])) {
                    continue;
                }
                for (String option : fields[3].split(",")) {
                    if ("rw".equals(option)) {
                        count++;
                        break;
                    }
                }
            }
            read = true;
        } catch (Throwable ignored) {
            // The target may not be able to read its own mount table; say so.
        }
        return read ? count + "/" + RW_DIRS.length : "err";
    }

    private static boolean writableDir(String path) {
        for (String dir : RW_DIRS) {
            if (dir.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static String value(Supplier supplier) {
        try {
            String value = supplier.get();
            return value == null ? "null" : value.replace('\n', ' ');
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static boolean accessible(String path) {
        try {
            Os.access(path, OsConstants.F_OK);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private interface Supplier {
        String get() throws Throwable;
    }
}
