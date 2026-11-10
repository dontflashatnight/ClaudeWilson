package com.walter.claudewilson.platform;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.walter.claudewilson.model.Account;
import com.walter.claudewilson.model.AttestationFacts;
import com.walter.claudewilson.model.BuildValues;
import com.walter.claudewilson.model.NetworkFacts;
import com.walter.claudewilson.model.PlatformFacts;
import com.walter.claudewilson.model.TlsFacts;
import com.walter.claudewilson.model.Mount;
import com.walter.claudewilson.model.SimValues;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Tables;
import com.walter.claudewilson.model.TargetEvidence;
import com.walter.claudewilson.model.Text;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * Collects one {@link Snapshot}. The only class in this package that reads device state, and the
 * only place where an Android API appears at all above {@link Shell} and {@link Diag}.
 */
public final class AndroidSnapshotCollector {

    /**
     * Last full snapshot. Fast reconciles reuse the expensive, region-independent facts
     * (attestation, TLS issuers, package census) and re-collect only the cheap ones.
     */
    private static volatile Snapshot cached;

    private static final String[] PROPS = {
            "ro.product.manufacturer",
            "ro.product.brand",
            "ro.product.model",
            "ro.product.device",
            "ro.product.name",
            "ro.product.locale",
            "ro.csc.sales_code",
            "persist.sys.locale",
            "gsm.sim.operator.numeric",
            "gsm.sim.operator.iso-country",
            "gsm.operator.iso-country",
            "gsm.sim.operator.alpha",
            "ro.build.fingerprint",
            "ro.build.tags",
            "ro.build.type",
            "ro.build.id",
            "ro.build.version.incremental",
            "ro.build.display.id",
            "ro.product.first_api_level",
            "ro.system.build.fingerprint",
            "ro.product.build.fingerprint",
            "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint",
            "ro.bootimage.build.fingerprint",
            "ro.boot.flash.locked",
            "ro.boot.verifiedbootstate",
            "ro.boot.vbmeta.device_state",
            "ro.boot.veritymode",
            "ro.boot.warranty_bit",
            "sys.oem_unlock_allowed",
            "ro.debuggable",
            "ro.secure",
    };

    public Snapshot collect(Context context, String targetPackage,
                            List<Account> accounts, boolean accountsReadable) {
        return collect(context, targetPackage, accounts, accountsReadable, false, false);
    }

    public Snapshot collect(Context context, String targetPackage,
                            List<Account> accounts, boolean accountsReadable, boolean fast) {
        return collect(context, targetPackage, accounts, accountsReadable, fast, false);
    }

    /**
     * @param fast when true, reuses the last full snapshot's attestation, TLS and package census.
     *             Those facts do not change when the region does, and they are the expensive half
     *             of a scan (a fresh Keystore key for every attestation). A fast scan with no
     *             cached snapshot falls back to a full one.
     * @param mirrorVpn when true, the network facts are filtered the same way the target's
     *                  injected payload filters them (tun/ppp/wg/tap/utun interfaces, VPN
     *                  transport, VPN-declared proxy). This replaces the old frida self-injection
     *                  that made the root channel visibly slower. The filter is a read-side view,
     *                  not a claim that the traffic itself bypasses the tunnel.
     */
    public Snapshot collect(Context context, String targetPackage,
                            List<Account> accounts, boolean accountsReadable, boolean fast,
                            boolean mirrorVpn) {
        return collect(context, targetPackage, accounts, accountsReadable, fast, mirrorVpn,
                TargetEvidence.EMPTY);
    }

    public Snapshot collect(Context context, String targetPackage,
                            List<Account> accounts, boolean accountsReadable, boolean fast,
                            boolean mirrorVpn, TargetEvidence targetEvidence) {
        return collect(context, targetPackage, accounts, accountsReadable, fast, mirrorVpn,
                targetEvidence, com.walter.claudewilson.model.PermissionFacts.EMPTY,
                com.walter.claudewilson.model.LocationFacts.EMPTY);
    }

    public Snapshot collect(Context context, String targetPackage,
                            List<Account> accounts, boolean accountsReadable, boolean fast,
                            boolean mirrorVpn, TargetEvidence targetEvidence,
                            com.walter.claudewilson.model.PermissionFacts permissions,
                            com.walter.claudewilson.model.LocationFacts location) {
        Context app = context.getApplicationContext();
        Tables tables = TablesLoader.load(app);
        Snapshot base = fast ? cached : null;
        boolean reuse = base != null;

        Map<String, Text> props = collectProps();
        Snapshot.Builder builder = Snapshot.builder()
                .capturedAt(System.currentTimeMillis())
                .tables(tables)
                .buildValues(collectBuild(props))
                .sim(simValues(app))
                .selinuxEnforce(selinuxEnforce())
                .androidId(androidId(app))
                .platform(reuse ? base.platform() : collectPlatform(app))
                .tls(reuse ? base.tls() : collectTls())
                .network(collectNetwork(app, mirrorVpn))
                .targetEvidence(targetEvidence)
                .permissions(permissions)
                .location(location);
        for (Map.Entry<String, Text> entry : props.entrySet()) {
            builder.prop(entry.getKey(), entry.getValue());
        }
        collectMounts(builder);
        if (reuse) {
            builder.packages(base.packages(), base.packageLabels(), base.packagesReadable());
            builder.installedPackages(
                    new ArrayList<String>(base.installedPackages()),
                    base.installedReadable());
        } else {
            collectPackages(app, builder);
        }
        if (targetEvidence != null && targetEvidence.collected()) {
            builder.targetInstall(
                    targetEvidence.installer().isEmpty()
                            ? Text.unreadable() : Text.of(targetEvidence.installer()),
                    targetEvidence.version().isEmpty()
                            ? Text.unreadable() : Text.of(targetEvidence.version()),
                    targetEvidence.installed());
        } else {
            collectTarget(app, targetPackage, builder);
        }
        builder.hiddenApiPolicy(hiddenApiPolicy(app));
        builder.accounts(accounts, accountsReadable);
        Snapshot snapshot = builder.build();
        if (!fast || !reuse) {
            cached = snapshot;
        }
        return snapshot;
    }

    /**
     * Reads the properties we need with one process instead of one per key: {@code getprop} with
     * no arguments prints the whole property area. Falls back to per-key calls if the dump is
     * unreadable, so a restricted dump degrades to the old behaviour rather than to unknowns.
     */
    private static Map<String, Text> collectProps() {
        Map<String, Text> props = new LinkedHashMap<String, Text>();
        Text dump = Shell.all("getprop");
        Map<String, String> parsed = dump.isReadable() ? parsePropDump(dump.value()) : null;
        for (String key : PROPS) {
            props.put(key, parsed == null
                    ? Shell.firstLine("getprop", key)
                    : Text.of(parsed.get(key)));
        }
        return props;
    }

    /** Parses the {@code [key]: [value]} lines printed by {@code getprop}. */
    private static Map<String, String> parsePropDump(String dump) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String line : dump.split("\n")) {
            int separator = line.indexOf("]: [");
            if (!line.startsWith("[") || separator < 0) {
                continue;
            }
            int end = line.endsWith("]") ? line.length() - 1 : line.length();
            if (separator + 4 > end) {
                continue;
            }
            values.put(line.substring(1, separator), line.substring(separator + 4, end));
        }
        return values;
    }

    /** selinuxfs first, then the binary; either source answering is enough. */
    private static Text selinuxEnforce() {
        Text fromFile = readFirstLine("/sys/fs/selinux/enforce");
        if (fromFile.isReadable() && !fromFile.isEmpty()) {
            return fromFile;
        }
        return Shell.firstLine("getenforce");
    }

    private static Text readFirstLine(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            return Text.of(reader.readLine());
        } catch (Exception e) {
            Diag.warn("files", path + " unreadable", e);
            return Text.unreadable();
        } finally {
            close(reader);
        }
    }

    private static Text androidId(Context app) {
        try {
            return Text.of(Settings.Secure.getString(app.getContentResolver(),
                    Settings.Secure.ANDROID_ID));
        } catch (Exception e) {
            Diag.warn("android_id", "read failed", e);
            return Text.unreadable();
        }
    }

    private static BuildValues collectBuild(Map<String, Text> props) {
        return BuildValues.builder()
                .manufacturer(Build.MANUFACTURER)
                .brand(Build.BRAND)
                .model(Build.MODEL)
                .device(Build.DEVICE)
                .product(Build.PRODUCT)
                .type(Build.TYPE)
                .buildId(Build.ID)
                .incremental(Build.VERSION.INCREMENTAL)
                .display(Build.DISPLAY)
                .fingerprint(Build.FINGERPRINT)
                .tags(Build.TAGS)
                .firstApiLevel(props.get("ro.product.first_api_level").value())
                .time(Build.TIME)
                .build();
    }

    private static SimValues simValues(Context app) {
        TelephonyManager telephony =
                (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
        Text operator = Text.unreadable();
        Text simCountry = Text.unreadable();
        Text networkCountry = Text.unreadable();
        Text carrier = Text.unreadable();
        if (telephony != null) {
            try {
                operator = Text.of(telephony.getSimOperator());
            } catch (Exception e) {
                Diag.warn("sim", "getSimOperator", e);
            }
            try {
                simCountry = Text.of(telephony.getSimCountryIso());
            } catch (Exception e) {
                Diag.warn("sim", "getSimCountryIso", e);
            }
            try {
                networkCountry = Text.of(telephony.getNetworkCountryIso());
            } catch (Exception e) {
                Diag.warn("sim", "getNetworkCountryIso", e);
            }
            try {
                carrier = Text.of(telephony.getNetworkOperatorName());
            } catch (Exception e) {
                Diag.warn("sim", "getNetworkOperatorName", e);
            }
        }
        // The property area is what the persona writes, and it is also what the target app reads
        // through getSimOperator(). TelephonyManager can answer empty on a device whose only
        // subscription is an eSIM, which used to make this layer report "no sim" while the rest of
        // the panel showed a coherent persona trio.
        operator = preferProp(operator, "gsm.sim.operator.numeric");
        simCountry = preferProp(simCountry, "gsm.sim.operator.iso-country");
        carrier = preferProp(carrier, "gsm.sim.operator.alpha");
        networkCountry = preferProp(networkCountry, "gsm.operator.iso-country");
        int subscriptions = 0;
        try {
            SubscriptionManager manager = app.getSystemService(SubscriptionManager.class);
            if (manager != null) {
                List<SubscriptionInfo> active = manager.getActiveSubscriptionInfoList();
                subscriptions = active == null ? 0 : active.size();
                // The identity belongs to the subscription that carries data, not to the first row:
                // this device lists a disabled pSIM before the live eSIM.
                if (active != null && !active.isEmpty()) {
                    int wanted = SubscriptionManager.getDefaultDataSubscriptionId();
                    SubscriptionInfo chosen = active.get(0);
                    for (SubscriptionInfo candidate : active) {
                        if (candidate.getSubscriptionId() == wanted) {
                            chosen = candidate;
                            break;
                        }
                    }
                    if (operator.isEmpty()) {
                        String mcc = chosen.getMccString();
                        String mnc = chosen.getMncString();
                        if (mcc != null && mnc != null) {
                            operator = Text.of(mcc + mnc);
                        }
                    }
                    if (simCountry.isEmpty()) {
                        String iso = chosen.getCountryIso();
                        if (iso != null) {
                            simCountry = Text.of(iso);
                        }
                    }
                    if (carrier.isEmpty() && chosen.getCarrierName() != null) {
                        carrier = Text.of(chosen.getCarrierName().toString());
                    }
                }
            }
        } catch (Exception e) {
            Diag.warn("sim", "subscriptions", e);
        }
        return new SimValues(operator, simCountry, networkCountry, carrier,
                Locale.getDefault().getCountry().toLowerCase(Locale.ROOT),
                TimeZone.getDefault().getRawOffset() / 3600000, subscriptions);
    }

    /**
     * Keeps what TelephonyManager answered unless it is empty, then reads the property area.
     *
     * <p>Both sources carry the persona: {@code getSimOperator()} itself is backed by
     * {@code gsm.sim.operator.numeric}, so the fallback cannot invent a value the platform would
     * not report.
     */
    private static Text preferProp(Text value, String key) {
        if (value != null && value.isReadable() && !value.isEmpty()) {
            return value;
        }
        Text prop = Shell.firstLine("getprop", key);
        return prop != null && prop.isReadable() && !prop.isEmpty() ? prop : value;
    }

    private static void collectMounts(Snapshot.Builder builder) {
        List<Mount> mounts = new ArrayList<Mount>();
        boolean readable = false;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length < 4) {
                    continue;
                }
                mounts.add(new Mount(fields[0], unescape(fields[1]), fields[2], fields[3]));
            }
            readable = true;
        } catch (Exception e) {
            Diag.warn("mounts", "/proc/mounts unreadable", e);
        } finally {
            close(reader);
        }
        builder.mounts(mounts, readable);
    }

    private static void collectPackages(Context app, Snapshot.Builder builder) {
        PackageManager packageManager = app.getPackageManager();
        List<String> names = new ArrayList<String>();
        Map<String, String> labels = new LinkedHashMap<String, String>();
        List<String> all = new ArrayList<String>();
        boolean readable = false;
        try {
            for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
                all.add(info.packageName);
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                names.add(info.packageName);
                labels.put(info.packageName, label(packageManager, info));
            }
            readable = true;
        } catch (Exception e) {
            Diag.warn("packages", "enumeration failed", e);
        }
        // names/labels are the user-visible packages the census scans; `all` also carries system
        // packages, because a root tool can be installed as one.
        builder.packages(names, labels, readable);
        builder.installedPackages(all, readable);
    }

    private static PlatformFacts collectPlatform(Context app) {
        PackageManager packageManager = app.getPackageManager();
        boolean strongbox =
                packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
        int biometric = -1;
        try {
            BiometricManager manager = app.getSystemService(BiometricManager.class);
            if (manager != null) {
                biometric = manager.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG);
            }
        } catch (Exception e) {
            Diag.warn("platform", "biometric", e);
        }
        boolean deviceSecure = false;
        try {
            KeyguardManager keyguard =
                    (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            deviceSecure = keyguard != null && keyguard.isDeviceSecure();
        } catch (Exception e) {
            Diag.warn("platform", "keyguard", e);
        }
        boolean packagesChecked = false;
        boolean storePresent = false;
        Text storeVersion = Text.unreadable();
        Text gmsVersion = Text.unreadable();
        boolean revanced = false;
        try {
            PackageInfo store = packageManager.getPackageInfo("com.android.vending", 0);
            storePresent = true;
            storeVersion = Text.of(store.versionName);
            packagesChecked = true;
        } catch (PackageManager.NameNotFoundException e) {
            packagesChecked = true;     // observed: the store is not installed
        } catch (Exception e) {
            Diag.warn("platform", "store query", e);
        }
        if (packagesChecked) {
            try {
                gmsVersion = Text.of(packageManager
                        .getPackageInfo("com.google.android.gms", 0).versionName);
            } catch (PackageManager.NameNotFoundException e) {
                // observed: GMS is not installed
            } catch (Exception e) {
                Diag.warn("platform", "gms query", e);
            }
            try {
                packageManager.getPackageInfo("app.revanced.android.gms", 0);
                revanced = true;
            } catch (PackageManager.NameNotFoundException e) {
                // observed: not present
            } catch (Exception e) {
                Diag.warn("platform", "revanced query", e);
            }
        }
        AttestationFacts attestation;
        try {
            Attest attest = Attest.generate(app);
            attestation = new AttestationFacts(true, true, attest.securityLevelName(),
                    attest.verifiedBoot, attest.rootOfTrustPresent, attest.deviceLocked,
                    attest.osVersion, attest.osPatchLevel, tags(attest.teeTags),
                    props(attest), attest.challengeHex(), "");
        } catch (Exception e) {
            Diag.warn("platform", "attestation", e);
            attestation = AttestationFacts.failed(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return new PlatformFacts(true, attestation, strongbox, biometric, deviceSecure,
                packagesChecked, storePresent, storeVersion, gmsVersion, revanced);
    }

    // "[opt-in] brand=Google model=husky": no suffix = TEE-claimed (hardware truth);
    // "(sw)" = software-enforced only, the subset an injection layer can override.
    // "[unsupported]" = the Keymaster cannot attest device properties at all.
    private static String props(Attest attest) {
        StringBuilder out = new StringBuilder("[").append(attest.propsMode).append("]");
        for (java.util.Map.Entry<String, String> e : attest.deviceProps.entrySet()) {
            out.append(' ').append(e.getKey()).append('=').append(e.getValue());
            out.append(attest.teeClaimed.contains(e.getKey()) ? "" : "(sw)");
        }
        return out.toString();
    }

    private static String tags(List<Integer> values) {
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(values.get(i));
        }
        return out.append(']').toString();
    }

    private static TlsFacts collectTls() {
        List<String> issuers = new ArrayList<String>();
        boolean issuersReadable = false;
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (!(manager instanceof X509TrustManager)) {
                    continue;
                }
                for (X509Certificate certificate
                        : ((X509TrustManager) manager).getAcceptedIssuers()) {
                    issuers.add(certificate.getSubjectX500Principal()
                            .getName(X500Principal.RFC2253).replaceAll(",\\s*", ","));
                }
            }
            issuersReadable = true;
        } catch (Exception e) {
            Diag.warn("tls", "trust store enumeration failed", e);
        }
        List<String> userCaFiles = new ArrayList<String>();
        File directory = new File("/data/misc/user/0/cacerts-added");
        File[] files = directory.listFiles();
        boolean userCaReadable;
        if (files != null) {
            userCaReadable = true;
            for (File file : files) {
                userCaFiles.add(file.getName());
            }
        } else {
            // listFiles() answers null both for "absent" and for "denied", and absent is the
            // normal answer here. Only a directory that exists yet cannot be listed is unknown.
            userCaReadable = !directory.exists();
        }
        return new TlsFacts(issuersReadable, issuers, userCaReadable, userCaFiles);
    }

    private static NetworkFacts collectNetwork(Context app, boolean mirrorVpn) {
        boolean interfacesReadable = false;
        boolean proxyReadable = false;
        String error = "";
        boolean vpnTransport = false;
        List<String> names = new ArrayList<String>();
        List<String> addresses = new ArrayList<String>();
        try {
            // Returns null rather than throwing when the caller may not see the interface list.
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            if (enumeration == null) {
                error = "interfaces: getNetworkInterfaces() returned null";
            } else {
                for (; enumeration.hasMoreElements();) {
                    NetworkInterface networkInterface = enumeration.nextElement();
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                        continue;
                    }
                    String name = networkInterface.getName();
                    if (mirrorVpn && tunnelName(name)) {
                        // The target's payload removes these interfaces before telemetry sees
                        // them; mirroring that view here avoids a contradictory report.
                        continue;
                    }
                    names.add(name);
                    for (InetAddress address
                            : Collections.list(networkInterface.getInetAddresses())) {
                        String host = address.getHostAddress();
                        if (host != null && host.indexOf('%') > 0) {
                            host = host.substring(0, host.indexOf('%'));
                        }
                        if (host != null && !host.startsWith("fe80")) {
                            addresses.add(name + "  " + host);
                        }
                    }
                }
                interfacesReadable = true;
            }
        } catch (Exception e) {
            error = "interfaces: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Diag.warn("network", "interface enumeration failed", e);
        }
        if (mirrorVpn && !interfacesReadable) {
            // The app has no INTERNET permission, so its NetworkInterface table can be null even
            // when the root channel can read it. Use the shell table and apply the same tunnel
            // filter as the target payload; otherwise network.vpn stays WARN because the source
            // is unreadable even though the target view is clean.
            String links = com.walter.claudewilson.RootShell.run(
                    "ip -o link show up 2>/dev/null", 4096);
            boolean sawLink = false;
            for (String line : links.split("\\n")) {
                String[] parts = line.split(":", 3);
                if (parts.length < 2) continue;
                String name = parts[1].trim();
                if (name.isEmpty() || "lo".equals(name)) continue;
                sawLink = true;
                if (tunnelName(name)) continue;
                names.add(name);
            }
            if (sawLink) {
                // The target hook filters every tunnel and still returns an enumeration; an
                // empty *filtered* list is the target's clean view, not an unreadable source.
                interfacesReadable = true;
                error = "";
            }
        }
        Text proxyHost = Text.unreadable();
        int proxyPort = -1;
        boolean proxyViaVpn = false;
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                error = append(error, "connectivity: service unavailable");
            } else {
                Network active = manager.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
                    proxyViaVpn = capabilities != null
                            && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                }
                ProxyInfo proxy = manager.getDefaultProxy();
                proxyHost = Text.of(proxy == null ? "" : proxy.getHost());
                proxyPort = proxy == null ? -1 : proxy.getPort();
                for (Network network : manager.getAllNetworks()) {
                    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                    if (capabilities != null
                            && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        vpnTransport = true;
                    }
                }
                proxyReadable = true;
            }
        } catch (Exception e) {
            error = append(error, "connectivity: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            Diag.warn("network", "connectivity query failed", e);
        }
        if (mirrorVpn) {
            // Same three facts the target payload hides: VPN transport, tunnel interfaces and
            // the proxy the VPN client installed. Keep the source's readability flags as-is so a
            // denied enumeration still reports unknown instead of "clean".
            vpnTransport = false;
            proxyViaVpn = false;
            proxyHost = Text.of("");
            proxyPort = -1;
        }
        return new NetworkFacts(interfacesReadable, proxyReadable, error, vpnTransport, names,
                addresses, proxyHost, proxyPort, proxyViaVpn);
    }

    /** The same prefixes the target payload filters (persona.js IF_BAD). */
    private static boolean tunnelName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("tun") || lower.startsWith("ppp") || lower.startsWith("wg")
                || lower.startsWith("tap") || lower.startsWith("utun");
    }

    private static String append(String current, String addition) {
        return current.isEmpty() ? addition : current + " | " + addition;
    }

    private static void collectTarget(Context app, String targetPackage, Snapshot.Builder builder) {
        if (targetPackage == null || targetPackage.isEmpty()) {
            return;
        }
        PackageManager packageManager = app.getPackageManager();
        try {
            PackageInfo info = packageManager.getPackageInfo(targetPackage, 0);
            Text installer = Text.unreadable();
            try {
                installer = Text.of(packageManager.getInstallSourceInfo(targetPackage)
                        .getInstallingPackageName());
            } catch (Exception e) {
                Diag.warn("target", "install source", e);
            }
            builder.targetInstall(installer, Text.of(info.versionName), true);
        } catch (PackageManager.NameNotFoundException e) {
            builder.targetInstall(Text.unreadable(), Text.unreadable(), false);
        } catch (Exception e) {
            Diag.warn("target", "package info", e);
        }
    }

    private static Text hiddenApiPolicy(Context app) {
        try {
            return Text.of(Settings.Global.getString(app.getContentResolver(),
                    "hidden_api_policy"));
        } catch (Exception e) {
            Diag.warn("target", "hidden_api_policy", e);
            return Text.unreadable();
        }
    }


    private static String label(PackageManager packageManager, ApplicationInfo info) {        try {
            return String.valueOf(packageManager.getApplicationLabel(info));
        } catch (Exception e) {
            return "";
        }
    }

    /** /proc escapes spaces in path names as \040. */
    private static String unescape(String path) {
        return path.replace("\\040", " ");
    }

    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // nothing useful to do while closing a read-only stream
            }
        }
    }
}
