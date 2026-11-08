package com.walter.claudewilson;

import android.os.IBinder;
import android.os.PersistableBundle;
import java.lang.reflect.Method;

/** Existing Binder commands for Bridge; no Activity or application context. */
final class CarrierCommands {
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
