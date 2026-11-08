package com.walter.claudewilson.model;

/** SIM, network and locale facts, as plain data. Every field records whether it was read. */
public final class SimValues {
    public static final SimValues EMPTY = new SimValues(Text.unreadable(), Text.unreadable(),
            Text.unreadable(), Text.unreadable(), "", 0, 0);

    private final Text simOperator;
    private final Text simCountryIso;
    private final Text networkCountryIso;
    private final Text carrierName;
    private final String localeCountry;
    private final int tzOffsetHours;
    private final int subscriptionCount;

    public SimValues(Text simOperator, Text simCountryIso, Text networkCountryIso,
                     Text carrierName, String localeCountry, int tzOffsetHours,
                     int subscriptionCount) {
        this.simOperator = simOperator == null ? Text.unreadable() : simOperator;
        this.simCountryIso = simCountryIso == null ? Text.unreadable() : simCountryIso;
        this.networkCountryIso = networkCountryIso == null ? Text.unreadable() : networkCountryIso;
        this.carrierName = carrierName == null ? Text.unreadable() : carrierName;
        this.localeCountry = localeCountry == null ? "" : localeCountry;
        this.tzOffsetHours = tzOffsetHours;
        this.subscriptionCount = subscriptionCount;
    }

    public Text simOperator() {
        return simOperator;
    }

    public Text simCountryIso() {
        return simCountryIso;
    }

    public Text networkCountryIso() {
        return networkCountryIso;
    }

    public Text carrierName() {
        return carrierName;
    }

    public String localeCountry() {
        return localeCountry;
    }

    public int tzOffsetHours() {
        return tzOffsetHours;
    }

    public int subscriptionCount() {
        return subscriptionCount;
    }

    /** A SIM is present when the operator id could be read and is not empty. */
    public boolean hasSim() {
        return simOperator.isReadable() && !simOperator.isEmpty();
    }
}
