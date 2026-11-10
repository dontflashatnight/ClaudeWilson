package com.walter.claudewilson.selftest;

import com.walter.claudewilson.model.Account;
import com.walter.claudewilson.model.AttestationFacts;
import com.walter.claudewilson.model.BuildValues;
import com.walter.claudewilson.model.CheckConstraint;
import com.walter.claudewilson.model.CheckItem;
import com.walter.claudewilson.model.ChatGptChecklist;
import com.walter.claudewilson.model.ClaudeChecklist;
import com.walter.claudewilson.model.NetworkFacts;
import com.walter.claudewilson.model.PlatformFacts;
import com.walter.claudewilson.model.PersonaSynth;
import com.walter.claudewilson.model.TlsFacts;
import com.walter.claudewilson.model.UserSpaces;
import com.walter.claudewilson.model.Command;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.GeoTable;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.LocationFix;
import com.walter.claudewilson.model.LocationPayload;
import com.walter.claudewilson.model.LocationPlan;
import com.walter.claudewilson.model.Mount;
import com.walter.claudewilson.model.Outcome;
import com.walter.claudewilson.model.Output;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.RegionTags;
import com.walter.claudewilson.model.Report;
import com.walter.claudewilson.model.RiskModel;
import com.walter.claudewilson.model.RootArtifactPolicy;
import com.walter.claudewilson.model.RootScript;
import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.SimValues;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Tables;
import com.walter.claudewilson.model.TargetEvidence;
import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;
import com.walter.claudewilson.model.Text;
import com.walter.claudewilson.rule.AccountsProbe;
import com.walter.claudewilson.rule.AndroidIdProbe;
import com.walter.claudewilson.rule.AttestationProbe;
import com.walter.claudewilson.rule.BiometricProbe;
import com.walter.claudewilson.rule.BuildTagsProbe;
import com.walter.claudewilson.rule.EmulatorSignatureProbe;
import com.walter.claudewilson.rule.FingerprintCoherenceProbe;
import com.walter.claudewilson.rule.GmsProbe;
import com.walter.claudewilson.rule.HiddenApiProbe;
import com.walter.claudewilson.rule.IdentitySanityProbe;
import com.walter.claudewilson.rule.IntegrityVerdictProbe;
import com.walter.claudewilson.rule.InterfaceAddressProbe;
import com.walter.claudewilson.rule.MountStateProbe;
import com.walter.claudewilson.rule.PackageSuspicionProbe;
import com.walter.claudewilson.rule.PartitionFingerprintProbe;
import com.walter.claudewilson.rule.ProbeRegistry;
import com.walter.claudewilson.rule.PropCoherenceProbe;
import com.walter.claudewilson.rule.ProxyProbe;
import com.walter.claudewilson.rule.RestrictedPropsProbe;
import com.walter.claudewilson.rule.RootPackagesProbe;
import com.walter.claudewilson.rule.SelinuxProbe;
import com.walter.claudewilson.rule.SimCoherenceProbe;
import com.walter.claudewilson.rule.StrongBoxProbe;
import com.walter.claudewilson.rule.TargetIdentityProbe;
import com.walter.claudewilson.rule.TargetInstallProbe;
import com.walter.claudewilson.rule.TargetPlayIntegrityProbe;
import com.walter.claudewilson.rule.TargetShapeProbe;
import com.walter.claudewilson.rule.TargetSuViewProbe;
import com.walter.claudewilson.rule.TargetVpnViewProbe;
import com.walter.claudewilson.rule.TlsIssuerProbe;
import com.walter.claudewilson.rule.UserCaProbe;
import com.walter.claudewilson.rule.VerifiedBootProbe;
import com.walter.claudewilson.rule.VpnTunnelProbe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the rule layer against crafted snapshots.
 *
 * <p>This file imports no Android class and the runner compiles it without {@code android.jar},
 * so the decoupling is checked by the compiler rather than asserted in a document.
 */
public final class SelfTest {

    private static final String SAMSUNG_FINGERPRINT =
            "samsung/dm3qzhw/dm3q:14/UP1A.231005.007/S928BXXU4CXK1:user/release-keys";

    private static final List<String> FAILURES = new ArrayList<String>();
    private static int checks;

    private SelfTest() {
    }

    public static void main(String[] args) {
        readOnlyMountsPass();
        writableSystemMountFails();
        injectedSourceOnSystemFails();
        unreadableMountTableIsUnknown();

        testKeysFail();
        releaseTagsPass();

        coherentFingerprintPasses();
        samsungCscSuffixIsCoherent();
        fingerprintBrandMismatchFails();
        malformedFingerprintIsUnknown();
        missingBuildIsUnknown();

        agreeingPropsPass();
        propMismatchFails();
        unreadablePropsAreUnknown();

        // su is a target self-check fact (target.su-view); the control app's own root channel is
        // not evidence about the target and is deliberately not a probe.

        packagesFlaggedOnceWithAllReasons();
        unreadablePackagesAreUnknown();
        cleanPackageCensusPasses();

        coherentSimPasses();
        noSimIsInformational();
        carrierMismatchWarns();
        unknownOperatorIsUnknown();
        registryMetadataIsComplete();

        enforcingSelinuxPasses();
        permissiveSelinuxFails();
        unreadableSelinuxIsUnknown();

        physicalIdentityIsNotAnEmulator();
        emulatorSignatureFails();

        partitionFingerprintsAgree();
        partitionFingerprintMismatchFails();
        absentPartitionFingerprintsAreUnknown();

        foreignVendorInModelWarns();
        subBrandUnderParentPasses();
        syntheticVariantKeepsIdentity();
        syntheticVariantPassesCoherence();
        pixelIncrementalStaysNumeric();
        androidIdPasses();

        listedRootPackageFails();
        unlistedPackagesPass();
        installedPackagesUnreadableIsUnknown();
        restrictedPropsCollectedEmptyPasses();
        restrictedPropsReadableWarns();
        restrictedPropsNotCollectedIsUnknown();
        targetNotCollectedIsUnknown();
        targetMissingIsInformational();
        targetFromPlayStorePasses();
        targetSideloadFails();
        hiddenApiUnreadableIsUnknown();
        hiddenApiZeroFails();
        accountsUnreadableIsUnknown();
        googleAccountDumpParses();
        googleAccountsAreListed();
        nonGoogleAccountsAreIgnored();
        noGoogleAccountsWarns();
        userSpaceNamesParse();

        lockedBootPasses();
        unlockedBootFails();
        bootPropsNotCollectedIsUnknown();
        attestationPasses();
        attestationNotCollectedIsUnknown();
        attestationFailureWarns();
        strongboxAvailablePasses();
        strongboxAbsentIsInformational();
        biometricReadyPasses();
        biometricNotEnrolledFails();
        biometricNotCollectedIsUnknown();
        genuineGmsPasses();
        replacementGmsFails();
        patchedGmsWarns();
        integrityVerdictPasses();
        integrityVerdictUnlockedFails();
        integrityVerdictIncompleteWarns();

        leakedCaFails();
        cleanTrustStorePasses();
        trustStoreUnreadableIsUnknown();
        userCaPresentFails();
        noUserCaPasses();
        vpnTransportFails();
        tunnelInterfaceFails();
        noTunnelPasses();
        networkNotCollectedIsUnknown();
        proxySetWarns();
        proxyFromVpnPasses();
        noProxyPasses();
        interfaceListIsInformational();
        unreadableInterfaceTableIsNormal();
        regionTagsSplitByVendorAndGeneric();
        shellQuoteEscapesAndValidates();
        commandBuildsFailureMarkedSteps();
        outcomeReadsMarkers();
        suCommandWrapsAndReportsStatus();
        rootScriptsStayParameterisedAndBounded();
        boundedOutputKeepsTheVerdict();

        unknownNeverAggregatesToPass();
        duplicateProbeIdsRejected();
        emptySnapshotYieldsNoPass();
        tablesParseCommentsAndPairs();
        targetRegistryIsDataDriven();
        targetConfigPathsAreDistinct();
        targetInventoryIsInformational();
        targetIntegrityBoundaryIsNeverAPass();
        targetVpnViewIsInformational();
        targetSuViewIsSeparate();
        rootArtifactPolicyIsNarrow();
        checkItemUnreadableIsUnknown();
        checkConstraintSemantics();
        riskModelConflictOutranksBoundary();
        chatGptChecklistCoversAnalysisChains();
        chatGptChecklistBuildsKnownModel();
        claudeChecklistCoversAnalysisChains();
        claudeChecklistBuildsKnownModel();
        claudeChecklistDetectsIdentitySplit();
        claudeChecklistDetectsBuildSplit();
        claudeChecklistDetectsFingerprintBuildSplit();
        claudeNextLaunchUsesPayload();
        claudeChecklistFlagsRootEvidence();
        claudeStoppedTargetIsStale();
        claudeMissingSegmentUserIsNoRecord();

        geoTableResolvesZoneAndCentre();
        locationPayloadIsSelfContained();
        locationPlanStaysInsideItsRadius();
        coarseLocationSnapsAndDropsMotion();
        locationPlanRejectsImpossibleCoordinates();

        report();
    }

    // ---- mount table -------------------------------------------------------------------

    private static void readOnlyMountsPass() {
        Snapshot snapshot = base()
                .mounts(Arrays.asList(
                        mount("/dev/block/dm-1", "/system", "ext4", "ro,seclabel"),
                        mount("/dev/block/dm-15", "/data", "f2fs", "rw,seclabel")), true)
                .build();
        expect("read-only system mounts", new MountStateProbe().run(snapshot),
                Status.PASS, Reason.OK);
    }

    private static void writableSystemMountFails() {
        Snapshot snapshot = base()
                .mounts(Arrays.asList(mount("/dev/block/dm-1", "/system", "ext4", "rw,seclabel")),
                        true)
                .build();
        expect("writable /system", new MountStateProbe().run(snapshot), Status.FAIL, Reason.PRESENT);
    }

    private static void injectedSourceOnSystemFails() {
        Snapshot snapshot = base()
                .mounts(Arrays.asList(mount("overlay", "/system/lib64", "overlay", "ro")), true)
                .build();
        expect("overlay on system partition", new MountStateProbe().run(snapshot),
                Status.FAIL, Reason.PRESENT);
    }

    /** The regression this whole layer exists for: no answer must not read as a good answer. */
    private static void unreadableMountTableIsUnknown() {
        Snapshot snapshot = base()
                .mounts(new ArrayList<Mount>(), false)
                .build();
        expect("unreadable mount table", new MountStateProbe().run(snapshot),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    // ---- build tags --------------------------------------------------------------------

    private static void testKeysFail() {
        Snapshot snapshot = base().buildValues(samsung().toBuilder().tags("test-keys").build()).build();
        expect("test-keys build", new BuildTagsProbe().run(snapshot), Status.FAIL, Reason.TEST_KEYS);
    }

    private static void releaseTagsPass() {
        expect("release-keys build", new BuildTagsProbe().run(base().build()),
                Status.PASS, Reason.OK);
    }

    // ---- fingerprint -------------------------------------------------------------------

    private static void coherentFingerprintPasses() {
        expect("coherent fingerprint", new FingerprintCoherenceProbe().run(base().build()),
                Status.PASS, Reason.OK);
    }

    private static void samsungCscSuffixIsCoherent() {
        String fingerprint =
                "samsung/dm3qzhw/dm3q:14/UP1A.231005.007/S928BXXU4CXK1_CSC:user/release-keys";
        Snapshot snapshot = base()
                .buildValues(samsung().toBuilder().fingerprint(fingerprint).build())
                .build();
        expect("samsung CSC suffix is not a mismatch",
                new FingerprintCoherenceProbe().run(snapshot), Status.PASS, Reason.OK);
    }

    private static void fingerprintBrandMismatchFails() {
        Snapshot snapshot = base()
                .buildValues(samsung().toBuilder().fingerprint(
                        "google/husky/husky:14/AP1A.240505.005/1234567:user/release-keys").build())
                .build();
        expect("brand rewritten in one layer", new FingerprintCoherenceProbe().run(snapshot),
                Status.FAIL, Reason.INCONSISTENT);
    }

    private static void malformedFingerprintIsUnknown() {
        Snapshot snapshot = base()
                .buildValues(samsung().toBuilder().fingerprint("not-a-fingerprint").build())
                .build();
        expect("unparseable fingerprint", new FingerprintCoherenceProbe().run(snapshot),
                Status.UNKNOWN, Reason.UNPARSEABLE);
    }

    private static void missingBuildIsUnknown() {
        Snapshot snapshot = Snapshot.builder().tables(catalog()).build();
        expect("no build data at all", new FingerprintCoherenceProbe().run(snapshot),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    // ---- framework vs native properties ------------------------------------------------

    private static void agreeingPropsPass() {
        Snapshot snapshot = base()
                .prop("ro.product.model", Text.of("SM-S928B"))
                .prop("ro.product.brand", Text.of("samsung"))
                .build();
        expect("framework and native agree", new PropCoherenceProbe().run(snapshot),
                Status.PASS, Reason.OK);
    }

    private static void propMismatchFails() {
        Snapshot snapshot = base()
                .prop("ro.product.model", Text.of("Pixel 8 Pro"))
                .build();
        expect("model rewritten in framework only", new PropCoherenceProbe().run(snapshot),
                Status.FAIL, Reason.INCONSISTENT);
    }

    private static void unreadablePropsAreUnknown() {
        Snapshot snapshot = base()
                .prop("ro.product.model", Text.unreadable())
                .prop("ro.product.brand", Text.of(""))
                .build();
        expect("no readable native properties", new PropCoherenceProbe().run(snapshot),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    // ---- package census ----------------------------------------------------------------

    private static void packagesFlaggedOnceWithAllReasons() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("com.example.magisk.helper", "Root Master");
        Snapshot snapshot = base()
                .packages(Arrays.asList("com.example.magisk.helper", "com.example.shopping"),
                        labels, true)
                .build();
        Finding finding = new PackageSuspicionProbe().run(snapshot);
        expect("suspicious package", finding, Status.FAIL, Reason.SUSPICIOUS);
        check("suspicious package counted once", "1".equals(finding.detail().get("flagged")),
                "flagged=" + finding.detail().get("flagged"));
        check("both reasons kept", finding.detail().get("reasons").contains("name:magisk")
                        && finding.detail().get("reasons").contains("label:root"),
                finding.detail().get("reasons"));
    }

    private static void unreadablePackagesAreUnknown() {
        Snapshot snapshot = base()
                .packages(new ArrayList<String>(), new LinkedHashMap<String, String>(), false)
                .build();
        expect("package list unreadable", new PackageSuspicionProbe().run(snapshot),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    /** An empty census is a clean layer, not an unobserved one: it used to be INFO (grey). */
    private static void cleanPackageCensusPasses() {
        Snapshot snapshot = base()
                .packages(Arrays.asList("com.example.shopping", "com.example.notes"),
                        new LinkedHashMap<String, String>(), true)
                .build();
        expect("no flagged packages", new PackageSuspicionProbe().run(snapshot),
                Status.PASS, Reason.ABSENT);
    }

    // ---- sim ---------------------------------------------------------------------------

    private static void coherentSimPasses() {
        Snapshot snapshot = base()
                .sim(new SimValues(Text.of("45005"), Text.of("kr"), Text.of("kr"),
                        Text.of("SKT"), "kr", 9, 1))
                .build();
        expect("coherent sim", new SimCoherenceProbe().run(snapshot), Status.PASS, Reason.OK);
    }

    private static void noSimIsInformational() {
        Snapshot snapshot = base()
                .sim(new SimValues(Text.of(""), Text.of(""), Text.of(""), Text.of(""), "kr", 9, 0))
                .build();
        expect("no sim", new SimCoherenceProbe().run(snapshot), Status.INFO, Reason.NO_SIM);
    }

    private static void carrierMismatchWarns() {
        Snapshot snapshot = base()
                .sim(new SimValues(Text.of("45005"), Text.of("kr"), Text.of("kr"),
                        Text.of("Verizon"), "kr", 9, 1))
                .build();
        expect("carrier does not match operator", new SimCoherenceProbe().run(snapshot),
                Status.WARN, Reason.INCONSISTENT);
    }

    /**
     * An operator that neither table knows means nothing was compared. Reporting it as a clean
     * informational result would reintroduce "green because we did not look".
     */
    private static void unknownOperatorIsUnknown() {
        Snapshot snapshot = base()
                .sim(new SimValues(Text.of("99999"), Text.of("zz"), Text.of("zz"),
                        Text.of("Nowhere Telecom"), "zz", 0, 1))
                .build();
        expect("operator absent from catalog", new SimCoherenceProbe().run(snapshot),
                Status.UNKNOWN, Reason.CARRIER_UNKNOWN);
    }

    private static void registryMetadataIsComplete() {
        Set<String> ids = new LinkedHashSet<String>();
        for (Probe probe : ProbeRegistry.all()) {
            check("probe id unique: " + probe.id(), ids.add(probe.id()), "duplicate id");
            check("probe declares layer: " + probe.id(), probe.layer() != null, "null layer");
            check("probe declares field: " + probe.id(), !probe.field().isEmpty(), "empty field");
        }
    }

    // ---- selinux, emulator, partitions, identity fields, android id ---------------------

    private static void enforcingSelinuxPasses() {
        expect("enforcing", new SelinuxProbe().run(base().selinuxEnforce(Text.of("1")).build()),
                Status.PASS, Reason.OK);
    }

    private static void permissiveSelinuxFails() {
        expect("permissive", new SelinuxProbe().run(base().selinuxEnforce(Text.of("0")).build()),
                Status.FAIL, Reason.PRESENT);
    }

    private static void unreadableSelinuxIsUnknown() {
        expect("selinux switch unreadable is normal", new SelinuxProbe().run(base().build()),
                Status.PASS, Reason.UNREADABLE);
    }

    private static void physicalIdentityIsNotAnEmulator() {
        expect("physical device identity", new EmulatorSignatureProbe().run(base().build()),
                Status.PASS, Reason.ABSENT);
    }

    private static void emulatorSignatureFails() {
        Snapshot snapshot = base()
                .buildValues(samsung().toBuilder().fingerprint(
                        "google/sdk_gphone64_x86_64/emu64x:14/UP1A/1:userdebug/test-keys").build())
                .build();
        expect("emulator fingerprint", new EmulatorSignatureProbe().run(snapshot),
                Status.FAIL, Reason.SUSPICIOUS);
    }

    private static void partitionFingerprintsAgree() {
        Snapshot snapshot = base()
                .prop("ro.vendor.build.fingerprint", Text.of(SAMSUNG_FINGERPRINT))
                .build();
        expect("partition fingerprints agree", new PartitionFingerprintProbe().run(snapshot),
                Status.PASS, Reason.OK);
    }

    private static void partitionFingerprintMismatchFails() {
        Snapshot snapshot = base()
                .prop("ro.vendor.build.fingerprint", Text.of(
                        "google/husky/husky:14/AP1A.240505.005/1234567:user/release-keys"))
                .build();
        expect("partition identity differs", new PartitionFingerprintProbe().run(snapshot),
                Status.FAIL, Reason.INCONSISTENT);
    }

    private static void absentPartitionFingerprintsAreUnknown() {
        expect("no partition fingerprint readable",
                new PartitionFingerprintProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void foreignVendorInModelWarns() {
        Snapshot snapshot = base()
                .buildValues(samsung().toBuilder().model("Pixel 8 Pro").build())
                .build();
        expect("model names another vendor", new IdentitySanityProbe().run(snapshot),
                Status.WARN, Reason.SUSPICIOUS);
    }

    /** A sub-brand in the model is not a spoof when the manufacturer is its parent. */
    private static void subBrandUnderParentPasses() {
        Snapshot snapshot = base()
                .buildValues(samsung().toBuilder()
                        .manufacturer("Xiaomi").model("Redmi Note 14").build())
                .build();
        expect("sub-brand under its parent", new IdentitySanityProbe().run(snapshot),
                Status.PASS, Reason.OK);
    }

    // ---- persona synthesis -------------------------------------------------------------

    private static final String[] SAMSUNG_ROW = {
            "Galaxy S24U", "samsung", "samsung", "SM-S928B", "e3q", "e3qxeea",
            "qcom", "kalama",
            "samsung/e3qxeea/e3q:15/AP3A.240905.015.A2/S928BXXS5AYD4:user/release-keys",
            "AP3A.240905.015.A2", "S928BXXS5AYD4", "15", "35", "2024-11-01"};

    private static BuildValues fromRow(String[] device) {
        return BuildValues.builder()
                .manufacturer(device[1])
                .brand(device[2])
                .model(device[3])
                .device(device[4])
                .product(device[5])
                .buildId(device[9])
                .incremental(device[10])
                .display(device[9] + "." + device[10])
                .fingerprint(device[8])
                .type("user")
                .tags("release-keys")
                .time(1730000000000L)
                .build();
    }

    private static void syntheticVariantKeepsIdentity() {
        String[] out = PersonaSynth.cross(SAMSUNG_ROW, 42L);
        check("brand kept", "samsung".equals(out[2]), out[2]);
        check("product kept", "e3qxeea".equals(out[5]), out[5]);
        check("incremental keeps its length", out[10].length() == SAMSUNG_ROW[10].length(),
                out[10]);
        check("incremental actually varies", !out[10].equals(SAMSUNG_ROW[10]), out[10]);
        check("build id keeps its length", out[9].length() == SAMSUNG_ROW[9].length(), out[9]);
        check("same seed, same variant", Arrays.equals(out, PersonaSynth.cross(SAMSUNG_ROW, 42L)),
                "not deterministic");
    }

    /** The whole point of the synthesis: the generated build must survive the coherence rule. */
    private static void syntheticVariantPassesCoherence() {
        String[] out = PersonaSynth.cross(SAMSUNG_ROW, 7L);
        Snapshot snapshot = base().buildValues(fromRow(out)).build();
        expect("synthesised build is self-consistent",
                new FingerprintCoherenceProbe().run(snapshot), Status.PASS, Reason.OK);
    }

    private static void pixelIncrementalStaysNumeric() {
        String[] pixel = {"Pixel 9", "Google", "google", "Pixel 9", "tokay", "tokay",
                "tokay", "tokay",
                "google/tokay/tokay:15/AP3A.241005.015/12270533:user/release-keys",
                "AP3A.241005.015", "12270533", "15", "35", "2024-10-05"};
        String[] out = PersonaSynth.cross(pixel, 3L);
        check("numeric incremental stays numeric", out[10].matches("\\d+"), out[10]);
        // The build id is not numeric on this model (AP3A.…), so only its shape is guaranteed.
        check("build id keeps its length", out[9].length() == pixel[9].length(), out[9]);
    }

    private static void androidIdPasses() {
        Snapshot snapshot = base().androidId(Text.of("fedcba9876543210")).build();
        expect("android id present", new AndroidIdProbe().run(snapshot), Status.PASS, Reason.OK);
    }

    // ---- packages, restricted props, target install, hidden api, accounts ------------------

    private static void listedRootPackageFails() {
        Snapshot snapshot = base()
                .installedPackages(Arrays.asList("com.example.magisk", "com.example.shopping"),
                        true)
                .build();
        expect("listed root package installed", new RootPackagesProbe().run(snapshot),
                Status.FAIL, Reason.PRESENT);
    }

    private static void unlistedPackagesPass() {
        Snapshot snapshot = base()
                .installedPackages(Arrays.asList("com.example.shopping"), true)
                .build();
        expect("no listed package installed", new RootPackagesProbe().run(snapshot),
                Status.PASS, Reason.ABSENT);
    }

    private static void installedPackagesUnreadableIsUnknown() {
        expect("package list unreadable", new RootPackagesProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void restrictedPropsCollectedEmptyPasses() {
        Snapshot snapshot = base()
                .prop("ro.debuggable", Text.of(""))
                .prop("ro.secure", Text.of(""))
                .build();
        expect("restricted props observed empty", new RestrictedPropsProbe().run(snapshot),
                Status.PASS, Reason.ABSENT);
    }

    private static void restrictedPropsReadableWarns() {
        Snapshot snapshot = base()
                .prop("ro.debuggable", Text.of("0"))
                .prop("ro.secure", Text.of("1"))
                .build();
        expect("restricted props readable", new RestrictedPropsProbe().run(snapshot),
                Status.WARN, Reason.SUSPICIOUS);
    }

    private static void restrictedPropsNotCollectedIsUnknown() {
        expect("restricted props never collected",
                new RestrictedPropsProbe().run(base().build()), Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void targetNotCollectedIsUnknown() {
        expect("target state not collected", new TargetInstallProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void targetMissingIsInformational() {
        Snapshot snapshot = base()
                .targetInstall(Text.unreadable(), Text.unreadable(), false)
                .build();
        expect("target not installed", new TargetInstallProbe().run(snapshot),
                Status.INFO, Reason.ABSENT);
    }

    private static void targetFromPlayStorePasses() {
        Snapshot snapshot = base()
                .targetInstall(Text.of("com.android.vending"), Text.of("1.2"), true)
                .build();
        expect("installed from Play", new TargetInstallProbe().run(snapshot),
                Status.PASS, Reason.OK);
    }

    private static void targetSideloadFails() {
        Snapshot snapshot = base()
                .targetInstall(Text.of("com.android.packageinstaller"), Text.of("1.2"), true)
                .build();
        expect("sideloaded target", new TargetInstallProbe().run(snapshot),
                Status.FAIL, Reason.PRESENT);
    }

    private static void hiddenApiUnreadableIsUnknown() {
        expect("hidden api flag unreadable", new HiddenApiProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void hiddenApiZeroFails() {
        Snapshot snapshot = base().hiddenApiPolicy(Text.of("0")).build();
        expect("hidden api unrestricted", new HiddenApiProbe().run(snapshot),
                Status.FAIL, Reason.PRESENT);
    }

    private static void accountsUnreadableIsUnknown() {
        expect("accounts unreadable", new AccountsProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void googleAccountDumpParses() {
        String dump = "User UserInfo{0:owner:4c13}:\n"
                + "  Accounts: 3\n"
                + "    Account {name=one@gmail.com, type=com.google}\n"
                + "    Account {name=WhatsApp, type=com.whatsapp}\n"
                + "    Account {name=two@gmail.com, type=com.google}\n";
        List<Account> parsed = Account.googleFromDumpsys(dump);
        check("google accounts parsed", parsed != null && parsed.size() == 2
                        && "one@gmail.com".equals(parsed.get(0).name())
                        && "two@gmail.com".equals(parsed.get(1).name()),
                String.valueOf(parsed));
        check("invalid account dump unreadable",
                Account.googleFromDumpsys("exc:no service") == null, "parsed");
    }

    private static void googleAccountsAreListed() {
        List<Account> accounts = new ArrayList<Account>();
        accounts.add(new Account("one@gmail.com", "com.google"));
        accounts.add(new Account("two@gmail.com", "com.google"));
        Snapshot snapshot = base().accounts(accounts, true).build();
        Finding finding = new AccountsProbe().run(snapshot);
        expect("google accounts", finding, Status.PASS, Reason.OK);
        check("only account names are shown",
                "one@gmail.com\ntwo@gmail.com".equals(finding.observed()),
                finding.observed());
    }

    private static void nonGoogleAccountsAreIgnored() {
        List<Account> accounts = new ArrayList<Account>();
        accounts.add(new Account("someone@gmail.com", "com.microg"));
        Snapshot snapshot = base().accounts(accounts, true).build();
        Finding finding = new AccountsProbe().run(snapshot);
        expect("non-google account ignored", finding, Status.WARN, Reason.ABSENT);
        check("non-google account not listed", finding.observed().isEmpty(),
                finding.observed());
    }

    private static void noGoogleAccountsWarns() {
        Snapshot snapshot = base().accounts(new ArrayList<Account>(), true).build();
        expect("no google accounts", new AccountsProbe().run(snapshot),
                Status.WARN, Reason.ABSENT);
    }

    private static void userSpaceNamesParse() {
        Map<String, String> names = UserSpaces.parse(
                "Users:\n"
                + "\tUserInfo{0:owner:4c13} running\n"
                + "\tUserInfo{150:Secure Folder:10061030} running\n"
                + "\tUserInfo{11:ws-12345:30}\n");
        check("owner space name", "owner".equals(names.get("0")), names.get("0"));
        check("space name with spaces",
                "Secure Folder".equals(names.get("150")), names.get("150"));
        check("clone space name", "ws-12345".equals(names.get("11")), names.get("11"));
        check("name falls back to id", names.get("9") == null, String.valueOf(names.get("9")));
        check("no user rows parsed", UserSpaces.parse("rc=1").isEmpty(), "rc=1");
    }

    // ---- platform: boot, attestation, strongbox, biometrics, gms, verdict ----------------

    private static final AttestationFacts ATTESTED = new AttestationFacts(true, true, "TEE", 0,
            true, true, 150000, 202503, "[1, 2, 704]", "b550781e", "", "");

    private static Snapshot.Builder platformBase() {
        return base().platform(new PlatformFacts(true, ATTESTED, true, 0, true, true, true,
                Text.of("41.2.29"), Text.of("24.1.5"), false));
    }

    private static Snapshot.Builder lockedBoot(Snapshot.Builder builder) {
        return builder.prop("ro.boot.flash.locked", Text.of("1"))
                .prop("ro.boot.verifiedbootstate", Text.of("green"))
                .prop("ro.boot.vbmeta.device_state", Text.of("locked"));
    }

    private static void lockedBootPasses() {
        expect("locked boot", new VerifiedBootProbe().run(lockedBoot(base()).build()),
                Status.PASS, Reason.OK);
    }

    private static void unlockedBootFails() {
        Snapshot snapshot = base()
                .prop("ro.boot.flash.locked", Text.of("0"))
                .prop("ro.boot.verifiedbootstate", Text.of("orange"))
                .build();
        expect("unlocked boot", new VerifiedBootProbe().run(snapshot),
                Status.FAIL, Reason.INCONSISTENT);
    }

    private static void bootPropsNotCollectedIsUnknown() {
        expect("boot props not collected", new VerifiedBootProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void attestationPasses() {
        expect("attested TEE, locked", new AttestationProbe().run(platformBase().build()),
                Status.PASS, Reason.OK);
    }

    private static void attestationNotCollectedIsUnknown() {
        expect("attestation not attempted", new AttestationProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void attestationFailureWarns() {
        Snapshot snapshot = base()
                .platform(new PlatformFacts(true, AttestationFacts.failed("boom"), true, 0, true,
                        true, true, Text.of("41"), Text.of("24"), false))
                .build();
        expect("attestation could not run", new AttestationProbe().run(snapshot),
                Status.WARN, Reason.NOT_AVAILABLE);
    }

    private static void strongboxAvailablePasses() {
        expect("strongbox present", new StrongBoxProbe().run(platformBase().build()),
                Status.PASS, Reason.OK);
    }

    private static void strongboxAbsentIsInformational() {
        Snapshot snapshot = base()
                .platform(new PlatformFacts(true, ATTESTED, false, 0, true, true, true,
                        Text.of("41"), Text.of("24"), false))
                .build();
        expect("no strongbox", new StrongBoxProbe().run(snapshot), Status.INFO, Reason.ABSENT);
    }

    private static void biometricReadyPasses() {
        expect("biometric ready", new BiometricProbe().run(platformBase().build()),
                Status.PASS, Reason.OK);
    }

    private static void biometricNotEnrolledFails() {
        Snapshot snapshot = base()
                .platform(new PlatformFacts(true, ATTESTED, true, 11, true, true, true,
                        Text.of("41"), Text.of("24"), false))
                .build();
        expect("no enrolled biometric", new BiometricProbe().run(snapshot),
                Status.FAIL, Reason.NOT_AVAILABLE);
    }

    private static void biometricNotCollectedIsUnknown() {
        expect("biometric not collected", new BiometricProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void genuineGmsPasses() {
        expect("genuine GMS", new GmsProbe().run(platformBase().build()), Status.PASS, Reason.OK);
    }

    private static void replacementGmsFails() {
        Snapshot snapshot = base()
                .platform(new PlatformFacts(true, ATTESTED, true, 0, true, true, true,
                        Text.of("0.2.1"), Text.of("0.2.1"), false))
                .build();
        expect("reimplemented store stack", new GmsProbe().run(snapshot),
                Status.FAIL, Reason.INCONSISTENT);
    }

    private static void patchedGmsWarns() {
        Snapshot snapshot = base()
                .platform(new PlatformFacts(true, ATTESTED, true, 0, true, true, true,
                        Text.of("53.8.11"), Text.of("26.42.31"), true))
                .build();
        Finding finding = new GmsProbe().run(snapshot);
        expect("patched GMS alongside the real one", finding, Status.WARN, Reason.SUSPICIOUS);
        check("patched GMS package listed",
                "app.revanced.android.gms".equals(finding.detail().get("packages")),
                finding.detail().toString());
    }

    private static void integrityVerdictPasses() {
        expect("complete hardware chain",
                new IntegrityVerdictProbe().run(lockedBoot(platformBase()).build()),
                Status.PASS, Reason.OK);
    }

    private static void integrityVerdictUnlockedFails() {
        Snapshot snapshot = platformBase()
                .prop("ro.boot.flash.locked", Text.of("0"))
                .prop("ro.boot.verifiedbootstate", Text.of("orange"))
                .build();
        expect("unlocked boot sinks the verdict",
                new IntegrityVerdictProbe().run(snapshot), Status.FAIL, Reason.INCONSISTENT);
    }

    private static void integrityVerdictIncompleteWarns() {
        Snapshot snapshot = lockedBoot(base())
                .platform(new PlatformFacts(true, AttestationFacts.NOT_COLLECTED, true, 0, true,
                        true, true, Text.of("41"), Text.of("24"), false))
                .build();
        expect("no attestation evidence", new IntegrityVerdictProbe().run(snapshot),
                Status.WARN, Reason.INCOMPLETE);
    }

    // ---- tls and network ---------------------------------------------------------------

    private static final String LEAKED_CA = "CN=EM DV TLS CA - G3B,O=eMudhra BV,C=NL";

    private static NetworkFacts netFacts(boolean collected, boolean vpn, List<String> names,
                                         Text host, int port, boolean viaVpn, boolean readable) {
        return new NetworkFacts(collected, readable, "", vpn, names, new ArrayList<String>(), host,
                port, viaVpn);
    }

    private static void leakedCaFails() {
        Snapshot snapshot = base().tls(new TlsFacts(true, Arrays.asList(LEAKED_CA), true,
                new ArrayList<String>())).build();
        expect("leaked CA present", new TlsIssuerProbe().run(snapshot), Status.FAIL, Reason.PRESENT);
    }

    private static void cleanTrustStorePasses() {
        Snapshot snapshot = base().tls(new TlsFacts(true,
                Arrays.asList("CN=Some Other CA,O=X,C=US"), true, new ArrayList<String>())).build();
        expect("trust store clean", new TlsIssuerProbe().run(snapshot), Status.PASS, Reason.ABSENT);
    }

    private static void trustStoreUnreadableIsUnknown() {
        expect("trust store unreadable", new TlsIssuerProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void userCaPresentFails() {
        Snapshot snapshot = base().tls(new TlsFacts(true, new ArrayList<String>(), true,
                Arrays.asList("9a5f0c0d.0"))).build();
        expect("user CA installed", new UserCaProbe().run(snapshot), Status.FAIL, Reason.PRESENT);
    }

    private static void noUserCaPasses() {
        Snapshot snapshot = base().tls(new TlsFacts(true, new ArrayList<String>(), true,
                new ArrayList<String>())).build();
        expect("no user CA", new UserCaProbe().run(snapshot), Status.PASS, Reason.ABSENT);
    }

    private static void vpnTransportFails() {
        Snapshot snapshot = base().network(netFacts(true, true, Arrays.asList("wlan0"),
                Text.of(""), -1, false, true)).build();
        expect("vpn transport", new VpnTunnelProbe().run(snapshot), Status.FAIL, Reason.PRESENT);
    }

    private static void tunnelInterfaceFails() {
        Snapshot snapshot = base().network(netFacts(true, false, Arrays.asList("wlan0", "tun0"),
                Text.of(""), -1, false, true)).build();
        expect("tunnel interface", new VpnTunnelProbe().run(snapshot), Status.FAIL, Reason.PRESENT);
    }

    private static void noTunnelPasses() {
        Snapshot snapshot = base().network(netFacts(true, false, Arrays.asList("wlan0"),
                Text.of(""), -1, false, true)).build();
        expect("no tunnel", new VpnTunnelProbe().run(snapshot), Status.PASS, Reason.ABSENT);
    }

    private static void networkNotCollectedIsUnknown() {
        expect("network not collected", new VpnTunnelProbe().run(base().build()),
                Status.UNKNOWN, Reason.UNREADABLE);
    }

    private static void proxySetWarns() {
        Snapshot snapshot = base().network(netFacts(true, false, Arrays.asList("wlan0"),
                Text.of("10.0.0.5"), 8080, false, true)).build();
        expect("proxy configured", new ProxyProbe().run(snapshot), Status.WARN, Reason.PRESENT);
    }

    private static void proxyFromVpnPasses() {
        Snapshot snapshot = base().network(netFacts(true, true, Arrays.asList("tun0"),
                Text.of("10.0.0.5"), 8080, true, true)).build();
        expect("proxy declared by vpn", new ProxyProbe().run(snapshot), Status.PASS, Reason.OK);
    }

    private static void noProxyPasses() {
        Snapshot snapshot = base().network(netFacts(true, false, Arrays.asList("wlan0"),
                Text.of(""), -1, false, true)).build();
        expect("no proxy", new ProxyProbe().run(snapshot), Status.PASS, Reason.ABSENT);
    }

    private static void interfaceListIsInformational() {
        Snapshot snapshot = base().network(netFacts(true, false, Arrays.asList("wlan0"),
                Text.of(""), -1, false, true)).build();
        expect("interface list", new InterfaceAddressProbe().run(snapshot),
                Status.INFO, Reason.ABSENT);
    }

    private static void unreadableInterfaceTableIsNormal() {
        Snapshot snapshot = base().network(netFacts(false, false,
                new ArrayList<String>(), Text.of(""), -1, false, true)).build();
        expect("interface table unreadable is normal",
                new InterfaceAddressProbe().run(snapshot), Status.PASS, Reason.UNREADABLE);
    }

    /** The region card's expected-tag line, tested without a phone (it used to be unverifiable). */
    private static void regionTagsSplitByVendorAndGeneric() {
        String[][] table = {{"DCM", "jp"}, {"KDI", "jp"}, {"_JP", "jp"}, {"XSP", "sg"}};
        String[] jp = RegionTags.split("jp", table);
        check("vendor tags for jp", "DCM/KDI".equals(jp[0]), jp[0]);
        check("generic tags for jp", "_JP".equals(jp[1]), jp[1]);
        String[] sg = RegionTags.split("sg", table);
        check("vendor only", "XSP".equals(sg[0]) && sg[1].isEmpty(), sg[0] + "|" + sg[1]);
        String[] none = RegionTags.split("zz", table);
        check("unknown region has no tags", none[0].isEmpty() && none[1].isEmpty(), none[0]);
    }

    private static void commandBuildsFailureMarkedSteps() {
        Command command = Command.sh()
                .raw("APK=/x.apk")
                .step("tz", "cmd alarm set-timezone Asia/Tokyo")
                .step("locale", "cmd locale set-app-locales a.b --locales ja-JP")
                .done("APPLY:DONE");
        String script = command.build();
        check("prefix hoisted ahead of the steps", script.startsWith("APK=/x.apk; "), script);
        check("each step marks its own failure", script.contains(" || echo FAIL:tz; "), script);
        check("done marker is last", script.endsWith("echo APPLY:DONE"), script);
        check("step names are exposed",
                Arrays.equals(command.stepNames(), new String[]{"tz", "locale"}), "names");
    }

    private static void outcomeReadsMarkers() {
        String[] steps = {"tz", "locale"};
        Outcome clean = Outcome.of("APPLY:DONE", steps);
        check("clean output is ok", clean.ok() && clean.failedSteps().isEmpty(),
                clean.summary());
        Outcome bad = Outcome.of("FAIL:locale\nAPPLY:DONE", steps);
        check("the failing step is named", !bad.ok() && bad.failedSteps().contains("locale"),
                bad.summary());
        check("timeout is a failure", !Outcome.of("timeout: no answer", steps).ok(), "ok");
        check("transport exception is a failure",
                !Outcome.of("exc:java.io.IOException", steps).ok(), "ok");
    }

    /** Quoting rules for everything that reaches a `sh -c` line (used by the Shizuku layer). */
    private static void suCommandWrapsAndReportsStatus() {
        String built = Command.su().raw("echo hi").build();
        check("su wrapper", built.startsWith("su -c '"), built);
        check("su status marker", built.endsWith("; echo SU_RC=$?"), built);
        check("payload stays single-quoted", built.contains("'echo hi; echo DONE'"), built);
        check("sh mode is unwrapped",
                Command.sh().step("x", "true").build().equals("true || echo FAIL:x; echo DONE"),
                Command.sh().step("x", "true").build());
        check("su rc zero is not a failure", !ShellQuote.failed("SU_RC=0"), "failed");
        check("su rc non-zero is a failure", ShellQuote.failed("Permission denied\nSU_RC=1"),
                "not failed");
        check("missing marker is not a su failure", ShellQuote.suRc("plain output")
                == Integer.MIN_VALUE, "marker found");
        check("audit tag keeps the status", "su=0".equals(ShellQuote.suRcTag("APPLY:DONE\nSU_RC=0")),
                ShellQuote.suRcTag("APPLY:DONE\nSU_RC=0"));
        check("audit tag names a refusal", "su=1".equals(ShellQuote.suRcTag("SU_RC=1")),
                ShellQuote.suRcTag("SU_RC=1"));
        check("audit tag admits an absent marker",
                "su=absent".equals(ShellQuote.suRcTag("plain output")),
                ShellQuote.suRcTag("plain output"));
        check("missing command is not a refusal", ShellQuote.suMissing(127)
                        && ShellQuote.suMissing(126) && !ShellQuote.suMissing(1)
                        && !ShellQuote.suMissing(0), "126/127 must read as missing");
    }

    /** The two root payloads are the most dangerous strings in the app; assert their shape. */
    private static void rootScriptsStayParameterisedAndBounded() {
        String restore = RootScript.restoreSsaid("10", "com.example.app");
        check("restore targets the user", restore.startsWith("U=10;"), restore);
        check("restore touches the ssaid table", restore.contains("settings_ssaid.xml"), restore);
        check("restore names the package", restore.contains("com.example.app"), restore);
        check("restore keeps a file backup", restore.contains("ssaid_file_$U.abx"), restore);
        check("restore replaces atomically", restore.contains("mv $W $F"), restore);
        check("restore reports its marker", restore.contains("echo aid-restored:$O"), restore);
        String random = RootScript.randomizeSsaid("10", "com.example.app");
        check("randomize verifies by read-back", random.contains("FAIL:verify"), random);
        check("randomize records the original", random.contains("ssaid_orig_$U"), random);
        check("payload has no unescaped quote", !random.contains("'"), random);
    }

    private static void shellQuoteEscapesAndValidates() {
        check("plain token accepted", ShellQuote.safeToken("Asia/Tokyo"), "rejected");
        check("locale list accepted", ShellQuote.safeToken("en-US,zh-SG"), "rejected");
        check("semicolon rejected", !ShellQuote.safeToken("x;reboot"), "accepted");
        check("backtick rejected", !ShellQuote.safeToken("x`id`"), "accepted");
        check("space rejected in token", !ShellQuote.safeToken("LG U+"), "accepted");
        check("carrier quoted", "'LG U+'".equals(ShellQuote.quote("LG U+")),
                ShellQuote.quote("LG U+"));
        check("single quote escaped", "'a'\\''b'".equals(ShellQuote.quote("a'b")),
                ShellQuote.quote("a'b"));
        check("package name accepted", ShellQuote.packageName("com.walter.claudewilson"),
                "rejected");
        check("package name with metachar rejected", !ShellQuote.packageName("a;reboot"),
                "accepted");
        check("numeric id", ShellQuote.numeric("10") && !ShellQuote.numeric("10;x"), "mixed");
        check("failure markers", ShellQuote.failed("FAIL:timezone")
                        && ShellQuote.failed("timeout: no answer")
                        && ShellQuote.failed("refused: unsafe")
                        && !ShellQuote.failed("APPLY:DONE"), "marker mismatch");
    }

    /**
     * Bounding output must not delete the verdict.
     *
     * <p>The first real run of the status button cut off at exactly 300 characters, which was
     * before {@code SU_RC=}: the payload had run as root and the screen could not say so. These
     * checks pin both ends of the bound.
     */
    private static void boundedOutputKeepsTheVerdict() {
        String fit = "==finject==\nnone\nSU_RC=0";
        check("fitting output is untouched", fit.equals(Output.bounded(fit, 2000)), fit);
        check("null output is empty", "".equals(Output.bounded(null, 10)), "not empty");

        StringBuilder body = new StringBuilder("==selfcheck==\n");
        for (int i = 0; i < 400; i++) {
            body.append("noise ").append(i).append('\n');
        }
        String longOut = body + "FAIL:verify\nSU_RC=1";
        String bounded = Output.bounded(longOut, 200);
        check("bound is respected", bounded.length() == 200, String.valueOf(bounded.length()));
        check("head survives", bounded.startsWith("==selfcheck=="), bounded);
        check("su status survives", bounded.endsWith("SU_RC=1"), bounded);
        check("last failure marker survives", bounded.contains("FAIL:verify"), bounded);
        check("the cut is visible", bounded.contains("chars]"), bounded);
        check("surviving status is still parsed", ShellQuote.suRc(bounded) == 1, bounded);
        check("the bounded run is still a failure", ShellQuote.failed(bounded), bounded);

        check("tiny bound keeps the tail", "def".equals(Output.bounded("abcdef", 3)),
                Output.bounded("abcdef", 3));
        check("zero bound disables bounding", "abcdef".equals(Output.bounded("abcdef", 0)),
                Output.bounded("abcdef", 0));
    }

    private static void unknownNeverAggregatesToPass() {
        Report report = Report.of(Arrays.asList(
                Finding.of("a", Layer.ENV_EVIDENCE, "", Status.PASS, Reason.OK, ""),
                Finding.of("b", Layer.ENV_EVIDENCE, "", Status.UNKNOWN, Reason.UNREADABLE, "")));
        check("unknown is not a pass", report.verdict() == Status.UNKNOWN,
                "verdict=" + report.verdict());
        check("incomplete scan is reported", !report.isComplete(), "isComplete()=true");
    }

    private static void duplicateProbeIdsRejected() {
        boolean rejected = false;
        try {
            Report.of(Arrays.asList(
                    Finding.of("dup", Layer.ENV_EVIDENCE, "", Status.PASS, Reason.OK, ""),
                    Finding.of("dup", Layer.ENV_EVIDENCE, "", Status.PASS, Reason.OK, "")));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check("duplicate probe id rejected", rejected, "Report.of accepted duplicates");
    }

    /**
     * Property test: an empty snapshot has nothing observable, so it cannot fail. SELinux and the
     * interface table have explicitly-normal unreadable results; everything else stays unknown.
     */
    private static void emptySnapshotYieldsNoPass() {
        Report report = ProbeRegistry.scan(Snapshot.builder().build());
        check("empty snapshot has only normal-unreadable passes",
                report.count(Status.PASS) == 2,
                "passes=" + report.count(Status.PASS) + " " + report.findings());
        check("empty snapshot has no failure", report.count(Status.FAIL) == 0,
                "failures=" + report.count(Status.FAIL));
        check("empty snapshot leaves the rest unknown",
                report.count(Status.UNKNOWN) == report.findings().size() - 2,
                report.summary());
    }

    private static void tablesParseCommentsAndPairs() {
        Tables tables = catalog();
        check("catalog version parsed", "selftest".equals(tables.version()), tables.version());
        check("path list parsed", tables.suPaths().size() == 2, tables.suPaths().toString());
        check("mcc table parsed", "kr".equals(tables.mccToIso().get("450")),
                tables.mccToIso().toString());
        check("carrier table parsed", "skt|sktelecom".equals(tables.carrierKeywords().get("45005")),
                tables.carrierKeywords().toString());
        check("keywords lower-cased", tables.packageKeywords().contains("magisk"),
                tables.packageKeywords().toString());
    }

    private static void targetRegistryIsDataDriven() {
        TargetSpec claude = TargetRegistry.byPackage("com.anthropic.claude");
        TargetSpec chatgpt = TargetRegistry.byPackage("com.openai.chatgpt");
        check("claude registry entry", claude == TargetRegistry.CLAUDE);
        check("chatgpt registry entry", chatgpt == TargetRegistry.CHATGPT);
        check("unknown package is not a target",
                TargetRegistry.byPackage("com.example.unknown") == null);
        check("unknown stored id falls back to claude",
                TargetRegistry.byId("removed-target") == TargetRegistry.CLAUDE);
        check("claude can arm hooks", claude != null && claude.hooksEnabled());
        check("claude has full local identity support",
                claude != null && claude.localIdentityWrites());
        check("chatgpt has environment hooks", chatgpt != null && chatgpt.hooksEnabled()
                && chatgpt.environmentWrites());
        check("chatgpt local identity is enabled",
                chatgpt != null && chatgpt.localIdentityWrites()
                        && !chatgpt.diagnosticOnly());
        check("chatgpt market uri follows its package",
                chatgpt != null && chatgpt.marketUri().endsWith("com.openai.chatgpt"));
        boolean rejected = false;
        try {
            new TargetSpec("../escape", "com.example.app", "x", "market://x", "", 1, null);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check("invalid target id rejected", rejected);
    }

    private static void targetConfigPathsAreDistinct() {
        check("claude config path",
                "/data/local/tmp/claudewilson/claude.json"
                        .equals(TargetRegistry.CLAUDE.configPath()));
        check("chatgpt config path",
                "/data/local/tmp/claudewilson/chatgpt.json"
                        .equals(TargetRegistry.CHATGPT.configPath()));
        check("per-target config paths differ",
                !TargetRegistry.CLAUDE.configPath()
                        .equals(TargetRegistry.CHATGPT.configPath()));
        check("legacy path is claude-only",
                !TargetRegistry.CLAUDE.legacyConfigPath().isEmpty()
                        && TargetRegistry.CHATGPT.legacyConfigPath().isEmpty());
    }

    private static void targetInventoryIsInformational() {
        TargetEvidence evidence = TargetEvidence.builder()
                .collected(true).installed(true).dataRequested(true).dataReadable(true)
                .version("1.2026.300").uid("10431").source("root")
                .store("shared_prefs").identityKind("segment").identityKind("firebase")
                .filesSeen(42).build();
        Snapshot snapshot = base().targetEvidence(evidence).build();
        Finding shape = new TargetShapeProbe().run(snapshot);
        Finding identity = new TargetIdentityProbe().run(snapshot);
        check("target shape is observed", shape.status() == Status.INFO,
                "shape=" + shape);
        check("identity inventory is informational", identity.status() == Status.INFO,
                "identity=" + identity);
        check("identity inventory is not trusted as a pass", identity.status() != Status.PASS,
                "identity=" + identity);
        check("identity inventory names the boundary",
                "file-names-only".equals(identity.detail().get("boundary")),
                identity.detail().toString());
    }

    private static void targetIntegrityBoundaryIsNeverAPass() {
        TargetEvidence evidence = TargetEvidence.builder()
                .collected(true).installed(true).dataRequested(true).dataReadable(true)
                .webViewStore(true).cookieStore(true).integrityKind("playintegrity").build();
        Snapshot snapshot = base().targetEvidence(evidence).build();
        Finding play = new TargetPlayIntegrityProbe().run(snapshot);
        check("play integrity token is not claimed",
                play.status() == Status.INFO && "not-attempted".equals(
                        play.detail().get("forgery")), "play=" + play);
        check("play integrity is never a pass", play.status() != Status.PASS, "play=" + play);

        Snapshot unreadable = base().targetEvidence(TargetEvidence.builder()
                .collected(true).installed(true).dataRequested(true).dataReadable(false).build())
                .build();
        Finding unknown = new TargetPlayIntegrityProbe().run(unreadable);
        check("unreadable local stores do not become no-store",
                unknown.status() == Status.UNKNOWN, "unknown=" + unknown);
    }

    private static void targetVpnViewIsInformational() {
        NetworkFacts network = new NetworkFacts(true, true, "", true,
                Arrays.asList("wlan0", "tun0"), Arrays.asList("10.0.0.2/24"),
                Text.of("127.0.0.1"), 7890, true);
        Snapshot snapshot = base().network(network)
                .targetEvidence(TargetEvidence.builder().collected(true).installed(true).build())
                .build();
        Finding vpn = new TargetVpnViewProbe().run(snapshot);
        check("target VPN view is informational", vpn.status() == Status.INFO, "vpn=" + vpn);
        check("target VPN view names client scope",
                "client-view-only".equals(vpn.detail().get("scope")), vpn.detail().toString());
    }

    private static void targetSuViewIsSeparate() {
        Snapshot hidden = base().targetEvidence(TargetEvidence.builder()
                .collected(true).installed(true).configPresent(true)
                .suView(TargetEvidence.SU_HIDDEN).build()).build();
        expect("target su hidden", new TargetSuViewProbe().run(hidden),
                Status.PASS, Reason.OK);

        Snapshot visible = base().targetEvidence(TargetEvidence.builder()
                .collected(true).installed(true).configPresent(true)
                .suView(TargetEvidence.SU_VISIBLE).build()).build();
        expect("target su visible", new TargetSuViewProbe().run(visible),
                Status.FAIL, Reason.PRESENT);

        Snapshot stale = base().targetEvidence(TargetEvidence.builder()
                .collected(true).installed(true).configPresent(false)
                .suView(TargetEvidence.SU_HIDDEN).build()).build();
        expect("stale self-check without payload", new TargetSuViewProbe().run(stale),
                Status.INFO, Reason.NOT_AVAILABLE);

        Snapshot unknown = base().targetEvidence(TargetEvidence.builder()
                .collected(true).installed(true).configPresent(true).build()).build();
        expect("target self-check absent", new TargetSuViewProbe().run(unknown),
                Status.INFO, Reason.NOT_AVAILABLE);
    }

    private static void rootArtifactPolicyIsNarrow() {
        check("su binary hidden", RootArtifactPolicy.hidden("/system/bin/su"));
        check("ksu tree hidden", RootArtifactPolicy.hidden("/data/adb/ksu/bin/ksud"));
        check("lookalike path kept", !RootArtifactPolicy.hidden("/system/bin/su2"));
        check("ordinary path kept", !RootArtifactPolicy.hidden("/system/bin/sh"));
        check("su command hidden", RootArtifactPolicy.isSuCommand(new String[]{"su", "-c", "id"}));
        check("which su hidden", RootArtifactPolicy.isSuCommand("which su"));
        check("absolute su hidden", RootArtifactPolicy.isSuCommand("/system/bin/su -c id"));
        check("issue is not su", !RootArtifactPolicy.isSuCommand("issue"));
        check("sudo is not su", !RootArtifactPolicy.isSuCommand("sudo -i"));
    }

    // ---- risk model --------------------------------------------------------------------

    private static void checkItemUnreadableIsUnknown() {
        CheckItem spec = testItem("x");
        RiskModel model = RiskModel.evaluate(Arrays.asList(spec),
                Collections.<CheckConstraint>emptyList());
        check("unread item stays unknown",
                model.item("x").verdict() == CheckItem.Verdict.UNKNOWN,
                model.item("x").toString());
        check("unread item keeps coverage reason",
                model.item("x").coverage() == CheckItem.Coverage.UNREADABLE,
                model.item("x").coverage().name());
        CheckItem noRecord = CheckItem.spec("nr", "identity", "logs", CheckItem.Writable.NO,
                "rerun-target", false, false, null, CheckItem.Coverage.NO_RECORD);
        check("blind coverage survives unreadable",
                noRecord.unreadable().coverage() == CheckItem.Coverage.NO_RECORD,
                noRecord.unreadable().coverage().name());
        check("observed value clears blind coverage",
                noRecord.withValue("US").coverage() == CheckItem.Coverage.OBSERVED,
                noRecord.withValue("US").coverage().name());

        CheckItem stale = testItem("stale").withValue("v").stale();
        RiskModel staleModel = RiskModel.evaluate(Arrays.asList(stale),
                Collections.<CheckConstraint>emptyList());
        check("stale item stays unknown",
                staleModel.item("stale").verdict() == CheckItem.Verdict.UNKNOWN,
                staleModel.item("stale").toString());
    }

    private static void checkConstraintSemantics() {
        Map<String, CheckItem> items = new LinkedHashMap<String, CheckItem>();
        items.put("locale", writableItem("locale").withValue("zh-Hans-CN"));
        items.put("product", writableItem("product").withValue("en-GB"));
        CheckConstraint language = CheckConstraint.of("language", "locale",
                CheckConstraint.Relation.SAME_LANGUAGE, "product").evaluate(items);
        check("en-GB is not zh", language.verdict() == CheckItem.Verdict.CONFLICT,
                language.detail());

        items.put("product", writableItem("product").withValue("zh_CN_#Hans"));
        CheckConstraint sameLanguage = CheckConstraint.of("language", "locale",
                CheckConstraint.Relation.SAME_LANGUAGE, "product").evaluate(items);
        check("zh variants share a language",
                sameLanguage.verdict() == CheckItem.Verdict.CONSISTENT,
                sameLanguage.detail());

        items.put("hint", writableItem("hint").withValue("JP"));
        CheckConstraint country = CheckConstraint.of("country", "locale",
                CheckConstraint.Relation.SAME_COUNTRY, "hint").evaluate(items);
        check("JP differs from CN", country.verdict() == CheckItem.Verdict.CONFLICT,
                country.detail());

        items.put("operator", testItem("operator").withValue("52501"));
        items.put("operator2", testItem("operator2").withValue("52501"));
        CheckConstraint equals = CheckConstraint.of("operator", "operator",
                CheckConstraint.Relation.EQUALS, "operator2").evaluate(items);
        check("operator equality", equals.verdict() == CheckItem.Verdict.CONSISTENT,
                equals.detail());

        CheckConstraint missing = CheckConstraint.of("missing", "absent",
                CheckConstraint.Relation.EQUALS, "operator").evaluate(items);
        check("missing constraint side is unknown",
                missing.verdict() == CheckItem.Verdict.UNKNOWN, missing.detail());

        Map<String, CheckItem> readOnly = new LinkedHashMap<String, CheckItem>();
        readOnly.put("a", testItem("a").withValue("x"));
        readOnly.put("b", testItem("b").withValue("y"));
        CheckConstraint boundary = CheckConstraint.of("readonly", "a",
                CheckConstraint.Relation.EQUALS, "b").evaluate(readOnly);
        check("read-only mismatch is a boundary",
                boundary.verdict() == CheckItem.Verdict.BOUNDARY, boundary.detail());
    }

    private static void riskModelConflictOutranksBoundary() {
        CheckItem boundary = CheckItem.spec("boundary", "test", "src",
                CheckItem.Writable.BOUNDARY, "cannot-change", true, false, null)
                .withValue("v");
        CheckItem bad = CheckItem.spec("bad", "test", "src", CheckItem.Writable.NO,
                "", false, false, "ok").withValue("no");
        CheckItem good = testItem("good").withValue("x");
        RiskModel model = RiskModel.evaluate(Arrays.asList(boundary, bad, good),
                Collections.<CheckConstraint>emptyList());
        check("boundary stays visible",
                model.item("boundary").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("boundary").toString());
        check("expected mismatch is conflict",
                model.item("bad").verdict() == CheckItem.Verdict.CONFLICT,
                model.item("bad").toString());
        check("good item is consistent",
                model.item("good").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("good").toString());
        check("conflict outranks boundary",
                model.verdict() == CheckItem.Verdict.CONFLICT, model.summary());
        RiskModel fixed = model.withValue("bad", "ok");
        check("withValue re-evaluates constraints",
                fixed.item("bad").verdict() == CheckItem.Verdict.CONSISTENT
                        && fixed.verdict() == CheckItem.Verdict.CONSISTENT,
                fixed.summary());
    }

    private static void chatGptChecklistCoversAnalysisChains() {
        List<CheckItem> specs = ChatGptChecklist.specs();
        check("checklist is broad", specs.size() >= 40, "size=" + specs.size());
        Set<String> ids = new LinkedHashSet<String>();
        Set<String> channels = new LinkedHashSet<String>();
        Map<String, CheckItem> byId = new LinkedHashMap<String, CheckItem>();
        for (CheckItem item : specs) {
            ids.add(item.id());
            channels.add(item.channel());
            byId.put(item.id(), item);
        }
        check("check ids are unique", ids.size() == specs.size(), "ids=" + ids.size());
        check("datadog play country is no-record", byId.get("sdk.datadog.play_country")
                        .coverage() == CheckItem.Coverage.NO_RECORD,
                String.valueOf(byId.get("sdk.datadog.play_country").coverage()));
        check("hcaptcha has no local record", byId.get("integrity.hcaptcha")
                        .coverage() == CheckItem.Coverage.NO_RECORD,
                String.valueOf(byId.get("integrity.hcaptcha").coverage()));
        check("cf ray is no-record", byId.get("transport.cf_ray")
                        .coverage() == CheckItem.Coverage.NO_RECORD,
                String.valueOf(byId.get("transport.cf_ray").coverage()));
        for (String channel : Arrays.asList("identity", "account", "integrity",
                "transport", "target", "system")) {
            check("channel present: " + channel, channels.contains(channel),
                    channels.toString());
        }
        check("constraints are broad", ChatGptChecklist.constraints().size() >= 15,
                "size=" + ChatGptChecklist.constraints().size());
    }

    private static void chatGptChecklistBuildsKnownModel() {
        Snapshot snapshot = base()
                .prop("persist.sys.locale", Text.of("zh-Hans-CN"))
                .prop("ro.product.locale", Text.of("en-GB"))
                .prop("ro.csc.sales_code", Text.of("WWA"))
                .prop("gsm.sim.operator.numeric", Text.of("52501"))
                .prop("gsm.sim.operator.iso-country", Text.of("sg"))
                .prop("gsm.operator.iso-country", Text.of("sg"))
                .prop("gsm.sim.operator.alpha", Text.of("SingTel"))
                .sim(new SimValues(Text.of("52501"), Text.of("sg"), Text.of("sg"),
                        Text.of("SingTel"), "sg", 8, 1))
                .androidId(Text.of("e78869c09b9f27ab"))
                .build();

        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("device_uuid", "fcf81b72-2c3d-4a80-894b-2b33161a6c8e");
        data.put("block_seed", "fcf81b72-2c3d-4a80-894b-2b33161a6c8e");
        data.put("segment_anonymous", "affa1795-aaa7-44a5-a9f0-ec56413ca2af");
        data.put("segment_user", "ua-fcf81b72-2c3d-4a80-894b-2b33161a6c8e");
        data.put("segment_device", "0d3e6a4c-2f1b-4c7e-9a51-7b8d2e4f6a10");
        data.put("statsig_stable", "fcf81b72-2c3d-4a80-894b-2b33161a6c8e");
        data.put("statsig_locale", "zh_CN");
        data.put("sentry_installation", "ab425eeec03d48f39c2de83505f0401a");
        data.put("sentry_device", "fcf81b72-2c3d-4a80-894b-2b33161a6c8e");
        data.put("datadog_anonymous", "3439add8-8e9b-44ac-b35c-31e5b89d6f84");
        data.put("firebase_fid", "cZaffpr1T82ndAHEqwCcbG");
        data.put("firebase_country", "CN");
        data.put("firebase_locale", "zh");
        data.put("firebase_mcc", "46001");
        data.put("firebase_tz", "28800");
        data.put("firebase_fingerprint", SAMSUNG_FINGERPRINT);
        data.put("datadog_play_country", "US");
        data.put("cf_ray", "SIN");
        data.put("account_handle", "ua-fcf81b72-2c3d-4a80-894b-2b33161a6c8e");
        data.put("country_hint", "JP");
        data.put("payload_cc_hint", "CN");
        data.put("playintegrity_prefix", "fcf81b72-2c3d-4a80-894b-2b33161a6c8e");
        data.put("playintegrity_ts", "6400");
        data.put("cfuvid", "4");
        data.put("oai_sc", "1");
        data.put("conv_key", "0");
        data.put("target_state", "running");
        data.put("target_app", "1.2026.300 (2630015)");
        data.put("build_parts", "samsung|dm3q|dm3qzhw|qcom|SM-S928B");
        data.put("payload_simname", "Telstra");
        data.put("payload_build_parts", "samsung|dm3q|dm3qzhw|qcom|SM-S928B");

        Map<String, String> hook = new LinkedHashMap<String, String>();
        hook.put("armed", "1");
        hook.put("cc_hint", "cn");
        hook.put("locale", "zh_CN");
        hook.put("tz", "Asia/Shanghai");
        hook.put("simop", "52501");
        hook.put("simiso", "sg");
        hook.put("aid", "e78869c09b9f27ab");
        hook.put("model", "SM-S928B");
        hook.put("fp", SAMSUNG_FINGERPRINT);
        hook.put("su_file", "hidden");
        hook.put("su_os", "hidden");
        hook.put("su_system", "hidden");

        RiskModel model = ChatGptChecklist.build(data, hook, snapshot, "Asia/Singapore",
                "US", 10000L, true);
        check("device uuid is consistent",
                model.item("device.uuid").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("device.uuid").toString());
        // Segment's deviceId is the DRM-derived id or its random fallback, not anonymousId.
        check("segment anonymous is an observation",
                model.item("sdk.segment.anonymous").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("sdk.segment.anonymous").toString());
        check("segment device id is independent",
                model.item("sdk.segment.device").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("sdk.segment.device").toString());
        check("cookie prefix stays a boundary",
                model.item("integrity.play_prefix").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("integrity.play_prefix").toString());
        check("cookie age is read", "1h".equals(model.item("integrity.play_age").value()),
                model.item("integrity.play_age").toString());
        check("firebase MCC is a boundary",
                model.item("sdk.firebase.mcc").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("sdk.firebase.mcc").toString());
        check("firebase timezone matches",
                model.item("sdk.firebase.tz").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("sdk.firebase.tz").toString());
        check("firebase country is a boundary",
                model.item("sdk.firebase.country").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("sdk.firebase.country").toString());
        check("published hint is observed",
                "CN".equals(model.item("target.payload.cc_hint").value()),
                model.item("target.payload.cc_hint").toString());
        check("published hint participates in conflicts",
                model.item("target.payload.cc_hint").verdict() == CheckItem.Verdict.CONFLICT,
                model.item("target.payload.cc_hint").toString());
        check("product locale is a boundary",
                model.item("system.product_locale").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("system.product_locale").toString());
        check("country hint conflicts",
                model.item("target.data.country_hint").verdict()
                        == CheckItem.Verdict.CONFLICT,
                model.item("target.data.country_hint").toString());
        check("play country is a boundary",
                model.item("account.play_country").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("account.play_country").toString());
        check("target timezone is a boundary",
                model.item("target.tz").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("target.tz").toString());
        check("target carrier is observed",
                "Telstra".equals(model.item("target.carrier").value()),
                model.item("target.carrier").toString());
        check("target build parts are observed",
                "samsung|dm3q|dm3qzhw|qcom|SM-S928B"
                        .equals(model.item("target.build_parts").value()),
                model.item("target.build_parts").toString());
        check("target app version is observed",
                "1.2026.300 (2630015)".equals(model.item("target.version").value()),
                model.item("target.version").toString());
        check("target su remains hidden",
                model.item("target.su").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("target.su").toString());
        check("model verdict is conflict",
                model.verdict() == CheckItem.Verdict.CONFLICT, model.summary());

        data.put("payload_locale", "en-AU");
        data.put("payload_tz", "Australia/Brisbane");
        data.put("payload_simop", "50501");
        data.put("payload_simiso", "au");
        data.put("payload_aid", "e78869c09b9f27ab");
        data.put("payload_build", "SM-S918B|" + SAMSUNG_FINGERPRINT);
        RiskModel preview = ChatGptChecklist.build(data, new LinkedHashMap<String, String>(),
                snapshot, "Australia/Brisbane", "US", 10000L, false);
        check("chatgpt next launch locale comes from payload",
                "en-AU".equals(preview.item("target.locale").value())
                        && "next-launch".equals(preview.item("target.locale").note()),
                preview.item("target.locale").toString());
        check("chatgpt next launch build comes from payload",
                ("SM-S918B|" + SAMSUNG_FINGERPRINT)
                        .equals(preview.item("target.build").value())
                        && !preview.item("target.build").isStale(),
                preview.item("target.build").toString());
    }

    private static void claudeChecklistCoversAnalysisChains() {
        List<CheckItem> specs = ClaudeChecklist.specs();
        check("claude checklist is broad", specs.size() >= 45, "size=" + specs.size());
        Set<String> ids = new LinkedHashSet<String>();
        Set<String> channels = new LinkedHashSet<String>();
        Map<String, CheckItem> byId = new LinkedHashMap<String, CheckItem>();
        for (CheckItem item : specs) {
            ids.add(item.id());
            channels.add(item.channel());
            byId.put(item.id(), item);
        }
        check("claude ids are unique", ids.size() == specs.size(), "ids=" + ids.size());
        for (String channel : Arrays.asList("identity", "account", "integrity",
                "transport", "target", "system")) {
            check("claude channel present: " + channel, channels.contains(channel),
                    channels.toString());
        }
        check("claude trusted device has a local reader",
                byId.get("account.trusted_device").coverage()
                        != CheckItem.Coverage.NOT_COVERED,
                String.valueOf(byId.get("account.trusted_device").coverage()));
        check("claude tls has a local reader",
                byId.get("integrity.tls").coverage() != CheckItem.Coverage.NOT_COVERED,
                String.valueOf(byId.get("integrity.tls").coverage()));
        check("claude play token is server only",
                byId.get("integrity.play_token").coverage()
                        == CheckItem.Coverage.SERVER_ONLY,
                String.valueOf(byId.get("integrity.play_token").coverage()));
        check("claude constraints are broad",
                ClaudeChecklist.constraints().size() >= 12,
                "size=" + ClaudeChecklist.constraints().size());
    }

    private static void claudeChecklistBuildsKnownModel() {
        Snapshot snapshot = claudeSnapshot();
        Map<String, String> data = claudeData();
        Map<String, String> hook = claudeHook();
        RiskModel model = ClaudeChecklist.build(data, hook, snapshot, "Asia/Tokyo",
                "JP", true);
        check("claude device id matches growthbook",
                model.item("device.id").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("device.id").toString());
        check("claude segment user is a boundary",
                model.item("sdk.segment.user").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("sdk.segment.user").toString());
        check("claude target locale is observed",
                "ja_JP".equals(model.item("target.locale").value()),
                model.item("target.locale").toString());
        check("claude target payload is observed",
                "JP".equals(model.item("target.payload.cc_hint").value()),
                model.item("target.payload.cc_hint").toString());
        check("claude su paths stay hidden",
                model.item("target.su.paths").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("target.su.paths").toString());
        check("claude su packages stay hidden",
                model.item("target.su.packages").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("target.su.packages").toString());
        check("claude su properties stay clean",
                model.item("target.su.properties").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("target.su.properties").toString());
        check("claude su mounts stay ro",
                model.item("target.su.mounts").verdict() == CheckItem.Verdict.CONSISTENT,
                model.item("target.su.mounts").toString());
        check("claude datadog is observed",
                "fed68761-decf-4fad-bc83-d35d7fd3e6c5"
                        .equals(model.item("sdk.datadog.anonymous").value()),
                model.item("sdk.datadog.anonymous").toString());
        check("claude organization is a boundary",
                model.item("account.organization").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("account.organization").toString());
        check("claude subscription is a boundary",
                model.item("account.subscription").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("account.subscription").toString());
        check("claude target version is observed",
                "1.261030.19 (26103019)".equals(model.item("target.version").value()),
                model.item("target.version").toString());
        CheckConstraint buildConstraint = constraint(model, "transport.build_parts");
        check("claude build parts match the transport metadata",
                buildConstraint != null
                        && buildConstraint.verdict() == CheckItem.Verdict.CONSISTENT,
                String.valueOf(buildConstraint));
        check("claude transport is a boundary",
                model.item("transport.datatransport.country").verdict()
                        == CheckItem.Verdict.BOUNDARY,
                model.item("transport.datatransport.country").toString());
        check("claude play country is a boundary",
                model.item("account.play_country").verdict() == CheckItem.Verdict.BOUNDARY,
                model.item("account.play_country").toString());
        check("claude trusted device is observed",
                "empty".equals(model.item("account.trusted_device").value())
                        && model.item("account.trusted_device").verdict()
                        == CheckItem.Verdict.BOUNDARY,
                model.item("account.trusted_device").toString());
        check("claude tls state is observed",
                "clean".equals(model.item("integrity.tls").value())
                        && model.item("integrity.tls").verdict()
                        == CheckItem.Verdict.BOUNDARY,
                model.item("integrity.tls").toString());
        check("claude model has no conflict",
                model.verdict() != CheckItem.Verdict.CONFLICT, model.summary());
    }

    private static void claudeChecklistDetectsIdentitySplit() {
        Snapshot snapshot = claudeSnapshot();
        Map<String, String> data = claudeData();
        data.put("growthbook_unit", "0e3f8a5c-1111-4b2c-9d3e-5f6a7b8c9d0e");
        RiskModel model = ClaudeChecklist.build(data, claudeHook(), snapshot, "Asia/Tokyo",
                "JP", true);
        check("claude identity split is a conflict",
                model.item("device.id").verdict() == CheckItem.Verdict.CONFLICT
                        && model.item("sdk.growthbook.unit").verdict()
                        == CheckItem.Verdict.CONFLICT,
                model.summary());
        check("claude split is actionable",
                model.verdict() == CheckItem.Verdict.CONFLICT, model.summary());
    }

    private static void claudeChecklistDetectsBuildSplit() {
        Map<String, String> data = claudeData();
        data.put("transport_build", "samsung|dm3q|dm3qzhw|qcom|SM-S918B");
        RiskModel model = ClaudeChecklist.build(data, claudeHook(), claudeSnapshot(),
                "Asia/Tokyo", "JP", true);
        CheckConstraint buildConstraint = constraint(model, "transport.build_parts");
        check("claude build split is visible",
                buildConstraint != null
                        && buildConstraint.verdict() == CheckItem.Verdict.BOUNDARY,
                String.valueOf(buildConstraint));
        check("claude build split shows both sides",
                !model.mismatchesFor("transport.datatransport.build_parts").isEmpty(),
                model.item("transport.datatransport.build_parts").toString());
    }

    private static void claudeChecklistDetectsFingerprintBuildSplit() {
        Map<String, String> data = claudeData();
        data.put("transport_build", "motorola|dm3q|dm3qzhw|qcom|moto g");
        RiskModel model = ClaudeChecklist.build(data, claudeHook(), claudeSnapshot(),
                "Asia/Tokyo", "JP", true);
        CheckConstraint split = constraint(model, "transport.fingerprint_build");
        check("claude fingerprint/build split is visible",
                split != null && split.verdict() == CheckItem.Verdict.BOUNDARY,
                String.valueOf(split));
        check("claude fingerprint/build split shows both sides",
                !model.mismatchesFor("transport.datatransport.fingerprint").isEmpty(),
                model.item("transport.datatransport.fingerprint").toString());
    }

    private static void claudeNextLaunchUsesPayload() {
        Map<String, String> data = claudeData();
        data.put("payload_locale", "en-AU");
        data.put("payload_tz", "Australia/Darwin");
        data.put("payload_simop", "50501");
        data.put("payload_simiso", "au");
        data.put("payload_simname", "Telstra");
        data.put("payload_aid", "e78869c09b9f27ab");
        data.put("payload_build", "SM-S918B|" + SAMSUNG_FINGERPRINT);
        data.put("payload_build_parts", "samsung|dm3q|dm3qzhw|qcom|SM-S918B");
        RiskModel model = ClaudeChecklist.build(data, new LinkedHashMap<String, String>(),
                claudeSnapshot(), "Australia/Darwin", "AU", false);
        check("claude next launch locale comes from payload",
                "en-AU".equals(model.item("target.locale").value())
                        && "next-launch".equals(model.item("target.locale").note()),
                model.item("target.locale").toString());
        check("claude next launch build comes from payload",
                ("SM-S918B|" + SAMSUNG_FINGERPRINT)
                        .equals(model.item("target.build").value())
                        && !model.item("target.build").isStale(),
                model.item("target.build").toString());
    }

    private static void claudeChecklistFlagsRootEvidence() {
        Map<String, String> hook = claudeHook();
        hook.put("su_paths", "3/10");
        hook.put("su_props", "danger");
        RiskModel model = ClaudeChecklist.build(claudeData(), hook, claudeSnapshot(),
                "Asia/Tokyo", "JP", true);
        check("claude root path hit is a conflict",
                model.item("target.su.paths").verdict() == CheckItem.Verdict.CONFLICT,
                model.item("target.su.paths").toString());
        check("claude root property hit is a conflict",
                model.item("target.su.properties").verdict() == CheckItem.Verdict.CONFLICT,
                model.item("target.su.properties").toString());
    }

    private static void claudeStoppedTargetIsStale() {
        Map<String, String> data = claudeData();
        data.put("target_state", "stopped");
        Map<String, String> hook = new LinkedHashMap<String, String>();
        RiskModel model = ClaudeChecklist.build(data, hook, claudeSnapshot(), "Asia/Tokyo",
                "JP", false);
        check("stopped target su paths are stale",
                model.item("target.su.paths").isStale(),
                model.item("target.su.paths").toString());
        check("stopped target su packages are stale",
                model.item("target.su.packages").isStale(),
                model.item("target.su.packages").toString());
        check("stopped target su properties are stale",
                model.item("target.su.properties").isStale(),
                model.item("target.su.properties").toString());
        check("stopped target su mounts are stale",
                model.item("target.su.mounts").isStale(),
                model.item("target.su.mounts").toString());
        check("stopped target network view is stale",
                model.item("target.network_view").isStale(),
                model.item("target.network_view").toString());

        Map<String, String> old = claudeHook();
        old.remove("selfcheck");
        RiskModel oldModel = ClaudeChecklist.build(claudeData(), old, claudeSnapshot(),
                "Asia/Tokyo", "JP", true);
        check("old self-check format is stale, not unreadable",
                oldModel.item("target.su.paths").isStale(),
                oldModel.item("target.su.paths").toString());
    }

    private static void claudeMissingSegmentUserIsNoRecord() {
        Map<String, String> data = claudeData();
        data.remove("segment_user");
        data.put("segment_user_absent", "1");
        RiskModel model = ClaudeChecklist.build(data, claudeHook(), claudeSnapshot(),
                "Asia/Tokyo", "JP", true);
        check("segment user missing is no record",
                model.item("sdk.segment.user").coverage()
                        == CheckItem.Coverage.NO_RECORD,
                model.item("sdk.segment.user").toString());
    }

    private static Snapshot claudeSnapshot() {
        return base()
                .prop("persist.sys.locale", Text.of("ja-JP"))
                .prop("ro.product.locale", Text.of("ja-JP"))
                .prop("ro.csc.sales_code", Text.of("DCM"))
                .prop("ro.build.tags", Text.of("release-keys"))
                .prop("gsm.sim.operator.numeric", Text.of("44010"))
                .prop("gsm.sim.operator.iso-country", Text.of("jp"))
                .prop("gsm.operator.iso-country", Text.of("jp"))
                .prop("gsm.sim.operator.alpha", Text.of("NTT DOCOMO"))
                .sim(new SimValues(Text.of("44010"), Text.of("jp"), Text.of("jp"),
                        Text.of("NTT DOCOMO"), "jp", 10, 1))
                .androidId(Text.of("e78869c09b9f27ab"))
                .build();
    }

    private static Map<String, String> claudeData() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("device_id", "d0e3f8a5-1111-4b2c-9d3e-5f6a7b8c9d0e");
        data.put("growthbook_unit", "d0e3f8a5-1111-4b2c-9d3e-5f6a7b8c9d0e");
        data.put("segment_anonymous", "2a4c4369-de0d-4565-a5fa-d6050121be60");
        data.put("segment_user", "ua-d0e3f8a5-1111-4b2c-9d3e-5f6a7b8c9d0e");
        data.put("segment_device", "2a4c4369-de0d-4565-a5fa-d6050121be60");
        data.put("sift_installation", "89fbee203af24964a2a4359c8118d91a");
        data.put("sift_fingerprint",
                "d8a85653326d7dffa34344759ae9b1a860e66e616a95a5169d1dfa079be2322e");
        data.put("sentry_installation", "13dcfaf8-b426-4adc-d9da-efd948bd9bde");
        data.put("datadog_anonymous", "fed68761-decf-4fad-bc83-d35d7fd3e6c5");
        data.put("firebase_fid", "ffSgH8J9RbGXusy3bSSQYA");
        data.put("account_handle", "d0e3f8a5-1111-4b2c-9d3e-5f6a7b8c9d0e");
        data.put("account_plan", "pro");
        data.put("account_organization", "c701a1b6-5de5-4548-941b-c820c023a77e");
        data.put("account_subscription", "claude_pro");
        data.put("trusted_device", "empty");
        data.put("tls_exposed", "clean");
        data.put("installer", "com.android.vending");
        data.put("transport_country", "JP");
        data.put("transport_locale", "ja");
        data.put("transport_mcc", "44010");
        data.put("transport_tz", "32400");
        data.put("transport_fingerprint", SAMSUNG_FINGERPRINT);
        data.put("transport_build", "samsung|dm3q|dm3qzhw|qcom|SM-S928B");
        data.put("cfuvid", "1");
        data.put("session", "1");
        data.put("payload_cc_hint", "JP");
        data.put("target_app", "1.261030.19 (26103019)");
        data.put("target_state", "running");
        return data;
    }

    private static Map<String, String> claudeHook() {
        Map<String, String> hook = new LinkedHashMap<String, String>();
        hook.put("pid", "4242");
        hook.put("armed", "1");
        hook.put("selfcheck", "2");
        hook.put("res_locale", "ja_JP");
        hook.put("locale", "ja_JP");
        hook.put("tz", "Asia/Tokyo");
        hook.put("simop", "44010");
        hook.put("simiso", "jp");
        hook.put("simname", "NTT DOCOMO");
        hook.put("aid", "e78869c09b9f27ab");
        hook.put("manufacturer", "samsung");
        hook.put("device", "dm3q");
        hook.put("product", "dm3qzhw");
        hook.put("hardware", "qcom");
        hook.put("model", "SM-S928B");
        hook.put("fp", SAMSUNG_FINGERPRINT);
        hook.put("su_paths", "0/10");
        hook.put("su_packages", "0/17");
        hook.put("su_props", "clean");
        hook.put("su_mounts", "0/7");
        hook.put("ifaces", "wlan0");
        hook.put("proxy", "none");
        return hook;
    }

    private static CheckConstraint constraint(RiskModel model, String id) {
        for (CheckConstraint check : model.constraints()) {
            if (check.id().equals(id)) {
                return check;
            }
        }
        return null;
    }

    private static CheckItem testItem(String id) {
        return CheckItem.spec(id, "test", "src", CheckItem.Writable.NO, "",
                false, false, null);
    }

    private static CheckItem writableItem(String id) {
        return CheckItem.spec(id, "test", "src", CheckItem.Writable.YES, "",
                false, false, null);
    }

    // ---- fixtures ----------------------------------------------------------------------

    private static Tables catalog() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put(Tables.SU_PATHS, "# catalog selftest\n/sbin/su\n/system/bin/su\n");
        raw.put(Tables.RW_DIRS, "/system\n/sbin\n/etc\n");
        raw.put(Tables.MCC, "450=kr\n310=us\n");
        raw.put(Tables.CARRIERS, "45005=skt|sktelecom\n");
        raw.put(Tables.PACKAGE_KEYWORDS, "magisk\nfrida\n");
        raw.put(Tables.LABEL_KEYWORDS, "root\n");
        raw.put(Tables.EMULATOR_SIGNATURES, "goldfish\nranchu\nsdk_gphone\nemulator\n");
        raw.put(Tables.VENDOR_WORDS, "samsung\npixel\ngoogle\nmotorola\nmoto\nxiaomi\nredmi\n");
        raw.put(Tables.VENDOR_FAMILIES, "pixel=google\nmoto=motorola\nredmi=xiaomi\n");
        raw.put(Tables.ROOT_PACKAGES, "com.example.magisk\ncom.example.rootcloak\n");
        raw.put(Tables.MODERN_PACKAGES, "me.weishu.kernelsu\n");
        raw.put(Tables.LEAKED_CAS, LEAKED_CA + "\n");
        return Tables.parse(raw);
    }

    private static BuildValues samsung() {
        return BuildValues.builder()
                .manufacturer("samsung")
                .brand("samsung")
                .model("SM-S928B")
                .device("dm3q")
                .product("dm3qzhw")
                .type("user")
                .buildId("UP1A.231005.007")
                .incremental("S928BXXU4CXK1")
                .display("UP1A.231005.007.S928BXXU4CXK1")
                .fingerprint(SAMSUNG_FINGERPRINT)
                .tags("release-keys")
                .time(1730000000000L)
                .build();
    }

    private static Snapshot.Builder base() {
        return Snapshot.builder().tables(catalog()).buildValues(samsung())
                .capturedAt(1730000000000L);
    }

    private static Mount mount(String source, String mountPoint, String fsType, String options) {
        return new Mount(source, mountPoint, fsType, options);
    }

    // ---- synthetic location ------------------------------------------------------------

    private static void geoTableResolvesZoneAndCentre() {
        String text = "# iso|city|lat|lng|zone\n"
                + "us||39.8283|-98.5795|\n"
                + "us|New York|40.7128|-74.0060|America/New_York\n"
                + "zz|Broken|91.0000|0.0000|\n"
                + "zz|Broken|0.0000|181.0000|Bad/Zone\n";
        GeoTable table = GeoTable.parse(text);
        check("geo: one country centre", table.centers() == 1, "centers=" + table.centers());
        check("geo: one zone row", table.zones() == 1, "zones=" + table.zones());
        GeoTable.Point zone = table.point("us", "America/New_York");
        check("geo: zone wins", zone != null && "New York".equals(zone.city)
                && Math.abs(zone.latitude - 40.7128d) < 1.0E-9d, String.valueOf(zone));
        GeoTable.Point centre = table.point("us", "America/Chicago");
        check("geo: country centre falls back", centre != null && centre.city.isEmpty()
                && Math.abs(centre.longitude + 98.5795d) < 1.0E-9d, String.valueOf(centre));
        check("geo: unknown iso is null", table.point("xx", null) == null);
        check("geo: invalid rows skipped", table.point("zz", "Bad/Zone") == null);
    }

    private static void locationPayloadIsSelfContained() {
        String json = LocationPayload.json(true, 35.6762d, 139.6503d, 42L, "Tokyo");
        String expected = "{\"enabled\":true,\"latitude\":35.6762,\"longitude\":139.6503,"
                + "\"seed\":42,\"city\":\"Tokyo\"}";
        check("location payload: exact shape", expected.equals(json), json);
        String escaped = LocationPayload.json(false, 0.0d, 0.0d, -1L, "A\"B\\C");
        check("location payload: escapes city",
                escaped.contains("\"city\":\"A\\\"B\\\\C\"}"), escaped);
    }

    private static void locationPlanStaysInsideItsRadius() {
        LocationPlan plan = new LocationPlan(35.6762d, 139.6503d, 42L, false);
        LocationFix first = plan.fix(1700000000000L, 1234567890123L);
        LocationFix second = plan.fix(1700000000000L, 1234567890123L);
        check("location: deterministic coordinates",
                first.latitude == second.latitude && first.longitude == second.longitude,
                first.latitude + "," + first.longitude);
        double north = (first.latitude - 35.6762d) * 111320.0d;
        double east = (first.longitude - 139.6503d) * 111320.0d
                * Math.cos(Math.toRadians(35.6762d));
        double distance = Math.hypot(north, east);
        check("location: jitter inside 4 m", distance <= 4.01d, "distance=" + distance);
        check("location: accuracy in 5 m band",
                first.accuracy >= 4.39f && first.accuracy <= 5.61f,
                "accuracy=" + first.accuracy);
        check("location: altitude/speed/bearing present",
                first.hasAltitude && first.hasSpeed && first.hasBearing);
        check("location: satellite count", first.satellites == 20, "sat=" + first.satellites);
        check("location: clocks copied", first.timeMillis == 1700000000000L
                && first.elapsedRealtimeNanos == 1234567890123L);
    }

    private static void coarseLocationSnapsAndDropsMotion() {
        LocationPlan plan = new LocationPlan(35.6762d, 139.6503d, 42L, true);
        LocationFix fix = plan.fix(1700000000000L, 1234567890123L);
        check("coarse: no altitude", !fix.hasAltitude);
        check("coarse: no speed", !fix.hasSpeed);
        check("coarse: no bearing", !fix.hasBearing);
        check("coarse: accuracy at least 3 km", fix.accuracy >= 3000.0f,
                "accuracy=" + fix.accuracy);
        double north = (fix.latitude - 35.6762d) * 111320.0d;
        double east = (fix.longitude - 139.6503d) * 111320.0d
                * Math.cos(Math.toRadians(35.6762d));
        double distance = Math.hypot(north, east);
        check("coarse: snapped no farther than 3.5 km", distance <= 3500.0d,
                "distance=" + distance);
    }

    private static void locationPlanRejectsImpossibleCoordinates() {
        check("location: valid point", LocationPlan.valid(0.0d, 0.0d));
        check("location: latitude bound", !LocationPlan.valid(90.1d, 0.0d));
        check("location: longitude bound", !LocationPlan.valid(0.0d, -180.1d));
        check("location: NaN rejected", !LocationPlan.valid(Double.NaN, 0.0d));
        boolean rejected = false;
        try {
            new LocationPlan(91.0d, 0.0d, 0L, false);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check("location: constructor rejects", rejected);
    }

    // ---- harness -----------------------------------------------------------------------

    private static void expect(String name, Finding finding, Status status, Reason reason) {
        check(name, finding.status() == status && finding.reason() == reason,
                "got " + finding.status() + "/" + finding.reason() + " (observed="
                        + finding.observed() + ")");
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "condition=false");
    }

    private static void check(String name, boolean condition, String detail) {
        checks++;
        if (!condition) {
            FAILURES.add(name + ": " + detail);
        }
    }

    private static void report() {
        System.out.println("selftest: " + (checks - FAILURES.size()) + "/" + checks
                + " checks passed");
        for (String failure : FAILURES) {
            System.out.println("  FAILED " + failure);
        }
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }
}
