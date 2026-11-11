package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The ChatGPT check-item catalogue derived from the Android risk analysis.
 *
 * <p>This class is the 1:1 definition: every item names the channel that observes it and the
 * path the target reads. Values are supplied by the collector; the catalogue never substitutes
 * a "typical" value for a missing read. Cross-item relations live in {@link #constraints()} and
 * are evaluated by {@link RiskModel}.
 *
 * <p>The analysis snapshot is 1.2026.272; the installed package may be newer. That is why the
 * model observes the device instead of copying the analysed values.
 */
public final class ChatGptChecklist {
    public static final String IDENTITY = "identity";
    public static final String ACCOUNT = "account";
    public static final String INTEGRITY = "integrity";
    public static final String TRANSPORT = "transport";
    public static final String TARGET = "target";
    public static final String SYSTEM = "system";
    public static final String PERMISSION = "permission";
    public static final String LOCATION = "location";

    private static final CheckItem.Writable YES = CheckItem.Writable.YES;
    private static final CheckItem.Writable NO = CheckItem.Writable.NO;
    private static final CheckItem.Writable BOUNDARY = CheckItem.Writable.BOUNDARY;

    private ChatGptChecklist() {
    }

    /** All definitions, in screen order. */
    public static List<CheckItem> specs() {
        List<CheckItem> out = new ArrayList<CheckItem>();
        out.add(spec("device.uuid", IDENTITY, "files/datastore/global_device_uuid.json",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("device.seeded", IDENTITY, "files/datastore/global_app_settings.json",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.segment.anonymous", IDENTITY,
                "shared_prefs/analytics-android-oai.xml", YES, "reset-local-identity",
                false, false, null));
        out.add(spec("sdk.segment.user", IDENTITY,
                "shared_prefs/analytics-android-oai.xml", YES, "reset-local-identity",
                false, false, null));
        // Segment generates device.id on its own: a Widevine-derived device id when available,
        // otherwise a random UUID fallback. It is not a copy of anonymousId, so the two values are
        // independent observations, not a pair that must match.
        out.add(spec("sdk.segment.device", IDENTITY,
                "shared_prefs/analytics-android-oai.xml", YES, "reset-local-identity",
                false, false, null));
        out.add(spec("sdk.statsig.stable", IDENTITY,
                "no_backup/statsig_event_queue_*.db", YES, "reset-local-identity",
                false, false, null));
        out.add(spec("sdk.statsig.locale", IDENTITY,
                "no_backup/statsig_event_queue_*.db", NO, "rerun-target",
                false, false, null));
        out.add(spec("sdk.sentry.installation", IDENTITY, "files/INSTALLATION",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.sentry.device_tag", IDENTITY,
                "cache/sentry/*/.scope-cache/tags.json", NO, "rerun-target",
                false, false, null));
        out.add(spec("sdk.datadog.anonymous", IDENTITY,
                "cache/datadog-*/datastore_v0/rum/anonymous_id_key", YES,
                "reset-local-identity", false, false, null));
        out.add(spec("sdk.datadog.play_country", IDENTITY,
                "cache/datadog-*/logs-v2/*", NO, "rerun-target", false, false, null,
                CheckItem.Coverage.NO_RECORD));
        out.add(spec("sdk.firebase.fid", IDENTITY,
                "files/PersistedInstallation.*.json", BOUNDARY, "cannot-change",
                true, false, null));
        out.add(spec("sdk.firebase.country", IDENTITY,
                "databases/com.google.android.datatransport.events", NO, "rerun-target",
                false, false, null));
        out.add(spec("sdk.firebase.locale", IDENTITY,
                "databases/com.google.android.datatransport.events", NO, "rerun-target",
                false, false, null));
        out.add(spec("sdk.firebase.mcc", IDENTITY,
                "databases/com.google.android.datatransport.events", NO, "rerun-target",
                false, false, null));
        out.add(spec("sdk.firebase.tz", IDENTITY,
                "databases/com.google.android.datatransport.events", NO, "rerun-target",
                false, false, null));
        out.add(spec("sdk.firebase.fingerprint", IDENTITY,
                "databases/com.google.android.datatransport.events", NO, "rerun-target",
                false, false, null));
        out.add(spec("sdk.bitdrift", IDENTITY,
                "io.bitdrift.capture (APK only)", BOUNDARY, "cannot-change",
                true, false, "not-local"));

        out.add(spec("account.local_handle", ACCOUNT,
                "cache/sentry/*/.scope-cache/user.json", BOUNDARY, "cannot-change",
                true, false, null));
        out.add(spec("account.play_country", ACCOUNT, "billing:getBillingConfig",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("account.age_days", ACCOUNT, "Statsig user.days_since_user_creation",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("account.subscription", ACCOUNT, "Statsig user subscription fields",
                BOUNDARY, "cannot-change", true, false, null));

        out.add(spec("integrity.play_prefix", INTEGRITY,
                "files/network/shared-cookies/global_cookie_storage.json",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("integrity.play_age", INTEGRITY,
                "files/network/shared-cookies/global_cookie_storage.json",
                BOUNDARY, "cannot-change", true, false, null));
        // hCaptcha tokens live in memory only; there is no persisted record to read.
        out.add(spec("integrity.hcaptcha", INTEGRITY, "hcaptcha token store",
                BOUNDARY, "cannot-change", true, true, null,
                CheckItem.Coverage.NO_RECORD));
        out.add(spec("integrity.verdict", INTEGRITY, "server-side Play Integrity verdict",
                BOUNDARY, "cannot-change", true, false, "not-local"));
        out.add(spec("integrity.boot", INTEGRITY,
                "ro.boot.flash.locked / verifiedbootstate / vbmeta.device_state",
                NO, "cannot-change", true, false, null));
        out.add(spec("integrity.selinux", INTEGRITY,
                "selinuxfs / getenforce", NO, "cannot-change", true, false, null));
        out.add(spec("integrity.strongbox", INTEGRITY,
                "PackageManager FEATURE_STRONGBOX_KEYSTORE",
                NO, "cannot-change", true, false, null));
        out.add(spec("integrity.attestation", INTEGRITY,
                "AndroidKeyStore hardware attestation",
                NO, "cannot-change", true, false, null));
        out.add(spec("integrity.play_store", INTEGRITY, "PackageManager com.android.vending",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("integrity.codex_key", INTEGRITY, "Keystore/Codex device key",
                BOUNDARY, "cannot-change", true, false, "not-local"));

        out.add(spec("transport.cfuvid", TRANSPORT,
                "files/network/shared-cookies/global_cookie_storage.json",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("transport.cflb", TRANSPORT,
                "cookie storage __cflb", NO, "cannot-change", true, false, null,
                CheckItem.Coverage.NO_RECORD));
        out.add(spec("transport.oailb", TRANSPORT,
                "cookie storage __oailb", NO, "cannot-change", true, false, null,
                CheckItem.Coverage.NO_RECORD));
        out.add(spec("transport.oai_sc", TRANSPORT,
                "files/network/shared-cookies/global_cookie_storage.json",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("transport.conv_key", TRANSPORT,
                "files/network/shared-cookies/global_cookie_storage.json",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("transport.cf_ray", TRANSPORT,
                "segment/datadog network events", BOUNDARY, "cannot-change",
                true, false, null, CheckItem.Coverage.NO_RECORD));

        out.add(spec("target.state", TARGET, "pidof com.openai.chatgpt",
                NO, "none", false, false, null));
        out.add(spec("target.version", TARGET, "PackageManager versionName/versionCode",
                NO, "none", false, false, null));
        out.add(spec("target.payload.cc_hint", TARGET,
                "/data/local/tmp/claudewilson/chatgpt.json", YES, "hook-payload",
                false, false, null));
        out.add(spec("target.locale", TARGET, "target self-check res_locale",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.tz", TARGET, "target self-check tz",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.simop", TARGET, "target self-check simop",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.simiso", TARGET, "target self-check simiso",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.carrier", TARGET, "target self-check simname",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.aid", TARGET, "target self-check aid",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.build", TARGET, "target self-check model+fp",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.build_parts", TARGET,
                "target self-check Build manufacturer/device/product/hardware/model",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.su", TARGET, "target self-check su_file/su_os/su_system",
                NO, "hook-payload", false, false,
                "file=hidden,os=hidden,system=hidden"));
        out.add(spec("target.network_view", TARGET,
                "target self-check ifaces/proxy", NO, "hook-payload", false, false,
                "clean"));
        out.add(spec("target.network_addresses", TARGET,
                "target self-check non-loopback interface addresses",
                NO, "hook-payload", false, false, null));
        out.add(spec("target.data.country_hint", TARGET,
                "files/datastore/global_phone_country_codes.json",
                YES, "edit-target-data", false, false, null));

        out.add(spec("system.locale", SYSTEM, "getprop persist.sys.locale",
                NO, "app-locale-only", false, false, null));
        out.add(spec("system.product_locale", SYSTEM, "getprop ro.product.locale",
                NO, "hook-only", false, false, null));
        out.add(spec("system.sales_code", SYSTEM, "getprop ro.csc.sales_code",
                NO, "cannot-change", false, false, null));
        out.add(spec("system.timezone", SYSTEM, "TimeZone.getDefault",
                YES, "region-apply", false, false, null));
        out.add(spec("system.tz_offset", SYSTEM, "TimeZone.getDefault().getRawOffset",
                NO, "region-apply", false, false, null));
        out.add(spec("system.android_id", SYSTEM, "Settings.Secure ANDROID_ID",
                NO, "cannot-change", false, false, null));
        out.add(spec("system.sim_operator", SYSTEM, "gsm.sim.operator.numeric",
                YES, "region-apply", false, false, null));
        out.add(spec("system.sim_iso", SYSTEM, "gsm.sim.operator.iso-country",
                YES, "region-apply", false, false, null));
        out.add(spec("system.network_iso", SYSTEM, "gsm.operator.iso-country",
                NO, "region-apply", false, false, null));
        out.add(spec("system.carrier", SYSTEM, "gsm.sim.operator.alpha",
                YES, "region-apply", false, false, null));
        out.add(spec("system.sim_carrier_id", SYSTEM,
                "SubscriptionInfo.getCarrierId", NO, "cannot-change", false, false, null));
        out.add(spec("system.build", SYSTEM, "Build.MODEL + Build.FINGERPRINT",
                NO, "cannot-change", false, false, null));
        out.add(spec("permission.location.fine", PERMISSION,
                "manifest + PackageManager + appops", NO, "cannot-change", true, false, null));
        out.add(spec("permission.location.coarse", PERMISSION,
                "manifest + PackageManager + appops", NO, "cannot-change", true, false, null));
        out.add(spec("permission.location.background", PERMISSION,
                "manifest ACCESS_BACKGROUND_LOCATION", NO, "cannot-change", true, false, null));
        out.add(spec("permission.calendar", PERMISSION,
                "manifest READ/WRITE_CALENDAR", NO, "cannot-change", true, false, null));
        out.add(spec("permission.microphone", PERMISSION,
                "manifest RECORD_AUDIO", NO, "cannot-change", true, false, null));
        out.add(spec("permission.health", PERMISSION,
                "manifest android.permission.health.*", NO, "cannot-change", true, false, null));
        out.add(spec("permission.screen_capture", PERMISSION,
                "manifest DETECT_SCREEN_CAPTURE", NO, "cannot-change", true, false, null));
        out.add(spec("permission.wifi", PERMISSION,
                "manifest NEARBY_WIFI_DEVICES", NO, "cannot-change", true, false, null));
        out.add(spec("permission.bluetooth_scan", PERMISSION,
                "manifest BLUETOOTH_SCAN", NO, "cannot-change", true, false, null));
        out.add(spec("permission.bluetooth_connect", PERMISSION,
                "manifest BLUETOOTH_CONNECT", NO, "cannot-change", true, false, null));
        out.add(spec("permission.activity_recognition", PERMISSION,
                "manifest ACTIVITY_RECOGNITION", NO, "cannot-change", true, false, null));
        out.add(spec("permission.body_sensors", PERMISSION,
                "manifest BODY_SENSORS", NO, "cannot-change", true, false, null));
        out.add(spec("location.mode", LOCATION,
                "Settings.Secure location_mode", NO, "cannot-change", false, false, null));
        out.add(spec("location.provider", LOCATION,
                "Settings.Secure location_providers_allowed", NO, "cannot-change", false, false,
                null));
        out.add(spec("location.appops", LOCATION,
                "cmd appops get <pkg> FINE/COARSE_LOCATION", NO, "cannot-change", true, false,
                null));
        out.add(spec("location.mock", LOCATION,
                "cmd appops get <pkg> MOCK_LOCATION", NO, "cannot-change", true, false, null));
        out.add(spec("location.last_request", LOCATION,
                "target hookcheck location_served/location_last", NO, "cannot-change", true,
                false, null));
        return Collections.unmodifiableList(out);
    }

    private static CheckItem spec(String id, String channel, String source,
                                  CheckItem.Writable writable, String writePath,
                                  boolean boundary, boolean sensitive, String expected) {
        return spec(id, channel, source, writable, writePath, boundary, sensitive, expected,
                CheckItem.Coverage.UNREADABLE);
    }

    private static CheckItem spec(String id, String channel, String source,
                                  CheckItem.Writable writable, String writePath,
                                  boolean boundary, boolean sensitive, String expected,
                                  CheckItem.Coverage blind) {
        return CheckItem.spec(id, channel, source, writable, writePath, boundary, sensitive,
                expected, blind);
    }

    public static List<CheckConstraint> constraints() {
        return Arrays.asList(
                CheckConstraint.of("device.seed", "device.uuid",
                        CheckConstraint.Relation.EQUALS, "device.seeded"),
                CheckConstraint.of("segment.user", "device.uuid",
                        CheckConstraint.Relation.CONTAINS, "sdk.segment.user"),
                CheckConstraint.of("statsig.stable", "device.uuid",
                        CheckConstraint.Relation.EQUALS, "sdk.statsig.stable"),
                CheckConstraint.of("sentry.device", "device.uuid",
                        CheckConstraint.Relation.EQUALS, "sdk.sentry.device_tag"),
                CheckConstraint.of("playintegrity.prefix", "device.uuid",
                        CheckConstraint.Relation.EQUALS, "integrity.play_prefix"),
                CheckConstraint.of("segment.anonymous.independent", "sdk.segment.anonymous",
                        CheckConstraint.Relation.NOT_EQUALS, "device.uuid"),
                CheckConstraint.of("statsig.locale", "system.locale",
                        CheckConstraint.Relation.SAME_LANGUAGE, "sdk.statsig.locale"),
                CheckConstraint.of("target.locale", "system.locale",
                        CheckConstraint.Relation.SAME_LANGUAGE, "target.locale"),
                CheckConstraint.of("target.tz", "system.timezone",
                        CheckConstraint.Relation.EQUALS, "target.tz"),
                CheckConstraint.of("target.simop", "system.sim_operator",
                        CheckConstraint.Relation.EQUALS, "target.simop"),
                CheckConstraint.of("target.simiso", "system.sim_iso",
                        CheckConstraint.Relation.SAME_COUNTRY, "target.simiso"),
                CheckConstraint.of("target.aid", "system.android_id",
                        CheckConstraint.Relation.EQUALS, "target.aid"),
                CheckConstraint.of("target.build", "system.build",
                        CheckConstraint.Relation.EQUALS, "target.build"),
                CheckConstraint.of("target.payload_hint", "target.data.country_hint",
                        CheckConstraint.Relation.SAME_COUNTRY, "target.payload.cc_hint"),
                CheckConstraint.of("target.payload_sim", "system.sim_iso",
                        CheckConstraint.Relation.SAME_COUNTRY, "target.payload.cc_hint"),
                CheckConstraint.of("system.product_language", "system.locale",
                        CheckConstraint.Relation.SAME_LANGUAGE, "system.product_locale"),
                CheckConstraint.of("account.play_country", "system.sim_iso",
                        CheckConstraint.Relation.SAME_COUNTRY, "account.play_country"),
                CheckConstraint.of("firebase.locale", "system.locale",
                        CheckConstraint.Relation.SAME_LANGUAGE, "sdk.firebase.locale"),
                CheckConstraint.of("firebase.mcc", "system.sim_operator",
                        CheckConstraint.Relation.EQUALS, "sdk.firebase.mcc"),
                CheckConstraint.of("firebase.tz", "system.tz_offset",
                        CheckConstraint.Relation.EQUALS, "sdk.firebase.tz"),
                CheckConstraint.of("firebase.fingerprint", "sdk.firebase.fingerprint",
                        CheckConstraint.Relation.CONTAINS, "system.build"),
                CheckConstraint.of("firebase.country", "system.sim_iso",
                        CheckConstraint.Relation.SAME_COUNTRY, "sdk.firebase.country"));
    }

    /**
     * Build one observed model.
     *
     * @param data   parsed target-data values; a missing key means the read did not produce a value
     * @param hook   target self-check key/value pairs
     * @param live   true only when the target process is running and the check pid matches
     * @param now    Unix seconds, used only to describe Play Integrity cookie age
     * @param play   Billing country, or null / {@code err:*} when unavailable
     */
    public static RiskModel build(java.util.Map<String, String> data,
                                  java.util.Map<String, String> hook,
                                  Snapshot snapshot, String timezone, String play,
                                  long now, boolean live) {
        List<CheckItem> out = new ArrayList<CheckItem>();
        out.add(value(item("device.uuid"), data.get("device_uuid")));
        out.add(value(item("device.seeded"), data.get("block_seed")));
        out.add(value(item("sdk.segment.anonymous"), data.get("segment_anonymous")));
        out.add(value(item("sdk.segment.user"), data.get("segment_user")));
        out.add(value(item("sdk.segment.device"), data.get("segment_device")));
        out.add(value(item("sdk.statsig.stable"), data.get("statsig_stable")));
        out.add(value(item("sdk.statsig.locale"), data.get("statsig_locale")));
        out.add(value(item("sdk.sentry.installation"), data.get("sentry_installation")));
        out.add(value(item("sdk.sentry.device_tag"), data.get("sentry_device")));
        out.add(value(item("sdk.datadog.anonymous"), data.get("datadog_anonymous")));
        out.add(value(item("sdk.datadog.play_country"),
                data.get("datadog_play_country")));
        out.add(storedValue(item("sdk.firebase.fid"), data, "firebase_fid"));
        out.add(storedValue(item("sdk.firebase.country"), data, "firebase_country"));
        out.add(storedValue(item("sdk.firebase.locale"), data, "firebase_locale"));
        out.add(storedValue(item("sdk.firebase.mcc"), data, "firebase_mcc"));
        out.add(storedValue(item("sdk.firebase.tz"), data, "firebase_tz"));
        out.add(storedValue(item("sdk.firebase.fingerprint"),
                data, "firebase_fingerprint"));
        out.add(value(item("sdk.bitdrift"), "not-local"));

        out.add(value(item("account.local_handle"), data.get("account_handle")));
        out.add(value(item("account.play_country"), playCountry(play)));
        out.add(value(item("account.age_days"), data.get("account_age")));
        out.add(value(item("account.subscription"), data.get("account_subscription")));

        String prefix = data.get("playintegrity_prefix");
        String issued = data.get("playintegrity_ts");
        out.add(value(item("integrity.play_prefix"), prefix));
        out.add(age(item("integrity.play_age"), issued, now));
        out.add(value(item("integrity.hcaptcha"), data.get("hcaptcha")));
        out.add(value(item("integrity.verdict"), "not-local"));
        out.add(value(item("integrity.boot"), bootValue(snapshot)));
        out.add(value(item("integrity.selinux"), selinuxValue(snapshot)));
        out.add(value(item("integrity.strongbox"), strongBoxValue(snapshot)));
        out.add(value(item("integrity.attestation"), attestationValue(snapshot)));
        out.add(value(item("integrity.play_store"),
                snapshot.platform().packagesChecked()
                        ? (snapshot.platform().playStorePresent() ? "present" : "absent")
                        : null));
        out.add(value(item("integrity.codex_key"), "not-local"));

        out.add(value(item("transport.cfuvid"), data.get("cfuvid")));
        out.add(value(item("transport.cflb"), data.get("cflb")));
        out.add(value(item("transport.oailb"), data.get("oailb")));
        out.add(value(item("transport.oai_sc"), data.get("oai_sc")));
        out.add(value(item("transport.conv_key"), data.get("conv_key")));
        out.add(value(item("transport.cf_ray"), data.get("cf_ray")));

        out.add(value(item("target.state"), data.get("target_state")));
        out.add(value(item("target.version"), data.get("target_app")));
        out.add(value(item("target.payload.cc_hint"), data.get("payload_cc_hint")));
        String targetLocale = hook.get("res_locale");
        if (targetLocale == null || targetLocale.isEmpty()) {
            targetLocale = hook.get("locale");
        }
        out.add(appNext(item("target.locale"), targetLocale, data.get("payload_locale"),
                data.get("app_locale"), live));
        out.add(next(item("target.tz"), hook.get("tz"), data.get("payload_tz"), live));
        out.add(next(item("target.simop"), hook.get("simop"), data.get("payload_simop"), live));
        out.add(next(item("target.simiso"), hook.get("simiso"),
                data.get("payload_simiso"), live));
        String carrierPreview = data.get("payload_simname");
        if (carrierPreview == null) {
            carrierPreview = text(snapshot.sim().carrierName());
        }
        out.add(next(item("target.carrier"), hook.get("simname"), carrierPreview, live));
        out.add(next(item("target.aid"), hook.get("aid"), data.get("payload_aid"), live));
        out.add(next(item("target.build"), build(hook.get("model"), hook.get("fp")),
                data.get("payload_build"), live));
        String partsPreview = data.get("payload_build_parts");
        if (partsPreview == null) {
            partsPreview = data.get("build_parts");
        }
        out.add(next(item("target.build_parts"), buildParts(hook), partsPreview, live));
        out.add(hook(item("target.su"), su(hook), live));
        out.add(networkView(item("target.network_view"), hook, live));
        out.add(hook(item("target.network_addresses"), hook.get("iface_ips"), live));
        out.add(value(item("target.data.country_hint"), data.get("country_hint")));

        out.add(value(item("system.locale"), text(snapshot.prop("persist.sys.locale"))));
        out.add(value(item("system.product_locale"),
                text(snapshot.prop("ro.product.locale"))));
        out.add(value(item("system.sales_code"), text(snapshot.prop("ro.csc.sales_code"))));
        out.add(value(item("system.timezone"), timezone));
        out.add(value(item("system.tz_offset"), tzOffset(timezone)));
        out.add(value(item("system.android_id"), text(snapshot.androidId())));
        out.add(value(item("system.sim_operator"),
                first(text(snapshot.sim().simOperator()), text(snapshot.prop(
                        "gsm.sim.operator.numeric")))));
        out.add(value(item("system.sim_iso"),
                first(text(snapshot.sim().simCountryIso()), text(snapshot.prop(
                        "gsm.sim.operator.iso-country")))));
        out.add(value(item("system.network_iso"),
                first(text(snapshot.sim().networkCountryIso()), text(snapshot.prop(
                        "gsm.operator.iso-country")))));
        out.add(value(item("system.carrier"),
                first(text(snapshot.sim().carrierName()), text(snapshot.prop(
                        "gsm.sim.operator.alpha")))));
        out.add(value(item("system.sim_carrier_id"), data.get("sim_carrier_id")));
        out.add(value(item("system.build"), build(snapshot.build().model(),
                snapshot.build().fingerprint())));
        out.add(value(item("permission.location.fine"),
                permission(snapshot, "location.fine")));
        out.add(value(item("permission.location.coarse"),
                permission(snapshot, "location.coarse")));
        out.add(value(item("permission.location.background"),
                permission(snapshot, "location.background")));
        out.add(value(item("permission.calendar"), permission(snapshot, "calendar")));
        out.add(value(item("permission.microphone"), permission(snapshot, "microphone")));
        out.add(value(item("permission.health"), permission(snapshot, "health")));
        out.add(value(item("permission.screen_capture"), permission(snapshot, "screen_capture")));
        out.add(value(item("permission.wifi"), permission(snapshot, "wifi")));
        out.add(value(item("permission.bluetooth_scan"),
                permission(snapshot, "bluetooth_scan")));
        out.add(value(item("permission.bluetooth_connect"),
                permission(snapshot, "bluetooth_connect")));
        out.add(value(item("permission.activity_recognition"),
                permission(snapshot, "activity_recognition")));
        out.add(value(item("permission.body_sensors"), permission(snapshot, "body_sensors")));
        out.add(value(item("location.mode"), locationMode(snapshot)));
        out.add(value(item("location.provider"), locationProviders(snapshot)));
        out.add(value(item("location.appops"), locationAppOps(snapshot)));
        out.add(value(item("location.mock"), locationMock(snapshot)));
        out.add(lastRequest(item("location.last_request"), hook, live));
        return RiskModel.evaluate(out, constraints());
    }

    private static String bootValue(Snapshot snapshot) {
        Text locked = snapshot.prop("ro.boot.flash.locked");
        Text boot = snapshot.prop("ro.boot.verifiedbootstate");
        Text device = snapshot.prop("ro.boot.vbmeta.device_state");
        Text verity = snapshot.prop("ro.boot.veritymode");
        Text warranty = snapshot.prop("ro.boot.warranty_bit");
        Text unlock = snapshot.prop("sys.oem_unlock_allowed");
        if (!locked.isReadable() && !boot.isReadable() && !device.isReadable()) {
            return null;
        }
        return "flash.locked=" + show(locked) + " vbstate=" + show(boot)
                + " device_state=" + show(device) + " verity=" + show(verity)
                + " warranty=" + show(warranty) + " oem_unlock=" + show(unlock);
    }

    private static String selinuxValue(Snapshot snapshot) {
        Text value = snapshot.selinuxEnforce();
        if (!value.isReadable() || value.isEmpty()) {
            return null;
        }
        return value.value();
    }

    private static String strongBoxValue(Snapshot snapshot) {
        if (!snapshot.platform().collected()) {
            return null;
        }
        return snapshot.platform().strongbox() ? "available" : "absent";
    }

    private static String attestationValue(Snapshot snapshot) {
        AttestationFacts facts = snapshot.platform().attestation();
        if (!facts.collected()) {
            return null;
        }
        if (!facts.available()) {
            return "unavailable";
        }
        return "level=" + facts.securityLevel()
                + " boot=" + facts.verifiedBoot()
                + " locked=" + (facts.rootOfTrust() ? facts.deviceLocked() : "not-provided")
                + " tee=" + facts.teeTags();
    }

    private static String show(Text value) {
        return value.isReadable() ? (value.isEmpty() ? "(empty)" : value.value()) : "?";
    }

    private static CheckItem item(String id) {
        for (CheckItem spec : specs()) {
            if (spec.id().equals(id)) {
                return spec;
            }
        }
        throw new IllegalArgumentException("unknown check item: " + id);
    }

    private static CheckItem value(CheckItem spec, String raw) {
        return raw == null ? spec.unreadable() : spec.withValue(raw);
    }

    /** Empty datatransport queue is no-record, not a read failure. */
    private static CheckItem storedValue(CheckItem spec, java.util.Map<String, String> data,
            String key) {
        String value = data.get(key);
        if (value != null) {
            return spec.withValue(value);
        }
        return "0".equals(data.get("firebase_readable"))
                ? spec.unreadable() : spec.withCoverage(CheckItem.Coverage.NO_RECORD);
    }

    private static String permission(Snapshot snapshot, String key) {
        PermissionFacts facts = snapshot.permissions();
        if (!facts.readable()) {
            return null;
        }
        switch (facts.state(key)) {
            case GRANTED:
                return "granted";
            case DENIED:
                return "denied";
            case NOT_REQUESTED:
                return "not-requested";
            case APPROXIMATE:
                return "approximate";
            default:
                return null;
        }
    }

    private static String locationMode(Snapshot snapshot) {
        return snapshot.location().readable() ? snapshot.location().modeLabel() : null;
    }

    private static String locationProviders(Snapshot snapshot) {
        if (!snapshot.location().readable()) {
            return null;
        }
        String providers = snapshot.location().providers();
        if (providers.isEmpty()) {
            return null;
        }
        return snapshot.location().mode() == LocationFacts.Mode.OFF
                ? providers + " (mode off)" : providers;
    }

    private static String locationAppOps(Snapshot snapshot) {
        PermissionFacts facts = snapshot.permissions();
        String fine = facts.appOp("location.fine");
        String coarse = facts.appOp("location.coarse");
        if (fine.isEmpty() && coarse.isEmpty()) {
            return null;
        }
        return "fine=" + (fine.isEmpty() ? "?" : fine)
                + " coarse=" + (coarse.isEmpty() ? "?" : coarse);
    }

    private static String locationMock(Snapshot snapshot) {
        return snapshot.location().mockKnown()
                ? (snapshot.location().mock() ? "mock" : "no-mock") : null;
    }

    private static CheckItem lastRequest(CheckItem spec, java.util.Map<String, String> hook,
            boolean live) {
        String served = hook.get("location_served");
        if (served == null) {
            return spec.unreadable();
        }
        String age = hook.get("location_last");
        String value = "served=" + served;
        if (age != null && !age.startsWith("-")) {
            value += " last=" + age + "ms";
        }
        CheckItem item = spec.withValue(value);
        if ("0".equals(served)) {
            item = item.withCoverage(CheckItem.Coverage.NO_RECORD);
        }
        return live ? item : item.stale();
    }

    private static CheckItem hook(CheckItem spec, String raw, boolean live) {
        if (raw == null) {
            // No self-check key while the target is stopped is a stale target view, not a
            // file that exists but cannot be read.
            return live ? spec.unreadable() : spec.stale();
        }
        CheckItem item = spec.withValue(raw);
        return live ? item : item.stale();
    }

    private static String buildParts(java.util.Map<String, String> hook) {
        String manufacturer = hook.get("manufacturer");
        String device = hook.get("device");
        String product = hook.get("product");
        String hardware = hook.get("hardware");
        String model = hook.get("model");
        if (manufacturer == null || device == null || product == null
                || hardware == null || model == null) {
            return null;
        }
        return manufacturer + "|" + device + "|" + product + "|" + hardware + "|" + model;
    }

    /** System per-app locale written by the non-root channel is the target's current locale. */
    private static CheckItem appNext(CheckItem spec, String observed, String preview,
                                     String appLocale, boolean live) {
        if (live && observed != null) {
            return spec.withValue(observed);
        }
        if (preview != null && !preview.isEmpty()) {
            return spec.withValue(preview).withNote("next-launch");
        }
        if (appLocale != null && !appLocale.isEmpty()) {
            return spec.withValue(appLocale).withNote("app-locale");
        }
        return live ? spec.unreadable() : spec.stale();
    }

    /** Live self-check first; otherwise the published payload is next launch's value. */
    private static CheckItem next(CheckItem spec, String observed, String preview, boolean live) {
        if (live && observed != null) {
            return spec.withValue(observed);
        }
        if (preview != null && !preview.isEmpty()) {
            return spec.withValue(preview).withNote("next-launch");
        }
        return live ? spec.unreadable() : spec.stale();
    }

    private static CheckItem age(CheckItem spec, String issued, long now) {
        if (issued == null) {
            return spec.unreadable();
        }
        long seconds;
        try {
            seconds = Long.parseLong(issued.trim());
        } catch (NumberFormatException e) {
            return spec.unreadable();
        }
        long age = now - seconds;
        if (age < 0) {
            return spec.withValue("clock-skew").withNote("timestamp-in-future");
        }
        long hours = age / 3600L;
        CheckItem item = spec.withValue(hours + "h");
        return hours > 24L ? item.withNote("expired") : item.withNote("fresh");
    }

    private static String su(java.util.Map<String, String> hook) {
        String file = hook.get("su_file");
        String os = hook.get("su_os");
        String system = hook.get("su_system");
        if (file == null || os == null || system == null) {
            return null;
        }
        return "file=" + file + ",os=" + os + ",system=" + system;
    }

    /** The target's own filtered interface/proxy view, not the control app's NIC table. */
    private static CheckItem networkView(CheckItem spec, java.util.Map<String, String> hook,
                                         boolean live) {
        String ifaces = hook.get("ifaces");
        String proxy = hook.get("proxy");
        if (ifaces == null || proxy == null) {
            return live ? spec.unreadable() : spec.stale();
        }
        boolean tunnel = false;
        for (String name : ifaces.split(",")) {
            String n = name.trim().toLowerCase(Locale.ROOT);
            if (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg")
                    || n.startsWith("tap") || n.startsWith("utun")) {
                tunnel = true;
                break;
            }
        }
        CheckItem item = spec.withValue(tunnel || !"none".equals(proxy) ? "dirty" : "clean");
        return live ? item : item.stale();
    }

    private static String build(String model, String fingerprint) {
        if (model == null || model.isEmpty() || fingerprint == null || fingerprint.isEmpty()) {
            return null;
        }
        return model + "|" + fingerprint;
    }

    private static String playCountry(String value) {
        if (value == null || value.startsWith("err") || value.isEmpty()) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /** Raw UTC offset in seconds, the unit Firebase's {@code tz-offset} metadata uses. */
    private static String tzOffset(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            return null;
        }
        return String.valueOf(TimeZone.getTimeZone(timezone).getRawOffset() / 1000);
    }

    private static String text(Text value) {
        return value != null && value.isReadable() ? value.value() : null;
    }

    private static String first(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b;
    }
}
