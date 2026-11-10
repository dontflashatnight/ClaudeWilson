package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Claude check-item catalogue derived from the Android analysis snapshot.
 *
 * <p>The analysis names the stores Claude itself writes: its installation device id, Segment,
 * Sift, Sentry, Firebase, GrowthBook, the datatransport metadata and the local cookies. This
 * catalogue is the 1:1 definition of those observations. Values are supplied by
 * {@code ClaudeRiskCollector}; a missing read keeps its reason instead of becoming an empty value.
 *
 * <p>The installed package may be newer than the analysed build, so no analysed identifier is
 * copied into the model. The model observes the device.
 */
public final class ClaudeChecklist {
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

    private ClaudeChecklist() {
    }

    /** All definitions, in screen order. */
    public static List<CheckItem> specs() {
        List<CheckItem> out = new ArrayList<CheckItem>();
        out.add(spec("device.id", IDENTITY, "shared_prefs/device_id_prefs.xml",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.growthbook.unit",
                IDENTITY, "files/global/global_bootstrap/cache.json",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.segment.anonymous", IDENTITY,
                "shared_prefs/analytics-android-*.xml",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.segment.user", IDENTITY,
                "shared_prefs/analytics-android-*.xml",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.segment.device", IDENTITY,
                "shared_prefs/analytics-android-*.xml",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.sift.installation", IDENTITY, "files/INSTALLATION",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.sift.fingerprint", IDENTITY,
                "files/datastores/sift_queue.preferences_pb",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.sentry.installation", IDENTITY,
                "cache/sentry/*/.sentry-native/installation_id",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.datadog.anonymous", IDENTITY,
                "cache/datadog-*/datastore_v0/rum/anonymous_id_key",
                YES, "reset-local-identity", false, false, null));
        out.add(spec("sdk.firebase.fid", IDENTITY,
                "no_backup/PersistedInstallation.*.json",
                BOUNDARY, "cannot-change", true, false, null));

        out.add(spec("account.local_handle", ACCOUNT, "shared_prefs/app_prefs.xml",
                BOUNDARY, "cannot-change", true, true, null));
        out.add(spec("account.play_country", ACCOUNT, "billing:getBillingConfig",
                BOUNDARY, "cannot-change", true, true, null));
        out.add(spec("account.plan", ACCOUNT,
                "shared_prefs/app_prefs.xml last_subscription_level",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("account.trusted_device", ACCOUNT,
                "shared_prefs/trusted_device_*.xml",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("account.organization", ACCOUNT,
                "shared_prefs/analytics_state.xml identity_organization",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("account.subscription", ACCOUNT,
                "shared_prefs/analytics_state.xml identity_subscription_plan",
                BOUNDARY, "cannot-change", true, false, null));

        out.add(spec("integrity.play_token", INTEGRITY,
                "api.login Verify* play_integrity_token",
                BOUNDARY, "cannot-change", true, false, "not-local",
                CheckItem.Coverage.SERVER_ONLY));
        out.add(spec("integrity.client_attestation", INTEGRITY,
                "api ClientAttestation",
                BOUNDARY, "cannot-change", true, false, "not-local",
                CheckItem.Coverage.SERVER_ONLY));
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
        out.add(spec("integrity.play_store", INTEGRITY,
                "PackageManager com.android.vending",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("integrity.gms", INTEGRITY,
                "PackageManager com.google.android.gms",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("integrity.tls", INTEGRITY,
                "platform trust store eMudhra G3B/G3C",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("integrity.installer", INTEGRITY,
                "PackageManager installSourceInfo",
                BOUNDARY, "cannot-change", true, false, null));

        out.add(spec("transport.cfuvid", TRANSPORT,
                "shared_prefs/user_cookies_*.xml",
                BOUNDARY, "cannot-change", true, false, null));
        out.add(spec("transport.session", TRANSPORT,
                "shared_prefs/user_cookies_*.xml sessionKeyV3",
                BOUNDARY, "cannot-change", true, false, null));
        // The datatransport DB is the queued Play Billing metadata, not a live read of the
        // current process. It is still the target's own last observation, so it stays visible;
        // a disagreement is a boundary, not a task the user can fix by editing one value.
        out.add(spec("transport.datatransport.country", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.locale", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.mcc", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.tz", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.fingerprint", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.build_parts", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.net_type", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.mobile_subtype", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));
        out.add(spec("transport.datatransport.os_build", TRANSPORT,
                "databases/com.google.android.datatransport.events",
                NO, "rerun-target", true, false, null));

        out.add(spec("target.state", TARGET, "pidof com.anthropic.claude",
                NO, "none", false, false, null));
        out.add(spec("target.version", TARGET,
                "shared_prefs/analytics_state.xml last_app_version/last_app_build",
                NO, "none", false, false, null));
        out.add(spec("target.payload.cc_hint", TARGET,
                "/data/local/tmp/claudewilson/claude.json", YES, "hook-payload",
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
        out.add(spec("target.su.paths", TARGET, "target self-check su paths (10)",
                NO, "hook-payload", false, false, "0/10"));
        out.add(spec("target.su.packages", TARGET, "target self-check root packages (17)",
                NO, "hook-payload", false, false, "0/17"));
        out.add(spec("target.su.properties", TARGET,
                "target self-check ro.debuggable/ro.secure",
                NO, "hook-payload", false, false, "clean"));
        out.add(spec("target.su.mounts", TARGET, "target self-check rw system dirs (7)",
                NO, "hook-payload", false, false, "0/7"));
        out.add(spec("target.network_view", TARGET,
                "target self-check ifaces/proxy", NO, "hook-payload", false, false, null));
        out.add(spec("target.network_addresses", TARGET,
                "target self-check non-loopback interface addresses",
                NO, "hook-payload", false, false, null));

        out.add(spec("system.locale", SYSTEM, "getprop persist.sys.locale",
                NO, "cannot-change", false, false, null));
        out.add(spec("system.product_locale", SYSTEM, "getprop ro.product.locale",
                NO, "cannot-change", false, false, null));
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
        out.add(spec("system.build_tags", SYSTEM, "getprop ro.build.tags",
                NO, "cannot-change", true, false, null));
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
                CheckConstraint.of("device.growthbook", "device.id",
                        CheckConstraint.Relation.EQUALS, "sdk.growthbook.unit"),
                CheckConstraint.of("segment.account", "sdk.segment.user",
                        CheckConstraint.Relation.CONTAINS, "account.local_handle"),
                CheckConstraint.of("transport.country", "transport.datatransport.country",
                        CheckConstraint.Relation.SAME_COUNTRY, "target.locale"),
                CheckConstraint.of("transport.language", "transport.datatransport.locale",
                        CheckConstraint.Relation.SAME_LANGUAGE, "target.locale"),
                CheckConstraint.of("transport.mcc", "transport.datatransport.mcc",
                        CheckConstraint.Relation.EQUALS, "target.simop"),
                CheckConstraint.of("transport.tz", "transport.datatransport.tz",
                        CheckConstraint.Relation.EQUALS, "system.tz_offset"),
                CheckConstraint.of("transport.fingerprint", "transport.datatransport.fingerprint",
                        CheckConstraint.Relation.CONTAINS, "target.build"),
                CheckConstraint.of("transport.fingerprint_build",
                        "transport.datatransport.fingerprint",
                        CheckConstraint.Relation.BUILD_FINGERPRINT,
                        "transport.datatransport.build_parts"),
                CheckConstraint.of("transport.build_parts",
                        "transport.datatransport.build_parts",
                        CheckConstraint.Relation.EQUALS, "target.build_parts"),
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
                CheckConstraint.of("system.product_language", "system.locale",
                        CheckConstraint.Relation.SAME_LANGUAGE, "system.product_locale"),
                CheckConstraint.of("account.play_country", "system.sim_iso",
                        CheckConstraint.Relation.SAME_COUNTRY, "account.play_country"),
                CheckConstraint.of("target.payload_sim", "system.sim_iso",
                        CheckConstraint.Relation.SAME_COUNTRY, "target.payload.cc_hint"));
    }

    /**
     * Build one observed Claude model.
     *
     * @param data   parsed target-data values; a missing key means the read did not produce a value
     * @param hook   target self-check key/value pairs
     * @param snapshot system facts
     * @param timezone current system time zone id
     * @param play   Billing country, or null / {@code err:*} when unavailable
     * @param live   true only when the target process is running and the check pid matches
     */
    public static RiskModel build(java.util.Map<String, String> data,
                                  java.util.Map<String, String> hook,
                                  Snapshot snapshot, String timezone, String play,
                                  boolean live) {
        List<CheckItem> out = new ArrayList<CheckItem>();
        out.add(value(item("device.id"), data.get("device_id")));
        out.add(value(item("sdk.growthbook.unit"), data.get("growthbook_unit")));
        out.add(value(item("sdk.segment.anonymous"), data.get("segment_anonymous")));
        out.add(segmentUser(item("sdk.segment.user"), data));
        out.add(value(item("sdk.segment.device"), data.get("segment_device")));
        out.add(value(item("sdk.sift.installation"), data.get("sift_installation")));
        out.add(value(item("sdk.sift.fingerprint"), data.get("sift_fingerprint")));
        out.add(value(item("sdk.sentry.installation"), data.get("sentry_installation")));
        out.add(value(item("sdk.datadog.anonymous"), data.get("datadog_anonymous")));
        out.add(value(item("sdk.firebase.fid"), data.get("firebase_fid")));

        out.add(value(item("account.local_handle"), data.get("account_handle")));
        out.add(value(item("account.play_country"), playCountry(play)));
        out.add(value(item("account.plan"), data.get("account_plan")));
        out.add(value(item("account.trusted_device"), data.get("trusted_device")));
        out.add(value(item("account.organization"), data.get("account_organization")));
        out.add(value(item("account.subscription"), data.get("account_subscription")));

        out.add(value(item("integrity.play_token"), "not-local"));
        out.add(value(item("integrity.client_attestation"), "not-local"));
        out.add(value(item("integrity.boot"), bootValue(snapshot)));
        out.add(value(item("integrity.selinux"), selinuxValue(snapshot)));
        out.add(value(item("integrity.strongbox"), strongBoxValue(snapshot)));
        out.add(value(item("integrity.attestation"), attestationValue(snapshot)));
        out.add(value(item("integrity.play_store"),
                snapshot.platform().packagesChecked()
                        ? (snapshot.platform().playStorePresent() ? "present" : "absent")
                        : null));
        out.add(value(item("integrity.gms"),
                snapshot.platform().packagesChecked()
                        ? (text(snapshot.platform().gmsVersion()) == null
                        ? "absent" : "present")
                        : null));
        out.add(value(item("integrity.tls"), data.get("tls_exposed")));
        out.add(value(item("integrity.installer"), data.get("installer")));

        out.add(value(item("transport.cfuvid"), count(data.get("cfuvid"))));
        out.add(value(item("transport.session"), count(data.get("session"))));
        out.add(value(item("transport.datatransport.country"),
                data.get("transport_country")));
        out.add(value(item("transport.datatransport.locale"),
                data.get("transport_locale")));
        out.add(value(item("transport.datatransport.mcc"), data.get("transport_mcc")));
        out.add(value(item("transport.datatransport.tz"), data.get("transport_tz")));
        out.add(value(item("transport.datatransport.fingerprint"),
                data.get("transport_fingerprint")));
        out.add(value(item("transport.datatransport.build_parts"),
                data.get("transport_build")));
        out.add(value(item("transport.datatransport.net_type"),
                data.get("transport_net_type")));
        out.add(value(item("transport.datatransport.mobile_subtype"),
                data.get("transport_mobile_subtype")));
        out.add(value(item("transport.datatransport.os_build"),
                data.get("transport_os_build")));

        out.add(value(item("target.state"), data.get("target_state")));
        out.add(value(item("target.version"), data.get("target_app")));
        out.add(value(item("target.payload.cc_hint"), data.get("payload_cc_hint")));
        String targetLocale = hook.get("res_locale");
        if (targetLocale == null || targetLocale.isEmpty()) {
            targetLocale = hook.get("locale");
        }
        String sysLocale = first(text(snapshot.prop("persist.sys.locale")),
                text(snapshot.prop("ro.product.locale")));
        String sysSimop = first(text(snapshot.sim().simOperator()),
                text(snapshot.prop("gsm.sim.operator.numeric")));
        String sysSimiso = first(text(snapshot.sim().simCountryIso()),
                text(snapshot.prop("gsm.sim.operator.iso-country")));
        String sysCarrier = first(text(snapshot.sim().carrierName()),
                text(snapshot.prop("gsm.sim.operator.alpha")));
        String sysBuild = build(snapshot.build().model(), snapshot.build().fingerprint());
        out.add(appNext(item("target.locale"), targetLocale,
                data.get("payload_locale"), data.get("app_locale"), sysLocale, live));
        out.add(systemNext(item("target.tz"), hook.get("tz"),
                data.get("payload_tz"), timezone, live));
        out.add(systemNext(item("target.simop"), hook.get("simop"),
                data.get("payload_simop"), sysSimop, live));
        out.add(systemNext(item("target.simiso"), hook.get("simiso"),
                data.get("payload_simiso"), sysSimiso, live));
        out.add(systemNext(item("target.carrier"), hook.get("simname"),
                data.get("payload_simname"), sysCarrier, live));
        out.add(systemNext(item("target.aid"), hook.get("aid"),
                data.get("payload_aid"), text(snapshot.androidId()), live));
        out.add(systemNext(item("target.build"), build(hook.get("model"), hook.get("fp")),
                data.get("payload_build"), sysBuild, live));
        // A self-check written by an older module has no detailed evidence fields. Treat it as
        // stale instead of claiming the target's file was unreadable.
        boolean detailed = "2".equals(hook.get("selfcheck"));
        out.add(systemNext(item("target.build_parts"), buildParts(hook),
                data.get("payload_build_parts"), data.get("build_parts"), live && detailed));
        out.add(hook(item("target.su.paths"), suCount(hook.get("su_paths")), live && detailed));
        out.add(hook(item("target.su.packages"), suCount(hook.get("su_packages")),
                live && detailed));
        out.add(hook(item("target.su.properties"), suProps(hook.get("su_props")),
                live && detailed));
        out.add(hook(item("target.su.mounts"), suCount(hook.get("su_mounts")),
                live && detailed));
        out.add(networkView(item("target.network_view"), hook, live));
        out.add(hook(item("target.network_addresses"), hook.get("iface_ips"), live && detailed));

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
        out.add(value(item("system.build_tags"), text(snapshot.prop("ro.build.tags"))));
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
        out.add(value(item("location.mode"), locationMode(snapshot)));
        out.add(value(item("location.provider"), locationProviders(snapshot)));
        out.add(value(item("location.appops"), locationAppOps(snapshot)));
        out.add(value(item("location.mock"), locationMock(snapshot)));
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

    /**
     * Segment writes userId only after the target has an account. A readable prefs file without
     * that key is a real no-record state, not an unreadable file; a missing file stays unreadable.
     */
    private static CheckItem segmentUser(CheckItem spec, java.util.Map<String, String> data) {
        String raw = data.get("segment_user");
        if (raw != null) {
            return spec.withValue(raw);
        }
        return "1".equals(data.get("segment_user_absent"))
                ? spec.unreadable().withCoverage(CheckItem.Coverage.NO_RECORD)
                : spec.unreadable();
    }

    private static CheckItem hook(CheckItem spec, String raw, boolean live) {
        if (raw == null) {
            // A missing self-check key while the target is stopped is a stale target view, not
            // a file that exists but cannot be read.
            return live ? spec.unreadable() : spec.stale();
        }
        CheckItem item = spec.withValue(raw);
        return live ? item : item.stale();
    }

    /**
     * Live self-check first; otherwise the published hook payload is the value the target reads
     * on its next launch. Only when neither exists does the row become stale.
     */
    private static CheckItem next(CheckItem spec, String observed, String preview, boolean live) {
        if (live && observed != null) {
            return spec.withValue(observed);
        }
        if (preview != null && !preview.isEmpty()) {
            return spec.withValue(preview).withNote("next-launch");
        }
        return live ? spec.unreadable() : spec.stale();
    }

    /** System per-app locale, when the non-root channel wrote it, outranks the global locale. */
    private static CheckItem appNext(CheckItem spec, String observed, String payload,
                                     String appLocale, String system, boolean live) {
        if (live && observed != null) {
            return spec.withValue(observed);
        }
        if (payload != null && !payload.isEmpty()) {
            return spec.withValue(payload).withNote("next-launch");
        }
        if (appLocale != null && !appLocale.isEmpty()) {
            return spec.withValue(appLocale).withNote("app-locale");
        }
        if (system != null && !system.isEmpty()) {
            return spec.withValue(system).withNote("system-fallback");
        }
        return live ? spec.unreadable() : spec.stale();
    }

    /** Payload is a stronger preview than the system fallback; label each separately. */
    private static CheckItem systemNext(CheckItem spec, String observed, String payload,
                                        String system, boolean live) {
        if (live && observed != null) {
            return spec.withValue(observed);
        }
        if (payload != null && !payload.isEmpty()) {
            return spec.withValue(payload).withNote("next-launch");
        }
        if (system != null && !system.isEmpty()) {
            return spec.withValue(system).withNote("system-fallback");
        }
        return live ? spec.unreadable() : spec.stale();
    }

    /** The Build identity the target process sees, in one comparable tuple. */
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

    /** Root-evidence counts keep the denominator so a partial hit is never read as clean. */
    private static String suCount(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("err")) {
            return null;
        }
        return raw;
    }

    private static String suProps(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("err")) {
            return null;
        }
        return raw;
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

    /** Raw UTC offset in seconds, the unit the datatransport {@code tz-offset} uses. */
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

    /** Cookie presence only: the value itself is a session token and never enters the model. */
    private static String count(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim()) > 0 ? "present" : "absent";
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
