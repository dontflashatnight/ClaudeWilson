package com.walter.claudewilson.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a physical SIM belongs to a non-restricted country and may keep its genuine
 * SIM operator trio ({@code MCC/MNC}, {@code ISO}, carrier name) instead of being spoofed.
 *
 * <p>Fail-closed by design: an unreadable catalog, a missing or malformed operator code, a
 * restricted country/MCC on the active card, or a restricted peer slot on a dual-SIM device all
 * return {@code preserveRealSim = false} so the persona spoofing path runs unchanged.
 */
public final class SimPolicy {

    public static final String REASON_PRESERVED = "preserved";
    public static final String REASON_DISABLED = "disabled";
    public static final String REASON_MISSING_CATALOG = "missing-catalog";
    public static final String REASON_NO_SIM = "no-sim";
    public static final String REASON_INVALID_OPERATOR = "invalid-operator";
    public static final String REASON_RESTRICTED_SIM = "restricted-sim";
    public static final String REASON_RESTRICTED_PEER = "restricted-peer-slot";

    private SimPolicy() {
    }

    public static final class Decision {
        public final boolean preserveRealSim;
        public final boolean simEligible;
        public final String reason;
        public final String resolvedOp;
        public final String resolvedIso;
        public final String resolvedCarrier;

        private Decision(boolean preserveRealSim, boolean simEligible, String reason,
                         String resolvedOp, String resolvedIso, String resolvedCarrier) {
            this.preserveRealSim = preserveRealSim;
            this.simEligible = simEligible;
            this.reason = reason;
            this.resolvedOp = resolvedOp;
            this.resolvedIso = resolvedIso;
            this.resolvedCarrier = resolvedCarrier;
        }
    }

    /**
     * True when {@code iso} appears in {@code catalog/restricted-regions.txt} for the given
     * target (or for {@code all} / {@code conservative} scopes).
     */
    public static boolean isRestrictedIso(Tables tables, String iso, String targetId) {
        if (tables == null || iso == null) {
            return false;
        }
        String norm = iso.trim().toLowerCase(Locale.ROOT);
        if (norm.length() != 2) {
            return false;
        }
        String entry = tables.restrictedRegions().get(norm);
        return entry != null && scopeApplies(entry, targetId);
    }

    /**
     * True when the 3-digit MCC of {@code operatorNumeric} is listed in
     * {@code catalog/restricted-regions.txt} or maps via {@code catalog/mcc.txt} to a restricted
     * ISO country.
     */
    public static boolean isRestrictedOperator(Tables tables, String operatorNumeric,
                                               String targetId) {
        if (tables == null || operatorNumeric == null) {
            return false;
        }
        String op = operatorNumeric.trim();
        if (op.length() < 3) {
            return false;
        }
        String mcc = op.substring(0, 3);
        if (!isDigits(mcc)) {
            return false;
        }
        String mappedIso = tables.mccToIso().get(mcc);
        if (mappedIso != null && isRestrictedIso(tables, mappedIso, targetId)) {
            return true;
        }
        for (Map.Entry<String, String> e : tables.restrictedRegions().entrySet()) {
            String value = e.getValue();
            if (value == null || !scopeApplies(value, targetId)) {
                continue;
            }
            String[] parts = value.split("\\|", -1);
            if (parts.length < 2 || parts[1].isEmpty()) {
                continue;
            }
            for (String listed : parts[1].split(",")) {
                if (mcc.equals(listed.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Evaluates whether the real physical SIM trio can be preserved untouched.
     *
     * <p>{@code allActiveOps} and {@code allActiveIsos} cover every active SIM slot on dual-SIM
     * devices; if any active slot is restricted, preservation is denied so the restricted slot
     * cannot leak through per-slot properties or {@code SubscriptionInfo}.
     */
    public static Decision evaluate(Tables tables, String targetId, boolean preserveEnabled,
                                    String activeOp, String activeIso,
                                    String activeCarrier, String activeDisplay,
                                    List<String> allActiveOps, List<String> allActiveIsos) {
        if (tables == null || tables.restrictedRegions().isEmpty()) {
            return new Decision(false, false, REASON_MISSING_CATALOG, "", "", "");
        }
        String op = clean(activeOp);
        String rawIso = clean(activeIso).toLowerCase(Locale.ROOT);
        if (op.isEmpty() && rawIso.isEmpty()) {
            return new Decision(false, false, REASON_NO_SIM, "", "", "");
        }
        if (!isValidOperator(op)) {
            return new Decision(false, false, REASON_INVALID_OPERATOR, op, rawIso, "");
        }
        String mccIso = tables.mccToIso().get(op.substring(0, 3));
        String resolvedIso = mccIso != null && !mccIso.isEmpty()
                ? mccIso.trim().toLowerCase(Locale.ROOT)
                : rawIso;
        if (resolvedIso.length() != 2) {
            return new Decision(false, false, REASON_INVALID_OPERATOR, op, "", "");
        }
        // Check both the authoritative MCC country and the reported ISO country so a stale
        // CarrierConfig ISO on a restricted card can never bypass the ban list.
        if (isRestrictedOperator(tables, op, targetId)
                || isRestrictedIso(tables, resolvedIso, targetId)
                || isRestrictedIso(tables, rawIso, targetId)) {
            return new Decision(false, false, REASON_RESTRICTED_SIM, op, resolvedIso, "");
        }
        if (allActiveOps != null) {
            for (String peerOp : allActiveOps) {
                String trimmed = clean(peerOp);
                if (!trimmed.isEmpty() && isRestrictedOperator(tables, trimmed, targetId)) {
                    return new Decision(false, false, REASON_RESTRICTED_PEER, op, resolvedIso, "");
                }
            }
        }
        if (allActiveIsos != null) {
            for (String peerIso : allActiveIsos) {
                String trimmed = clean(peerIso).toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && isRestrictedIso(tables, trimmed, targetId)) {
                    return new Decision(false, false, REASON_RESTRICTED_PEER, op, resolvedIso, "");
                }
            }
        }
        String carrier = resolveCarrier(activeCarrier, activeDisplay, op, tables);
        if (!preserveEnabled) {
            return new Decision(false, true, REASON_DISABLED, op, resolvedIso, carrier);
        }
        return new Decision(true, true, REASON_PRESERVED, op, resolvedIso, carrier);
    }

    private static boolean scopeApplies(String entry, String targetId) {
        String scope = entry.split("\\|", -1)[0].trim().toLowerCase(Locale.ROOT);
        if (scope.isEmpty() || "all".equals(scope) || "conservative".equals(scope)) {
            return true;
        }
        if (targetId == null || targetId.trim().isEmpty()) {
            return true;
        }
        return scope.equals(targetId.trim().toLowerCase(Locale.ROOT));
    }

    private static String resolveCarrier(String activeCarrier, String activeDisplay,
                                         String op, Tables tables) {
        String primary = sanitizeCarrier(activeCarrier);
        if (!primary.isEmpty()) {
            return primary;
        }
        String fallback = sanitizeCarrier(activeDisplay);
        if (!fallback.isEmpty()) {
            return fallback;
        }
        String exact = tables.carrierKeywords().get(op);
        if (exact != null && !exact.isEmpty()) {
            return firstToken(exact);
        }
        String byMcc = tables.carrierKeywords().get(op.substring(0, 3));
        if (byMcc != null && !byMcc.isEmpty()) {
            return firstToken(byMcc);
        }
        return op;
    }

    private static String firstToken(String pipeList) {
        for (String part : pipeList.split("\\|")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    private static String sanitizeCarrier(String raw) {
        String s = clean(raw);
        if (s.isEmpty()) {
            return "";
        }
        int bar = s.indexOf(" | ");
        if (bar > 0) {
            s = s.substring(0, bar).trim();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if ("no service".equals(lower)
                || "emergency calls only".equals(lower)
                || "searching for service".equals(lower)
                || "limited service".equals(lower)
                || "unknown".equals(lower)
                || "null".equals(lower)) {
            return "";
        }
        return s;
    }

    private static boolean isValidOperator(String value) {
        int len = value.length();
        return (len == 5 || len == 6) && isDigits(value);
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
