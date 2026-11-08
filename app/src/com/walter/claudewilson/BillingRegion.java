package com.walter.claudewilson;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.GetBillingConfigParams;
import com.android.billingclient.api.PendingPurchasesParams;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only Play country probe: connects BillingClient, asks only for
 * getBillingConfig().getCountryCode(), never launches a purchase flow.
 * Must be called on the UI thread. Callback fires exactly once with a
 * lowercase ISO code ("us") or a diagnostic string ("err:*" / "无渠道" / "超时").
 */
public final class BillingRegion {

    public interface Cb { void done(String result); }

    private static final long TIMEOUT_MS = 8000;

    public static void queryAsync(Context ctx, Cb cb) {
        final AtomicBoolean fired = new AtomicBoolean(false);
        final Handler h = new Handler(Looper.getMainLooper());
        final BillingClient[] box = new BillingClient[1];
        final Runnable finish = new Runnable() {
            public void run() {
                if (!fired.compareAndSet(false, true)) return;
                cb.done("err:超时");
            }
        };
        h.postDelayed(finish, TIMEOUT_MS);
        try {
            BillingClient bc = BillingClient.newBuilder(ctx)
                    .setListener((res, list) -> {})
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                            .enableOneTimeProducts().build())
                    .build();
            box[0] = bc;
            bc.startConnection(new BillingClientStateListener() {
                private void fin(String v) {
                    if (!fired.compareAndSet(false, true)) return;
                    h.removeCallbacks(finish);
                    try { bc.endConnection(); } catch (Throwable ignored) {}
                    cb.done(v);
                }
                public void onBillingSetupFinished(BillingResult res) {
                    if (res.getResponseCode()
                            != BillingClient.BillingResponseCode.OK) {
                        fin("err:connect " + res.getResponseCode()
                                + " " + res.getDebugMessage());
                        return;
                    }
                    bc.getBillingConfigAsync(
                            GetBillingConfigParams.newBuilder().build(),
                            (r2, cfg) -> {
                                if (r2.getResponseCode()
                                        == BillingClient.BillingResponseCode.OK
                                        && cfg != null) {
                                    fin(cfg.getCountryCode()
                                            .toLowerCase(Locale.ROOT));
                                } else {
                                    fin("err:config " + r2.getResponseCode()
                                            + " " + r2.getDebugMessage());
                                }
                            });
                }
                public void onBillingServiceDisconnected() {}
            });
        } catch (Throwable t) {
            if (fired.compareAndSet(false, true)) {
                h.removeCallbacks(finish);
                cb.done("err:" + t.getClass().getSimpleName());
            }
        }
    }
}
