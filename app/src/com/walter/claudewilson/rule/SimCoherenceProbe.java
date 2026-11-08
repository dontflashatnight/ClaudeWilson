package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.SimValues;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the SIM, its carrier name and the locale tell one consistent story.
 *
 * <p>A coherent SIM is the expected baseline. A mismatch is worth showing, but it is a property
 * of the environment rather than a defect in it, so the check warns instead of failing.
 */
public final class SimCoherenceProbe implements Probe {

    @Override
    public String id() {
        return "identity.sim-coherence";
    }

    @Override
    public Layer layer() {
        return Layer.IDENTITY;
    }

    @Override
    public String field() {
        return "mobile_carrier_name + mobile_iso_country_code + utc_offset";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        SimValues sim = snapshot.sim();
        if (!sim.simOperator().isReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "SimOperator");
        }
        if (sim.simOperator().isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.NO_SIM, "no sim");
        }
        String operator = sim.simOperator().value();
        Map<String, String> mccToIso = snapshot.tables().mccToIso();
        if (mccToIso.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.NO_CATALOG, "mcc");
        }
        String operatorKey = operator.length() >= 3 ? operator.substring(0, 3) : operator;
        String expectedIso = mccToIso.get(operatorKey);
        List<String> odd = new ArrayList<String>();
        if (expectedIso != null && !sim.simCountryIso().isEmpty()
                && !expectedIso.equalsIgnoreCase(sim.simCountryIso().value())) {
            odd.add("mcc " + operatorKey + " implies " + expectedIso
                    + " but simCountryIso=" + sim.simCountryIso().value());
        }
        String expectedCarrier = expectedCarrier(snapshot.tables().carrierKeywords(), operator);
        if (expectedIso == null && expectedCarrier == null) {
            // Neither table knows this operator, so nothing was actually compared. Reporting that
            // as a clean informational result would be the same "green because we did not look"
            // failure this pipeline exists to remove.
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.CARRIER_UNKNOWN,
                    "operator=" + operator)
                    .with("catalog", snapshot.tables().version());
        }
        if (expectedCarrier != null && !sim.carrierName().isEmpty()
                && !matches(expectedCarrier, sim.carrierName().value())) {
            odd.add("carrier=" + sim.carrierName().value() + " does not match operator="
                    + operator);
        }
        Finding finding;
        if (odd.isEmpty()) {
            finding = Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    "operator=" + operator + " country=" + expectedIso);
        } else {
            finding = Finding.of(id(), layer(), field(), Status.WARN, Reason.INCONSISTENT,
                    String.join("; ", odd));
        }
        return finding
                .with("localeCountry", sim.localeCountry())
                .with("tzOffsetHours", String.valueOf(sim.tzOffsetHours()))
                .with("subscriptions", String.valueOf(sim.subscriptionCount()));
    }

    private static String expectedCarrier(Map<String, String> carrierKeywords, String operator) {
        String exact = carrierKeywords.get(operator);
        if (exact != null) {
            return exact;
        }
        return operator.length() >= 3 ? carrierKeywords.get(operator.substring(0, 3)) : null;
    }

    private static boolean matches(String alternatives, String carrierName) {
        String haystack = carrierName.toLowerCase(Locale.ROOT);
        for (String alternative : alternatives.split("\\|")) {
            String needle = alternative.trim();
            if (!needle.isEmpty() && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
