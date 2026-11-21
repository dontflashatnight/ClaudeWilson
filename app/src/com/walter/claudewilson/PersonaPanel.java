package com.walter.claudewilson;

import android.content.Context;
import com.walter.claudewilson.model.PersonaSalt;
import com.walter.claudewilson.model.PersonaSynth;
import com.walter.claudewilson.model.TargetSpec;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import static com.walter.claudewilson.RegionSelection.*;

/** Persona workbench: SpaceProfile + Region Switch + 2x2 expandable identity matrix. */
final class PersonaPanel extends UiSection {
    interface WorkbenchActions {
        void openSpacePicker();
        void openRegionPicker();
        void selectRegionIso(String iso);
        void reapplyCurrent();
        boolean isApplyBusy();
        boolean isReverting();
    }

    private static volatile String expandedDrawer;

    private final Runnable renderScreen;
    private final java.util.function.Supplier<String> currentUser;
    private final java.util.function.Supplier<String> playCountry;
    private final java.util.function.Supplier<String> applyStatus;
    private final java.util.function.Supplier<com.walter.claudewilson.model.RiskModel> riskModel;
    private java.util.function.Supplier<java.util.List<com.walter.claudewilson.model.Account>> accountsSupplier;
    private java.util.function.BooleanSupplier accountsReadableSupplier;
    private final PersonaState persona;
    private WorkbenchActions actions;
    private int mismatches;
    private boolean restrictedNativeSim;
    private final RootActions rootActions;
    private final RegionActions regionActions;
    private volatile String payload;
    private volatile String lastLedgerSnapSig;
    private LinearLayout lastCard;
    private final Object injectLock = new Object();
    private TextView rootHint, actionStatus;
    private String memoRadioKey, memoWifi, memoBle, memoSensor;
    private java.util.List<com.walter.claudewilson.model.WifiProfile.Ap> memoWifiList;
    private java.util.List<com.walter.claudewilson.model.BleProfile.Device> memoBleList;
    private com.walter.claudewilson.model.SensorProfile memoSensorProfile;
    private static final java.util.concurrent.ExecutorService EDIT_TX =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cw-persona-edit");
                t.setDaemon(true);
                return t;
            });

    PersonaPanel(Context context, Runnable renderScreen,
                 java.util.function.Supplier<String> currentUser,
                 java.util.function.Supplier<String> playCountry,
                 java.util.function.Supplier<String> applyStatus,
                 java.util.function.Supplier<com.walter.claudewilson.model.RiskModel> riskModel) {
        super(context);
        this.renderScreen = renderScreen;
        this.currentUser = currentUser;
        this.playCountry = playCountry;
        this.applyStatus = applyStatus;
        this.riskModel = riskModel;
        this.persona = new PersonaState(context);
        this.rootActions = new RootActions(context);
        this.regionActions = new RegionActions(context);
    }

    void setWorkbenchActions(WorkbenchActions actions) {
        this.actions = actions;
    }

    void setAccountSupplier(
            java.util.function.Supplier<java.util.List<com.walter.claudewilson.model.Account>> accounts,
            java.util.function.BooleanSupplier readable) {
        this.accountsSupplier = accounts;
        this.accountsReadableSupplier = readable;
    }

    String spaceRegionIso(String user) {
        return persona.regionIso(user);
    }

    String spaceRegionZone(String user) {
        return persona.regionZone(user);
    }

    String spaceRegionLocale(String user) {
        return persona.regionLocale(user);
    }

    void bindSpaceRegion(String user, String iso, String zone, String locale) {
        persona.putRegion(user, iso, zone, locale);
    }

    void clearSpaceRegion(String user) {
        persona.clearRegion(user);
    }

    String lastSelectedUser() {
        return persona.lastSelectedUser();
    }

    void putLastSelectedUser(String user) {
        persona.putLastSelectedUser(user);
    }

    boolean legacyRegionMigrated() {
        return persona.legacyRegionMigrated();
    }

    void markLegacyRegionMigrated() {
        persona.markLegacyRegionMigrated();
    }

    boolean hasAnyBoundRegion() {
        return persona.hasAnyBoundRegion();
    }

    com.walter.claudewilson.model.SpaceProfile spaceProfile(String user) {
        return persona.profile(user);
    }

    /** iso → {simOperator, carrierName}：官方大区主要拜访网值，缺省 mcc+01。 */
    private static final String[][] NET = {
        {"jp","44010","docomo"},{"kr","45005","SKTelecom"},
        {"nl","20416","KPN"},{"us","310260","T-Mobile"},
        {"gb","23430","EE"},{"de","26201","Telekom"},
        {"fr","20801","Orange"},{"sg","52501","SingTel"},
        {"ca","302220","Telus"},{"au","50501","Telstra"},
        {"hk","45400","CSL"},{"tw","46692","Chunghwa"},
        {"cn","46000","China Mobile"},{"in","40410","Airtel"},
        {"br","72405","Claro"},{"it","22210","TIM"},
        {"es","21401","Movistar"},{"se","24001","Telia"},
        {"ae","42402","Etisalat"},{"tr","28601","Turkcell"},
        {"th","52001","AIS"},{"my","50212","Maxis"},
        {"id","51010","Telkomsel"},{"vn","45204","Viettel"},
        {"ph","51501","Smart"},{"mn","42801","Mobicom"},
        {"nz","53001","One NZ"},{"za","65501","Vodacom"},
    };

    /** iso → {simOperator, carrierName} row, or null. Shared with Dashboard's apply path. */
    static String[] netFor(String iso) {
        for (String[] n : NET) if (n[0].equals(iso)) return n;
        return null;
    }

    private String[][] devices;

    /** Curated presets from catalog/personas.txt; the running device is always available too. */
    private String[][] devices() {
        if (devices == null) {
            java.util.List<String[]> rows = com.walter.claudewilson.platform.TablesLoader
                    .load(getApplicationContext()).personas();
            devices = rows.toArray(new String[0][]);
        }
        return devices;
    }

    /**
     * The running device itself, as a preset.
     *
     * <p>Everything comes from the pre-persona property snapshot when one exists, so a simulated
     * build does not become "this device" on the next app start. {@code Build} is only the
     * fallback: it is fixed at zygote start and may already describe the persona.
     */
    private static String[] selfDevice() {
        String manufacturer = originalOr("ro.product.manufacturer",
                android.os.Build.MANUFACTURER);
        String model = originalOr("ro.product.model", android.os.Build.MODEL);
        return new String[]{
                manufacturer + " " + model,
                manufacturer,
                originalOr("ro.product.brand", android.os.Build.BRAND),
                model,
                originalOr("ro.product.device", android.os.Build.DEVICE),
                originalOr("ro.product.name", android.os.Build.PRODUCT),
                originalOr("ro.hardware", android.os.Build.HARDWARE),
                originalOr("ro.product.board", android.os.Build.BOARD),
                originalOr("ro.build.fingerprint", android.os.Build.FINGERPRINT),
                originalOr("ro.build.id", android.os.Build.ID),
                originalOr("ro.build.version.incremental", android.os.Build.VERSION.INCREMENTAL),
                originalOr("ro.build.version.release", android.os.Build.VERSION.RELEASE),
                String.valueOf(android.os.Build.VERSION.SDK_INT),
                originalOr("ro.build.version.security_patch",
                        android.os.Build.VERSION.SECURITY_PATCH == null
                                ? "" : android.os.Build.VERSION.SECURITY_PATCH),
        };
    }

    private static String originalOr(String key, String fallback) {
        String value = DeviceSignals.originalProp(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** One extra slot in front of the curated list: the device we are running on. */
    private int deviceCount() { return devices().length + 1; }

    private String[] deviceAt(int index) {
        String[][] table = devices();
        if (table.length == 0) {
            return selfDevice();
        }
        return index <= 0 ? selfDevice() : table[(index - 1) % table.length];
    }

    String payload() { return payload; }

    /** The 14-field device row this space simulates, or null when it runs the device's own one. */
    String[] deviceValues(String user) {
        if (!persona.hasDeviceOverride(user)) {
            return null;
        }
        String[] custom = persona.customDevice(user);
        String[] baseRow = custom != null
                ? custom
                : deviceAt(Math.floorMod(persona.device(user), deviceCount()));
        long crossSeed = persona.effectiveDeviceSeed(user);
        Regions.R r = regionIdx >= 0 && regionIdx < Regions.ALL.size()
                ? Regions.ALL.get(regionIdx) : null;
        long profileSeed = profileSeedFor(user, baseRow, crossSeed, r != null ? r.iso : null);
        return resolveDeviceRow(user, baseRow, crossSeed, profileSeed, r);
    }

    private String modelIdentityFor(String user, String[] dv) {
        String profileIdentity =
                com.walter.claudewilson.model.ProfileIdentity.of(dv != null ? dv : selfDevice());
        if (!com.walter.claudewilson.model.ProfileIdentity.usable(profileIdentity)) {
            profileIdentity = persona.unlistedIdentity(user);
        }
        return profileIdentity;
    }

    private long profileSeedFor(String user, String[] dv, long crossSeed, String regionIso) {
        String profileIdentity = modelIdentityFor(user, dv);
        return PersonaSalt.profileSeed(persona.saltVersion(user), crossSeed,
                persona.profileSalt(user, profileIdentity), regionIso);
    }

    /**
     * Resolves the 14-field build identity for {@code (user, baseRow)} with permanent build-pin
     * freezing:
     * <ol>
     *   <li>Returns the already-frozen build pin if present and matching this model/release.</li>
     *   <li>For legacy v1 spaces without a pin yet, adopts the previously uploaded fingerprint +
     *       os_build from telemetry when it matches this model/release, preventing mid-flight
     *       identity mutation across app upgrades.</li>
     *   <li>Otherwise synthesizes once (cross + align + region CSC tag) and freezes the result.</li>
     * </ol>
     */
    private String[] resolveDeviceRow(String user, String[] baseRow, long crossSeed,
                                      long profileSeed, Regions.R region) {
        if (baseRow == null) {
            return null;
        }
        String modelId = modelIdentityFor(user, baseRow);
        String[] pin = persona.buildPin(user, modelId);
        if (pin != null && pin.length == 4) {
            String[] applied = PersonaSalt.applyBuildPin(baseRow, pin[0], pin[1], pin[2], pin[3]);
            if (applied != null) {
                return applied;
            }
        }
        if (persona.saltVersion(user) <= PersonaSalt.SALT_V1) {
            String[] adopted = adoptLegacyBuildPin(user, modelId, baseRow);
            if (adopted != null) {
                return adopted;
            }
        }
        String[] crossed = PersonaSynth.cross(baseRow, crossSeed);
        String[] aligned = alignToHost(crossed, profileSeed);
        String[] finalDv = taggedForRegion(aligned, region);
        persona.putBuildPin(user, modelId, finalDv);
        return finalDv;
    }

    private String[] adoptLegacyBuildPin(String user, String modelId, String[] baseRow) {
        try {
            TargetSpec target = Targets.current();
            if (target == null) {
                return null;
            }
            java.util.Map<String, TelemetryStore.Entry> entries =
                    TelemetryStore.read(this, target.id());
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            String histFp = TelemetryStore.latest(entries, "fingerprint");
            String histBuild = TelemetryStore.latest(entries, "os-build");
            String[] adopted = PersonaSalt.applyBuildPin(baseRow, histFp, histBuild, "", "");
            if (adopted != null) {
                persona.putBuildPin(user, modelId, adopted);
                return adopted;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String[] alignToHost(String[] dv, long alignSeed) {
        if (dv == null) {
            return null;
        }
        return PersonaSynth.alignToHostSdk(
                dv, android.os.Build.VERSION.SDK_INT, android.os.Build.VERSION.RELEASE, alignSeed);
    }

    /** Host {@code Build.VERSION.SDK_INT_FULL} (API 36+), or 0 on earlier releases. */
    private static int hostSdkIntFull() {
        try {
            return android.os.Build.VERSION.class.getField("SDK_INT_FULL").getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String formatSdkFull(int sdkIntFull) {
        if (sdkIntFull >= 100000) {
            return (sdkIntFull / 100000) + "." + (sdkIntFull % 100000);
        }
        return sdkIntFull + ".0";
    }

    /**
     * Aligns OEM region properties present on the host/brand (Samsung {@code ro.csc.*} /
     * {@code ril.product_code} / {@code ril.matchedcsc*} / {@code ril.simoperator} /
     * {@code ril.epdg.currenMno} and Xiaomi {@code ro.miui.region}) so neither Java nor native
     * property reads leak the physical firmware's country or domestic MNO code.
     */
    private static String oemRegionPropsJson(String[] dv, Regions.R r, String effSimCar) {
        if (r == null || r.iso == null || r.iso.isEmpty()) {
            return "";
        }
        String isoUp = r.iso.toUpperCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean isSamsung = (dv != null && dv.length > 2 && "samsung".equalsIgnoreCase(dv[2]))
                || !empty(DeviceSignals.getprop("ro.csc.sales_code"))
                || !empty(DeviceSignals.getprop("ro.csc.country_code"))
                || !empty(DeviceSignals.getprop("ril.product_code"));
        if (isSamsung) {
            String countryName = new java.util.Locale("", isoUp)
                    .getDisplayCountry(java.util.Locale.ENGLISH);
            if (countryName == null || countryName.isEmpty()) {
                countryName = isoUp;
            }
            String salesCode = Regions.cscFor(r.iso);
            if (salesCode == null || salesCode.isEmpty()) {
                salesCode = isoUp;
            }
            sb.append(",\"ro.csc.country_code\":").append(q(countryName));
            sb.append(",\"ro.csc.countryiso_code\":").append(q(isoUp));
            sb.append(",\"ro.csc.sales_code\":").append(q(salesCode));

            String hostProdCode = DeviceSignals.getprop("ril.product_code");
            String spoofProdCode = "";
            if (hostProdCode != null && hostProdCode.length() > 3 && salesCode.length() == 3) {
                spoofProdCode = hostProdCode.substring(0, hostProdCode.length() - 3) + salesCode;
            } else {
                String model = (dv != null && dv.length > 3 && !empty(dv[3]))
                        ? dv[3]
                        : (!empty(DeviceSignals.getprop("ro.product.model"))
                                ? DeviceSignals.getprop("ro.product.model")
                                : android.os.Build.MODEL);
                if (!empty(model)) {
                    spoofProdCode = model + "ZSE" + salesCode;
                }
            }
            if (!spoofProdCode.isEmpty()) {
                sb.append(",\"ril.product_code\":").append(q(spoofProdCode));
            }
            sb.append(",\"ril.matchedcsc\":").append(q(salesCode));
            sb.append(",\"ril.matchedcsc2\":").append(q(salesCode));

            String carTok = (effSimCar != null && !effSimCar.trim().isEmpty())
                    ? effSimCar.trim().replaceAll("[^A-Za-z0-9]", "") : salesCode;
            if (carTok.isEmpty()) {
                carTok = salesCode;
            }
            sb.append(",\"ril.simoperator\":")
                    .append(q(slotFilledProp("ril.simoperator", carTok)));
            sb.append(",\"ril.epdg.currenMno\":")
                    .append(q(slotFilledProp("ril.epdg.currenMno", carTok + "_" + isoUp)));
        }
        if (!empty(DeviceSignals.getprop("ro.miui.region"))) {
            sb.append(",\"ro.miui.region\":").append(q(isoUp));
        }
        return sb.toString();
    }

    /** Formats a single-slot value to match the device's per-slot comma list length. */
    private static String slotFilledProp(String propName, String singleVal) {
        String val = singleVal == null ? "" : singleVal;
        String cur = DeviceSignals.getprop(propName);
        if (cur == null || cur.indexOf(',') < 0) {
            return val;
        }
        int commas = 0;
        for (int i = 0; i < cur.length(); i++) {
            if (cur.charAt(i) == ',') {
                commas++;
            }
        }
        StringBuilder out = new StringBuilder(val);
        for (int i = 0; i < commas; i++) {
            out.append(',').append(val);
        }
        return out.toString();
    }

    void renderInto(LinearLayout card) {
        lastCard = card;
        card.removeAllViews();
        payload = null;
        mismatches = 0;
        restrictedNativeSim = false;
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) {
            card.addView(txt(Lang.t("请输入数字空间 ID", "Enter a numeric space ID",
                    "数字の空間 ID を入力してください"), 11, DIM));
            return;
        }
        Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        boolean hasPersona = r != null;
        RegionPersona.Snapshot saved = hasPersona
                ? RegionPersona.of(this, user, r) : null;
        if (saved != null) {
            // The region record is the truth after the first selection; do not re-pick.
            applyZone = saved.zone;
            applyLocale = saved.locale;
        }
        String iso = hasPersona ? r.iso : "—";
        String ccHint = persona.countryHint(user);
        if (ccHint == null || !ccHint.matches("[A-Za-z]{2}")) {
            ccHint = iso;
        }
        String[] net = netFor(iso);
        String op = hasPersona ? currentOp(r) : "—";
        String car = hasPersona ? currentCarrier(r) : "";
        String opShown = op;
        String carShown = car.isEmpty() ? "—" : car;
        // No persona value is minted up front: an unset AID means "this device's own id", and
        // only an explicit save in the AID dialog turns it into an override.
        String aidOverride = persona.aid(user);
        String[] customDevice = hasPersona ? persona.customDevice(user) : null;
        int devIdx = hasPersona ? persona.device(user) % deviceCount() : 0;
        String[] baseDevice = hasPersona
                ? (customDevice != null ? customDevice : deviceAt(devIdx)) : null;
        final int saltVer = hasPersona ? persona.saltVersion(user) : PersonaSalt.SALT_V1;
        long crossSeed = hasPersona ? persona.effectiveDeviceSeed(user) : 0L;
        final String loc = hasPersona && applyLocale != null ? applyLocale : "—";
        final String tz  = hasPersona && applyZone   != null ? applyZone   : "—";
        com.walter.claudewilson.model.GeoTable.Point regionPoint =
                hasPersona ? Geo.point(this, r.iso, applyZone) : null;
        com.walter.claudewilson.model.GeoTable.Point place = regionPoint;
        if (hasPersona && saved != null && !saved.city.isEmpty()) {
            place = com.walter.claudewilson.model.GeoTable.Point.of(
                    saved.city, saved.latitude, saved.longitude);
        }
        boolean customLocation = false;
        if (hasPersona && persona.hasLocationPoint(user)) {
            place = com.walter.claudewilson.model.GeoTable.Point.of(
                    persona.locationCity(user),
                    persona.locationLatitude(user), persona.locationLongitude(user));
            customLocation = true;
        }
        final boolean locationOn = hasPersona && persona.location(user);
        final long profileSeed = hasPersona
                ? profileSeedFor(user, baseDevice, crossSeed, r.iso) : 0L;
        String[] dv = hasPersona
                ? resolveDeviceRow(user, baseDevice, crossSeed, profileSeed, r) : null;
        final boolean wifiOn = persona.wifiEnabled(user);
        final long wifiSalt = persona.wifiSalt(user);
        final String wifiCustomSsid = persona.wifiCustomSsid(user);
        final String wifiCustomBssid = persona.wifiCustomBssid(user);
        final boolean bleOn = persona.bleEnabled(user);
        final boolean bleEmpty = persona.bleEmptyMode(user);
        final long bleSalt = persona.bleSalt(user);
        final String bleCustomBonded = persona.bleCustomBonded(user);
        final boolean sensorsOn = persona.sensorsEnabled(user);
        final String sensorPreset = persona.sensorPreset(user);
        final String locAccMode = persona.locationAccuracyMode(user);
        final double locAlt = persona.locationAltitude(user);

        final String wifiJson;
        final String bleJson;
        final String sensorJson;
        final java.util.List<com.walter.claudewilson.model.WifiProfile.Ap> activeWifiList;
        final java.util.List<com.walter.claudewilson.model.BleProfile.Device> activeBleList;
        final com.walter.claudewilson.model.SensorProfile activeSensorProfile;
        final int localHour;
        if (place == null) {
            wifiJson = "[]";
            bleJson = "[]";
            sensorJson = "{}";
            activeWifiList = java.util.Collections.emptyList();
            activeBleList = java.util.Collections.emptyList();
            activeSensorProfile = null;
            localHour = 12;
        } else {
            java.util.TimeZone zoneObj = java.util.TimeZone.getTimeZone(
                    applyZone == null || applyZone.isEmpty() ? "UTC" : applyZone);
            localHour = java.util.Calendar.getInstance(zoneObj)
                    .get(java.util.Calendar.HOUR_OF_DAY);
            String radioKey = r.iso + "|" + place.city + "|" + place.latitude + "|"
                    + place.longitude + "|" + profileSeed + "|" + localHour
                    + "|" + wifiOn + "|" + wifiSalt + "|" + wifiCustomSsid + "|" + wifiCustomBssid
                    + "|" + bleOn + "|" + bleEmpty + "|" + bleSalt + "|" + bleCustomBonded
                    + "|" + sensorsOn + "|" + sensorPreset + "|" + locAlt;
            if (radioKey.equals(memoRadioKey) && memoWifi != null
                    && memoBle != null && memoSensor != null
                    && memoWifiList != null && memoBleList != null) {
                wifiJson = memoWifi;
                bleJson = memoBle;
                sensorJson = memoSensor;
                activeWifiList = memoWifiList;
                activeBleList = memoBleList;
                activeSensorProfile = memoSensorProfile;
            } else {
                String cityTag = place.city.isEmpty()
                        ? r.iso.toUpperCase(java.util.Locale.ROOT) : place.city;
                java.util.List<com.walter.claudewilson.model.WifiProfile.Ap> wList = wifiOn
                        ? com.walter.claudewilson.model.WifiProfile.applyOverrides(
                                com.walter.claudewilson.model.WifiProfile.forRegion(
                                        place.latitude, place.longitude, cityTag,
                                        PersonaSalt.wifiSeed(saltVer, profileSeed, wifiSalt), 8,
                                        ProfileLibrary.wifi(this, r.iso), ProfileLibrary.oui(this)),
                                wifiCustomSsid, wifiCustomBssid)
                        : java.util.Collections.<com.walter.claudewilson.model.WifiProfile.Ap>emptyList();
                java.util.List<com.walter.claudewilson.model.BleProfile.Device> bList = (bleOn && !bleEmpty)
                        ? com.walter.claudewilson.model.BleProfile.applyOverrides(
                                com.walter.claudewilson.model.BleProfile.forRegion(
                                        PersonaSalt.bleSeed(saltVer, profileSeed, bleSalt), 4,
                                        ProfileLibrary.ble(this, r.iso), ProfileLibrary.oui(this)),
                                bleCustomBonded, false)
                        : java.util.Collections.<com.walter.claudewilson.model.BleProfile.Device>emptyList();
                com.walter.claudewilson.model.SensorProfile sProf = sensorsOn
                        ? com.walter.claudewilson.model.SensorProfile.forPreset(
                                profileSeed, place.latitude, place.longitude, localHour,
                                ProfileLibrary.sensor(this, profileSeed), sensorPreset, locAlt)
                        : null;
                wifiJson = com.walter.claudewilson.model.WifiProfile.json(wList);
                bleJson = com.walter.claudewilson.model.BleProfile.json(bList);
                sensorJson = sProf != null ? sProf.json() : "{}";
                activeWifiList = wList;
                activeBleList = bList;
                activeSensorProfile = sProf;
                memoRadioKey = radioKey;
                memoWifi = wifiJson;
                memoBle = bleJson;
                memoSensor = sensorJson;
                memoWifiList = wList;
                memoBleList = bList;
                memoSensorProfile = sProf;
            }
        }
        boolean rootChannel = RootShell.rootReady();
        String liveOp = "", liveIso = "", liveCarrier = "";
        int simSlot = activeSlot();
        String propOp = slotValue(DeviceSignals.getprop("gsm.sim.operator.numeric"), simSlot);
        String propIso = slotValue(DeviceSignals.getprop("gsm.sim.operator.iso-country"), simSlot);
        String propCarrier = slotValue(DeviceSignals.getprop("gsm.sim.operator.alpha"), simSlot);
        if (rootChannel) {
            liveOp = propOp;
            liveIso = propIso;
            liveCarrier = propCarrier;
        }
        String subOp = "", subIso = "", subCarrier = "", subDisplay = "";
        try {
            android.telephony.SubscriptionManager sm =
                    (android.telephony.SubscriptionManager) getSystemService(
                            android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            java.util.List<android.telephony.SubscriptionInfo> subs =
                    sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (subs != null && !subs.isEmpty()) {
                // The identity belongs to the subscription that carries data, not to the first
                // row: this device lists a disabled pSIM (460/01) before the live eSIM
                // (310/840), and the first row named a SIM the phone is not using.
                int wanted = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
                android.telephony.SubscriptionInfo chosen = subs.get(0);
                for (android.telephony.SubscriptionInfo candidate : subs) {
                    if (candidate.getSubscriptionId() == wanted) {
                        chosen = candidate;
                        break;
                    }
                }
                String mcc = chosen.getMccString();
                String mnc = chosen.getMncString();
                if (!empty(mcc) && !empty(mnc)) {
                    subOp = mcc + mnc;
                }
                String simIso = chosen.getCountryIso();
                if (!empty(simIso)) {
                    subIso = simIso;
                }
                CharSequence name = chosen.getCarrierName();
                if (name != null && name.length() > 0) {
                    subCarrier = clean(name.toString());
                }
                CharSequence disp = chosen.getDisplayName();
                if (disp != null && disp.length() > 0) {
                    subDisplay = clean(disp.toString());
                }
            }
        } catch (Throwable ignored) {
        }
        android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                getSystemService(Context.TELEPHONY_SERVICE);
        String tmOp = "", tmIso = "", tmCarrier = "";
        if (tm != null) {
            try { tmOp = tm.getSimOperator(); } catch (RuntimeException ignored) {}
            try { tmIso = tm.getSimCountryIso(); } catch (RuntimeException ignored) {}
            try { tmCarrier = clean(tm.getSimOperatorName()); } catch (RuntimeException ignored) {}
        }
        if (rootChannel) {
            if (empty(liveOp)) liveOp = !empty(subOp) ? subOp : tmOp;
            if (empty(liveIso)) liveIso = !empty(subIso) ? subIso : tmIso;
            if (empty(liveCarrier)) liveCarrier = !empty(subCarrier) ? subCarrier : tmCarrier;
        } else {
            // On a shell channel gsm.sim.operator.* stays on the physical card; CarrierConfig
            // and subscription display-name overrides appear in TelephonyManager / SubscriptionInfo.
            liveOp = !empty(subOp) ? subOp : !empty(tmOp) ? tmOp : propOp;
            liveIso = !empty(tmIso) ? tmIso : !empty(subIso) ? subIso : propIso;
            if (hasPersona && RootShell.preservesMobileData() && !empty(subDisplay)) {
                liveCarrier = subDisplay;
            } else {
                liveCarrier = !empty(tmCarrier) ? tmCarrier
                        : !empty(subDisplay) ? subDisplay
                        : !empty(subCarrier) ? subCarrier : propCarrier;
            }
        }
        boolean simCountryWritable = RootShell.simCountryWritable();
        boolean simCarrierWritable = RootShell.simCarrierWritable();
        String liveNet = "", liveNetIso = "", liveNetName = "";
        if (tm != null) {
            try { liveNet = tm.getNetworkOperator(); } catch (RuntimeException ignored) {}
            try { liveNetName = tm.getNetworkOperatorName(); } catch (RuntimeException ignored) {}
        }
        String propNet = slotValue(DeviceSignals.getprop("gsm.operator.numeric"), simSlot);
        String propNetName = slotValue(DeviceSignals.getprop("gsm.operator.alpha"), simSlot);
        String propNetIso = slotValue(DeviceSignals.getprop("gsm.operator.iso-country"), simSlot);
        if (rootChannel) {
            if (!empty(propNet)) liveNet = propNet;
            if (!empty(propNetName)) liveNetName = propNetName;
            if (!empty(propNetIso)) liveNetIso = propNetIso;
        } else {
            if (empty(liveNet)) liveNet = propNet;
            if (empty(liveNetName)) liveNetName = propNetName;
            if (empty(liveNetIso)) liveNetIso = propNetIso;
        }
        // A device in airplane mode has no registered network at all: the row must not present the
        // last known PLMN as a current value.
        boolean airplane = false;
        try {
            airplane = android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Throwable ignored) {}
        // The telephony property writer joins per-slot values with commas, so a dual-SIM device
        // stores "52501," for one filled slot. The comma is list syntax, not part of the value.
        liveOp = simValue(liveOp);
        liveIso = simValue(liveIso);
        liveCarrier = simValue(liveCarrier);
        // With no region selected, the card's own country is what should be shown; a previous
        // carrier-config override leaves its value cached in the subscription record and the
        // platform only refreshes it when it re-reads the card. The MCC still names the card, so
        // in the off state it wins when the two disagree. Computed after simValue(): the raw
        // property may still carry the leading per-slot comma.
        if (!hasPersona && !empty(liveOp) && liveOp.length() >= 3) {
            String fromMcc = mccIso(liveOp.substring(0, 3));
            if (!empty(fromMcc) && !fromMcc.equalsIgnoreCase(liveIso)) {
                liveIso = fromMcc;
            }
        }
        liveNet = simValue(liveNet);
        liveNetIso = simValue(liveNetIso);
        liveNetName = simValue(liveNetName);
        // This process may have started before the system zone changed. TimeZone.getDefault()
        // keeps the old value, so read the property the rest of the system actually uses.
        String liveTz = DeviceSignals.getprop("persist.sys.timezone");
        if (empty(liveTz)) {
            liveTz = java.util.TimeZone.getDefault().getID();
        }
        // The target language is whatever the target process reads from the hook payload or per-app
        // locale. It is not this control app's UI language and not the system fallback locale.
        com.walter.claudewilson.model.CheckItem targetLocale =
                modelItem("target.locale");
        String targetLocaleNote = targetLocale != null ? targetLocale.note() : null;
        boolean targetLocaleUsable = targetLocale != null && targetLocale.isRead()
                && !targetLocale.isStale()
                && !"system-fallback".equals(targetLocaleNote);
        boolean targetLocaleLive = targetLocaleUsable
                && !"next-launch".equals(targetLocaleNote);
        String liveLoc = targetLocaleLive
                ? targetLocale.value().replace('-', '_')
                : "—";
        // Read the property area, not this process's Build class: a persona write reaches new
        // processes only, so Build.MODEL would keep showing the value cached at app start.
        String liveModel = val(firstNonEmpty(
                DeviceSignals.getprop("ro.product.model"), android.os.Build.MODEL));
        String liveAidText;
        try {
            liveAidText = val(android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
        } catch (Throwable t) {
            liveAidText = "?";
        }
        String dash = "—";
        // Model and AID are injection-layer targets: without hooks they can only ever show
        // the device own value, so they must not pretend to compare against a persona.
        TargetSpec target = Targets.current();
        com.walter.claudewilson.model.CheckItem spoofProbe = modelItem("target.ipv6_spoof");
        boolean hookObserved = targetProcessLive()
                || (spoofProbe != null && spoofProbe.isRead());
        boolean hookChannel = rootChannel || hookObserved;
        boolean canInject = hasPersona && hookChannel
                && target.localIdentityWrites();
        // A target is shown only after a deliberate pick; a stored value alone is not a request.
        boolean devTarget = canInject && persona.hasDeviceOverride(user);
        boolean aidTarget = canInject && persona.hasAidOverride(user);
        // Built here rather than at the bottom: the rows below need the payload's signature to
        // tell "this process is running exactly this persona" from "the region tab moved on".
        final com.walter.claudewilson.model.SimPolicy.Decision simDecision =
                RegionActions.evaluateRealSim(this, target.id(), persona.keepRealSim(user));
        final boolean preserveRealSim = hasPersona && simDecision.preserveRealSim;
        final String effSimOp = preserveRealSim ? simDecision.resolvedOp : op;
        final String effSimIso = preserveRealSim ? simDecision.resolvedIso : iso;
        final String effSimCar = preserveRealSim ? simDecision.resolvedCarrier : car;
        final String effSimOpShown = effSimOp;
        final String effSimCarShown = effSimCar.isEmpty() ? "—" : effSimCar;
        final boolean ipv6SpoofOn = persona.ipv6SpoofEnabled();
        String[] v6Pair = (hasPersona && ipv6SpoofOn)
                ? resolveIpv6Pair(user, iso, car, profileSeed)
                : new String[]{"", ""};
        String v6PayloadPfx = v6Pair[0];
        String v6MobilePayloadPfx = v6Pair[1];
        if (hasPersona) {
            String aidVal = aidOverride != null ? aidOverride : liveAidText;
            String dispId = dv[9] + "." + dv[10];
            int hostSdkFull = hostSdkIntFull();
            String[] soc = com.walter.claudewilson.model.PersonaSynth.socFor(
                    dv[1], dv[3], dv[4], dv[6], dv[7]);
            payload = "{\"target\":" + q(target.id())
                + ",\"simOperator\":" + q(effSimOp)
                + ",\"simCountryIso\":" + q(effSimIso)
                + ",\"simOperatorName\":" + q(effSimCar)
                + ",\"keepRealSim\":" + preserveRealSim
                + ",\"netOperator\":" + q(effSimOp)
                + ",\"netOperatorName\":" + q(effSimCar)
                + ",\"netCountryIso\":" + q(effSimIso)
                + ",\"countryCodeHint\":" + q(ccHint)
                + ",\"simCarrierId\":"
                + com.walter.claudewilson.model.PersonaEditRules.carrierIdFor(effSimOp, effSimCar)
                + ",\"androidId\":" + q(aidVal)
                + (applyLocale != null
                        ? ",\"locale\":" + q(applyLocale) : "")
                + (applyZone != null
                        ? ",\"tz\":" + q(applyZone) : "")
                + (place != null
                        ? ",\"location\":" + com.walter.claudewilson.model.LocationPayload.json(
                                locationOn, place.latitude, place.longitude, profileSeed,
                                place.city, locAccMode, locAlt)
                                + ",\"wifi\":" + wifiJson
                                + ",\"ble\":" + bleJson
                                + ",\"bleEmpty\":" + (bleOn && bleEmpty)
                                + ",\"sensors\":" + sensorJson
                                + ",\"sensorsEnabled\":" + sensorsOn
                                + ",\"build\":{"
                        : ",\"build\":{")
                + "\"MANUFACTURER\":" + q(dv[1])
                + ",\"BRAND\":" + q(dv[2])
                + ",\"MODEL\":" + q(dv[3])
                + ",\"DEVICE\":" + q(dv[4])
                + ",\"PRODUCT\":" + q(dv[5])
                + ",\"HARDWARE\":" + q(dv[6])
                + ",\"BOARD\":" + q(dv[7])
                + ",\"SOC_MANUFACTURER\":" + q(soc[0])
                + ",\"SOC_MODEL\":" + q(soc[1])
                + ",\"SKU\":" + q(soc[2])
                + ",\"FINGERPRINT\":" + q(dv[8])
                + ",\"ID\":" + q(dv[9])
                + ",\"TAGS\":\"release-keys\",\"TYPE\":\"user\""
                + ",\"DISPLAY\":" + q(dispId)
                + ",\"VERSION.RELEASE\":" + q(dv[11])
                + ",\"VERSION.SDK_INT\":" + Integer.parseInt(dv[12])
                + (hostSdkFull > 0 ? ",\"VERSION.SDK_INT_FULL\":" + hostSdkFull : "")
                + ",\"VERSION.SECURITY_PATCH\":" + q(dv[13])
                + ",\"VERSION.INCREMENTAL\":" + q(dv[10])
                + "},\"props\":{"
                + "\"ro.product.brand\":" + q(dv[2])
                + ",\"ro.product.model\":" + q(dv[3])
                + ",\"ro.product.device\":" + q(dv[4])
                + ",\"ro.product.name\":" + q(dv[5])
                + ",\"ro.product.manufacturer\":" + q(dv[1])
                + ",\"ro.product.board\":" + q(dv[7])
                + ",\"ro.hardware\":" + q(dv[6])
                + ",\"ro.soc.manufacturer\":" + q(soc[0])
                + ",\"ro.soc.model\":" + q(soc[1])
                + ",\"ro.boot.hardware.sku\":" + q(soc[2])
                + ",\"ro.build.fingerprint\":" + q(dv[8])
                + ",\"ro.build.display.id\":" + q(dispId)
                + ",\"ro.build.version.release\":" + q(dv[11])
                + ",\"ro.build.version.release_or_codename\":" + q(dv[11])
                + ",\"ro.build.version.release_or_preview_display\":" + q(dv[11])
                + ",\"ro.build.version.sdk\":" + q(dv[12])
                + (hostSdkFull > 0
                        ? ",\"ro.build.version.sdk_full\":" + q(formatSdkFull(hostSdkFull)) : "")
                + ",\"ro.build.version.security_patch\":" + q(dv[13])
                + ",\"ro.build.version.incremental\":" + q(dv[10])
                + ",\"ro.build.id\":" + q(dv[9])
                + ",\"ro.product.locale\":" + q(applyLocale == null ? "" : applyLocale)
                + ",\"ro.build.tags\":\"release-keys\""
                + ",\"ro.build.type\":\"user\""
                + ",\"gsm.operator.numeric\":"
                        + q(slotFilledProp("gsm.operator.numeric", effSimOp))
                + ",\"gsm.operator.iso-country\":"
                        + q(slotFilledProp("gsm.operator.iso-country", effSimIso))
                + ",\"gsm.operator.alpha\":"
                        + q(slotFilledProp("gsm.operator.alpha", effSimCar))
                + ",\"gsm.operator.isroaming\":"
                        + q(slotFilledProp("gsm.operator.isroaming", "false"))
                + ",\"gsm.sim.operator.numeric\":"
                        + q(slotFilledProp("gsm.sim.operator.numeric", effSimOp))
                + ",\"gsm.sim.operator.iso-country\":"
                        + q(slotFilledProp("gsm.sim.operator.iso-country", effSimIso))
                + ",\"gsm.sim.operator.alpha\":"
                        + q(slotFilledProp("gsm.sim.operator.alpha", effSimCar))
                + ",\"persist.sys.timezone\":" + q(applyZone == null ? "" : applyZone)
                + oemRegionPropsJson(dv, r, effSimCar) + "}"
                + ",\"ipv6Disabled\":" + (!ipv6SpoofOn)
                + ",\"ipv6Prefix\":" + q(v6PayloadPfx)
                + ",\"ipv6MobilePrefix\":" + q(v6MobilePayloadPfx)
                + ",\"ipv6Seed\":" + profileSeed
                + ",\"appVisMode\":" + q(persona.appVisMode(user))
                + ",\"appVisPackages\":" + com.walter.claudewilson.model.AppVisibilityPolicy.toJsonArray(
                        persona.appVisPackages(user))
                + ",\"appVisExtraHidden\":" + com.walter.claudewilson.model.AppVisibilityPolicy.toJsonArray(
                        persona.hasExplicitAppVisExtra(user)
                                ? persona.appVisExtraHidden(user)
                                : HmaPresetScanner.resolveSystemExtraHidden(
                                        getApplicationContext(), target.packageName(),
                                        persona.appVisPresetGroups(user)))
                + ",\"appVisExcludeSystem\":" + persona.appVisExcludeSystem(user)
                + ",\"appVisStrict\":" + persona.appVisStrict(user)
                + ",\"appVisHideDev\":" + persona.appVisHideDev(user)
                + ",\"appVisHideA11y\":" + persona.appVisHideA11y(user)
                + ",\"appVisHideIme\":" + persona.appVisHideIme(user)
                + ",\"bodyguard\":" + BodyguardStore.payloadJson(this, target.id(), user)
                + "}";
        }
        String payloadSig = payload == null ? null : Integer.toHexString(payload.hashCode());
        if (payload != null && payloadSig != null && !payloadSig.equals(lastLedgerSnapSig)) {
            lastLedgerSnapSig = payloadSig;
            final String snapJson = payload;
            final String snapTarget = target.id();
            final String snapRegion = iso;
            final String rawCc = NetworkPanel.lastEgressCc();
            final String snapEgress = rawCc == null ? "" : rawCc;
            new Thread(() -> LedgerIngestor.recordPersonaFromPayload(
                    getApplicationContext(), snapTarget, user, snapJson, snapRegion, snapEgress),
                    "ledger-snap").start();
        }
        boolean injectedNow = hookChannel && payloadSig != null
                && payloadSig.equals(persona.injectedSig(user));
        boolean targetLive = injectedNow && targetProcessLive();
        com.walter.claudewilson.model.CheckItem simOpItem = modelItem("target.simop");
        com.walter.claudewilson.model.CheckItem simIsoItem = modelItem("target.simiso");
        com.walter.claudewilson.model.CheckItem simCarItem = modelItem("target.carrier");
        boolean simFresh = targetLive && fresh(simOpItem) && fresh(simIsoItem) && fresh(simCarItem);
        // Under root/hook channels the rows project the hook payload and turn green on self-check;
        // under pure Shizuku (no hooks) they report the real card / CarrierConfig state instead of
        // freezing forever on "next launch".
        String actualOp;
        String actualIso;
        String actualCar;
        int opStatus;
        int isoStatus;
        int carStatus;
        if (!hasPersona) {
            actualOp = liveOp;
            actualIso = liveIso;
            actualCar = liveCarrier;
            opStatus = -1;
            isoStatus = -1;
            carStatus = -1;
        } else if (preserveRealSim) {
            actualOp = liveOp;
            actualIso = liveIso;
            actualCar = liveCarrier;
            opStatus = mccComparison(effSimOp, liveOp) == 1 ? 1 : -3;
            isoStatus = comparison(effSimIso, liveIso) == 1 ? 1 : -3;
            carStatus = comparison(effSimCar, liveCarrier) == 1 ? 1 : -3;
        } else if (hookChannel) {
            actualOp = simFresh ? simOpItem.value() : effSimOp;
            actualIso = simFresh ? simIsoItem.value() : effSimIso;
            actualCar = simFresh ? simCarItem.value() : effSimCar;
            opStatus = simFresh ? mccComparison(effSimOp, actualOp) : -3;
            isoStatus = simFresh ? comparison(effSimIso, actualIso) : -3;
            carStatus = simFresh ? comparison(effSimCar, actualCar) : -3;
        } else {
            actualOp = liveOp;
            actualIso = liveIso;
            actualCar = liveCarrier;
            opStatus = mccComparison(effSimOp, liveOp) == 1 ? 1 : -1;
            isoStatus = simCountryWritable
                    ? (RegionSelection.regionApplied ? comparison(effSimIso, liveIso) : -3)
                    : (comparison(effSimIso, liveIso) == 1 ? 1 : -1);
            carStatus = simCarrierWritable
                    ? (RegionSelection.regionApplied ? comparison(effSimCar, liveCarrier) : -3)
                    : (comparison(effSimCar, liveCarrier) == 1 ? 1 : -1);
        }
        rootHint = txt("", 10, AMBER);
        rootHint.setVisibility(View.GONE);
        rootHint.setPadding(0, dp(this, 4), 0, dp(this, 2));
        actionStatus = txt("", 10, DIM);
        String apply = applyStatus.get();
        if (apply != null && !apply.isEmpty()) actionStatus.setText(apply);

        // Collect all persona fields into 6 orthogonal workbench groups while computing exact mismatches.
        final java.util.List<DrawerField> simFields = new java.util.ArrayList<DrawerField>();
        final java.util.List<DrawerField> locFields = new java.util.ArrayList<DrawerField>();
        final java.util.List<DrawerField> gpsFields = new java.util.ArrayList<DrawerField>();
        final java.util.List<DrawerField> radioFields = new java.util.ArrayList<DrawerField>();
        final java.util.List<DrawerField> devFields = new java.util.ArrayList<DrawerField>();
        final java.util.List<DrawerField> storeFields = new java.util.ArrayList<DrawerField>();

        // ---- GROUP 1: SIM & NETWORK ----
        final String simModeActual;
        final int simModeStatus;
        {
            String modeExpected;
            String isoTag = effSimIso != null && !effSimIso.isEmpty() && !"—".equals(effSimIso)
                    ? effSimIso.toUpperCase(java.util.Locale.ROOT) : "";
            if (!hasPersona) {
                modeExpected = dash;
            } else if (simDecision.simEligible) {
                modeExpected = preserveRealSim
                        ? Lang.t("保留原卡", "keep real", "実SIM維持")
                        : Lang.t("伪装覆写", "spoof", "偽装上書");
            } else if (com.walter.claudewilson.model.SimPolicy.REASON_RESTRICTED_SIM
                            .equals(simDecision.reason)
                    || com.walter.claudewilson.model.SimPolicy.REASON_RESTRICTED_PEER
                            .equals(simDecision.reason)) {
                modeExpected = Lang.t("禁区伪装", "spoof", "制限偽装");
            } else {
                modeExpected = Lang.t("伪装覆写", "spoof", "偽装上書");
            }
            if (com.walter.claudewilson.model.SimPolicy.REASON_PRESERVED
                    .equals(simDecision.reason)) {
                simModeActual = hasPersona
                        ? Lang.t("保留原卡", "Real", "実SIM") + " · "
                                + simDecision.resolvedIso.toUpperCase(java.util.Locale.ROOT)
                        : simDecision.resolvedIso + " · " + Lang.t("原卡", "real", "実SIM");
                simModeStatus = hasPersona ? 1 : -1;
            } else if (com.walter.claudewilson.model.SimPolicy.REASON_DISABLED
                    .equals(simDecision.reason)) {
                simModeActual = hasPersona
                        ? Lang.t("伪装覆写", "Spoofed", "偽装")
                                + (isoTag.isEmpty() ? "" : " · " + isoTag)
                        : simDecision.resolvedIso + " · " + Lang.t("原卡", "real", "実SIM");
                simModeStatus = hasPersona ? 1 : -1;
            } else if (com.walter.claudewilson.model.SimPolicy.REASON_RESTRICTED_SIM
                    .equals(simDecision.reason)) {
                simModeActual = hasPersona
                        ? Lang.t("禁区伪装", "Spoofed", "制限偽装")
                                + (isoTag.isEmpty() ? "" : " · " + isoTag)
                        : simDecision.resolvedIso + " · " + Lang.t("禁区", "banned", "制限国");
                simModeStatus = hasPersona ? 1 : 0;
                if (!hasPersona) restrictedNativeSim = true;
            } else if (com.walter.claudewilson.model.SimPolicy.REASON_RESTRICTED_PEER
                    .equals(simDecision.reason)) {
                simModeActual = hasPersona
                        ? Lang.t("禁区伪装", "Spoofed", "制限偽装")
                                + (isoTag.isEmpty() ? "" : " · " + isoTag)
                        : Lang.t("双卡含禁区", "banned peer", "制限併用");
                simModeStatus = hasPersona ? 1 : 0;
                if (!hasPersona) restrictedNativeSim = true;
            } else {
                simModeActual = hasPersona
                        ? Lang.t("无卡伪装", "Virtual", "仮想SIM")
                                + (isoTag.isEmpty() ? "" : " · " + isoTag)
                        : Lang.t("无卡", "no SIM", "SIMなし");
                simModeStatus = hasPersona ? 1 : -1;
            }
            Runnable openCarrier = !hasPersona ? null : () -> runAnyChannelGated(
                    Lang.t("SIM 运营商配置需要 root 或 Shizuku 授权",
                            "SIM carrier configuration needs root or Shizuku"),
                    () -> openCarrierSheet(simDecision, profileSeed));
            addField(simFields, Lang.t("SIM 策略", "SIM mode", "SIM 方針"),
                    modeExpected, simModeActual, simModeStatus,
                    hasPersona, false, openCarrier);
            String groupExpected = hasPersona ? (effSimCarShown + " · " + effSimOpShown) : dash;
            String groupActual = !hasPersona
                    ? (shown(actualCar) + " · " + shown(actualOp))
                    : (shown(actualCar) + " · " + shown(actualOp));
            int groupStatus = !hasPersona ? -1
                    : (opStatus == 0 || carStatus == 0) ? 0
                    : (opStatus == 1 && carStatus == 1) ? 1 : -3;
            addField(simFields, Lang.t("SIM 运营商组", "SIM carrier", "SIM キャリア"),
                    groupExpected, groupActual, groupStatus,
                    hasPersona, false, openCarrier);
        }
        addField(simFields, Lang.t("SIM 国家", "SIM country", "SIM 国"),
                hasPersona ? effSimIso : dash, shown(actualIso), isoStatus,
                false, false, null);

        if (liveNet.length() >= 3) {
            String derived = mccIso(liveNet.substring(0, 3));
            if (!empty(derived) && (empty(liveNetIso)
                    || !derived.equalsIgnoreCase(liveNetIso))) {
                liveNetIso = derived;
            }
        }
        // Visited network in both hook payload and gsm.operator.* props follows the effective SIM trio.
        String netOp = effSimOp;
        String netIso = effSimIso;
        String netName = effSimCar;
        String netTarget = val(netOp) + " · " + netIso + " · " + val(netName);
        String liveNetTrio = shown(liveNet) + " · " + shown(liveNetIso) + " · "
                + shown(liveNetName);
        if (airplane) {
            addField(simFields, Lang.t("拜访网络", "visited network", "訪問ネットワーク"),
                    dash, dash, -1, false, false, null);
        } else {
            String visitedActual = !hasPersona ? liveNetTrio
                    : hookChannel ? netTarget : liveNetTrio;
            int netStatus = !hasPersona ? -1
                    : hookChannel ? (simFresh ? 1 : -3)
                    : (visitedStatus(netOp, netIso, liveNet, liveNetIso) == 1 ? 1 : -1);
            addField(simFields, Lang.t("拜访网络", "visited network", "訪問ネットワーク"),
                    hasPersona ? netTarget : dash, visitedActual, netStatus,
                    false, false, null);
        }

        // ---- GROUP 2: LANGUAGE & LOCATION ----
        String langShown;
        int langStatus;
        if (!hasPersona) {
            langShown = targetLocaleUsable
                    ? targetLocale.value().replace('-', '_')
                    : dash;
            langStatus = -1;
        } else if (targetLocaleLive) {
            langShown = liveLoc;
            langStatus = comparison(loc.replace("-", "_"), liveLoc) == 1
                    || Regions.localeMatch(loc, liveLoc) ? 1 : 0;
        } else if (targetLocaleUsable && "next-launch".equals(targetLocaleNote)) {
            String pendingLoc = targetLocale.value().replace('-', '_');
            boolean match = comparison(loc.replace("-", "_"), pendingLoc) == 1
                    || Regions.localeMatch(loc, pendingLoc);
            langShown = pendingLoc + " · " + Lang.t("下次启动", "next launch", "次回起動");
            langStatus = match ? -3 : 0;
        } else if (hookChannel && injectedNow && !"—".equals(loc)) {
            langShown = loc.replace('-', '_') + " · " + Lang.t("下次启动", "next launch", "次回起動");
            langStatus = -3;
        } else {
            langShown = dash;
            langStatus = -3;
        }
        Runnable openLocTz = !hasPersona ? null : this::openLocaleTzSheet;
        addField(locFields, Lang.t("目标语言", "target language", "対象言語"),
                hasPersona ? loc.replace("-", "_") : dash, langShown, langStatus,
                hasPersona, false, openLocTz);
        int tzStatus = !hasPersona ? -1 : comparison(tz, liveTz);
        addField(locFields, Lang.t("时区", "tz", "TZ"),
                hasPersona ? tz : dash, liveTz, tzStatus,
                hasPersona, false, openLocTz);
        String liveUtc = Regions.utcAt(liveTz);
        int utcStatus = !hasPersona ? -1 : Regions.utcMatch(r.utc, liveUtc) ? 1 : 0;
        addField(locFields, Lang.t("UTC 偏移", "UTC offset", "UTC オフセット"),
                hasPersona ? r.utc : dash, liveUtc, utcStatus,
                hasPersona, false, openLocTz);

        // ---- GROUP 3: GPS LOCATION ----
        int targetLocation = targetLocationPermission(this, target);
        boolean targetHasLocPerm = targetLocation > 0;
        String locWhere = dash;
        if (place != null) {
            locWhere = (place.city.isEmpty() ? r.name : place.city) + (customLocation ? "*" : "");
        }
        boolean effCoarse = (targetLocation == 1) || "coarse".equals(locAccMode);
        String accTag = "coarse".equals(locAccMode) ? "±3km"
                : "drift".equals(locAccMode) ? "±15m" : "±5m";
        String effAccTag = targetHasLocPerm
                ? (effCoarse ? "±3km" : accTag)
                : accTag;
        String altTag = Double.isNaN(locAlt) ? "" : " · " + Math.round(locAlt) + "m";
        String effAltTag = (targetHasLocPerm && effCoarse) ? "" : altTag;
        String locActual;
        int locStatus;
        if (!hasPersona || !hookChannel) {
            locActual = dash;
            locStatus = -1;
        } else if (!locationOn) {
            locActual = targetHasLocPerm
                    ? Lang.t("关闭 · 透传", "off · passthrough", "オフ · 透過")
                    : Lang.t("关闭", "off", "オフ");
            locStatus = targetHasLocPerm ? -2 : -1;
        } else if (place == null) {
            locActual = targetHasLocPerm
                    ? Lang.t("无坐标", "no coordinate", "座標なし")
                    : dash;
            locStatus = targetHasLocPerm ? -2 : -1;
        } else if (!targetHasLocPerm) {
            // Target has no location permission: GPS is not a judgment/risk item; display staged
            // coordinates neutrally without warning color.
            String coordShort = String.format(java.util.Locale.US, "%.4f, %.4f",
                    place.latitude, place.longitude);
            locActual = locWhere + " · " + coordShort;
            locStatus = -1;
        } else {
            // Target holds location permission: inject by default and display the target-visible fix.
            com.walter.claudewilson.model.LocationFix preview =
                    new com.walter.claudewilson.model.LocationPlan(
                            place.latitude, place.longitude, profileSeed, effCoarse)
                            .fix(0L, 0L);
            String coordShort = effCoarse
                    ? String.format(java.util.Locale.US, "%.3f, %.3f",
                            preview.latitude, preview.longitude)
                    : String.format(java.util.Locale.US, "%.4f, %.4f",
                            preview.latitude, preview.longitude);
            locActual = locWhere + " · " + coordShort;
            locStatus = 1;
        }
        Runnable openMap = hasPersona ? (hookChannel ? this::openLocationPicker
                : () -> showRootHint(Lang.t("定位模拟需要系统级权限（root）",
                        "Location simulation needs root"))) : null;
        addField(gpsFields, Lang.t("定位坐标", "Coordinates", "位置座標"),
                hasPersona ? locWhere : dash,
                locActual,
                locStatus, hasPersona && hookChannel, false, openMap);
        addField(gpsFields, Lang.t("精度与海拔", "Accuracy & alt", "精度・高度"),
                hasPersona && locationOn ? effAccTag + effAltTag : dash,
                !hasPersona ? dash
                        : !locationOn ? Lang.t("已关闭", "Off", "オフ")
                        : effAccTag + effAltTag,
                !hasPersona || !locationOn || !targetHasLocPerm ? -1 : 1,
                hasPersona && hookChannel, false, openMap);

        String hintExpected = hasPersona ? r.iso.toUpperCase(java.util.Locale.ROOT) : dash;
        if (modelItem("target.data.country_hint") != null) {
            String storedHint = modelValue("target.data.country_hint");
            addField(gpsFields, Lang.t("应用已存国家", "stored country", "保存国"),
                    hintExpected, storedHint,
                    hintStatus(hasPersona, "target.data.country_hint", hintExpected, storedHint),
                    false, false, null);
        }
        if (modelItem("target.payload.cc_hint") != null) {
            String nextHint = modelValue("target.payload.cc_hint");
            String nextHintShown = nextHint != null && nextHint.matches("[A-Za-z]{2}")
                    ? nextHint.toUpperCase(java.util.Locale.ROOT) : nextHint;
            addField(gpsFields, Lang.t("下次启动国家", "next launch country", "次回起動国"),
                    hintExpected, nextHintShown,
                    hintStatus(hasPersona, "target.payload.cc_hint", hintExpected, nextHint),
                    false, false, null);
        }

        // ---- GROUP 4: WIRELESS & SENSORS (Wi-Fi / BLE / Sensors / Cell Shield) ----
        final String curIso = r != null ? r.iso : "";
        final com.walter.claudewilson.model.GeoTable.Point curPlace = place;
        final String wifiDisplay;
        final int wifiSt;
        if (!hasPersona || !hookChannel) {
            wifiDisplay = dash;
            wifiSt = -1;
        } else if (!wifiOn) {
            wifiDisplay = Lang.t("关闭 · 透传", "Off", "オフ");
            wifiSt = -1;
        } else if (!activeWifiList.isEmpty()) {
            wifiDisplay = activeWifiList.get(0).ssid + " · " + activeWifiList.size() + " AP";
            wifiSt = 1;
        } else {
            wifiDisplay = "0 AP";
            wifiSt = -1;
        }
        Runnable openWifi = !hasPersona ? null : () -> runRootGated(
                Lang.t("Wi-Fi 邻域注入需要 root 权限", "Wi-Fi neighbourhood hook needs root"),
                () -> openWifiSheet(user, curIso, curPlace, profileSeed));
        addField(radioFields, Lang.t("Wi-Fi 邻域", "Wi-Fi scan", "Wi-Fi 環境"),
                wifiDisplay, wifiDisplay, wifiSt, hasPersona && hookChannel, false, openWifi);

        final String bleDisplay;
        final int bleSt;
        if (!hasPersona || !hookChannel) {
            bleDisplay = dash;
            bleSt = -1;
        } else if (!bleOn) {
            bleDisplay = Lang.t("关闭 · 透传", "Off", "オフ");
            bleSt = -1;
        } else if (bleEmpty) {
            bleDisplay = Lang.t("空蓝牙洁净", "Empty clean", "空モード");
            bleSt = 1;
        } else if (!activeBleList.isEmpty()) {
            bleDisplay = activeBleList.get(0).name + " · " + activeBleList.size();
            bleSt = 1;
        } else {
            bleDisplay = "0";
            bleSt = -1;
        }
        Runnable openBle = !hasPersona ? null : () -> runRootGated(
                Lang.t("蓝牙 BLE 注入需要 root 权限", "BLE neighbourhood hook needs root"),
                () -> openBleSheet(user, curIso, profileSeed));
        addField(radioFields, Lang.t("蓝牙 BLE", "Bluetooth BLE", "Bluetooth"),
                bleDisplay, bleDisplay, bleSt, hasPersona && hookChannel, false, openBle);

        final String sensDisplay;
        final int sensSt;
        if (!hasPersona || !hookChannel) {
            sensDisplay = dash;
            sensSt = -1;
        } else if (!sensorsOn) {
            sensDisplay = Lang.t("关闭 · 透传", "Off", "オフ");
            sensSt = -1;
        } else {
            String pTag = sensorPresetLabel(sensorPreset);
            String baro = activeSensorProfile != null
                    ? String.format(java.util.Locale.US, " · %.0fhPa", activeSensorProfile.pressure)
                    : "";
            sensDisplay = pTag + baro;
            sensSt = 1;
        }
        Runnable openSensor = !hasPersona ? null : () -> runRootGated(
                Lang.t("传感器基线注入需要 root 权限", "Sensor hook needs root"),
                () -> openSensorSheet(user, curPlace, profileSeed, localHour, locAlt));
        addField(radioFields, Lang.t("环境传感器", "Sensors", "センサー"),
                sensDisplay, sensDisplay, sensSt, hasPersona && hookChannel, false, openSensor);

        addField(radioFields, Lang.t("基站扫描", "Cell info", "基地局"),
                hasPersona && hookChannel ? Lang.t("已置空防泄漏", "Blanked", "遮断済") : dash,
                hasPersona && hookChannel ? Lang.t("已置空防泄漏", "Blanked", "遮断済") : dash,
                hasPersona && hookChannel ? 1 : -1, false, false, null);

        // ---- GROUP 3: DEVICE & IPV6 ----
        boolean devHookOnly = devTarget && persona.deviceHookOnly(user);
        String modelActual = devHookOnly
                ? dv[3] + " · " + Lang.t("Hook", "Hook", "Hook")
                : liveModel;
        int modelStatus = !hasPersona ? -1
                : devHookOnly ? (targetLive ? 1 : -3)
                : comparison(devTarget ? dv[3] : liveModel, liveModel);
        Runnable openDev = !hasPersona ? null : () -> runRootGated(
                Lang.t("机型与区域标记覆写需要系统级权限（root）", "Device/CSC override needs root"),
                this::openDeviceCscSheet);
        addField(devFields, Lang.t("机型", "model", "機種"),
                devTarget ? dv[3] : liveModel, modelActual, modelStatus,
                hasPersona, true, openDev);

        String liveFp = val(DeviceSignals.getprop("ro.build.fingerprint"));
        String fpExpected = devTarget ? shortFp(dv[8]) : shortFp(liveFp);
        String fpActual = devHookOnly ? fpExpected : shortFp(liveFp);
        int fpStatus = !hasPersona ? -1
                : devHookOnly ? (targetLive ? 1 : -3)
                : comparison(fpExpected, shortFp(liveFp));
        addField(devFields, Lang.t("指纹", "fingerprint", "指紋"),
                fpExpected, fpActual, fpStatus, false, false, null);

        String[] hw = Regions.hwTag();
        String cscTarget = persona.csc(user);
        String cscExpected = !hasPersona ? dash : cscTarget != null ? cscTarget : hw[0];
        int cscStatus = !hasPersona ? -1 : tagKey(cscExpected).equals(tagKey(hw[0])) ? 1 : 0;
        addField(devFields, Lang.t("区域标记/CSC", "region tag/CSC", "地域タグ/CSC"),
                cscExpected, hw[0], cscStatus, hasPersona, true, openDev);

        String isoUpper = iso.toUpperCase(java.util.Locale.ROOT);
        String v6OffText = Lang.t("IPv6 已关闭", "IPv6 Off", "IPv6 無効");
        String v6Expected = !ipv6SpoofOn ? v6OffText : (hasPersona ? v6PayloadPfx : dash);
        String v6MobExpected = (hasPersona && ipv6SpoofOn) ? v6MobilePayloadPfx : "";
        String v6Actual = !ipv6SpoofOn ? v6OffText : dash;
        int v6Status = !ipv6SpoofOn ? 1 : -1;
        com.walter.claudewilson.model.CheckItem netItem = modelItem("target.network_addresses");
        if (ipv6SpoofOn && hasPersona && hookChannel && targetLive && netItem != null
                && netItem.isRead() && !netItem.isStale()) {
            String ips = netItem.value();
            v6Actual = extractGlobalV6(ips);
            if (!v6Actual.equals(dash)) {
                boolean matchesPool = sharesPrefix(v6Actual, v6Expected)
                        || sharesPrefix(v6Actual, v6MobExpected);
                v6Status = matchesPool ? 1 : 0;
                if (matchesPool) {
                    v6Actual = v6Actual + " " + isoUpper;
                }
            }
        } else if (ipv6SpoofOn && hasPersona && hookChannel && !v6Expected.isEmpty()
                && !v6Expected.equals(dash)) {
            v6Actual = v6Expected + " · " + Lang.t("下次启动", "next launch", "次回起動");
            v6Status = -3;
        }
        final String v6SheetIso = isoUpper;
        final String v6SheetPfx = v6PayloadPfx;
        Runnable openIpv6Toggle = () -> PersonaEditSheets.showIpv6ModeSheet(
                this, ipv6SpoofOn, v6SheetIso, v6SheetPfx, on -> {
                    boolean enable = Boolean.TRUE.equals(on);
                    if (getBaseContext() instanceof Dashboard) {
                        ((Dashboard) getBaseContext()).setIpv6SpoofEnabled(enable);
                    } else {
                        persona.putIpv6SpoofEnabled(enable);
                        new Thread(() -> rootActions.syncIpv6Enforcement(enable),
                                "ipv6-toggle").start();
                        if (enable) publishRotated();
                        else publishCurrent();
                    }
                });
        addField(devFields, Lang.t("IPv6 伪装", "IPv6 spoof", "IPv6 偽装"),
                !ipv6SpoofOn ? v6OffText : (v6Expected.isEmpty() || v6Expected.equals(dash)
                        ? dash : v6Expected + " " + isoUpper),
                v6Actual, v6Status, true, true, openIpv6Toggle);

        String aidShown = aidOverride != null ? aidOverride : liveAidText;
        com.walter.claudewilson.model.CheckItem aidItem = modelItem("target.aid");
        boolean aidFresh = targetLive && fresh(aidItem);
        String aidActual = !aidTarget ? liveAidText
                : aidFresh ? aidItem.value()
                : injectedNow ? aidOverride : liveAidText;
        int aidStatus = !aidTarget ? comparison(liveAidText, liveAidText)
                : aidFresh ? comparison(aidOverride, aidActual)
                : injectedNow ? -3 : comparison(aidOverride, liveAidText);
        addField(devFields, "Android ID",
                aidTarget ? aidShown : liveAidText,
                aidActual, aidStatus, hasPersona, true,
                hasPersona ? () -> runRootGated(
                        Lang.t("改写/注入 AID 需要系统级权限（root）", "AID rewrite needs root"),
                        this::openAidSheet) : null);

        final String appVisMode = persona.appVisMode(user);
        final java.util.Set<String> appVisPkgs = persona.appVisPackages(user);
        final java.util.Set<String> appVisGroups = persona.appVisPresetGroups(user);
        final java.util.Set<String> appVisExtra = persona.hasExplicitAppVisExtra(user)
                ? persona.appVisExtraHidden(user)
                : HmaPresetScanner.resolveSystemExtraHidden(
                        getApplicationContext(), target.packageName(), appVisGroups);
        final boolean appVisExclSys = persona.appVisExcludeSystem(user);
        final boolean appVisStrict = persona.appVisStrict(user);
        final String appVisShown;
        final int appVisSt;
        String presetTag = appVisGroups.isEmpty() ? ""
                : Lang.t(" · 预设 ", " · ") + appVisGroups.size();
        if (!hasPersona || !hookChannel) {
            appVisShown = dash;
            appVisSt = -1;
        } else if (com.walter.claudewilson.model.AppVisibilityPolicy.MODE_ALLOWLIST.equals(appVisMode)) {
            appVisShown = (appVisExclSys
                    ? Lang.t("白名单 · ", "Allow · ")
                    : Lang.t("严苛白 · ", "Strict · "))
                    + appVisPkgs.size() + presetTag;
            appVisSt = 1;
        } else if (com.walter.claudewilson.model.AppVisibilityPolicy.MODE_DENYLIST.equals(appVisMode)) {
            appVisShown = Lang.t("黑名单 · 隐 ", "Deny · ") + appVisPkgs.size() + presetTag;
            appVisSt = 1;
        } else {
            appVisShown = appVisExtra.isEmpty()
                    ? Lang.t("仅隐 Root · HMA", "Root only · HMA", "Root 隠蔽 · HMA")
                    : Lang.t("默认 · 预设隐 ", "Default · +") + appVisExtra.size();
            appVisSt = 1;
        }
        final TargetSpec curTarget = target;
        addField(devFields, Lang.t("应用列表", "App list", "アプリ一覧"),
                appVisShown, appVisShown, appVisSt, hasPersona && hookChannel, false,
                hasPersona ? () -> runRootGated(
                        Lang.t("应用列表可见性控制需要 root 权限", "App visibility control needs root"),
                        () -> AppVisibilitySheet.show(
                                this,
                                curTarget.displayName(),
                                curTarget.packageName(),
                                persona.appVisMode(user),
                                persona.appVisPackages(user),
                                persona.hasExplicitAppVisExtra(user)
                                        ? persona.appVisExtraHidden(user)
                                        : HmaPresetScanner.resolveSystemExtraHidden(
                                                getApplicationContext(), curTarget.packageName(),
                                                persona.appVisPresetGroups(user)),
                                persona.appVisExcludeSystem(user),
                                persona.appVisStrict(user),
                                persona.appVisHideDev(user),
                                persona.appVisHideA11y(user),
                                persona.appVisHideIme(user),
                                persona.appVisPresetGroups(user),
                                (newMode, newPkgs, newExtra, newExclSys, newStrict,
                                 newHideDev, newHideA11y, newHideIme, newGroups) -> {
                                    persona.putAppVisMode(user, newMode);
                                    persona.putAppVisPackages(user, newPkgs);
                                    persona.putAppVisExtraHidden(user, newExtra);
                                    persona.putAppVisExcludeSystem(user, newExclSys);
                                    persona.putAppVisStrict(user, newStrict);
                                    persona.putAppVisHideDev(user, newHideDev);
                                    persona.putAppVisHideA11y(user, newHideA11y);
                                    persona.putAppVisHideIme(user, newHideIme);
                                    persona.putAppVisPresetGroups(user, newGroups);
                                    publishCurrent();
                                })) : null);

        // ---- GROUP 4: STORE & PAYMENT ----
        String pc = playCountry.get();
        String playValue;
        boolean playIsIso = false;
        if (pc == null) {
            playValue = Lang.t("查询中…", "querying…", "照会中…");
        } else if (pc.startsWith("err")) {
            playValue = playErrorLabel(pc);
        } else {
            playValue = pc.toUpperCase(java.util.Locale.ROOT);
            playIsIso = playValue.matches("[A-Z]{2}");
        }
        boolean storeDiff = hasPersona && playIsIso && !playValue.equalsIgnoreCase(r.iso);
        String storeExp = hasPersona ? r.iso.toUpperCase(java.util.Locale.ROOT) : dash;
        String storeDisplay = storeDiff
                ? playValue + " · " + Lang.t("期望 ", "exp ") + storeExp
                : playValue;
        addField(storeFields, Lang.t("Play 商店", "Play store", "Play ストア"),
                storeExp, storeDisplay, storeDiff ? -2 : (playIsIso ? 1 : -1),
                false, false, null);
        addField(storeFields, "Google Pay",
                storeExp, storeDisplay, storeDiff ? -2 : (playIsIso ? 1 : -1),
                false, false, null);
        // Merge Google account identity right into Store & Payment drawer (replaces orphan AccountLayer card).
        boolean accReadable = accountsReadableSupplier != null && accountsReadableSupplier.getAsBoolean();
        java.util.List<com.walter.claudewilson.model.Account> accs =
                accountsSupplier != null ? accountsSupplier.get() : null;
        if (!accReadable) {
            addField(storeFields, Lang.t("Google 账号", "Google account", "Google アカウント"),
                    dash, Lang.t("— · 读不到", "— · unreadable"), -1, false, false, null);
        } else {
            boolean foundGoogle = false;
            if (accs != null) {
                for (com.walter.claudewilson.model.Account account : accs) {
                    if (account == null || account.name().isEmpty()
                            || !"com.google".equals(account.type())) {
                        continue;
                    }
                    addField(storeFields, Lang.t("Google 账号", "Google account", "Google アカウント"),
                            dash, Privacy.mask(account.name()), -1, false, false, null);
                    foundGoogle = true;
                }
            }
            if (!foundGoogle) {
                addField(storeFields, Lang.t("Google 账号", "Google account", "Google アカウント"),
                        dash, Lang.t("— · 无记录", "— · no record"), -1, false, false, null);
            }
        }

        // =====================================================================
        // LAYER 1: SPACE + REGION COMMAND BAR
        // =====================================================================
        final boolean busy = actions != null && actions.isApplyBusy();
        final boolean reverting = busy && actions.isReverting();
        final String busyText = reverting
                ? Lang.t("复位中…", "Reverting…", "復元中…")
                : Lang.t("应用中…", "Applying…", "適用中…");
        LinearLayout cmdRow = new LinearLayout(this);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);
        cmdRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout spacePill = new LinearLayout(this);
        spacePill.setOrientation(LinearLayout.HORIZONTAL);
        spacePill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        spacePill.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        spacePill.setBackground(wbBox(CARD2, STROKE, 12));
        if (busy) spacePill.setAlpha(0.6f);
        TextView spNameTv = txt(SpaceNames.name(this, user), 11.5f, FG);
        spNameTv.setTypeface(Typeface.DEFAULT_BOLD);
        spNameTv.setSingleLine(true);
        spacePill.addView(spNameTv);
        if (!busy) {
            bindWbPress(spacePill);
            spacePill.setOnClickListener(v -> {
                if (actions != null) actions.openSpacePicker();
            });
        }
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-2, -2);
        splp.rightMargin = dp(this, 7);
        spacePill.setLayoutParams(splp);
        cmdRow.addView(spacePill);

        LinearLayout regionHero = new LinearLayout(this);
        regionHero.setOrientation(LinearLayout.HORIZONTAL);
        regionHero.setGravity(android.view.Gravity.CENTER_VERTICAL);
        regionHero.setPadding(dp(this, 11), dp(this, 8), dp(this, 11), dp(this, 8));
        int heroFill = busy ? 0x17E5A93B
                : !hasPersona ? CARD2
                : mismatches > 0 ? 0x17E5A93B : 0x142EC4B6;
        int heroStroke = busy ? 0x73E5A93B
                : !hasPersona ? 0x662EC4B6
                : mismatches > 0 ? 0x73E5A93B : 0x732EC4B6;
        regionHero.setBackground(wbBox(heroFill, heroStroke, 12));
        regionHero.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView flagTv = txt(hasPersona ? Regions.flagOf(r.iso) : "✕", 14, hasPersona ? FG : DIM);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.rightMargin = dp(this, 7);
        flagTv.setLayoutParams(flp);
        regionHero.addView(flagTv);

        TextView rNameTv = txt(busy ? busyText
                : hasPersona ? r.dispName() : Lang.t("原机态", "Native", "端末のまま"),
                12, busy ? AMBER : FG);
        rNameTv.setTypeface(Typeface.DEFAULT_BOLD);
        rNameTv.setSingleLine(true);
        rNameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rNameTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        regionHero.addView(rNameTv);

        TextView rIsoTv = txt(busy ? "…" : (hasPersona ? r.iso.toUpperCase(java.util.Locale.ROOT) : "OFF"),
                10, busy ? AMBER : DIM);
        rIsoTv.setTypeface(Typeface.MONOSPACE);
        regionHero.addView(rIsoTv);
        if (!busy) {
            bindWbPress(regionHero);
            regionHero.setOnClickListener(v -> {
                if (actions != null) actions.openRegionPicker();
            });
        }
        cmdRow.addView(regionHero);
        card.addView(cmdRow);

        // Conditional Follow-Egress Quick Bar (only when probed egress is valid, non-restricted, and differs)
        final String egressCc = NetworkPanel.lastEgressCc();
        if (!busy && egressCc != null && egressCc.length() == 2
                && (!hasPersona || !egressCc.equalsIgnoreCase(r.iso))
                && !com.walter.claudewilson.model.SimPolicy.isRestrictedIso(
                        com.walter.claudewilson.platform.TablesLoader.load(getApplicationContext()),
                        egressCc, target.id())) {
            Regions.R egressReg = findRegionByIso(egressCc);
            if (egressReg != null) {
                LinearLayout follow = new LinearLayout(this);
                follow.setOrientation(LinearLayout.HORIZONTAL);
                follow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                follow.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
                follow.setBackground(wbBox(0x122EC4B6, 0x552EC4B6, 10));
                LinearLayout.LayoutParams fllp = new LinearLayout.LayoutParams(-1, -2);
                fllp.topMargin = dp(this, 8);
                follow.setLayoutParams(fllp);

                TextView fl = txt(Lang.t("出口 ", "Exit ") + Regions.flagOf(egressReg.iso)
                        + " " + egressReg.dispName() + " (" + egressCc.toUpperCase(java.util.Locale.ROOT)
                        + ") " + Lang.t("与当前不一致", "differs"), 9.5f, FG);
                fl.setTypeface(Typeface.MONOSPACE);
                fl.setSingleLine(true);
                fl.setEllipsize(android.text.TextUtils.TruncateAt.END);
                fl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                follow.addView(fl);

                TextView fc = txt(Lang.t("一键对齐 →", "Align →"), 9.5f, CYAN);
                fc.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                LinearLayout.LayoutParams fclp = new LinearLayout.LayoutParams(-2, -2);
                fclp.leftMargin = dp(this, 8);
                fc.setLayoutParams(fclp);
                follow.addView(fc);

                bindWbPress(follow);
                follow.setOnClickListener(v -> {
                    if (actions != null) actions.selectRegionIso(egressReg.iso);
                });
                card.addView(follow);
            }
        }

        // =====================================================================
        // LAYER 2: 2x3 CORE IDENTITY MATRIX
        // =====================================================================
        boolean simDrift = !busy && hasDrift(simFields);
        boolean locDrift = !busy && hasDrift(locFields);
        boolean gpsDrift = !busy && hasDrift(gpsFields);
        boolean devDrift = !busy && hasDrift(devFields);

        String simVal;
        int simCol;
        if (busy) {
            simVal = hasPersona ? (effSimCarShown + " · " + effSimOpShown) : Lang.t("原机", "Native", "端末");
            simCol = DIM;
        } else if (!hasPersona) {
            boolean bannedNow = simModeStatus == 0;
            simVal = bannedNow ? simModeActual
                    : (!liveCarrier.isEmpty() ? liveCarrier + (liveIso.isEmpty() ? "" : " · " + liveIso)
                    : !liveIso.isEmpty() ? liveIso : Lang.t("无卡", "No SIM"));
            simCol = bannedNow ? RED : FG;
        } else if (simDrift) {
            simVal = Lang.t("偏差 · ", "Drift · ") + effSimCarShown;
            simCol = AMBER;
        } else if (preserveRealSim) {
            simVal = Lang.t("真卡 · ", "Real · ") + effSimCarShown;
            simCol = GREEN;
        } else {
            simVal = effSimCarShown + " · " + effSimOpShown;
            simCol = GREEN;
        }

        String langTzVal;
        int langTzCol;
        String tzShort = tz != null && tz.contains("/") ? tz.substring(tz.lastIndexOf('/') + 1).replace('_', ' ') : tz;
        if (busy) {
            langTzVal = hasPersona ? (loc + " · " + tzShort) : Lang.t("基线时区", "Baseline TZ", "基準 TZ");
            langTzCol = DIM;
        } else if (!hasPersona) {
            langTzVal = liveTz.isEmpty() ? dash : liveTz;
            langTzCol = FG;
        } else if (locDrift) {
            langTzVal = Lang.t("偏差 · ", "Drift · ") + tzShort;
            langTzCol = AMBER;
        } else {
            langTzVal = loc + " · " + tzShort;
            langTzCol = GREEN;
        }

        String gpsVal;
        int gpsCol;
        if (busy) {
            gpsVal = hasPersona ? (locWhere + " · " + effAccTag) : dash;
            gpsCol = DIM;
        } else if (!hasPersona) {
            gpsVal = Lang.t("未接管", "Native", "未介入");
            gpsCol = DIM;
        } else if (!locationOn) {
            gpsVal = targetHasLocPerm
                    ? Lang.t("关闭 · 透传", "Off · real", "オフ · 透過")
                    : Lang.t("已关闭", "Off", "オフ");
            gpsCol = targetHasLocPerm ? AMBER : DIM;
        } else if (gpsDrift || locStatus == -2) {
            gpsVal = locWhere + " · " + effAccTag;
            gpsCol = AMBER;
        } else if (!targetHasLocPerm) {
            gpsVal = locWhere + " · " + effAccTag;
            gpsCol = DIM;
        } else {
            gpsVal = locWhere + " · " + effAccTag;
            gpsCol = GREEN;
        }

        String radioVal;
        int radioCol;
        if (busy) {
            radioVal = hasPersona ? ("Wi-Fi " + activeWifiList.size() + " · BLE " + activeBleList.size()) : dash;
            radioCol = DIM;
        } else if (!hasPersona || !hookChannel) {
            radioVal = Lang.t("原机直通", "Native", "直通");
            radioCol = DIM;
        } else {
            String wPart = wifiOn ? ("Wi-Fi " + activeWifiList.size()) : "Wi-Fi " + Lang.t("关", "off");
            String bPart = !bleOn ? ("BLE " + Lang.t("关", "off"))
                    : bleEmpty ? ("BLE " + Lang.t("空", "0"))
                    : ("BLE " + activeBleList.size());
            radioVal = wPart + " · " + bPart;
            radioCol = (wifiOn || bleOn || sensorsOn) ? GREEN : DIM;
        }

        String v6Badge = !ipv6SpoofOn
                ? " · " + Lang.t("IPv6 关", "IPv6 Off", "IPv6 切")
                : (!v6Expected.isEmpty() && !v6Expected.equals(dash) ? " · v6" : "");
        String devVal;
        int devCol;
        if (busy) {
            devVal = hasPersona
                    ? ((devTarget ? dv[3] : liveModel) + v6Badge)
                    : (liveModel + (!ipv6SpoofOn ? v6Badge : ""));
            devCol = DIM;
        } else if (!hasPersona) {
            devVal = liveModel + (!ipv6SpoofOn ? v6Badge : "");
            devCol = FG;
        } else if (devDrift) {
            devVal = Lang.t("偏差 · ", "Drift · ") + (devTarget ? dv[3] : liveModel);
            devCol = AMBER;
        } else {
            devVal = (devTarget ? dv[3] : liveModel) + v6Badge;
            devCol = GREEN;
        }

        String storeVal = playIsIso ? ("Play " + playValue + " · Pay " + playValue) : playValue;
        int storeCol = !playIsIso ? DIM : storeDiff ? AMBER : (hasPersona ? GREEN : FG);

        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rAlp = new LinearLayout.LayoutParams(-1, -2);
        rAlp.topMargin = dp(this, 10);
        rAlp.bottomMargin = dp(this, 6);
        rowA.setLayoutParams(rAlp);
        rowA.addView(wbTile("sim", Lang.t("SIM 与网络", "SIM & Net", "SIM・回線"),
                simVal, simCol, 0, dp(this, 6)));
        rowA.addView(wbTile("loc", Lang.t("语言与时区", "Locale & TZ", "言語・TZ"),
                langTzVal, langTzCol, 0, 0));
        card.addView(rowA);

        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rBlp = new LinearLayout.LayoutParams(-1, -2);
        rBlp.bottomMargin = dp(this, 6);
        rowB.setLayoutParams(rBlp);
        rowB.addView(wbTile("gps", Lang.t("GPS 定位", "GPS & Map", "GPS 位置"),
                gpsVal, gpsCol, 0, dp(this, 6)));
        rowB.addView(wbTile("radio", Lang.t("无线与传感", "Wireless & Sensor", "無線・センサ"),
                radioVal, radioCol, 0, 0));
        card.addView(rowB);

        LinearLayout rowC = new LinearLayout(this);
        rowC.setOrientation(LinearLayout.HORIZONTAL);
        rowC.addView(wbTile("dev", Lang.t("设备与 IPv6", "Device & v6", "端末・IPv6"),
                devVal, devCol, 0, dp(this, 6)));
        rowC.addView(wbTile("store", Lang.t("商店与账号", "Store & Account", "ストア・口座"),
                storeVal, storeCol, 0, 0));
        card.addView(rowC);

        // Inline Expandable Drawer
        if (expandedDrawer != null) {
            String dTitle;
            java.util.List<DrawerField> list;
            if ("sim".equals(expandedDrawer)) {
                dTitle = Lang.t("SIM 与网络", "SIM & Net", "SIM・回線");
                list = simFields;
            } else if ("loc".equals(expandedDrawer)) {
                dTitle = Lang.t("语言与时区", "Locale & TZ", "言語・TZ");
                list = locFields;
            } else if ("gps".equals(expandedDrawer)) {
                dTitle = Lang.t("GPS 定位", "GPS & Map", "GPS 位置");
                list = gpsFields;
            } else if ("radio".equals(expandedDrawer)) {
                dTitle = Lang.t("无线与传感", "Wireless & Sensor", "無線・センサ");
                list = radioFields;
            } else if ("dev".equals(expandedDrawer)) {
                dTitle = Lang.t("设备与 IPv6", "Device & v6", "端末・IPv6");
                list = devFields;
            } else {
                dTitle = Lang.t("商店与账号", "Store & Account", "ストア・口座");
                list = storeFields;
            }
            card.addView(buildWorkbenchDrawer(dTitle, list, hasPersona));
        }

        card.addView(rootHint);

        // =====================================================================
        // LAYER 3: CLEAN ACTION FOOTER (WITH SIM MANAGER SHORTCUT)
        // =====================================================================
        View div = new View(this);
        div.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(this, 1));
        dlp.topMargin = dp(this, 10);
        dlp.bottomMargin = dp(this, 9);
        div.setLayoutParams(dlp);
        card.addView(div);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.CENTER_VERTICAL);

        String fText = "";
        int fColor = MUTE;
        if (busy) {
            fText = busyText;
            fColor = AMBER;
        } else if (hasPersona && mismatches > 0) {
            fText = mismatches + Lang.t(" 项偏差", " drift", " ズレ");
            fColor = AMBER;
        }
        TextView ftv = txt(fText, 9.5f, fColor);
        ftv.setTypeface(Typeface.MONOSPACE);
        ftv.setSingleLine(true);
        ftv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ftv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        footer.addView(ftv);

        if (!busy) {
            TextView simMgrBtn = wbPill(Lang.t("SIM 管理器", "SIM manager", "SIM 管理"),
                    9.5f, FG, CARD2, STROKE);
            LinearLayout.LayoutParams smlp = new LinearLayout.LayoutParams(-2, -2);
            smlp.leftMargin = dp(this, 6);
            simMgrBtn.setLayoutParams(smlp);
            bindWbPress(simMgrBtn);
            simMgrBtn.setOnClickListener(v -> new Thread(() -> RootActions.openSimManager(this)).start());
            footer.addView(simMgrBtn);

            if (hasPersona) {
                if (mismatches > 0) {
                    TextView alignBtn = wbPill(Lang.t("对齐", "Align", "同期"),
                            9.5f, AMBER, 0x22E5A93B, AMBER);
                    alignBtn.setTypeface(Typeface.DEFAULT_BOLD);
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
                    alp.leftMargin = dp(this, 6);
                    alignBtn.setLayoutParams(alp);
                    bindWbPress(alignBtn);
                    alignBtn.setOnClickListener(v -> {
                        if (actions != null) actions.reapplyCurrent();
                    });
                    footer.addView(alignBtn);
                }
                TextView offBtn = wbPill(Lang.t("关闭", "Off", "解除"),
                        9.5f, RED, 0x24E55353, 0x8CE55353);
                offBtn.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
                olp.leftMargin = dp(this, 6);
                offBtn.setLayoutParams(olp);
                bindWbPress(offBtn);
                offBtn.setOnClickListener(v -> {
                    if (actions != null) actions.selectRegionIso(null);
                });
                footer.addView(offBtn);
            }
        }
        card.addView(footer);
    }

    private static final class DrawerField {
        final String label;
        final String expected;
        final String actual;
        final int status;
        final boolean editable;
        final boolean needsRoot;
        final Runnable onClick;

        DrawerField(String label, String expected, String actual, int status,
                    boolean editable, boolean needsRoot, Runnable onClick) {
            this.label = label;
            this.expected = expected;
            this.actual = actual;
            this.status = status;
            this.editable = editable;
            this.needsRoot = needsRoot;
            this.onClick = onClick;
        }
    }

    private void addField(java.util.List<DrawerField> target, String label,
                          String expected, String actual, int status,
                          boolean editable, boolean needsRoot, Runnable onClick) {
        if (Privacy.hidden() && (Privacy.sensitivePersona(label) || "Android ID".equals(label))) {
            expected = Privacy.STARS;
            actual = Privacy.STARS;
            status = -1;
        }
        if (regionIdx >= 0 && status == 0) {
            mismatches++;
        }
        target.add(new DrawerField(label, expected, actual, status, editable, needsRoot, onClick));
    }

    private static boolean hasDrift(java.util.List<DrawerField> fields) {
        for (DrawerField f : fields) {
            if (f.status == 0) return true;
        }
        return false;
    }

    private static Regions.R findRegionByIso(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        for (Regions.R item : Regions.ALL) {
            if (item.iso.equalsIgnoreCase(iso)) return item;
        }
        return null;
    }

    private void runRootGated(String message, Runnable action) {
        if (RootShell.rootReady()) {
            if (action != null) action.run();
        } else {
            showRootHint(message);
        }
    }

    private void runAnyChannelGated(String message, Runnable action) {
        if (RootShell.shellReady()) {
            if (action != null) action.run();
        } else {
            showRootHint(message);
        }
    }

    private View wbTile(final String id, String label, String value,
                        int valColor, int leftM, int rightM) {
        boolean selected = id.equals(expandedDrawer);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        tile.setBackground(wbBox(selected ? 0xFF19282C : CARD2, selected ? CYAN : STROKE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = leftM;
        lp.rightMargin = rightM;
        tile.setLayoutParams(lp);

        TextView lbl = txt(label, 9.5f, DIM);
        lbl.setSingleLine(true);
        lbl.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(lbl);

        TextView v = txt(value, 11, valColor);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setPadding(0, dp(this, 4), 0, 0);
        tile.addView(v);

        bindWbPress(tile);
        tile.setOnClickListener(view -> {
            expandedDrawer = selected ? null : id;
            repaintSelf();
        });
        return tile;
    }

    private void repaintSelf() {
        if (lastCard != null) {
            renderInto(lastCard);
            if (getBaseContext() instanceof Dashboard) {
                ((Dashboard) getBaseContext()).refreshPreflight();
            }
        } else {
            renderScreen.run();
        }
    }

    private View buildWorkbenchDrawer(String title, java.util.List<DrawerField> fields,
                                      boolean hasPersona) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        box.setBackground(wbBox(CARD2, 0xFF2B454B, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 8);
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView ht = txt(title, 9.5f, CYAN);
        ht.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        ht.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(ht);

        TextView close = txt("✕", 10, MUTE);
        close.setPadding(dp(this, 8), dp(this, 1), dp(this, 2), dp(this, 1));
        close.setOnClickListener(v -> {
            expandedDrawer = null;
            repaintSelf();
        });
        head.addView(close);
        box.addView(head);

        for (int i = 0; i < fields.size(); i++) {
            final DrawerField f = fields.get(i);
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(0x661E3136);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 1);
                slp.topMargin = dp(this, 4);
                slp.bottomMargin = dp(this, 4);
                sep.setLayoutParams(slp);
                box.addView(sep);
            } else {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(this, 6)));
                box.addView(gap);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(this, 2), 0, dp(this, 2));

            TextView k = txt(f.label, 9.5f, DIM);
            k.setTypeface(Typeface.MONOSPACE);
            k.setSingleLine(true);
            row.addView(k);

            String shownVal;
            int valColor;
            if (hasPersona && f.status == 0 && !f.expected.isEmpty()
                    && !"—".equals(f.expected) && !f.expected.equals(f.actual)) {
                shownVal = f.actual + " → " + f.expected;
                valColor = AMBER;
            } else {
                shownVal = f.actual;
                valColor = f.status == 0 ? RED
                        : f.status == 1 ? GREEN
                        : f.status == -2 ? AMBER
                        : f.status == -3 ? (f.editable ? CYAN : SAND)
                        : FG;
            }
            if (f.editable && f.onClick != null) {
                shownVal = shownVal + ("Android ID".equals(f.label) ? " ↻" : " ✎");
                if (valColor == FG) valColor = CYAN;
            }

            TextView v = txt(shownVal, 9.5f, valColor);
            v.setTypeface(Typeface.MONOSPACE);
            v.setGravity(android.view.Gravity.END);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1f);
            vlp.leftMargin = dp(this, 10);
            v.setLayoutParams(vlp);
            row.addView(v);

            if (f.onClick != null) {
                if (f.needsRoot && !RootShell.rootReady()) {
                    row.setAlpha(0.55f);
                }
                bindWbPress(row);
                row.setOnClickListener(view -> f.onClick.run());
            }
            box.addView(row);
        }
        return box;
    }

    private android.graphics.drawable.GradientDrawable wbBox(int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(this, radiusDp));
        bg.setStroke(dp(this, 1), stroke);
        return bg;
    }

    private TextView wbPill(String text, float sp, int fg, int fill, int stroke) {
        TextView t = txt(text, sp, fg);
        t.setSingleLine(true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(this, 10), dp(this, 5), dp(this, 10), dp(this, 5));
        t.setBackground(wbBox(fill, stroke, 14));
        return t;
    }

    private void bindWbPress(View view) {
        view.setClickable(true);
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void showRootHint(String message) {
        if (rootHint == null) return;
        rootHint.setVisibility(View.VISIBLE);
        rootHint.setText(message);
    }

    private void showActionResult(String output) {
        if (actionStatus == null) return;
        actionStatus.setText(output);
        actionStatus.setTextColor(RootShell.failed(output)
                || output.contains("no pid") ? RED : DIM);
    }

    /**
     * Publishes the current persona to the Vector module. The target reads it on its next process
     * start; the app never attaches to or launches the target.
     */
    private void publishPersona() {
        publishPersona(false, null);
    }

    private void publishPersona(final Runnable after) {
        publishPersona(false, after);
    }

    private void publishPersona(final boolean rotateIpv6, final Runnable after) {
        TargetSpec target = Targets.current();
        if (!target.hooksEnabled()) {
            showRootHint(Lang.t("此目标目前只读诊断，不发布注入配置",
                    "this target is diagnostic-only; no payload is published",
                    "この対象は読取専用のため設定を配信しません"));
            if (after != null) after.run();
            return;
        }
        if (!RootShell.shellReady()) {
            showRootHint(Lang.t("先授权 root 或 Shizuku；配置会在下次启动 "
                            + target.displayName() + " 时生效",
                    "grant root or Shizuku first; the config applies on the next "
                            + target.displayName() + " launch",
                    "先に root または Shizuku を許可してください。設定は次回の "
                            + target.displayName() + " 起動時に反映されます"));
            if (after != null) after.run();
            return;
        }
        com.walter.claudewilson.model.CheckItem spoofProbe = modelItem("target.ipv6_spoof");
        boolean hookAvailable = RootShell.rootReady() || targetProcessLive()
                || (spoofProbe != null && spoofProbe.isRead());
        if (!hookAvailable) {
            // Pure Shizuku has no Vector/Zygisk hook consumer; writing a tmp payload only leaves
            // unconsumed preview state and false-positive injection markers.
            final String u = currentUser.get();
            persona.clearInjected(u);
            new Thread(() -> rootActions.clearConfig(target), "config-clear-shell").start();
            renderScreen.run();
            if (after != null) {
                after.run();
            }
            return;
        }
        // Only allocate/rotate spoofed IPv6 prefixes when the persistent IPv6 switch is ON.
        final boolean ipv6SpoofOn = persona.ipv6SpoofEnabled();
        final String prevPrefix;
        final String prevMobPrefix;
        final String prevIso;
        final String prevCar;
        {
            String user0 = currentUser.get();
            prevPrefix = persona.ipv6Prefix(user0);
            prevMobPrefix = persona.ipv6MobilePrefix(user0);
            prevIso = persona.ipv6Iso(user0);
            prevCar = persona.ipv6Carrier(user0);
            Regions.R r0 = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
            if (ipv6SpoofOn && r0 != null) {
                String car0 = currentCarrier(r0);
                boolean isoChanged = prevIso == null || !r0.iso.equalsIgnoreCase(prevIso);
                boolean carChanged = prevCar == null || !car0.equalsIgnoreCase(prevCar);
                String[] dv0 = deviceValues(user0);
                long crossSeed0 = persona.effectiveDeviceSeed(user0);
                long seed0 = profileSeedFor(user0, dv0, crossSeed0, r0.iso);
                long pickSeed = rotateIpv6
                        ? PersonaSalt.mix64(seed0 ^ System.currentTimeMillis()) : seed0;
                String wifiPfx = prevPrefix;
                boolean changed = false;
                if (rotateIpv6 || isoChanged || wifiPfx == null || wifiPfx.isEmpty()) {
                    String egressAsn = NetworkPanel.lastEgressAsn();
                    String picked = Ipv6Prefixes.pickPrefix(this,
                            r0.iso.toLowerCase(java.util.Locale.ROOT), pickSeed, egressAsn);
                    wifiPfx = picked != null ? picked : "";
                    changed = true;
                }
                String mobPfx = prevMobPrefix;
                if (rotateIpv6 || isoChanged || carChanged
                        || mobPfx == null || mobPfx.isEmpty()) {
                    String pickedMob = Ipv6Prefixes.pickMobilePrefix(this,
                            r0.iso.toLowerCase(java.util.Locale.ROOT), car0, wifiPfx, pickSeed);
                    mobPfx = pickedMob != null ? pickedMob : wifiPfx;
                    changed = true;
                }
                if (changed || isoChanged || carChanged) {
                    persona.putIpv6State(user0, wifiPfx, mobPfx, r0.iso, car0);
                }
            }
        }
        repaintSelf();
        final String json = payload;
        if (json == null) {
            restoreIpv6State(currentUser.get(), prevPrefix, prevMobPrefix, prevIso, prevCar);
            showRootHint(Lang.t("请先选择地区生成人设",
                    "Select a region first to generate a persona",
                    "先に地域を選択して人設を生成してください"));
            if (after != null) after.run();
            return;
        }
        final String user = currentUser.get();
        final String sig = Integer.toHexString(json.hashCode());
        final String snapshotRegionIso = regionIdx >= 0 && regionIdx < Regions.ALL.size()
                ? Regions.ALL.get(regionIdx).iso : "";
        final String rawEgress = NetworkPanel.lastEgressCc();
        final String snapshotEgressIso = rawEgress == null ? "" : rawEgress;
        try {
            new org.json.JSONObject(json);
        } catch (Exception e) {
            restoreIpv6State(user, prevPrefix, prevMobPrefix, prevIso, prevCar);
            showRootHint(Lang.t("人设 JSON 无效，已拒绝写入: ",
                    "persona JSON is invalid; not publishing: ",
                    "人設 JSON が不正なため配信しません: ") + e.getMessage());
            if (after != null) after.run();
            return;
        }
        EDIT_TX.execute(() -> {
            try {
                LedgerIngestor.recordPersonaFromPayload(getApplicationContext(),
                        target.id(), user, json, snapshotRegionIso, snapshotEgressIso);
                String out;
                synchronized (injectLock) {
                    if (!ipv6SpoofOn) {
                        rootActions.syncIpv6Enforcement(false);
                    }
                    out = rootActions.publish(target, json);
                    if (!RootShell.failed(out)) {
                        persona.markInjected(user, sig);
                    } else {
                        restoreIpv6State(user, prevPrefix, prevMobPrefix, prevIso, prevCar);
                    }
                }
                final String result = out;
                ScreenUpdates.post(screen -> {
                    repaintSelf();
                    showActionResult(result);
                    if (after != null) {
                        after.run();
                    }
                });
            } catch (Exception e) {
                restoreIpv6State(user, prevPrefix, prevMobPrefix, prevIso, prevCar);
                ScreenUpdates.post(screen -> {
                    screen.personaPanel.showActionResult("io: " + e);
                    if (after != null) {
                        after.run();
                    }
                });
            }
        });
    }

    /** Publishes the payload without rotating an already-persisted IPv6 prefix. */
    void publishCurrent() {
        publishPersona(false, null);
    }

    /** Publishes the payload and rotates the IPv6 prefix for a new region or egress ASN. */
    void publishRotated() {
        publishPersona(true, null);
    }

    /** Publishes the payload with IPv6 rotation, then invokes {@code after} on the UI thread. */
    void publishRotated(Runnable after) {
        publishPersona(true, after);
    }

    /**
     * Sets the target-visible country hint without changing the selected region. The payload is
     * rebuilt and published; {@code after} runs on the UI thread once the config write returned.
     */
    void setCountryHint(String iso, Runnable after) {
        if (iso == null || !iso.matches("[A-Za-z]{2}")) {
            return;
        }
        String user = currentUser.get();
        if (user == null || !com.walter.claudewilson.model.ShellQuote.numeric(user)) {
            return;
        }
        persona.putCountryHint(user, iso.toUpperCase(java.util.Locale.ROOT));
        renderScreen.run();
        publishPersona(after);
    }

    /** Returns to the region ISO; the next payload publication drops the custom hint. */
    void clearCountryHint(Runnable after) {
        String user = currentUser.get();
        if (user == null || !com.walter.claudewilson.model.ShellQuote.numeric(user)) {
            return;
        }
        persona.clearCountryHint(user);
        renderScreen.run();
        publishPersona(after);
    }

    /** Removes the config after a successful revert; the next target launch is unmodified. */
    void clearPublishedConfig() {
        TargetSpec target = Targets.current();
        new Thread(() -> rootActions.clearConfig(target), "config-clear").start();
    }

    /**
     * Writes the same identity into the system properties, then injects the target process.
     *
     * <p>The two layers are applied in one pass so they cannot drift: the injected Build fields
     * and the {@code ro.*} properties are derived from the same device row, and the visited-network
     * trio is written at the same time. {@code dv == null} restores the pre-persona snapshot.
     */
    private void syncSystemThenPublish(String[] dv, Regions.R r, Runnable after) {
        final String op = r != null ? currentOp(r) : null;
        final String iso = r != null ? r.iso : null;
        final String carrier = r != null ? currentCarrier(r) : null;
        new Thread(() -> {
            String out = "";
            if (RootShell.rootReady()) {
                out = RootShell.runCapture(dv == null
                        ? SystemBuild.restoreScript()
                        : SystemBuild.applyScript(dv, op, iso, carrier));
            }
            DeviceSignals.refresh();
            final String log = out;
            ScreenUpdates.post(screen -> {
                renderScreen.run();
                if (!log.trim().isEmpty()) showActionResult(log);
                publishPersona();
                if (after != null) after.run();
            });
        }).start();
    }

    /** Samsung profiles have no CSC in their template; append the selected region's CSC. */
    private static String[] taggedForRegion(String[] dv, Regions.R r) {
        if (dv == null || r == null || !"samsung".equalsIgnoreCase(dv[2])) {
            return dv;
        }
        String csc = Regions.cscFor(r.iso);
        if (csc == null || dv[10] == null || dv[10].contains("_")) {
            return dv;
        }
        String inc = dv[10];
        String suffix = inc.length() > 5 ? inc.substring(inc.length() - 5) : inc;
        String tagged = inc + "_" + csc + suffix;
        String[] out = dv.clone();
        out[10] = tagged;
        out[8] = PersonaSynth.fingerprint(out[2], out[5], out[4], out[11], out[9], tagged);
        return out;
    }

    private void rebootZygote() {
        new Thread(() -> {
            PersonaState.flushPending();
            RegionPersona.flushPending();
            RootShell.run("setprop ctl.restart zygote", 512, 10000L);
        }, "model-reboot").start();
    }

    /** A target without hooks must not accept a per-target identity edit. */
    private boolean hooksForInjection() {
        TargetSpec target = Targets.current();
        if (target.localIdentityWrites()) {
            return true;
        }
        showRootHint(Lang.t(target.displayName() + " 当前只有环境模拟，不写入逐应用身份",
                target.displayName() + " has environment simulation only; per-app identity is not written",
                target.displayName() + " は環境シミュレーションのみで、アプリ単位の ID は書きません"));
        return false;
    }

    /** The device's own SSAID; shown as the AID default and as the dialog's hint. */
    private String liveAid() {
        try {
            String id = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            return id == null || id.isEmpty() ? "—" : id;
        } catch (Throwable t) {
            return "—";
        }
    }

    /** Effective carrier for a region: persona override, else curated, else region list head. */
    private String currentCarrier(Regions.R r) {
        RegionPersona.Snapshot saved = RegionPersona.of(this, currentUser.get(), r);
        if (saved != null && saved.carrier != null && !saved.carrier.isEmpty()) {
            return saved.carrier;
        }
        String ov = persona.carrier(currentUser.get());
        if (ov != null && !ov.isEmpty()) return ov;
        String[] n = netFor(r.iso);
        if (n != null) return n[2];
        return r.carriers != null && !r.carriers.isEmpty()
                ? r.carriers.split("/")[0] : "";
    }

    /** Effective operator code: persisted region salt, else persona override, else curated. */
    private String currentOp(Regions.R r) {
        RegionPersona.Snapshot saved = RegionPersona.of(this, currentUser.get(), r);
        if (saved != null && saved.op != null && saved.op.matches("\\d{5,6}")) {
            return saved.op;
        }
        String ov = persona.op(currentUser.get());
        if (ov != null && ov.matches("\\d{5,6}")) return ov;
        String[] n = netFor(r.iso);
        return n != null ? n[1] : r.mcc.split("/")[0] + "01";
    }

    /** Runs a root-channel write on a worker, then reports and refreshes the rows. */
    private void sysWrite(final String script) {
        EDIT_TX.execute(() -> {
            final String out = RootShell.runCapture(script);
            RootShell.observeCarrierDenial(out);
            DeviceSignals.refresh();
            ScreenUpdates.post(s -> {
                showActionResult(out.trim().isEmpty() ? "ok" : out);
                repaintSelf();
            });
        });
    }

    private void openCarrierSheet(final com.walter.claudewilson.model.SimPolicy.Decision simDecision,
                                  final long profileSeed) {
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) return;
        final Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        if (r == null) return;
        PersonaEditSheets.showCarrierSheet(
                this, r, simDecision, persona.keepRealSim(user),
                currentOp(r), currentCarrier(r), profileSeed,
                (keepRealSim, op, carrier) -> applyCarrierEdit(user, r, keepRealSim, op, carrier));
    }

    private void applyCarrierEdit(final String user, final Regions.R r,
                                  final boolean keepRealSim, final String op, final String carrier) {
        final long t0 = android.os.SystemClock.elapsedRealtime();
        persona.putKeepRealSim(user, keepRealSim);
        if (!keepRealSim) {
            RegionPersona.putCarrierTuple(this, user, r, op, carrier);
            persona.putOp(user, op);
            persona.putCarrier(user, carrier);
        }
        repaintSelf();
        EDIT_TX.execute(() -> {
            final String script;
            if (keepRealSim) {
                EnvSession.Baseline base = EnvSession.baseline(this);
                String[] origSim = base == null ? null
                        : new String[]{base.simNum, base.simIso, base.simAlpha,
                                base.simDisplay, String.valueOf(base.simSource)};
                script = RootShell.restoreRealSimScript(regionActions.activeSub(), origSim);
            } else {
                script = RootShell.simOverrideScript(regionActions.activeSub(), op, r.iso, carrier);
            }
            String out = RootShell.runCapture(script);
            RootShell.observeCarrierDenial(out);
            DeviceSignals.refresh();
            EnvSession.setSimSpoofed(this, !keepRealSim);
            com.walter.claudewilson.platform.Diag.info("perf",
                    "edit-carrier ms=" + (android.os.SystemClock.elapsedRealtime() - t0));
            ScreenUpdates.post(screen -> {
                showActionResult(out.trim().isEmpty() ? "ok" : out);
                publishCurrent();
            });
        });
    }

    private void openLocaleTzSheet() {
        if (!RootShell.shellReady()) {
            showRootHint(Lang.t("语言与时区修改需要 root 或 Shizuku",
                    "Locale & timezone edit needs root or Shizuku",
                    "言語・タイムゾーン変更には root または Shizuku が必要"));
            return;
        }
        final Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        if (r == null) return;
        PersonaEditSheets.showLocaleTzSheet(this, r, applyLocale, applyZone,
                this::applyLocaleAndZone);
    }

    private void applyLocaleAndZone(final String locale, final String zone,
                                    final boolean syncLocation) {
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final String user = currentUser.get();
        final TargetSpec target = Targets.current();
        if (getBaseContext() instanceof Dashboard) {
            ((Dashboard) getBaseContext()).selectionLocaleAndZone(locale, zone, syncLocation);
        } else {
            if (locale != null && !locale.isEmpty()) RegionSelection.applyLocale = locale;
            if (zone != null && !zone.isEmpty()) RegionSelection.applyZone = zone;
        }
        if (zone != null && !zone.isEmpty()) {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
        }
        repaintSelf();
        EDIT_TX.execute(() -> {
            String browser = RootShell.browserPackage(this);
            String out = RootShell.setLocaleAndTimeZone(
                    zone, locale, target.packageName(), browser, user);
            boolean ok = !RootShell.failed(out);
            if (ok) {
                DeviceSignals.refresh();
            }
            com.walter.claudewilson.platform.Diag.info("perf",
                    "edit-loc-tz ms=" + (android.os.SystemClock.elapsedRealtime() - t0));
            ScreenUpdates.post(screen -> {
                if (ok) {
                    publishCurrent();
                } else {
                    showRootHint(Lang.t("语言/时区写入失败", "Locale/TZ write failed",
                            "言語・TZ書込み失敗") + ": " + out);
                    repaintSelf();
                }
            });
        });
    }

    private void openDeviceCscSheet() {
        if (!hooksForInjection()) return;
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) return;
        final Regions.R region = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        final String aid = persona.aid(user);
        final String[] hw = Regions.hwTag();
        final java.util.List<PersonaEditSheets.CscOption> cscOpts = buildCscOptions(region, hw[0]);
        PersonaEditSheets.showDeviceCscSheet(
                this, region, devices(), selfDevice(),
                persona.device(user), persona.hasDeviceOverride(user),
                persona.deviceHookOnly(user), persona.customDevice(user),
                cscOpts, hw[0],
                new PersonaEditSheets.DeviceCallback() {
                    @Override public void onSelectPreset(int presetIndex, boolean hookOnly) {
                        boolean wasSystem = persona.hasDeviceOverride(user)
                                && !persona.deviceHookOnly(user);
                        boolean modelChanged = presetIndex != persona.device(user)
                                || persona.customDevice(user) != null;
                        long seed = !modelChanged && persona.seed(user) != 0L
                                ? persona.seed(user)
                                : PersonaSalt.nonZero(new java.security.SecureRandom().nextLong());
                        if (modelChanged) {
                            persona.clearBuildPins(user);
                        }
                        if (presetIndex == 0) {
                            persona.clearDeviceOverride(user);
                            persona.put(user, 0, aid, seed);
                            repaintSelf();
                            if (wasSystem) {
                                syncSystemThenPublish(null, region, PersonaPanel.this::rebootZygote);
                            } else {
                                publishCurrent();
                            }
                            return;
                        }
                        int ver = persona.saltVersion(user);
                        long crossSeed = PersonaSalt.deviceCrossSeed(ver,
                                ver <= PersonaSalt.SALT_V1 ? 0L : persona.spaceSeed(user), seed);
                        String[] baseRow = deviceAt(presetIndex);
                        long pSeed = profileSeedFor(user, baseRow, crossSeed,
                                region != null ? region.iso : null);
                        String[] dv = resolveDeviceRow(user, baseRow, crossSeed, pSeed, region);
                        persona.markDeviceOverride(user);
                        persona.putDeviceHookOnly(user, hookOnly);
                        persona.put(user, presetIndex, aid, seed);
                        repaintSelf();
                        if (hookOnly) {
                            publishCurrent();
                        } else {
                            syncSystemThenPublish(dv, region, PersonaPanel.this::rebootZygote);
                        }
                    }

                    @Override public void onSelectCustom(String[] customRow, long seed,
                                                         boolean hookOnly) {
                        persona.clearBuildPins(user);
                        int ver = persona.saltVersion(user);
                        long crossSeed = PersonaSalt.deviceCrossSeed(ver,
                                ver <= PersonaSalt.SALT_V1 ? 0L : persona.spaceSeed(user), seed);
                        long pSeed = profileSeedFor(user, customRow, crossSeed,
                                region != null ? region.iso : null);
                        String[] dv = resolveDeviceRow(user, customRow, crossSeed, pSeed, region);
                        persona.putCustom(user, customRow, aid, seed);
                        persona.putDeviceHookOnly(user, hookOnly);
                        repaintSelf();
                        if (hookOnly) {
                            publishCurrent();
                        } else {
                            syncSystemThenPublish(dv, region, PersonaPanel.this::rebootZygote);
                        }
                    }

                    @Override public void onRunCscScript(String targetCsc, String script) {
                        if (targetCsc != null) {
                            if (targetCsc.isEmpty()) {
                                persona.clearCsc(user);
                            } else {
                                persona.putCsc(user, targetCsc);
                            }
                        }
                        sysWrite(script);
                    }
                });
    }

    private java.util.List<PersonaEditSheets.CscOption> buildCscOptions(Regions.R r, String src) {
        java.util.List<PersonaEditSheets.CscOption> out =
                new java.util.ArrayList<PersonaEditSheets.CscOption>();
        if (r == null) return out;
        String isoUp = r.iso.toUpperCase(java.util.Locale.ROOT);
        String rp = "RP=$(command -v resetprop || echo /data/adb/ksu/bin/resetprop); ";
        String value = cscValue(r);
        if (value != null) {
            out.add(new PersonaEditSheets.CscOption(
                    Lang.t("改为 " + value + "（软重启）",
                            "Change to " + value + " (soft reboot)",
                            value + " に変更（ソフト再起動）"),
                    cscWriteScript(value), value));
        }
        if (src != null && (src.startsWith("ro.") || src.startsWith("persist."))) {
            int eq = src.indexOf('=');
            if (eq > 0) {
                String key = src.substring(0, eq);
                out.add(new PersonaEditSheets.CscOption(
                        Lang.t("设置 " + key + " = " + isoUp + "（立即生效）",
                                "Set " + key + " = " + isoUp + " (instant)",
                                key + " = " + isoUp),
                        rp + "$RP " + key + " " + isoUp, null));
            }
        }
        String vendorRegion = DeviceSignals.getprop("ro.miui.region");
        if (vendorRegion != null && !vendorRegion.isEmpty()
                && (src == null || !src.startsWith("ro.miui.region"))) {
            out.add(new PersonaEditSheets.CscOption(
                    Lang.t("设置 ro.miui.region = " + isoUp + "（立即生效）",
                            "Set ro.miui.region = " + isoUp + " (instant)",
                            "ro.miui.region = " + isoUp),
                    rp + "$RP ro.miui.region " + isoUp, null));
        }
        if (!out.isEmpty()) {
            out.add(new PersonaEditSheets.CscOption(
                    Lang.t("恢复本机原始标记（软重启）",
                            "Restore original tag (soft reboot)",
                            "元のタグに戻す（ソフト再起動）"),
                    rp + "[ -f /data/local/tmp/ClaudeWilson_pn.bak ] && { "
                            + "for k in ro.product.name ro.product.system.name "
                            + "ro.product.system_ext.name ro.product.product.name "
                            + "ro.product.vendor.name ro.product.odm.name "
                            + "ro.product.vendor_dlkm.name ro.product.odm_dlkm.name; do "
                            + "[ -n \"$(getprop $k)\" ] && "
                            + "$RP $k \"$(cat /data/local/tmp/ClaudeWilson_pn.bak)\"; done; "
                            + "setprop ctl.restart zygote; } || echo no-backup",
                    ""));
        }
        return out;
    }

    private void openAidSheet() {
        if (!hooksForInjection()) return;
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) return;
        PersonaEditSheets.showAidSheet(this, liveAid(), persona.aid(user), value -> {
            if (value == null) {
                persona.clearAid(user);
                persona.clearAidOverride(user);
            } else {
                persona.putAid(user, value);
            }
            publishCurrent();
        });
    }

    private void openLocationPicker() {
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) {
            return;
        }
        final Regions.R region = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        final com.walter.claudewilson.model.GeoTable.Point regionPoint =
                region == null ? null : Geo.point(this, region.iso, applyZone);
        if (regionPoint == null) {
            showRootHint(Lang.t("当前地区没有坐标", "no coordinate for this region",
                    "この地域の座標がありません"));
            return;
        }
        LocationPickerDialog.show(this, region, regionPoint, persona, user, applyZone,
                this::publishCurrent,
                newZone -> applyLocaleAndZone(null, newZone, false));
    }

    private void openWifiSheet(final String user, final String iso,
                               final com.walter.claudewilson.model.GeoTable.Point place,
                               final long profileSeed) {
        if (place == null || iso == null || iso.isEmpty()) {
            return;
        }
        final String cityTag = place.city.isEmpty()
                ? iso.toUpperCase(java.util.Locale.ROOT) : place.city;
        final int saltVer = persona.saltVersion(user);
        PersonaEditSheets.showWifiEditorSheet(
                this,
                persona.wifiEnabled(user),
                persona.wifiCustomSsid(user),
                persona.wifiCustomBssid(user),
                persona.wifiSalt(user),
                salt -> com.walter.claudewilson.model.WifiProfile.forRegion(
                        place.latitude, place.longitude, cityTag,
                        PersonaSalt.wifiSeed(saltVer, profileSeed, salt), 8,
                        ProfileLibrary.wifi(this, iso), ProfileLibrary.oui(this)),
                (enabled, customSsid, customBssid, newSalt) -> {
                    persona.putWifiEnabled(user, enabled);
                    persona.putWifiCustomSsid(user, customSsid);
                    persona.putWifiCustomBssid(user, customBssid);
                    persona.putWifiSalt(user, newSalt);
                    memoRadioKey = null;
                    publishCurrent();
                });
    }

    private void openBleSheet(final String user, final String iso, final long profileSeed) {
        if (iso == null || iso.isEmpty()) {
            return;
        }
        final int saltVer = persona.saltVersion(user);
        PersonaEditSheets.showBleEditorSheet(
                this,
                persona.bleEnabled(user),
                persona.bleEmptyMode(user),
                persona.bleCustomBonded(user),
                persona.bleSalt(user),
                salt -> com.walter.claudewilson.model.BleProfile.forRegion(
                        PersonaSalt.bleSeed(saltVer, profileSeed, salt), 4,
                        ProfileLibrary.ble(this, iso), ProfileLibrary.oui(this)),
                (enabled, emptyMode, customBonded, newSalt) -> {
                    persona.putBleEnabled(user, enabled);
                    persona.putBleEmptyMode(user, emptyMode);
                    persona.putBleCustomBonded(user, customBonded);
                    persona.putBleSalt(user, newSalt);
                    memoRadioKey = null;
                    publishCurrent();
                });
    }

    private void openSensorSheet(final String user,
                                 final com.walter.claudewilson.model.GeoTable.Point place,
                                 final long profileSeed, final int localHour, final double alt) {
        if (place == null) {
            return;
        }
        PersonaEditSheets.showSensorEditorSheet(
                this,
                persona.sensorsEnabled(user),
                persona.sensorPreset(user),
                preset -> com.walter.claudewilson.model.SensorProfile.forPreset(
                        profileSeed, place.latitude, place.longitude, localHour,
                        ProfileLibrary.sensor(this, profileSeed), preset, alt),
                (enabled, preset) -> {
                    persona.putSensorsEnabled(user, enabled);
                    persona.putSensorPreset(user, preset);
                    memoRadioKey = null;
                    publishCurrent();
                });
    }

    private static String sensorPresetLabel(String preset) {
        if (com.walter.claudewilson.model.SensorProfile.PRESET_DESK.equals(preset)) {
            return Lang.t("桌面静止", "Desk", "卓上");
        }
        if (com.walter.claudewilson.model.SensorProfile.PRESET_HANDHELD.equals(preset)) {
            return Lang.t("手持阅读", "Handheld", "手持ち");
        }
        if (com.walter.claudewilson.model.SensorProfile.PRESET_INDOOR_NIGHT.equals(preset)) {
            return Lang.t("夜间室内", "Night", "夜間");
        }
        if (com.walter.claudewilson.model.SensorProfile.PRESET_OUTDOOR_DAY.equals(preset)) {
            return Lang.t("户外日间", "Outdoor", "屋外");
        }
        return Lang.t("自动昼夜", "Auto", "自動");
    }

    private static int targetLocationPermission(android.content.Context context,
            TargetSpec target) {
        if (context == null || target == null) {
            return 0;
        }
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            if (pm.checkPermission("android.permission.ACCESS_FINE_LOCATION",
                    target.packageName()) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return 2;
            }
            if (pm.checkPermission("android.permission.ACCESS_COARSE_LOCATION",
                    target.packageName()) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return 1;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * After a successful apply: rewrite the tag when the region names one and the device still
     * disagrees. Only runs when the user opted in (the rewrite ends in a zygote restart, which
     * closes every app on the device), and it is the same script the CSC picker runs.
     */
    void rewriteCscIfMismatched() {
        Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        if (r == null || !RootShell.rootReady()) return;
        String[] hw = Regions.hwTag();
        if (hw[1] == null || "open".equals(hw[1]) || r.iso.equals(hw[1])) return;
        String value = cscValue(r);
        if (value != null) {
            persona.putCsc(currentUser.get(), value);
            sysWrite(cscWriteScript(value));
        }
    }

    /**
     * Country-ish key of a product name ({@code oriole_sg} → {@code sg}) or of a tag
     * ({@code _sg@xiaomi} → {@code sg}), so a recorded target can be compared to what the
     * device reports after the rewrite.
     */
    private static String tagKey(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.startsWith("_") && text.indexOf('@') > 1) {
            return text.substring(1, text.indexOf('@')).toLowerCase(java.util.Locale.ROOT);
        }
        int underscore = text.lastIndexOf('_');
        return (underscore >= 0 && underscore + 1 < text.length()
                ? text.substring(underscore + 1) : text)
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The product name this region wants, or null when the device/region pair has no tag to mint
     * (the tag derives from the build fingerprint, so model override is the only lever).
     */
    private static String cscValue(Regions.R r) {
        if (!Regions.hwTag()[0].contains("@")) return null;
        String tag = pickTag(r.iso);
        return tag == null ? null
                : android.os.Build.DEVICE.toLowerCase(java.util.Locale.ROOT) + "_" + tag;
    }

    /**
     * Every partition name prop moves together — Build.PRODUCT resolves ro.product.name by
     * partition source order and the first existing match wins — and the write only becomes
     * visible to Java after a zygote restart, because Build.* is frozen at zygote start.
     */
    private static String cscWriteScript(String value) {
        String names = "ro.product.name ro.product.system.name "
                + "ro.product.system_ext.name ro.product.product.name "
                + "ro.product.vendor.name ro.product.odm.name "
                + "ro.product.vendor_dlkm.name ro.product.odm_dlkm.name";
        String rp = "RP=$(command -v resetprop || echo /data/adb/ksu/bin/resetprop); ";
        return rp + "[ -f /data/local/tmp/ClaudeWilson_pn.bak ] || "
                + "getprop ro.product.name > /data/local/tmp/ClaudeWilson_pn.bak; "
                + "for k in " + names + "; do "
                + "[ -n \"$(getprop $k)\" ] && $RP $k " + value + "; done; "
                + "setprop ctl.restart zygote";
    }

    /** Generic {@code _xx} suffix preferred; else the first vendor tag, lowercased. */
    private static String pickTag(String iso) {
        String[] p = com.walter.claudewilson.model.RegionTags.split(iso, Regions.TAG_ISO);
        if (!p[1].isEmpty())
            return p[1].split("/")[0].toLowerCase(java.util.Locale.ROOT).replace("_", "");
        if (!p[0].isEmpty())
            return p[0].split("/")[0].toLowerCase(java.util.Locale.ROOT);
        return null;
    }

    /** SSAIDs are 16 hex chars; a space every 8 gives the column a place to wrap. */
    /** Short table label for a Play query failure. */
    private String playErrorLabel(String raw) {
        if (raw.contains("Billing service unavailable") || raw.contains("connect 3")) {
            return Lang.t("无 Play 服务", "no Play services", "Play サービスなし");
        }
        if (raw.contains("超时") || raw.contains("timeout")) {
            return Lang.t("查询超时", "timed out", "タイムアウト");
        }
        return Lang.t("查询失败", "query failed", "照会失敗");
    }

    private com.walter.claudewilson.model.CheckItem modelItem(String id) {
        com.walter.claudewilson.model.RiskModel model =
                riskModel == null ? null : riskModel.get();
        return model == null ? null : model.item(id);
    }

    private String modelValue(String id) {
        com.walter.claudewilson.model.CheckItem item = modelItem(id);
        return item != null && item.isRead() ? item.value() : "—";
    }

    /** Compare this row's own value with the plan; a relation conflict belongs to the pair, not
     *  to the side that already matches. */
    private int hintStatus(boolean enabled, String id, String expected, String actual) {
        if (!enabled || "—".equals(actual)) {
            return -1;
        }
        return comparison(expected, actual);
    }

    private static boolean empty(String v) {
        return v == null || v.isEmpty() || "null".equals(v);
    }

    /** MCC → ISO through the bundled catalog, or "" when the catalog does not know it. */
    private String mccIso(String mcc) {
        try {
            String iso = com.walter.claudewilson.platform.TablesLoader
                    .load(getApplicationContext()).mccToIso().get(mcc);
            return iso == null ? "" : iso;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Slot index of the subscription that carries data, or -1 when it cannot be resolved. */
    private int activeSlot() {
        return activeSlotOf(this);
    }

    /** Package-visible so Dashboard's radio reassert compares the same slot as the card. */
    static int activeSlotOf(android.content.Context context) {
        try {
            android.telephony.SubscriptionManager sm = context == null ? null
                    : context.getSystemService(android.telephony.SubscriptionManager.class);
            int sub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            return sm == null || sub < 0 ? -1
                    : android.telephony.SubscriptionManager.getSlotIndex(sub);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Entry of a per-slot property list that belongs to {@code slot}, else the first non-empty one.
     *
     * <p>The property writer keeps one entry per slot in slot order, so "the first value" is the
     * physical card even when the eSIM is the active subscription.
     */
    static String slotValue(String raw, int slot) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] parts = raw.split(",");
        if (slot >= 0 && slot < parts.length) {
            String value = clean(parts[slot]);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return simValue(raw);
    }

    /**
     * Strips the display suffix telephony appends to a carrier name ("BetterRoaming | 没有服务").
     *
     * <p>The suffix describes service state, not the carrier, and it was also copied into overrides
     * when a baseline was re-applied.
     */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        int at = value.indexOf(" | ");
        return (at > 0 ? value.substring(0, at) : value).trim();
    }

    private static String firstNonEmpty(String first, String second) {
        return empty(first) ? second : first;
    }

    /** Display form for a live value: an unreadable field is a dash, not a question mark. */
    private static String shown(String v) {
        return empty(v) || "?".equals(v) ? "—" : v;
    }

    /** brand/product/device of a fingerprint; the id and incremental change per build. */
    private static String shortFp(String fp) {
        if (empty(fp)) {
            return "?";
        }
        String[] parts = fp.split("[/:]");
        return parts.length >= 3 ? parts[0] + "/" + parts[1] + "/" + parts[2] : fp;
    }

    /**
     * First usable value of a per-slot telephony property.
     *
     * <p>The property writer joins one entry per subscription with commas, so after a persona
     * write and a radio update the area can hold {@code mccmnc1,mccmnc2} -- the persona for the
     * default slot followed by the real network of the other one. The row shows the value the
     * default slot reports, which is the one the target app reads.
     */
    private static String simValue(String v) {
        if (v == null) {
            return "";
        }
        for (String part : v.split(",")) {
            String s = clean(part);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    int mismatches() {
        return mismatches;
    }

    boolean restrictedNativeSim() {
        return restrictedNativeSim;
    }

    /** True only when the target process itself reported this payload as live. */
    private boolean targetProcessLive() {
        com.walter.claudewilson.model.RiskModel model =
                riskModel == null ? null : riskModel.get();
        if (model == null) {
            return false;
        }
        com.walter.claudewilson.model.CheckItem state = model.item("target.state");
        com.walter.claudewilson.model.CheckItem locale = model.item("target.locale");
        return state != null && "running".equals(state.value())
                && locale != null && locale.isRead() && !locale.isStale()
                && !"next-launch".equals(locale.note())
                && !"system-fallback".equals(locale.note())
                && !"app-locale".equals(locale.note());
    }

    /** A self-check value that belongs to a currently running target, not a stale file. */
    private static boolean fresh(com.walter.claudewilson.model.CheckItem item) {
        return item != null && item.isRead() && !item.isStale()
                && item.value() != null && !item.value().isEmpty();
    }

    private static int comparison(String expected, String actual) {
        if (expected == null || expected.isEmpty() || "-".equals(expected)
                || actual == null || actual.isEmpty()) return -1;
        return expected.equalsIgnoreCase(actual) ? 1 : 0;
    }

    /**
     * MCC/MNC is judged at country level: the first three digits (MCC) must match.
     * The MNC identifies a carrier and is not a required persona field.
     */
    private static int mccComparison(String expectedOperator, String actualOperator) {
        if (expectedOperator == null || actualOperator == null
                || expectedOperator.length() < 3 || actualOperator.length() < 3) {
            return -1;
        }
        return expectedOperator.substring(0, 3)
                .equals(actualOperator.substring(0, 3)) ? 1 : 0;
    }

    /** Visited network is judged on its PLMN country, not on a localized carrier string. */
    private static int visitedStatus(String expectedOp, String expectedIso,
                                     String actualOp, String actualIso) {
        if (empty(expectedOp) || empty(expectedIso) || empty(actualOp) || empty(actualIso)) {
            return -1;
        }
        return Regions.mccMatch(expectedOp, actualOp)
                && comparison(expectedIso, actualIso) == 1 ? 1 : 0;
    }

    private static String val(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }

    private static String q(String s) {
        return org.json.JSONObject.quote(s == null ? "" : s);
    }

    private void restoreIpv6State(String user, String pfx, String mobPfx,
                                  String iso, String car) {
        persona.putIpv6State(user, pfx, mobPfx, iso, car);
    }

    /**
     * Resolves [wifiPrefix, mobilePrefix] without mutating PersonaState on UI render.
     * Reads savedIso and savedCar once up front so region switches never mix cross-country pools.
     */
    private String[] resolveIpv6Pair(String user, String iso, String carrier, long seed) {
        String savedWifi = persona.ipv6Prefix(user);
        String savedMob = persona.ipv6MobilePrefix(user);
        String savedIso = persona.ipv6Iso(user);
        String savedCar = persona.ipv6Carrier(user);
        boolean sameIso = savedIso != null && iso.equalsIgnoreCase(savedIso);
        boolean sameCar = sameIso && savedCar != null
                && savedCar.equalsIgnoreCase(carrier == null ? "" : carrier);

        String isoLow = iso.toLowerCase(java.util.Locale.ROOT);
        String wifi;
        if (sameIso && savedWifi != null && !savedWifi.isEmpty()) {
            wifi = savedWifi;
        } else {
            String p = Ipv6Prefixes.pickPrefix(this, isoLow, seed, NetworkPanel.lastEgressAsn());
            wifi = p != null ? p : "";
        }

        String mob;
        if (sameCar && savedMob != null && !savedMob.isEmpty()) {
            mob = savedMob;
        } else {
            String p = Ipv6Prefixes.pickMobilePrefix(this, isoLow, carrier, wifi, seed);
            mob = p != null ? p : wifi;
        }
        return new String[]{wifi, mob};
    }

    /** Extracts the first 2000::/3 global unicast IPv6 from a comma-separated iface_ips value. */
    private static String extractGlobalV6(String ips) {
        if (ips == null) return "—";
        for (String addr : ips.split(",")) {
            addr = addr.trim();
            int pct = addr.indexOf('%');
            if (pct >= 0) addr = addr.substring(0, pct);
            if (addr.isEmpty()) continue;
            if (addr.contains(":") && !addr.startsWith("fe80")
                    && !addr.startsWith("fd") && !addr.startsWith("fc")
                    && !addr.startsWith("::") && addr.matches("^[23][0-9a-fA-F]{0,3}:.*")
                    && expandV6(addr.toLowerCase(java.util.Locale.ROOT)) != null) {
                return addr;
            }
        }
        return "—";
    }

    /**
     * Exact bit-level check whether {@code addr} falls within {@code cidr} (for any /1..128).
     */
    private static boolean sharesPrefix(String addr, String cidr) {
        if (addr == null || cidr == null || cidr.isEmpty()) return false;
        int pct = addr.indexOf('%');
        if (pct >= 0) addr = addr.substring(0, pct);
        int slash = cidr.indexOf('/');
        if (slash < 0) return false;
        String base = cidr.substring(0, slash).toLowerCase(java.util.Locale.ROOT);
        int prefixLen;
        try { prefixLen = Integer.parseInt(cidr.substring(slash + 1).trim()); }
        catch (NumberFormatException e) { return false; }
        if (prefixLen <= 0 || prefixLen > 128) return false;
        String expandedBase = expandV6(base);
        String expandedAddr = expandV6(addr.toLowerCase(java.util.Locale.ROOT));
        if (expandedBase == null || expandedAddr == null) return false;
        int fullHex = prefixLen / 4;
        if (!expandedBase.substring(0, fullHex).equals(expandedAddr.substring(0, fullHex))) {
            return false;
        }
        int remBits = prefixLen % 4;
        if (remBits == 0) return true;
        int bNib = Character.digit(expandedBase.charAt(fullHex), 16);
        int aNib = Character.digit(expandedAddr.charAt(fullHex), 16);
        if (bNib < 0 || aNib < 0) return false;
        int mask = (0xF << (4 - remBits)) & 0xF;
        return (bNib & mask) == (aNib & mask);
    }

    /** Expands an IPv6 address to 32 hex chars (no colons). Returns null on parse failure. */
    private static String expandV6(String v6) {
        if (v6 == null || v6.isEmpty()) return null;
        int dc = v6.indexOf("::");
        if (dc >= 0 && dc != v6.lastIndexOf("::")) return null;
        if (dc >= 0) {
            String leftPart = v6.substring(0, dc);
            String rightPart = v6.substring(dc + 2);
            String[] left = leftPart.isEmpty() ? new String[0] : leftPart.split(":", -1);
            String[] right = rightPart.isEmpty() ? new String[0] : rightPart.split(":", -1);
            int missing = 8 - left.length - right.length;
            if (missing < 1) return null;
            StringBuilder sb = new StringBuilder(32);
            for (String g : left) {
                String p = padGroup(g);
                if (p == null) return null;
                sb.append(p);
            }
            for (int i = 0; i < missing; i++) sb.append("0000");
            for (String g : right) {
                String p = padGroup(g);
                if (p == null) return null;
                sb.append(p);
            }
            return sb.length() == 32 ? sb.toString() : null;
        }
        String[] groups = v6.split(":", -1);
        if (groups.length != 8) return null;
        StringBuilder sb = new StringBuilder(32);
        for (String g : groups) {
            String p = padGroup(g);
            if (p == null) return null;
            sb.append(p);
        }
        return sb.length() == 32 ? sb.toString() : null;
    }

    private static String padGroup(String g) {
        if (g == null || g.isEmpty() || g.length() > 4) return null;
        for (int i = 0; i < g.length(); i++) {
            if (Character.digit(g.charAt(i), 16) < 0) return null;
        }
        StringBuilder sb = new StringBuilder(4);
        for (int i = g.length(); i < 4; i++) sb.append('0');
        sb.append(g);
        return sb.toString();
    }
}
