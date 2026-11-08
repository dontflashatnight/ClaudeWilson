package com.walter.claudewilson;

import java.util.Locale;

/**
 * UI chrome strings. The control app has exactly two UI languages: Chinese when the phone's
 * system language is Chinese, English for everything else. The selected region never changes the
 * control app's language; that value belongs only to the target hook payload.
 */
public final class Lang {

    private static boolean zh() {
        return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }

    public static String t(String zh, String en, String ja) {
        return zh() ? zh : en;
    }

    public static String t(String zh, String en) {
        return zh() ? zh : en;
    }
}
