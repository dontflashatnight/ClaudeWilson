package com.walter.claudewilson;

import android.os.IBinder;
import android.os.PersistableBundle;
import java.lang.reflect.Method;

/** Existing Binder commands for Bridge; no Activity or application context. */
final class CarrierCommands {
    /**
     * SubscriptionManager.NAME_SOURCE_USER_INPUT is hidden and its numeric value moved between
     * releases (2 on the Lineage 22 test device, 3 on AOSP 15). Resolve it at runtime instead of
     * baking in a value; the fallback is the value observed on the target device.
     */
    static int nameSourceUserInput() {
        try {
            return android.telephony.SubscriptionManager.class
                    .getField("NAME_SOURCE_USER_INPUT").getInt(null);
        } catch (Throwable t) {
            return 2;
        }
    }

    private CarrierCommands() {}

    static Object binderSvc(String name, String stubClass) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        return Class.forName(stubClass)
                .getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    static void run(Object svc, String[] a) throws Exception {
        switch (a[0]) {
            case "cc": {
                int sub = Integer.parseInt(a[1]);
                PersistableBundle p = new PersistableBundle();
                p.putString("sim_country_iso_override_string", a[2]);
                if (a.length > 3 && !a[3].isEmpty()) {
                    p.putBoolean("carrier_name_override_bool", true);
                    p.putString("carrier_name_string", a[3]);
                }
                callOverride(svc, sub, p);
                System.out.println("cc set sub=" + sub + " iso=" + a[2]
                        + (a.length > 3 ? " name=" + a[3] : ""));
                break;
            }
            case "ccclear": {
                callOverride(svc, Integer.parseInt(a[1]), null);
                System.out.println("cc cleared sub=" + a[1]);
                break;
            }
            case "subname": {
                // The SIM manager renders SubscriptionInfo.displayName, not gsm.sim.operator.alpha
                // and not the carrier-config override. Without this write it keeps showing the
                // name the card (or an earlier simulation) put in the subscription database.
                Object sub = binderSvc("isub",
                        "com.android.internal.telephony.ISub$Stub");
                int subId = Integer.parseInt(a[1]);
                String name = a[2];
                int requested = a.length > 3 ? Integer.parseInt(a[3]) : -1;
                Method method = null;
                for (Method m : sub.getClass().getDeclaredMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (m.getName().equals("setDisplayNameUsingSrc")
                            && pt.length == 3 && pt[0] == String.class) {
                        method = m;
                        break;
                    }
                }
                if (method == null) {
                    System.out.println("SKIP:subname api-absent");
                    break;
                }
                // The hidden name-source enum moved between releases; try the requested value
                // first, then the resolved constant and both historical values.
                java.util.LinkedHashSet<Integer> sources = new java.util.LinkedHashSet<Integer>();
                if (requested > 0) sources.add(requested);
                sources.add(nameSourceUserInput());
                sources.add(2);
                sources.add(3);
                int updated = 0;
                int used = -1;
                String failure = null;
                for (int source : sources) {
                    try {
                        Object result = method.invoke(sub, name, subId, source);
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
                System.out.println(used > 0
                        ? "subname set sub=" + subId + " name=" + name
                                + " src=" + used + " rows=" + updated
                        : "SKIP:subname no-update"
                                + (failure == null ? "" : " " + failure));
                break;
            }
            case "ccget": {
                PersistableBundle cfg =
                        (PersistableBundle) callGet(svc,
                                Integer.parseInt(a[1]));
                for (String k : cfg.keySet()) {
                    if (a.length > 2 && !k.contains(a[2])) continue;
                    System.out.println(k + " = " + cfg.get(k));
                }
                break;
            }
            case "brand": {
                Object tel = binderSvc("phone",
                        "com.android.internal.telephony.ITelephony$Stub");
                boolean done = false;
                for (Method m : tel.getClass().getDeclaredMethods()) {
                    if (m.getName().equals("setOperatorBrandOverride")
                            && m.getParameterTypes().length == 2) {
                        m.invoke(tel, Integer.parseInt(a[1]),
                                (a.length > 2 && !a[2].equals("-"))
                                        ? a[2] : "");
                        done = true;
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
                    if (pt.length != 2) continue;
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

    /** Signatures vary by build: (int, bundle, boolean) or (int, bundle). */
    static void callOverride(Object svc, int sub, PersistableBundle p)
            throws Exception {
        for (Method m : svc.getClass().getDeclaredMethods()) {
            if (!m.getName().equals("overrideConfig")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 3) { m.invoke(svc, sub, p, false); return; }
            if (pt.length == 2) { m.invoke(svc, sub, p); return; }
        }
        throw new NoSuchMethodException("overrideConfig");
    }

    static Object callGet(Object svc, int sub) throws Exception {
        for (Method m : svc.getClass().getDeclaredMethods()) {
            if (!m.getName().startsWith("getConfigForSubId")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 1 && pt[0] == int.class)
                return m.invoke(svc, sub);
            if (pt.length == 3 && pt[0] == int.class)
                return m.invoke(svc, sub, "com.walter.claudewilson", "");
            if (pt.length == 2 && pt[0] == int.class)
                return m.invoke(svc, sub, "com.walter.claudewilson");
        }
        throw new NoSuchMethodException("getConfigForSubId*");
    }
}
