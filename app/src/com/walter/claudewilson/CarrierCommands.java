package com.walter.claudewilson;

import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.Process;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Existing Binder commands for Bridge; no Activity or application context. */
final class CarrierCommands {
    private static final String DEFAULT_CALLING_PKG = "com.walter.claudewilson";
    private static final String SHELL_CALLING_PKG = "com.android.shell";

    /**
     * Resolves {@code SubscriptionManager.NAME_SOURCE_USER_INPUT} via reflection, or -1 when
     * hidden-API restrictions block access in the current process.
     */
    static int resolveNameSourceUserInput() {
        try {
            return android.telephony.SubscriptionManager.class
                    .getField("NAME_SOURCE_USER_INPUT").getInt(null);
        } catch (Throwable ignored) {
            try {
                Field f = android.telephony.SubscriptionManager.class
                        .getDeclaredField("NAME_SOURCE_USER_INPUT");
                f.setAccessible(true);
                return f.getInt(null);
            } catch (Throwable t) {
                return -1;
            }
        }
    }

    /**
     * SubscriptionManager.NAME_SOURCE_USER_INPUT is hidden and its numeric value moved between
     * releases (2 on Android 13-14, 3 on Android 15+). Resolve it at runtime instead of baking
     * in a value; the fallback is the historical value when called from an untrusted app process.
     */
    static int nameSourceUserInput() {
        int live = resolveNameSourceUserInput();
        return live >= 0 ? live : 2;
    }

    private CarrierCommands() {}

    private static String callingPackage() {
        int uid = Process.myUid();
        if (uid == Process.ROOT_UID) {
            return "root";
        }
        return uid == Process.SHELL_UID ? SHELL_CALLING_PKG : DEFAULT_CALLING_PKG;
    }

    static Object binderSvc(String name, String stubClass) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        if (b == null) {
            return null;
        }
        return Class.forName(stubClass)
                .getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    private static Object requireCarrierConfig(Object existing) throws Exception {
        if (existing != null) {
            return existing;
        }
        Object svc = binderSvc("carrier_config",
                "com.android.internal.telephony.ICarrierConfigLoader$Stub");
        if (svc == null) {
            throw new IllegalStateException("service absent: carrier_config");
        }
        return svc;
    }

    static void run(String[] a) throws Exception {
        if (a != null && a.length > 1 && "batch".equals(a[0])) {
            runBatch(a);
            return;
        }
        run(null, a);
    }

    /** Executes multiple Binder subcommands separated by "--" in one single app_process JVM. */
    private static void runBatch(String[] a) {
        List<String> cur = new ArrayList<String>();
        for (int i = 1; i <= a.length; i++) {
            boolean sep = i == a.length || "--".equals(a[i]);
            if (sep) {
                if (!cur.isEmpty()) {
                    try {
                        run(null, cur.toArray(new String[0]));
                    } catch (Throwable t) {
                        System.out.println("BATCH_ERR:" + cur.get(0) + " "
                                + t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                    }
                    cur.clear();
                }
            } else {
                cur.add(a[i]);
            }
        }
    }

    static void run(Object svc, String[] a) throws Exception {
        switch (a[0]) {
            case "cc": {
                Object carrier = requireCarrierConfig(svc);
                int sub = Integer.parseInt(a[1]);
                PersistableBundle p = new PersistableBundle();
                p.putString("sim_country_iso_override_string", a[2]);
                if (a.length > 3 && !a[3].isEmpty()) {
                    p.putBoolean("carrier_name_override_bool", true);
                    p.putString("carrier_name_string", a[3]);
                }
                callOverride(carrier, sub, p);
                System.out.println("cc set sub=" + sub + " iso=" + a[2]
                        + (a.length > 3 ? " name=" + a[3] : ""));
                break;
            }
            case "ccclear": {
                Object carrier = requireCarrierConfig(svc);
                callOverride(carrier, Integer.parseInt(a[1]), null);
                System.out.println("cc cleared sub=" + a[1]);
                break;
            }
            case "subname": {
                // The SIM manager renders SubscriptionInfo.displayName, not gsm.sim.operator.alpha
                // and not the carrier-config override. Without this write it keeps showing the
                // name the card (or an earlier simulation) put in the subscription database.
                Object sub = binderSvc("isub",
                        "com.android.internal.telephony.ISub$Stub");
                if (sub == null) {
                    System.out.println("SKIP:subname service-absent");
                    break;
                }
                int subId = Integer.parseInt(a[1]);
                String name = a[2];
                int requested = a.length > 3 ? Integer.parseInt(a[3]) : -1;
                Method method = findSetDisplayNameMethod(sub.getClass());
                if (method == null) {
                    System.out.println("SKIP:subname api-absent");
                    break;
                }
                // When caller passes requested >= 0 (e.g., revert restoring a captured origSource
                // such as 0=CARRIER_ID or 1=SIM_SPN), honor requested first; when requested < 0
                // (apply), resolve the live platform NAME_SOURCE_USER_INPUT inside Bridge FIRST.
                int live = resolveNameSourceUserInput();
                LinkedHashSet<Integer> sources = new LinkedHashSet<Integer>();
                if (requested >= 0) {
                    sources.add(requested);
                    if (live >= 0) sources.add(live);
                } else {
                    if (live >= 0) sources.add(live);
                }
                sources.add(3);
                sources.add(2);
                int updated = 0;
                int used = -1;
                String failure = null;
                for (int source : sources) {
                    try {
                        Object result = invokeSetDisplayName(sub, method, name, subId, source);
                        int rows = result instanceof Integer ? (Integer) result : 1;
                        if (rows > 0) {
                            updated = rows;
                            used = source;
                            break;
                        }
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() == null ? t : t.getCause();
                        failure = cause.getClass().getSimpleName()
                                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
                    }
                }
                System.out.println(used >= 0
                        ? "subname set sub=" + subId + " name=" + name
                                + " src=" + used + " rows=" + updated
                        : "SKIP:subname no-update"
                                + (failure == null ? "" : " " + failure));
                break;
            }
            case "ccget": {
                Object carrier = requireCarrierConfig(svc);
                PersistableBundle cfg =
                        (PersistableBundle) callGet(carrier,
                                Integer.parseInt(a[1]));
                if (cfg != null) {
                    for (String k : cfg.keySet()) {
                        if (a.length > 2 && !k.contains(a[2])) continue;
                        System.out.println(k + " = " + bundleValue(cfg, k));
                    }
                }
                break;
            }
            case "brand": {
                Object tel = binderSvc("phone",
                        "com.android.internal.telephony.ITelephony$Stub");
                if (tel == null) {
                    System.out.println("SKIP:brand service-absent");
                    break;
                }
                boolean done = false;
                for (Method m : tel.getClass().getDeclaredMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (m.getName().equals("setOperatorBrandOverride")
                            && pt.length == 2
                            && pt[0] == int.class && pt[1] == String.class) {
                        m.invoke(tel, Integer.parseInt(a[1]),
                                (a.length > 2 && !a[2].equals("-"))
                                        ? a[2] : "");
                        done = true;
                        break;
                    }
                }
                System.out.println(done
                        ? "brand set sub=" + a[1]
                        : "setOperatorBrandOverride not found");
                break;
            }
            case "simop": {
                Object tel = binderSvc("phone",
                        "com.android.internal.telephony.ITelephony$Stub");
                if (tel == null) {
                    System.out.println("SKIP:simop service-absent");
                    break;
                }
                int target = phoneForSub(Integer.parseInt(a[1]));
                if (target < 0) {
                    System.out.println("SKIP:simop no-phone-for-sub");
                    break;
                }
                String numeric = "-".equals(a[2]) ? null : a[2];
                String name = a.length > 3 && !"-".equals(a[3]) ? a[3] : null;
                boolean any = false;
                for (Method m : tel.getClass().getDeclaredMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 2 || pt[0] != int.class || pt[1] != String.class) continue;
                    if (m.getName().equals("setSimOperatorNumericForPhone")) {
                        m.invoke(tel, target, numeric);
                        any = true;
                    } else if (a.length > 3
                            && m.getName().equals("setSimOperatorNameForPhone")) {
                        m.invoke(tel, target, name);
                        any = true;
                    }
                }
                // Android 12+ removed these test hooks from ITelephony; absent is
                // a fact about the build, not a failure of this command.
                System.out.println(any ? "simop set phone=" + target
                        : "SKIP:simop api-absent");
                break;
            }
            case "methods": {
                Object s2 = binderSvc(a[1], a[2]);
                if (s2 == null) {
                    System.out.println("service absent: " + a[1]);
                    break;
                }
                for (Method m : s2.getClass().getDeclaredMethods()) {
                    if (a.length > 3 && !m.getName().contains(a[3])) continue;
                    StringBuilder sb = new StringBuilder(m.getName() + "(");
                    for (Class<?> p : m.getParameterTypes())
                        sb.append(p.getSimpleName()).append(",");
                    System.out.println(sb + ")");
                }
                break;
            }
            default:
                System.out.println("unknown: " + a[0]);
        }
    }

    @SuppressWarnings("deprecation")
    private static Object bundleValue(PersistableBundle bundle, String key) {
        return bundle.get(key);
    }

    /** subId → phoneId through the hidden controller; -1 when it cannot resolve. */
    static int phoneForSub(int subId) {
        try {
            Class<?> sc = Class.forName(
                    "com.android.internal.telephony.SubscriptionController");
            Object inst = sc.getMethod("getInstance").invoke(null);
            return (Integer) sc.getMethod("getPhoneId", int.class)
                    .invoke(inst, subId);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Finds {@code ISub.setDisplayNameUsingSrc} matching exact parameter types across Android
     * 13-17 and OEM variants: {@code (String, int, int|long)} with optional trailing
     * {@code String callingPackage} / {@code int deviceId}.
     */
    private static Method findSetDisplayNameMethod(Class<?> cls) {
        List<Method> candidates = new ArrayList<Method>();
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals("setDisplayNameUsingSrc")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (isSupportedSetDisplayNameSignature(pt)) {
                candidates.add(m);
            }
        }
        Method best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Method m : candidates) {
            int rank = setDisplayNameRank(m.getParameterTypes());
            if (rank < bestRank) {
                bestRank = rank;
                best = m;
            }
        }
        return best;
    }

    private static boolean isSupportedSetDisplayNameSignature(Class<?>[] pt) {
        if (pt.length < 3 || pt.length > 5) return false;
        if (pt[0] != String.class || pt[1] != int.class) return false;
        if (pt[2] != int.class && pt[2] != long.class) return false;
        int strings = 0;
        int ints = 0;
        for (int i = 3; i < pt.length; i++) {
            if (pt[i] == String.class) {
                strings++;
            } else if (pt[i] == int.class) {
                ints++;
            } else {
                return false;
            }
        }
        return strings <= 2 && ints <= 1;
    }

    private static int setDisplayNameRank(Class<?>[] pt) {
        int base = pt[2] == int.class ? 0 : 10;
        return base + (pt.length - 3);
    }

    private static Object invokeSetDisplayName(Object sub, Method m,
                                               String name, int subId, int source)
            throws Exception {
        Class<?>[] pt = m.getParameterTypes();
        Object[] args = new Object[pt.length];
        args[0] = name;
        args[1] = Integer.valueOf(subId);
        // Explicit if/else: a ternary `Long.valueOf(...) : Integer.valueOf(...)` promotes both
        // branches to Long per JLS §15.25.2, causing IllegalArgumentException on int parameters.
        if (pt[2] == long.class) {
            args[2] = Long.valueOf(source);
        } else {
            args[2] = Integer.valueOf(source);
        }
        int stringIndex = 0;
        for (int i = 3; i < pt.length; i++) {
            if (pt[i] == String.class) {
                args[i] = stringIndex == 0 ? callingPackage() : null;
                stringIndex++;
            } else if (pt[i] == int.class) {
                args[i] = Integer.valueOf(0);
            }
        }
        return m.invoke(sub, args);
    }

    /**
     * Dispatches {@code ICarrierConfigLoader.overrideConfig} with exact parameter-type validation
     * across Android 13-17 and OEM/multi-device variants:
     * {@code (int, PersistableBundle, boolean)}, {@code (int, PersistableBundle)}, and variants
     * with trailing {@code String callingPackage} / {@code int deviceId}.
     */
    static void callOverride(Object svc, int sub, PersistableBundle p)
            throws Exception {
        Method best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().equals("overrideConfig")) continue;
            Class<?>[] pt = m.getParameterTypes();
            int rank = overrideRank(pt);
            if (rank < bestRank) {
                bestRank = rank;
                best = m;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException("overrideConfig");
        }
        Class<?>[] pt = best.getParameterTypes();
        Object[] args = new Object[pt.length];
        args[0] = Integer.valueOf(sub);
        args[1] = p;
        int stringIndex = 0;
        for (int i = 2; i < pt.length; i++) {
            if (pt[i] == boolean.class) {
                args[i] = Boolean.FALSE;
            } else if (pt[i] == String.class) {
                args[i] = stringIndex == 0 ? callingPackage() : null;
                stringIndex++;
            } else if (pt[i] == int.class) {
                args[i] = Integer.valueOf(0);
            }
        }
        best.invoke(svc, args);
    }

    private static int overrideRank(Class<?>[] pt) {
        if (pt.length < 2 || pt.length > 5) return Integer.MAX_VALUE;
        if (pt[0] != int.class || pt[1] != PersistableBundle.class) return Integer.MAX_VALUE;
        if (pt.length == 3 && pt[2] == boolean.class) return 0;
        if (pt.length == 2) return 1;
        int booleans = 0;
        int strings = 0;
        int ints = 0;
        for (int i = 2; i < pt.length; i++) {
            if (pt[i] == boolean.class) {
                booleans++;
            } else if (pt[i] == String.class) {
                strings++;
            } else if (pt[i] == int.class) {
                ints++;
            } else {
                return Integer.MAX_VALUE;
            }
        }
        if (booleans > 1 || strings > 2 || ints > 1) return Integer.MAX_VALUE;
        return (booleans == 1 ? 2 : 10) + pt.length;
    }

    static Object callGet(Object svc, int sub) throws Exception {
        String pkg = callingPackage();
        Method best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().startsWith("getConfigForSubId")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 1 && pt[0] == int.class) {
                best = m;
                bestRank = 0;
                break;
            } else if (pt.length == 3 && pt[0] == int.class
                    && pt[1] == String.class && pt[2] == String.class && bestRank > 1) {
                best = m;
                bestRank = 1;
            } else if (pt.length == 2 && pt[0] == int.class
                    && pt[1] == String.class && bestRank > 2) {
                best = m;
                bestRank = 2;
            } else if (pt.length == 4 && pt[0] == int.class
                    && pt[1] == String.class && pt[2] == String.class
                    && pt[3] == int.class && bestRank > 3) {
                best = m;
                bestRank = 3;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException("getConfigForSubId*");
        }
        Class<?>[] pt = best.getParameterTypes();
        if (pt.length == 1) return best.invoke(svc, Integer.valueOf(sub));
        if (pt.length == 2) return best.invoke(svc, Integer.valueOf(sub), pkg);
        if (pt.length == 3) return best.invoke(svc, Integer.valueOf(sub), pkg, "");
        return best.invoke(svc, Integer.valueOf(sub), pkg, "", Integer.valueOf(0));
    }
}
