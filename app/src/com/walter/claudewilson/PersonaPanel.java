package com.walter.claudewilson;

import android.content.Context;
import com.walter.claudewilson.model.PersonaSynth;
import com.walter.claudewilson.model.TargetSpec;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import static com.walter.claudewilson.RegionSelection.*;

/** Persona fields and selection controls, embedded in Dashboard's persona space. */
final class PersonaPanel extends UiSection {
    private final Runnable renderScreen;
    private final java.util.function.Supplier<String> currentUser;
    private final java.util.function.Supplier<String> playCountry;
    private final java.util.function.Supplier<String> applyStatus;
    private final java.util.function.Supplier<com.walter.claudewilson.model.RiskModel> riskModel;
    private final PersonaState persona;
    private int mismatches;
    private final RootActions rootActions;
    private final RegionActions regionActions;
    private volatile String payload;
    private final Object injectLock = new Object();
    private TextView rootHint, actionStatus;

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
     * <p>Everything comes from {@code Build}, so the picker is usable on any model instead of only
     * the curated list above -- which cannot cover a device nobody typed in.
     */
    private static String[] selfDevice() {
        return new String[]{
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                android.os.Build.MANUFACTURER,
                android.os.Build.BRAND,
                android.os.Build.MODEL,
                android.os.Build.DEVICE,
                android.os.Build.PRODUCT,
                android.os.Build.HARDWARE,
                android.os.Build.BOARD,
                android.os.Build.FINGERPRINT,
                android.os.Build.ID,
                android.os.Build.VERSION.INCREMENTAL,
                android.os.Build.VERSION.RELEASE,
                String.valueOf(android.os.Build.VERSION.SDK_INT),
                android.os.Build.VERSION.SECURITY_PATCH == null
                        ? "" : android.os.Build.VERSION.SECURITY_PATCH,
        };
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
        if (custom != null) {
            return PersonaSynth.cross(custom, persona.seed(user));
        }
        int index = Math.floorMod(persona.device(user), deviceCount());
        return PersonaSynth.cross(deviceAt(index), persona.seed(user));
    }

    void renderInto(LinearLayout card) {
        card.removeAllViews();
        payload = null;
        mismatches = 0;
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) {
            card.addView(txt(Lang.t("请输入数字空间 ID", "Enter a numeric space ID",
                    "数字の空間 ID を入力してください"), 11, DIM));
            return;
        }
        Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        boolean hasPersona = r != null;
        String iso = hasPersona ? r.iso : "—";
        String ccHint = persona.countryHint(user);
        if (ccHint == null || !ccHint.matches("[A-Za-z]{2}")) {
            ccHint = iso;
        }
        String[] net = netFor(iso);
        String opOv = hasPersona ? persona.op(user) : null;
        String carOv = hasPersona ? persona.carrier(user) : null;
        String op = opOv != null && opOv.matches("\\d{5,6}") ? opOv
                : net != null ? net[1]
                : (hasPersona ? r.mcc.split("/")[0] + "01" : "—");
        String car = carOv != null && !carOv.isEmpty() ? carOv
                : net != null ? net[2]
                : (hasPersona && r.carriers != null && !r.carriers.isEmpty()
                        ? r.carriers.split("/")[0] : "—");
        // "*" marks a per-space override; without it a user sees the same value after switching
        // regions and concludes the switch did nothing.
        String opShown = opOv != null ? op + "*" : op;
        String carShown = carOv != null && !carOv.isEmpty() ? car + "*" : car;
        // No persona value is minted up front: an unset AID means "this device's own id", and
        // only an explicit save in the AID dialog turns it into an override.
        String aidOverride = persona.aid(user);
        String[] customDevice = hasPersona ? persona.customDevice(user) : null;
        int devIdx = hasPersona ? persona.device(user) % deviceCount() : 0;
        long seed = hasPersona ? persona.seed(user) : 0L;
        String[] dv = hasPersona
                ? PersonaSynth.cross(customDevice != null ? customDevice : deviceAt(devIdx), seed)
                : null;
        final String loc = hasPersona && applyLocale != null ? applyLocale : "—";
        final String tz  = hasPersona && applyZone   != null ? applyZone   : "—";
        com.walter.claudewilson.model.GeoTable.Point regionPoint =
                hasPersona ? Geo.point(this, r.iso, applyZone) : null;
        com.walter.claudewilson.model.GeoTable.Point place = regionPoint;
        boolean customLocation = false;
        if (hasPersona && persona.hasLocationPoint(user)) {
            place = com.walter.claudewilson.model.GeoTable.Point.of(
                    persona.locationCity(user),
                    persona.locationLatitude(user), persona.locationLongitude(user));
            customLocation = true;
        }
        final boolean locationOn = hasPersona && persona.location(user);
        String profileIdentity =
                com.walter.claudewilson.model.ProfileIdentity.of(dv != null ? dv : selfDevice());
        if (!com.walter.claudewilson.model.ProfileIdentity.usable(profileIdentity)) {
            profileIdentity = persona.unlistedIdentity(user);
        }
        final long profileSeed = seed
                ^ (hasPersona ? persona.profileSalt(user, profileIdentity) : 0L);
        final String wifiJson = place == null ? "[]"
                : com.walter.claudewilson.model.WifiProfile.json(
                        com.walter.claudewilson.model.WifiProfile.forRegion(
                                place.latitude, place.longitude,
                                place.city.isEmpty() ? r.iso.toUpperCase(java.util.Locale.ROOT)
                                        : place.city, profileSeed, 8,
                                ProfileLibrary.wifi(this, r.iso), ProfileLibrary.oui(this)));
        final String bleJson = place == null ? "[]"
                : com.walter.claudewilson.model.BleProfile.json(
                        com.walter.claudewilson.model.BleProfile.forRegion(
                                profileSeed, 4, ProfileLibrary.ble(this, r.iso),
                                ProfileLibrary.oui(this)));
        final String sensorJson;
        if (place == null) {
            sensorJson = "{}";
        } else {
            java.util.TimeZone zone = java.util.TimeZone.getTimeZone(
                    applyZone == null || applyZone.isEmpty() ? "UTC" : applyZone);
            int hour = java.util.Calendar.getInstance(zone)
                    .get(java.util.Calendar.HOUR_OF_DAY);
            sensorJson = com.walter.claudewilson.model.SensorProfile.forRegion(
                    profileSeed, place.latitude, place.longitude, hour,
                    ProfileLibrary.sensor(this, profileSeed)).json();
        }
        String liveOp = "", liveIso = "", liveCarrier = "";
        // The property area holds one entry per slot ("mccmnc1,mccmnc2"), in slot order. Taking the
        // first non-empty entry named the wrong card as soon as the active subscription was not
        // slot 0 -- switching to the eSIM still showed the physical card's carrier. Read the entry
        // that belongs to the active subscription's slot.
        int simSlot = activeSlot();
        liveOp = slotValue(DeviceSignals.getprop("gsm.sim.operator.numeric"), simSlot);
        liveIso = slotValue(DeviceSignals.getprop("gsm.sim.operator.iso-country"), simSlot);
        liveCarrier = slotValue(DeviceSignals.getprop("gsm.sim.operator.alpha"), simSlot);
        // The subscription record still carries MCC/MNC when the property area is empty (eSIM, or
        // properties cleared by a previous root channel). Without it the row showed a dash for a
        // perfectly known SIM.
        if (empty(liveOp) || empty(liveIso) || empty(liveCarrier)) {
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
                    if (empty(liveOp) && !empty(mcc) && !empty(mnc)) {
                        liveOp = mcc + mnc;
                    }
                    if (empty(liveIso)) {
                        String simIso = chosen.getCountryIso();
                        if (!empty(simIso)) liveIso = simIso;
                    }
                    if (empty(liveCarrier)) {
                        CharSequence name = chosen.getCarrierName();
                        if (name != null && name.length() > 0) liveCarrier = name.toString();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            if (empty(liveOp)) {
                try { liveOp = tm.getSimOperator(); } catch (RuntimeException ignored) {}
            }
            if (empty(liveIso)) {
                try { liveIso = tm.getSimCountryIso(); } catch (RuntimeException ignored) {}
            }
            if (empty(liveCarrier)) {
                try { liveCarrier = tm.getSimOperatorName(); } catch (RuntimeException ignored) {}
            }
        }
        // Visited network. Which source is truthful depends on the channel: only root can keep
        // gsm.operator.* on the persona (telephony rewrites it to the registered PLMN), so on a
        // shell channel the property area is a leftover, not a statement -- measured: after a
        // carrier override as uid 2000 the trio still read back the physical card.
        // ServiceState is what the target process reads anyway, so it wins there.
        boolean rootChannel = RootShell.rootReady();
        // SIM country/carrier overrides are stored per space and written on the next apply.
        // Without any privileged channel there is nothing to write, so the columns say
        // "not compared" instead of showing red crosses for a value that cannot move.
        boolean simCarrierWritable = RootShell.state() == RootShell.ST_READY;
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
        // The target language is whatever the target process reads from the hook payload. It is
        // not this control app's UI language and not the system locale.
        com.walter.claudewilson.model.CheckItem targetLocale =
                modelItem("target.locale");
        boolean targetLocaleLive = targetLocale != null && targetLocale.isRead()
                && !targetLocale.isStale()
                && !"next-launch".equals(targetLocale.note())
                && !"system-fallback".equals(targetLocale.note());
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
        boolean canInject = hasPersona && RootShell.shellReady()
                && target.localIdentityWrites();
        // A target is shown only after a deliberate pick; a stored value alone is not a request.
        boolean devTarget = canInject && persona.hasDeviceOverride(user);
        boolean aidTarget = canInject && persona.hasAidOverride(user);
        // Built here rather than at the bottom: the rows below need the payload's signature to
        // tell "this process is running exactly this persona" from "the region tab moved on".
        if (hasPersona) {
            payload = "{\"target\":\"" + target.id()
                + "\",\"simOperator\":\"" + op
                + "\",\"simCountryIso\":\"" + iso
                + "\",\"simOperatorName\":\"" + car
                + "\",\"netOperator\":\"" + op
                + "\",\"netOperatorName\":\"" + car
                + "\",\"netCountryIso\":\"" + iso
                + "\",\"countryCodeHint\":\"" + ccHint
                + "\",\"simCarrierId\":"
                + ("docomo".equals(car) ? 1185 : -1)
                + ",\"androidId\":\"" + (aidOverride != null ? aidOverride : liveAidText)
                + (applyLocale != null
                        ? "\",\"locale\":\"" + applyLocale : "")
                + (applyZone != null
                        ? "\",\"tz\":\"" + applyZone : "")
                + (place != null
                        ? "\",\"location\":" + com.walter.claudewilson.model.LocationPayload.json(
                                locationOn, place.latitude, place.longitude, profileSeed, place.city)
                                + ",\"wifi\":" + wifiJson
                                + ",\"ble\":" + bleJson
                                + ",\"sensors\":" + sensorJson
                                + ",\"sensorsEnabled\":true"
                                + ",\"build\":{"
                        : "\",\"build\":{")
                + "\"MANUFACTURER\":\"" + dv[1]
                + "\",\"BRAND\":\"" + dv[2]
                + "\",\"MODEL\":\"" + dv[3]
                + "\",\"DEVICE\":\"" + dv[4]
                + "\",\"PRODUCT\":\"" + dv[5]
                + "\",\"HARDWARE\":\"" + dv[6]
                + "\",\"BOARD\":\"" + dv[7]
                + "\",\"FINGERPRINT\":\"" + dv[8]
                + "\",\"ID\":\"" + dv[9]
                + "\",\"TAGS\":\"release-keys\",\"TYPE\":\"user\""
                + ",\"DISPLAY\":\"" + dv[9] + "." + dv[10]
                + "\",\"VERSION.RELEASE\":\"" + dv[11]
                + "\",\"VERSION.SDK_INT\":" + Integer.parseInt(dv[12])
                + ",\"VERSION.SECURITY_PATCH\":\"" + dv[13]
                + "\",\"VERSION.INCREMENTAL\":\"" + dv[10]
                + "\"},\"props\":{"
                + "\"ro.product.brand\":\"" + dv[2]
                + "\",\"ro.product.model\":\"" + dv[3]
                + "\",\"ro.product.device\":\"" + dv[4]
                + "\",\"ro.product.name\":\"" + dv[5]
                + "\",\"ro.product.manufacturer\":\"" + dv[1]
                + "\",\"ro.product.board\":\"" + dv[7]
                + "\",\"ro.hardware\":\"" + dv[6]
                + "\",\"ro.build.fingerprint\":\"" + dv[8]
                + "\",\"ro.build.display.id\":\"" + dv[9] + "." + dv[10]
                + "\",\"ro.build.version.release\":\"" + dv[11]
                + "\",\"ro.build.version.sdk\":\"" + dv[12]
                + "\",\"ro.build.version.security_patch\":\"" + dv[13]
                + "\",\"ro.build.version.incremental\":\"" + dv[10]
                + "\",\"ro.build.id\":\"" + dv[9]
                + "\",\"ro.product.locale\":\""
                + (applyLocale == null ? "" : applyLocale)
                + "\""
                + ",\"ro.build.tags\":\"release-keys\""
                + ",\"ro.build.type\":\"user\"}"
                + "}";
        }
        String payloadSig = payload == null ? null : Integer.toHexString(payload.hashCode());
        boolean injectedNow = payloadSig != null && payloadSig.equals(persona.injectedSig(user));
        card.addView(row(hasPersona, Lang.t("字段", "field", "項目"),
                Lang.t("期待", "expected", "期待"),
                Lang.t("实际", "actual", "実際"), -2));
        rootHint = txt("", 10, AMBER);
        rootHint.setVisibility(View.GONE);
        rootHint.setPadding(0, dp(this, 2), 0, dp(this, 2));
        card.addView(rootHint);
        actionStatus = txt("", 10, DIM);
        // The apply log was injected but never drawn: the locale read-back, the skipped steps
        // and the failure reason all went to a supplier nobody rendered.
        String apply = applyStatus.get();
        if (apply != null && !apply.isEmpty()) actionStatus.setText(apply);
        actionStatus.setPadding(0, dp(this, 2), 0, dp(this, 2));
        // Status text is deliberately not attached: the card shows values, not a log stream.
        // Group 1: values the region switch writes by itself, so they belong at the top.
        LinearLayout mccRow = row(hasPersona, "SIM MCC/MNC",
                hasPersona ? opShown : dash,
                shown(liveOp),
                !hasPersona ? -1 : mccComparison(op, liveOp));
        if (hasPersona) gateRoot(mccRow,
                Lang.t("SIM MCC/MNC 覆写需要系统级权限（root）",
                        "SIM MCC/MNC override needs the system channel (root)",
                        "SIM MCC/MNC 上書きにはシステムチャネル（root）が必要"),
                this::editMcc);
        card.addView(mccRow);
        LinearLayout isoRow = row(hasPersona, Lang.t("SIM 国家", "SIM country", "SIM 国"),
                hasPersona ? iso : dash,
                shown(liveIso),
                !hasPersona ? -1 : comparison(iso, liveIso));
        if (hasPersona) {
            if (simCarrierWritable) {
                gateAnyChannel(isoRow,
                        Lang.t("SIM 国家覆写需要 root 授权（shell 通道即可）",
                                "SIM country override needs root (shell channel is enough)",
                                "SIM 国上書きには root 許可が必要（shell チャネルで可）"),
                        this::editIso);
            } else {
                gateRoot(isoRow,
                        Lang.t("此 ROM 拒绝 shell SIM 覆写；SIM 需要 root 通道",
                                "this ROM denies shell SIM overrides; use the root channel",
                                "この ROM は shell の SIM 上書きを拒否します。root チャネルが必要"),
                        this::editIso);
            }
        }
        card.addView(isoRow);
        LinearLayout carrierRow = row(hasPersona, Lang.t("SIM 运营商", "SIM carrier", "SIM キャリア"),
                hasPersona ? carShown : dash,
                shown(liveCarrier),
                !hasPersona ? -1 : comparison(car, liveCarrier));
        if (hasPersona) {
            if (simCarrierWritable) {
                gateAnyChannel(carrierRow,
                        Lang.t("SIM 运营商覆写需要 root 授权（shell 通道即可）",
                                "SIM carrier override needs root (shell channel is enough)",
                                "SIM キャリア上書きには root 許可が必要（shell チャネルで可）"),
                        this::editCarrier);
            } else {
                gateRoot(carrierRow,
                        Lang.t("此 ROM 拒绝 shell SIM 覆写；SIM 需要 root 通道",
                                "this ROM denies shell SIM overrides; use the root channel",
                                "この ROM は shell の SIM 上書きを拒否します。root チャネルが必要"),
                        this::editCarrier);
            }
        }
        card.addView(carrierRow);
        // The trio is written system-wide, so the actual value is the property area -- the same
        // three values the telemetry backend reads. A mismatch here is a real fact, not a UI state.
        // The visited country is often blank in ServiceState on OEM builds, but the PLMN still
        // carries the MCC, so the country is derivable instead of being displayed as "?".
        if (liveNet.length() >= 3) {
            // The PLMN's MCC is the authoritative country for the visited network. OEM
            // telephony props can keep a stale iso next to a China MCC, and showing
            // that beside the physical carrier is a contradiction, not an observation.
            String derived = mccIso(liveNet.substring(0, 3));
            if (!empty(derived) && (empty(liveNetIso)
                    || !derived.equalsIgnoreCase(liveNetIso))) {
                liveNetIso = derived;
            }
        }
        String netTarget = val(op) + " · " + iso + " · " + val(car);
        String liveNetTrio = shown(liveNet) + " · " + shown(liveNetIso) + " · "
                + shown(liveNetName);
        if (airplane) {
            // No registration, no visited network: both columns say so instead of repeating the
            // PLMN that was live before the radio was turned off.
            card.addView(row(hasPersona,
                    Lang.t("拜访网络", "visited network", "訪問ネットワーク"),
                    dash, dash, -1));
        } else {
            // What the target app currently receives: the injected payload while it is attached,
            // otherwise whatever the system reports. A radio event rewrites gsm.operator.*, so the
            // property value alone would flip back to the real PLMN under an active injection.
            // Without a region the value is still worth showing: it is the network the device is
            // actually attached to.
            boolean targetLive = injectedNow && targetProcessLive();
            String netActual = targetLive ? netTarget : liveNetTrio;
            card.addView(row(hasPersona,
                    Lang.t("拜访网络", "visited network", "訪問ネットワーク"),
                    hasPersona ? netTarget : dash,
                    hasPersona ? netActual : liveNetTrio,
                    !hasPersona ? -1
                            : targetLive ? 1
                            : visitedStatus(op, iso, liveNet, liveNetIso)));
        }
        int langStatus;
        if (!hasPersona) {
            langStatus = -1;
        } else if (!targetLocaleLive) {
            langStatus = -3;
        } else {
            langStatus = comparison(loc.replace("-", "_"), liveLoc) == 1
                    || Regions.localeMatch(loc, liveLoc) ? 1 : 0;
        }
        card.addView(row(hasPersona, Lang.t("目标语言", "target language", "対象言語"),
                hasPersona ? loc.replace("-", "_") : dash,
                liveLoc,
                langStatus));
        LinearLayout tzRow = row(hasPersona, Lang.t("时区", "tz", "TZ"),
                hasPersona ? tz : dash,
                liveTz,
                !hasPersona ? -1 : comparison(tz, liveTz));
        if (hasPersona) bindTimeZoneEditor(tzRow);
        card.addView(tzRow);
        String liveUtc = Regions.utcAt(liveTz);
        LinearLayout utcRow = row(hasPersona,
                Lang.t("UTC 偏移", "UTC offset", "UTC オフセット"),
                hasPersona ? r.utc : dash,
                liveUtc,
                !hasPersona ? -1 : Regions.utcMatch(r.utc, liveUtc) ? 1 : 0);
        if (hasPersona) bindTimeZoneEditor(utcRow);
        card.addView(utcRow);
        // Synthetic location: the region point, not a reading. The expected cell is the place
        // the target should see; the actual cell is the hook state (the control app never reads
        // a real fix, so there is nothing else truthful to put there).
        int targetLocation = targetLocationPermission(this, target);
        String locWhere = dash;
        if (place != null) {
            locWhere = (place.city.isEmpty() ? r.name : place.city) + (customLocation ? "*" : "");
        }
        String locActual;
        int locStatus;
        if (!hasPersona) {
            locActual = dash;
            locStatus = -1;
        } else if (!locationOn) {
            locActual = Lang.t("关闭", "off", "オフ");
            locStatus = -1;
        } else if (place == null) {
            locActual = Lang.t("无坐标", "no coordinate", "座標なし");
            locStatus = -3;
        } else if (targetLocation == 0) {
            locActual = Lang.t("无权限", "no permission", "権限なし");
            locStatus = -3;
        } else {
            locActual = targetLocation == 2 ? "gps ±5m" : "network ±3km";
            locStatus = 1;
        }
        LinearLayout locationRow = row(hasPersona,
                Lang.t("定位", "location", "位置情報"),
                hasPersona ? locWhere : dash,
                locActual,
                locStatus);
        if (hasPersona) bindLocationEditor(locationRow);
        card.addView(locationRow);
        String hintExpected = hasPersona ? r.iso.toUpperCase(java.util.Locale.ROOT) : dash;
        // A target that does not write a stored country hint has no row to compare. Claude names
        // only the hook payload it will read on the next launch, so do not invent a second store.
        if (modelItem("target.data.country_hint") != null) {
            String storedHint = modelValue("target.data.country_hint");
            card.addView(row(hasPersona, Lang.t("应用已存国家", "stored country", "保存国"),
                    hintExpected, storedHint,
                    hintStatus(hasPersona, "target.data.country_hint",
                            hintExpected, storedHint)));
        }
        if (modelItem("target.payload.cc_hint") != null) {
            String nextHint = modelValue("target.payload.cc_hint");
            card.addView(row(hasPersona,
                    Lang.t("下次启动国家", "next launch country", "次回起動国"),
                    hintExpected, nextHint,
                    hintStatus(hasPersona, "target.payload.cc_hint",
                            hintExpected, nextHint)));
        }

        // Group 2: never written by the region switch. Their expected value defaults to the
        // device's own value, so "nothing changed" reads as a match instead of "not compared".
        LinearLayout modelRow = row(hasPersona, Lang.t("机型", "model", "機種"),
                devTarget ? dv[3] : liveModel, liveModel,
                hasPersona ? comparison(devTarget ? dv[3] : liveModel, liveModel) : -1);
        if (hasPersona) gateRoot(modelRow,
                Lang.t("机型覆写需要系统级权限（root）",
                        "Model injection needs the system channel (root)",
                        "機種上書きにはシステムチャネル（root）が必要"),
                this::editModel);
        card.addView(modelRow);
        // The fingerprint is the one field that reached Google mixed (brand from the real device,
        // product/device from the persona). Shown as its three identity segments: the id and
        // incremental vary per build and only add noise at this width.
        String liveFp = val(DeviceSignals.getprop("ro.build.fingerprint"));
        String fpExpected = devTarget ? shortFp(dv[8]) : shortFp(liveFp);
        card.addView(row(hasPersona, Lang.t("指纹", "fingerprint", "指紋"),
                fpExpected, shortFp(liveFp),
                hasPersona ? comparison(fpExpected, shortFp(liveFp)) : -1));
        String aidShown = aidOverride != null ? aidOverride : liveAidText;
        // The SSAID cannot be written system-wide, so this row's mark means "the target process is
        // running the payload that carries this id". With no attach (or a different payload after
        // switching region) the column falls back to what the device itself reports.
        boolean aidLive = aidTarget && injectedNow;
        // No target means the row mirrors the device's own SSAID on both sides, which is a match:
        // the "—" state is reserved for a value that cannot be compared at all.
        int aidStatus = !aidTarget ? comparison(liveAidText, liveAidText)
                : aidLive ? 1 : comparison(aidOverride, liveAidText);
        LinearLayout aidRow = row(hasPersona, "AID",
                aidTarget ? aidShown : liveAidText,
                aidLive ? aidOverride : liveAidText,
                aidStatus);
        // 16 hex characters do not fit the column at the table size; shrink this row only. The row
        // has three cells with a region selected and two without, so walk the value cells instead
        // of indexing a fixed position.
        for (int i = 1; i < aidRow.getChildCount(); i++) {
            ((TextView) aidRow.getChildAt(i)).setTextSize(9);
        }
        if (hasPersona) gateRoot(aidRow,
                Lang.t("改写/注入 AID 需要系统级权限（root）",
                        "Rewriting/injecting AID needs the system channel (root)",
                        "AID の書き換え/注入にはシステムチャネル（root）が必要"),
                this::editAid);
        String[] hw = Regions.hwTag();
        // The tag is not assigned by default either: with no target the row simply mirrors the
        // device's own value. A target exists only after the CSC row wrote one, and then the row
        // is the proof that the rewrite survived the zygote restart.
        String cscTarget = persona.csc(user);
        String cscExpected = !hasPersona ? dash : cscTarget != null ? cscTarget : hw[0];
        // Same rule as the rows above: no target means both cells hold the device's own tag, so the
        // row is a match; a written target is compared against what the rewrite produced.
        int cscStatus = !hasPersona ? -1
                : tagKey(cscExpected).equals(tagKey(hw[0])) ? 1 : 0;
        LinearLayout cscRow = row(hasPersona,
                Lang.t("区域标记/CSC", "region tag/CSC", "地域タグ/CSC"),
                cscExpected, hw[0], cscStatus);
        if (hasPersona) gateRoot(cscRow,
                Lang.t("区域标记覆写需要系统级权限（root）",
                        "Region tag override needs the system channel (root)",
                        "地域タグ上書きにはシステムチャネル（root）が必要"),
                this::editCsc);
        card.addView(cscRow);
        String pc = playCountry.get();
        String playValue;
        // Informational only: the payments profile follows the Google account, not the region we
        // simulate, so this row is never compared against the expected country.
        int playStatus = -1;
        // The payments profile is a property of the Google account, not of the simulated region, so
        // it is worth showing even with no region selected -- that is the only way to see which
        // country the account currently resolves to.
        if (pc == null) {
            // The query is started by Dashboard's scan path, never from a render pass: creating a
            // BillingClient on the UI thread while the region card is being rebuilt is the kind
            // of hidden binder work this pass is removing.
            playValue = Lang.t("查询中…", "querying…", "照会中…");
        } else if (pc.startsWith("err")) {
            // Keep raw diagnostics out of the table: a missing Play service is a state, not a
            // log line, and the four-line message used to wreck the row height.
            playValue = playErrorLabel(pc);
            playStatus = -1;
        } else {
            playValue = pc.toUpperCase(java.util.Locale.ROOT);
            // Informational only: the payments profile follows the Google account, not the region
            // we simulate, so comparing it against the region would always read as a failure.
            playStatus = -1;
        }
        card.addView(row(hasPersona, Lang.t("Play 商店", "Play store", "Play ストア"),
                hasPersona ? r.iso.toUpperCase(java.util.Locale.ROOT) : dash,
                playValue, playStatus));
        card.addView(row(hasPersona, Lang.t("Google Pay", "Google Pay", "Google Pay"),
                hasPersona ? r.iso.toUpperCase(java.util.Locale.ROOT) : dash,
                playValue, playStatus));
        String hist = persona.history(user);
        String recent = "";
        if (hasPersona && !hist.isEmpty()) {
            StringBuilder hs = new StringBuilder();
            for (String e : hist.split(",")) {
                String[] t = e.split(":");
                if (t.length < 2) continue;
                try {
                    int di = Integer.parseInt(t[1]);
                    hs.append(' ').append(t[0])
                      .append('×').append(deviceAt(di % deviceCount())[0]);
                    if (t.length > 2) hs.append('·').append(t[2]);
                } catch (NumberFormatException ignore) {}
            }
            recent = hs.toString().trim();
        }
        // Only show the history line when there is history: an empty "recent: —" row is a label
        // that explains nothing.
        if (!recent.isEmpty()) {
            String spaceName = SpaceNames.name(this, user);
            TextView hv = txt(Lang.t(spaceName + " · 最近人设: ",
                    spaceName + " · recent: ", spaceName + " · 最近: ") + recent, 10, DIM);
            hv.setTypeface(Typeface.MONOSPACE);
            hv.setPadding(0, dp(this, 4), 0, 0);
            card.addView(hv);
        }
    }

    private void gateRoot(View row, String message, Runnable action) {
        if (RootShell.rootReady()) {
            row.setAlpha(1f);
            row.setOnClickListener(v -> {
                if (action != null) action.run();
            });
            return;
        }
        row.setAlpha(0.45f);
        row.setOnClickListener(v -> showRootHint(message));
    }

    /**
     * Rows driven by the carrier-config override: any root channel can make that binder call.
     *
     * <p>Measured as uid 2000: {@code Bridge cc} answers "cc set ..." while
     * {@code setprop gsm.sim.operator.*} is refused. Gating these rows on root disabled a path
     * that works.
     */
    private void gateAnyChannel(View row, String message, Runnable action) {
        if (RootShell.state() == RootShell.ST_READY) {
            row.setAlpha(1f);
            row.setOnClickListener(v -> {
                if (action != null) action.run();
            });
            return;
        }
        row.setAlpha(0.45f);
        row.setOnClickListener(v -> showRootHint(message));
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
        publishPersona(null);
    }

    private void publishPersona(final Runnable after) {
        TargetSpec target = Targets.current();
        if (!target.hooksEnabled()) {
            showRootHint(Lang.t("此目标目前只读诊断，不发布注入配置",
                    "this target is diagnostic-only; no payload is published",
                    "この対象は読取専用のため設定を配信しません"));
            return;
        }
        if (!RootShell.shellReady()) {
            showRootHint(Lang.t("先授权 root 或 Shizuku；配置会在下次启动 "
                            + target.displayName() + " 时生效",
                    "grant root or Shizuku first; the config applies on the next "
                            + target.displayName() + " launch",
                    "先に root または Shizuku を許可してください。設定は次回の "
                            + target.displayName() + " 起動時に反映されます"));
            return;
        }
        final String json = payload;
        if (json == null) {
            showRootHint(Lang.t("请先选择地区生成人设",
                    "Select a region first to generate a persona",
                    "先に地域を選択して人設を生成してください"));
            return;
        }
        final String user = currentUser.get();
        final String sig = Integer.toHexString(json.hashCode());
        try {
            new org.json.JSONObject(json);
        } catch (Exception e) {
            showRootHint(Lang.t("人设 JSON 无效，已拒绝写入: ",
                    "persona JSON is invalid; not publishing: ",
                    "人設 JSON が不正なため配信しません: ") + e.getMessage());
            return;
        }
        new Thread(() -> {
            try {
                String out;
                synchronized (injectLock) {
                    out = rootActions.publish(target, json);
                    if (!RootShell.failed(out)) {
                        persona.markInjected(user, sig);
                    }
                }
                final String result = out;
                ScreenUpdates.post(screen -> {
                    renderScreen.run();
                    showActionResult(result);
                    if (after != null) {
                        after.run();
                    }
                });
            } catch (Exception e) {
                ScreenUpdates.post(screen -> screen.personaPanel.showActionResult("io: " + e));
            }
        }).start();
    }

    /** Publishes the payload after the region/system write, without another UI action. */
    void publishCurrent() {
        publishPersona();
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
    private void syncSystemThenPublish(String[] dv, Regions.R r) {
        syncSystemThenPublish(dv, r, null);
    }

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

    private void editModel() {
        if (!hooksForInjection()) return;
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) return;
        final String aid = persona.aid(user);
        final Regions.R region = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        String[] names = new String[deviceCount() + 1];
        names[0] = Lang.t("本机", "this device", "実機") + ": " + selfDevice()[0];
        for (int i = 1; i < deviceCount(); i++) names[i] = deviceAt(i)[0];
        String[] custom = persona.customDevice(user);
        int customIndex = names.length - 1;
        names[customIndex] = custom == null
                ? Lang.t("自定义机型…", "custom model…", "カスタム機種…")
                : Lang.t("自定义机型：", "custom model: ", "カスタム機種：") + custom[3];
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("选择机型", "select model", "機種を選択"))
                .setItems(names, (dialog, which) -> {
                    if (which == customIndex) {
                        editCustomModel(region, user, aid);
                        return;
                    }
                    long seed = new java.util.Random().nextLong();
                    String[] dv = which == 0 ? null
                            : taggedForRegion(PersonaSynth.cross(deviceAt(which), seed), region);
                    String model = dv != null ? dv[3] : selfDevice()[3];
                    String fp = dv != null ? dv[8] : selfDevice()[8];
                    String csc = region != null ? Regions.cscFor(region.iso) : null;
                    String where = region != null
                            ? region.name + " " + region.iso.toUpperCase(java.util.Locale.ROOT)
                            : "—";
                    String message = Lang.t(
                            "机型：" + model + "\n指纹：" + shortFp(fp)
                                    + "\n区域：" + where + "\nCSC：" + (csc == null ? "—" : csc)
                                    + "\n需要软重启，是否确认？",
                            "model: " + model + "\nfingerprint: " + shortFp(fp)
                                    + "\nregion: " + where + "\nCSC: " + (csc == null ? "—" : csc)
                                    + "\nsoft reboot required. Continue?");
                    new android.app.AlertDialog.Builder(PersonaPanel.this)
                            .setTitle(Lang.t("确认修改机型", "confirm model change"))
                            .setMessage(message)
                            .setPositiveButton(Lang.t("确认软重启", "reboot now"),
                                    (confirm, button) -> {
                                        if (which == 0) {
                                            persona.clearDeviceOverride(user);
                                        } else {
                                            persona.markDeviceOverride(user);
                                        }
                                        persona.put(user, which, aid, seed);
                                        renderScreen.run();
                                        syncSystemThenPublish(dv, region,
                                                PersonaPanel.this::rebootZygote);
                                    })
                            .setNegativeButton(Lang.t("取消", "cancel"), null)
                            .show();
                }).show();
    }

    /**
     * Backend/catalog rows cannot cover every device, so the same fourteen fields are editable
     * here. Only the identity fields are asked for; build ID, incremental and patch are generated
     * coherently from the release and one seed.
     */
    private void editCustomModel(Regions.R region, String user, String aid) {
        String[] base = persona.customDevice(user);
        if (base == null) {
            base = deviceValues(user);
        }
        if (base == null) {
            base = selfDevice();
        }
        int pad = dp(this, 20);
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        form.setPadding(pad, dp(this, 8), pad, 0);

        android.widget.EditText brand = customField(form,
                Lang.t("品牌 / brand", "brand", "ブランド"), base[2]);
        android.widget.EditText model = customField(form,
                Lang.t("型号 / model", "model", "型番"), base[3]);
        android.widget.EditText device = customField(form,
                Lang.t("device", "device", "device"), base[4]);
        android.widget.EditText product = customField(form,
                Lang.t("product", "product", "product"), base[5]);
        android.widget.EditText release = customField(form,
                Lang.t("系统版本 / release（14 / 15 / 16）", "Android release (14 / 15 / 16)",
                        "Android バージョン（14 / 15 / 16）"), base[11]);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(form);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("自定义机型", "custom model", "カスタム機種"))
                .setView(scroll)
                .setPositiveButton(Lang.t("下一步", "next", "次へ"), null)
                .setNegativeButton(Lang.t("取消", "cancel", "キャンセル"), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String b = brand.getText().toString().trim();
            String m = model.getText().toString().trim();
            String d = device.getText().toString().trim();
            String p = product.getText().toString().trim();
            String r = release.getText().toString().trim();
            if (b.isEmpty() || m.isEmpty() || d.isEmpty() || p.isEmpty()) {
                toast(Lang.t("品牌、型号、device、product 都要填",
                        "brand, model, device and product are required",
                        "ブランド・型番・device・product は必須です"));
                return;
            }
            if (!b.matches("[A-Za-z0-9._-]{1,92}")
                    || !d.matches("[A-Za-z0-9._-]{1,92}")
                    || !p.matches("[A-Za-z0-9._-]{1,92}")) {
                toast(Lang.t("brand / device / product 只能包含字母、数字、点、下划线、连字符",
                        "brand / device / product: letters, digits, dot, underscore, hyphen only",
                        "brand / device / product は英数字・.・_・- のみ"));
                return;
            }
            if (m.length() > 64) {
                toast(Lang.t("型号过长", "model is too long", "型番が長すぎます"));
                return;
            }
            int sdk;
            if ("14".equals(r)) {
                sdk = 34;
            } else if ("15".equals(r)) {
                sdk = 35;
            } else if ("16".equals(r)) {
                sdk = 36;
            } else {
                toast(Lang.t("Android 版本只支持 14/15/16",
                        "Android release must be 14/15/16",
                        "Android バージョンは 14/15/16 のみ"));
                return;
            }
            java.util.Random random = new java.util.Random();
            java.time.LocalDate now = java.time.LocalDate.now();
            String date = now.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            String buildId = "AP3A." + date + "." + (100000 + random.nextInt(900000));
            String incremental = String.valueOf(10000000 + random.nextInt(90000000));
            String patch = now.minusMonths(random.nextInt(12)).withDayOfMonth(1).toString();
            String[] row = {
                    b + " " + m,
                    b,
                    b,
                    m,
                    d,
                    p,
                    d,
                    d,
                    "",
                    buildId,
                    incremental,
                    r,
                    String.valueOf(sdk),
                    patch
            };
            row[8] = PersonaSynth.fingerprint(row[2], row[5], row[4], row[11], row[9], row[10]);
            long seed = random.nextLong();
            String[] dv = taggedForRegion(PersonaSynth.cross(row, seed), region);
            dialog.dismiss();
            String csc = region != null ? Regions.cscFor(region.iso) : null;
            String where = region != null
                    ? region.name + " " + region.iso.toUpperCase(java.util.Locale.ROOT)
                    : "—";
            new android.app.AlertDialog.Builder(PersonaPanel.this)
                    .setTitle(Lang.t("确认修改机型", "confirm model change"))
                    .setMessage(Lang.t(
                            "品牌 brand：" + dv[2]
                                    + "\n厂商 manufacturer：" + dv[1]
                                    + "\n型号 model：" + dv[3]
                                    + "\ndevice：" + dv[4]
                                    + "\nproduct：" + dv[5]
                                    + "\nhardware：" + dv[6]
                                    + "\nboard：" + dv[7]
                                    + "\n系统版本 release：" + dv[11]
                                    + "\nSDK：" + dv[12]
                                    + "\nbuild ID：" + dv[9]
                                    + "\nincremental：" + dv[10]
                                    + "\nsecurity patch：" + dv[13]
                                    + "\nfingerprint：" + dv[8]
                                    + "\n区域：" + where + "\nCSC：" + (csc == null ? "—" : csc)
                                    + "\n需要软重启，是否确认？",
                            "brand: " + dv[2]
                                    + "\nmanufacturer: " + dv[1]
                                    + "\nmodel: " + dv[3]
                                    + "\ndevice: " + dv[4]
                                    + "\nproduct: " + dv[5]
                                    + "\nhardware: " + dv[6]
                                    + "\nboard: " + dv[7]
                                    + "\nrelease: " + dv[11]
                                    + "\nSDK: " + dv[12]
                                    + "\nbuild ID: " + dv[9]
                                    + "\nincremental: " + dv[10]
                                    + "\nsecurity patch: " + dv[13]
                                    + "\nfingerprint: " + dv[8]
                                    + "\nregion: " + where + "\nCSC: " + (csc == null ? "—" : csc)
                                    + "\nsoft reboot required. Continue?"))
                    .setPositiveButton(Lang.t("确认软重启", "reboot now"),
                            (confirm, button) -> {
                                persona.putCustom(user, row, aid, seed);
                                renderScreen.run();
                                syncSystemThenPublish(dv, region,
                                        PersonaPanel.this::rebootZygote);
                            })
                    .setNegativeButton(Lang.t("取消", "cancel"), null)
                    .show();
        }));
        dialog.show();
    }

    private android.widget.EditText customField(android.widget.LinearLayout form,
            String label, String value) {
        android.widget.TextView caption = new android.widget.TextView(this);
        caption.setText(label);
        caption.setTextSize(11);
        caption.setTextColor(0x66DDE4EE);
        caption.setPadding(0, dp(this, 6), 0, 0);
        form.addView(caption);
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setText(value == null ? "" : value);
        form.addView(field);
        return field;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
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
        new Thread(() -> RootShell.run("setprop ctl.restart zygote", 512, 10000L),
                "model-reboot").start();
    }

    private void editAid() {
        if (!hooksForInjection()) return;
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) return;
        final String override = persona.aid(user);
        android.widget.EditText input = edit(override != null ? override : liveAid());
        input.setHint(liveAid());
        new android.app.AlertDialog.Builder(this)
                .setTitle("AID")
                .setView(input)
                .setPositiveButton(Lang.t("保存", "save", "保存"),
                        (dialog, which) -> {
                            String value = input.getText().toString().trim().toLowerCase();
                            if (!value.matches("[0-9a-f]{16}")) {
                                showRootHint(Lang.t("AID 需要 16 位 hex",
                                        "AID must be 16 hex characters",
                                        "AID は 16 桁の hex"));
                                return;
                            }
                            persona.put(user, persona.device(user), value, persona.seed(user));
                            persona.markAidOverride(user);
                            renderScreen.run();
                            publishPersona();
                        })
                .setNeutralButton(Lang.t("用本机值", "use device id", "実機値を使う"),
                        (dialog, which) -> {
                            persona.clearAid(user);
                            persona.clearAidOverride(user);
                            renderScreen.run();
                        })
                .setNegativeButton(Lang.t("取消", "cancel", "取消"), null)
                .show();
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
        String ov = persona.carrier(currentUser.get());
        if (ov != null && !ov.isEmpty()) return ov;
        String[] n = netFor(r.iso);
        if (n != null) return n[2];
        return r.carriers != null && !r.carriers.isEmpty()
                ? r.carriers.split("/")[0] : "";
    }

    /** Effective operator code: persona override, else curated, else first MCC + 01. */
    private String currentOp(Regions.R r) {
        String ov = persona.op(currentUser.get());
        if (ov != null && ov.matches("\\d{5,6}")) return ov;
        String[] n = netFor(r.iso);
        return n != null ? n[1] : r.mcc.split("/")[0] + "01";
    }

    /** Runs a root-channel write on a worker, then reports and refreshes the rows. */
    private void sysWrite(final String script) {
        new Thread(() -> {
            final String out = RootShell.runCapture(script);
            // The property area changed; the render below reads the cache, not one getprop per row.
            DeviceSignals.refresh();
            ScreenUpdates.post(s -> {
                showActionResult(out.trim().isEmpty() ? "ok" : out);
                renderScreen.run();
            });
        }).start();
    }

    private void editMcc() {
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) return;
        Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        if (r == null) return;
        final String[] net = netFor(r.iso);
        // Values, not labels, drive the click: null opens the manual entry, "" clears the
        // per-space override so the region profile applies again.
        final java.util.List<String> values = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (net != null) { values.add(net[1]); names.add(net[1] + "  " + net[2]); }
        String fb = r.mcc.split("/")[0] + "01";
        if (!values.contains(fb)) { values.add(fb); names.add(fb); }
        values.add(null);
        names.add(Lang.t("手动…", "manual…", "手動…"));
        boolean overridden = persona.op(user) != null;
        if (overridden) {
            values.add("");
            names.add(Lang.t("地区默认（清除覆盖）", "region default (clear override)",
                    "地域既定（上書き解除）"));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("SIM MCC/MNC")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    String op = values.get(w);
                    if (op == null) { editMccManual(user, r); return; }
                    if (op.isEmpty()) persona.clearOp(user); else persona.putOp(user, op);
                    sysWrite(simScript(currentOp(r), r, null));
                    renderScreen.run();
                    publishPersona();
                }).show();
    }

    /** Timezone and UTC are one linked value: either cell opens the same IANA table. */
    private void bindTimeZoneEditor(LinearLayout row) {
        row.setClickable(true);
        row.setOnClickListener(v -> editTimeZone());
        for (int i = 1; i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            cell.setClickable(true);
            cell.setOnClickListener(v -> editTimeZone());
        }
    }

    private void bindLocationEditor(LinearLayout row) {
        row.setClickable(true);
        row.setOnClickListener(v -> openLocationPicker());
        for (int i = 1; i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            cell.setClickable(true);
            cell.setOnClickListener(v -> openLocationPicker());
        }
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
        LocationPickerDialog.show(this, region, regionPoint, persona, user, () -> {
            renderScreen.run();
            publishPersona();
        });
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

    /** Manual IANA timezone write; root and Shizuku share the same shell command. */
    private void editTimeZone() {
        if (!RootShell.shellReady()) {
            showRootHint(Lang.t("时区修改需要 root 或 Shizuku",
                    "timezone needs root or Shizuku",
                    "タイムゾーン変更には root または Shizuku が必要"));
            return;
        }
        final long now = System.currentTimeMillis();
        final java.util.List<String> all = new java.util.ArrayList<String>();
        final java.util.Map<String, String> ids =
                new java.util.HashMap<String, String>();
        final java.util.Map<String, Integer> offsets =
                new java.util.HashMap<String, Integer>();
        for (String id : java.util.TimeZone.getAvailableIDs()) {
            java.util.TimeZone tz = java.util.TimeZone.getTimeZone(id);
            int offset = tz.getOffset(now) / 60000;
            String label = "UTC" + Regions.utcFmt(offset) + "  " + id;
            all.add(label);
            ids.put(label, id);
            offsets.put(label, offset);
        }
        java.util.Collections.sort(all, (a, b) -> {
            int cmp = Integer.compare(offsets.get(a), offsets.get(b));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        final java.util.List<String> shown = new java.util.ArrayList<String>(all);
        final android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(this, 0, shown) {
                    @Override public View getView(int position, View convert,
                                                  android.view.ViewGroup parent) {
                        TextView row = convert instanceof TextView
                                ? (TextView) convert : txt("", 12, FG);
                        row.setText(getItem(position));
                        row.setTypeface(Typeface.MONOSPACE);
                        row.setPadding(dp(PersonaPanel.this, 12),
                                dp(PersonaPanel.this, 9),
                                dp(PersonaPanel.this, 12),
                                dp(PersonaPanel.this, 9));
                        return row;
                    }
                };
        android.widget.EditText search = edit("");
        search.setHint(Lang.t("点击搜索", "click to search", "検索をクリック"));
        android.widget.ListView list = new android.widget.ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(this, 420)));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(search);
        box.addView(list);
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("时区", "timezone", "タイムゾーン"))
                .setView(box)
                .setNegativeButton(Lang.t("取消", "cancel", "取消"), null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String zone = ids.get(shown.get(position));
            if (zone != null) {
                applyTimeZone(zone);
            }
            dialog.dismiss();
        });
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                shown.clear();
                for (String label : all) {
                    if (q.isEmpty()
                            || label.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                        shown.add(label);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });
        dialog.show();
    }

    private void applyTimeZone(final String zone) {
        new Thread(() -> {
            final String out = RootShell.setTimeZone(zone);
            final boolean ok = !RootShell.failed(out);
            ScreenUpdates.post(screen -> {
                if (ok) {
                    java.util.TimeZone.setDefault(
                            java.util.TimeZone.getTimeZone(zone));
                    RegionSelection.applyZone = zone;
                    if (getBaseContext() instanceof Dashboard) {
                        ((Dashboard) getBaseContext()).selectionZone(zone);
                    }
                    renderScreen.run();
                    publishPersona();
                } else {
                    showRootHint(Lang.t("时区写入失败", "timezone write failed",
                            "タイムゾーン書き込み失敗") + ": " + out);
                }
            });
        }, "cw-timezone").start();
    }

    private void editMccManual(final String user, final Regions.R r) {
        android.widget.EditText input = edit(persona.op(user));
        input.setHint("52501");
        new android.app.AlertDialog.Builder(this)
                .setTitle("SIM MCC/MNC")
                .setView(input)
                .setPositiveButton(Lang.t("保存", "save", "保存"),
                        (dialog, which) -> {
                            String value = input.getText().toString().trim();
                            if (!value.matches("\\d{5,6}")) {
                                showRootHint(Lang.t("MCC/MNC 需要 5-6 位数字",
                                        "MCC/MNC must be 5-6 digits",
                                        "MCC/MNC は 5-6 桁の数字"));
                                return;
                            }
                            persona.putOp(user, value);
                            sysWrite(simScript(value, r, null));
                            renderScreen.run();
                            publishPersona();
                        })
                .setNegativeButton(Lang.t("取消", "cancel", "取消"), null)
                .show();
    }

    /** gsm.sim.operator.* props + carrier-config + (best-effort) simop for the picked values. */
    private String simScript(String op, Regions.R r, String carrier) {
        String car = carrier != null ? carrier : currentCarrier(r);
        int sub = regionActions.activeSub();
        String name = com.walter.claudewilson.model.ShellQuote.quote(car);
        com.walter.claudewilson.model.Command cmd = com.walter.claudewilson.model.Command.sh()
                .raw("APK=$(pm path " + getPackageName() + " | cut -d: -f2)");
        // Property writes need uid 0; the carrier-config override works from a shell uid too.
        // Writing them unconditionally made a shell-channel tap report a setprop failure after the
        // override had already been accepted.
        if (RootShell.rootReady()) {
            cmd.raw("setprop gsm.sim.operator.numeric " + op
                            + " && setprop gsm.sim.operator.iso-country " + r.iso
                            + " && setprop gsm.sim.operator.alpha " + name)
                    // The visited-network trio the telemetry backend reads; kept in the same write
                    // so the two SIM surfaces cannot name different networks.
                    .raw("setprop gsm.operator.numeric " + op
                            + " && setprop gsm.operator.iso-country " + r.iso
                            + " && setprop gsm.operator.alpha " + name);
        }
        return cmd.raw(sub < 0 ? "echo no-sub"
                        : "app_process -Djava.class.path=$APK /system/bin "
                                + "com.walter.claudewilson.Bridge cc " + sub + " "
                                + r.iso + " " + name
                                + (RootShell.rootReady()
                                        ? "; app_process -Djava.class.path=$APK /system/bin "
                                                + "com.walter.claudewilson.Bridge simop " + sub + " "
                                                + op + " " + name
                                        : ""))
                .build();
    }

    private void editIso() {
        Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        if (r == null) return;
        sysWrite(simScript(currentOp(r), r, null));
    }

    private void editCarrier() {
        final String user = currentUser.get();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) return;
        Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        if (r == null) return;
        // Same contract as the MCC picker: a value applies, null opens manual entry, "" clears
        // the per-space override so switching regions moves the value again.
        final java.util.List<String> values = new java.util.ArrayList<>();
        if (r.carriers != null)
            for (String c : r.carriers.split("/"))
                if (!c.isEmpty() && values.stream().noneMatch(n -> n != null && n.equalsIgnoreCase(c)))
                    values.add(c);
        String[] net = netFor(r.iso);
        if (net != null && values.stream().noneMatch(n -> n != null && n.equalsIgnoreCase(net[2])))
            values.add(net[2]);
        values.add(null);
        boolean overridden = persona.carrier(user) != null;
        if (overridden) values.add("");
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (String v : values) {
            names.add(v == null ? Lang.t("手动…", "manual…", "手動…")
                    : v.isEmpty() ? Lang.t("地区默认（清除覆盖）", "region default (clear override)",
                            "地域既定（上書き解除）")
                    : v);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("SIM 运营商", "SIM carrier", "SIM キャリア"))
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String value = values.get(which);
                    if (value == null) { editCarrierManual(user, r); return; }
                    applyCarrier(user, r, value.isEmpty() ? null : value);
                }).show();
    }

    private void applyCarrier(String user, Regions.R r, String value) {
        // null means "no per-space override": drop it and let the region profile win, otherwise
        // every later region switch keeps re-applying this one carrier.
        if (value == null) persona.clearCarrier(user); else persona.putCarrier(user, value);
        sysWrite(simScript(currentOp(r), r, value));
        renderScreen.run();
        publishPersona();
    }

    private void editCarrierManual(final String user, final Regions.R r) {
        android.widget.EditText input = edit(persona.carrier(user));
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("SIM 运营商", "SIM carrier", "SIM キャリア"))
                .setView(input)
                .setPositiveButton(Lang.t("保存", "save", "保存"),
                        (dialog, which) -> {
                            String value = input.getText().toString().trim();
                            if (!RootShell.safe(value)) {
                                showRootHint(Lang.t("运营商名包含非法字符",
                                        "carrier name contains invalid characters",
                                        "キャリア名に不正文字"));
                                return;
                            }
                            applyCarrier(user, r, value);
                        })
                .setNegativeButton(Lang.t("取消", "cancel", "取消"), null)
                .show();
    }

    /**
     * Region-tag writes: the row's source decides what can move it. A tag taken from the
     * product/device name (e.g. {@code lineage_oriole}) shifts with a ro.product.*.name
     * rewrite plus a zygote restart — Build fields are frozen at zygote start, so prop
     * writes alone never reach Java readers. A dedicated region prop just takes the iso
     * (getprop-based readers see it live). A fingerprint-derived CSC (Samsung) moves
     * only with model override, and a device with no tag has nothing to aim at.
     */
    private void editCsc() {
        Regions.R r = regionIdx >= 0 ? Regions.ALL.get(regionIdx) : null;
        if (r == null) return;
        String[] hw = Regions.hwTag();
        String src = hw[0];
        String isoUp = r.iso.toUpperCase(java.util.Locale.ROOT);
        String rp = "RP=$(command -v resetprop || echo /data/adb/ksu/bin/resetprop); ";
        java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> scripts = new java.util.ArrayList<>();
        // "" = restore (forget the target), null = an action that does not move the target.
        final java.util.List<String> targets = new java.util.ArrayList<>();
        String value = cscValue(r);
        String tag = pickTag(r.iso);
        String targetTag = tag == null ? null : "_" + tag + "@"
                + (android.os.Build.BRAND == null ? "?"
                        : android.os.Build.BRAND.toLowerCase(java.util.Locale.ROOT));
        if (value != null) {
            // The title carries current → target, so the item itself stays short enough not to
            // break mid-word on a 1080-wide dialog.
            labels.add(Lang.t("改为 " + value + "（软重启）",
                    "change to " + value + " (soft reboot)",
                    value + " に変更（ソフト再起動）"));
            scripts.add(cscWriteScript(value));
            targets.add(value);
        }
        if (src.startsWith("ro.") || src.startsWith("persist.")) {
            String key = src.substring(0, src.indexOf('='));
            labels.add(Lang.t("只设置 " + key + " = " + isoUp + "（立即生效）",
                    "set only " + key + " = " + isoUp + " (takes effect now)",
                    key + " のみ設定 = " + isoUp));
            scripts.add(rp + "$RP " + key + " " + isoUp);
            targets.add(null);
        }
        // Only offer the vendor property when the device actually ships one; on builds where it
        // is empty the item would be a lever that moves nothing.
        String vendorRegion = DeviceSignals.getprop("ro.miui.region");
        if (vendorRegion != null && !vendorRegion.isEmpty()
                && !src.startsWith("ro.miui.region")) {
            labels.add(Lang.t("只设置小米区域属性 = " + isoUp + "（立即生效，标记不变）",
                    "set only the Xiaomi region property = " + isoUp + " (tag unchanged)",
                    "Xiaomi 地域属性のみ = " + isoUp + "（タグは不変）"));
            scripts.add(rp + "$RP ro.miui.region " + isoUp);
            targets.add(null);
        }
        labels.add(Lang.t("恢复本机原始标记（软重启）",
                "restore this device's original tag (soft reboot)",
                "実機の元のタグに戻す（ソフト再起動）"));
        scripts.add(rp + "[ -f /data/local/tmp/ClaudeWilson_pn.bak ] && { "
                + "for k in ro.product.name ro.product.system.name "
                + "ro.product.system_ext.name ro.product.product.name "
                + "ro.product.vendor.name ro.product.odm.name "
                + "ro.product.vendor_dlkm.name ro.product.odm_dlkm.name; do "
                + "[ -n \"$(getprop $k)\" ] && "
                + "$RP $k \"$(cat /data/local/tmp/ClaudeWilson_pn.bak)\"; done; "
                + "setprop ctl.restart zygote; } || echo no-backup");
        targets.add("");
        if (labels.size() <= 1) {
            showRootHint(Lang.t("该标记源自整机指纹，只能随机型伪装变更",
                    "the tag derives from the build fingerprint; it moves only with model override",
                    "タグはビルドフィンガープリント由来——機種上書きでのみ変更可"));
            return;
        }
        // AlertDialog shows either a message or the item list, not both, so the current/target
        // pair rides in the title and each item spells out its own cost.
        String title = value != null
                ? Lang.t("区域标记/CSC · " + src + " → " + targetTag,
                        "region tag/CSC · " + src + " → " + targetTag,
                        "地域タグ/CSC · " + src + " → " + targetTag)
                : Lang.t("区域标记/CSC · 当前 " + src,
                        "region tag/CSC · now " + src,
                        "地域タグ/CSC · 現在 " + src);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    String target = targets.get(w);
                    if (target == null) {
                        sysWrite(scripts.get(w));
                    } else if (target.isEmpty()) {
                        persona.clearCsc(currentUser.get());
                        sysWrite(scripts.get(w));
                    } else {
                        persona.putCsc(currentUser.get(), target);
                        sysWrite(scripts.get(w));
                    }
                })
                .show();
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
            android.telephony.SubscriptionManager sm =
                    (android.telephony.SubscriptionManager) context.getSystemService(
                            android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            int sub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            return sm == null || sub < 0 ? -1 : sm.getSlotIndex(sub);
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

    private static String spacedAid(String v) {
        return v != null && v.length() == 16 ? v.substring(0, 8) + " " + v.substring(8) : v;
    }

    /** Drop the comparison column when no region gives an expected value to compare against. */
    private LinearLayout row(boolean withExpected, String label, String exp, String act, int st) {
        if (Privacy.hidden() && Privacy.sensitivePersona(label)) {
            exp = Privacy.STARS;
            act = Privacy.STARS;
            st = -1;
        }
        if (st == 0) {
            mismatches++;
        }
        return withExpected ? row3(label, exp, act, st) : row2(label, act, st);
    }

    int mismatches() {
        return mismatches;
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

    private void addBullet(LinearLayout card, String text) {
        TextView t = txt("• " + text, 10, DIM);
        t.setPadding(0, dp(this, 2), 0, 0);
        card.addView(t);
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

}
