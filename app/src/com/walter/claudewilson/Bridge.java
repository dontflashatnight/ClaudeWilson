package com.walter.claudewilson;

/**
 * Stable app_process entry point. All commands run with the spawning shell/root
 * uid, outside the app process. Keep this class name and the CLI protocol stable.
 */
public final class Bridge {
    private Bridge() {}

    public static void main(String[] args) {
        int rc = 0;
        try {
            // Preserve the original service lookup and failure order.
            Object carrier = CarrierCommands.binderSvc("carrier_config",
                    "com.android.internal.telephony.ICarrierConfigLoader$Stub");
            if ("net".equals(args[0])) ShellNetworkProbe.netProbe();
            else if ("hw".equals(args[0])) {
                String[] t = Regions.hwTag();
                System.out.println("PRODUCT=" + android.os.Build.PRODUCT
                        + " DEVICE=" + android.os.Build.DEVICE
                        + " MODEL=" + android.os.Build.MODEL
                        + " BRAND=" + android.os.Build.BRAND
                        + " FINGERPRINT=" + android.os.Build.FINGERPRINT);
                System.out.println("hwTag=" + t[0] + " iso=" + t[1]);
            }
            else CarrierCommands.run(carrier, args);
        } catch (Throwable t) {
            t.printStackTrace();
            rc = 1;
        }
        // Returning from main is not enough: probes pull in connectivity/okhttp
        // internals that leave a non-daemon worker behind, and DestroyJavaVM then
        // waits on it forever. halt the VM so the shell always sees the exit.
        System.exit(rc);
    }
}
