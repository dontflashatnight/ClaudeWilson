package com.walter.claudewilson;

import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/** Retarget completions after locale recreation without retaining an Activity. */
final class ScreenUpdates {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Dashboard> current = new WeakReference<>(null);

    private ScreenUpdates() {}

    static void attach(Dashboard screen) { current = new WeakReference<>(screen); }

    static void detach(Dashboard screen) {
        if (current.get() == screen) current.clear();
    }

    static void post(Consumer<Dashboard> update) {
        MAIN.post(() -> {
            Dashboard screen = current.get();
            if (screen != null) update.accept(screen);
        });
    }
}
