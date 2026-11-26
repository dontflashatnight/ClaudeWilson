package com.walter.claudewilson.selftest;

import com.walter.claudewilson.model.Account;
import com.walter.claudewilson.model.AppVisibilityPolicy;
import com.walter.claudewilson.model.AtomicTextFile;
import com.walter.claudewilson.model.AttestationFacts;
import com.walter.claudewilson.model.AuditedBaselines;
import com.walter.claudewilson.model.BodyguardEventCodec;
import com.walter.claudewilson.model.BodyguardPolicy;
import com.walter.claudewilson.model.IpGuardPolicy;
import com.walter.claudewilson.model.BuildValues;
import com.walter.claudewilson.model.CheckConstraint;
import com.walter.claudewilson.model.CheckItem;
import com.walter.claudewilson.model.ChatGptChecklist;
import com.walter.claudewilson.model.ClaudeChecklist;
import com.walter.claudewilson.model.CoordinateParser;
import com.walter.claudewilson.model.DiagnosticClassifier;
import com.walter.claudewilson.model.NetworkFacts;
import com.walter.claudewilson.model.NetworkRules;
import com.walter.claudewilson.model.PlatformFacts;
import com.walter.claudewilson.model.PersonaSalt;
import com.walter.claudewilson.model.PersonaSynth;
import com.walter.claudewilson.model.TlsFacts;
import com.walter.claudewilson.model.UserSpaces;
import com.walter.claudewilson.model.Command;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.BleProfile;
import com.walter.claudewilson.model.GeoTable;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.LocationFix;
import com.walter.claudewilson.model.LocationFacts;
import com.walter.claudewilson.model.LocationPayload;
import com.walter.claudewilson.model.LocationPlan;
import com.walter.claudewilson.model.MapProjection;
import com.walter.claudewilson.model.PermissionFacts;
import com.walter.claudewilson.model.PersonaEditRules;
import com.walter.claudewilson.model.PersonaMatchEngine;
import com.walter.claudewilson.model.DiagnosticArchiveParser;
import com.walter.claudewilson.model.Ipv6Policy;
import com.walter.claudewilson.model.PreflightVerdict;
import com.walter.claudewilson.model.ProfileIdentity;
import com.walter.claudewilson.model.WifiProfile;
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
import com.walter.claudewilson.model.SensorProfile;
import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.SimPolicy;
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
        geoTableSearchesAndFindsNearest();
        mapProjectionRoundTrips();
        permissionFactsDistinguishStates();
        locationFactsExposeModeAndNoCoordinates();
        wifiAndBleProfilesAreDeterministic();
        checklistsIncludePermissionRows();
        locationPayloadIsSelfContained();
        locationPlanStaysInsideItsRadius();
        coarseLocationSnapsAndDropsMotion();
        locationPlanRejectsImpossibleCoordinates();
        checklistsIncludeIpv6SpoofAndLinkFallback();
        personaSynthVaryIsIsomorphic();
        personaSynthAlignsAcrossApi33To37();
        fingerprintCoherenceDetectsCrossGenMismatches();
        propCoherenceDetectsSdkAndReleaseMismatches();
        auditedBaselinesDetectVersionDrift();
        simPolicyEnforcesRestrictedCatalogAndDualSimSafety();
        personaMatchEngineDetectsLeaksTaintAndDrift();
        bodyguardPolicyAndDiagnosticClassifierWork();
        bodyguardEventCodecBoundsAndClassifies();
        ipGuardPolicyEvaluatesAndBuildsScripts();
        personasCatalogAssetIsValidAndSelfConsistent();
        personaEditRulesAndGeoCitiesWork();
        preflightVerdictPrioritizesBlockersAndWarnings();
        ipv6PolicyDefaultsOffAndBuildsWatchdogCommands();
        diagnosticArchiveParserSplitsStagingCachesCleanly();
        coordinateParserAndRadioOverridesWork();
        personaSaltLayersAndMemoryRulesWork();
        atomicTextFilePersistsAndReplacesCleanly();

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
        check("shell rc zero is not a failure", !ShellQuote.failed("rc=0"), "failed");
        check("shell rc non-zero is a failure", ShellQuote.failed("rc=1"), "not failed");
        check("shell trailing rc non-zero is a failure",
                ShellQuote.failed("mkdir: Permission denied\nrc=1"), "not failed");
        check("mid-line rc token does not masquerade as exit code",
                !ShellQuote.failed("CLEAR pkg=a.b user=0 rc=0 out=Success\nDONE"), "failed");
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
        check("config dir hidden", RootArtifactPolicy.hidden("/data/local/tmp/claudewilson/claude.json"));
        check("legacy config hidden", RootArtifactPolicy.hidden("/data/local/tmp/persona.json"));
        check("legacy hookcheck hidden",
                RootArtifactPolicy.hidden("/data/data/com.anthropic.claude/files/hookcheck.txt"));
        check("legacy cw-hookcheck hidden",
                RootArtifactPolicy.hidden("/sdcard/Android/data/com.anthropic.claude/files/cw-hookcheck.main.txt"));
        check("legacy cw-telemetry hidden",
                RootArtifactPolicy.hidden("/data/data/com.anthropic.claude/cache/cw-telemetry/events.jsonl"));
        check("lookalike path kept", !RootArtifactPolicy.hidden("/system/bin/su2"));
        check("ordinary path kept", !RootArtifactPolicy.hidden("/system/bin/sh"));
        check("ordinary target file kept",
                !RootArtifactPolicy.hidden("/data/data/com.anthropic.claude/files/INSTALLATION"));
        check("su command hidden", RootArtifactPolicy.isSuCommand(new String[]{"su", "-c", "id"}));
        check("which su hidden", RootArtifactPolicy.isSuCommand("which su"));
        check("absolute su hidden", RootArtifactPolicy.isSuCommand("/system/bin/su -c id"));
        check("issue is not su", !RootArtifactPolicy.isSuCommand("issue"));
        check("sudo is not su", !RootArtifactPolicy.isSuCommand("sudo -i"));
        check("control app package hidden",
                RootArtifactPolicy.hiddenPackage("com.walter.claudewilson"));
        check("magisk package hidden",
                RootArtifactPolicy.hiddenPackage("com.topjohnwu.magisk"));
        check("lsposed package hidden",
                RootArtifactPolicy.hiddenPackage("org.lsposed.manager"));
        check("shizuku package hidden",
                RootArtifactPolicy.hiddenPackage("moe.shizuku.privileged.api"));
        check("target package kept",
                !RootArtifactPolicy.hiddenPackage("com.anthropic.claude"));
        check("play store package kept",
                !RootArtifactPolicy.hiddenPackage("com.android.vending"));
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

    private static void geoTableSearchesAndFindsNearest() {
        String text = "us||39.8283|-98.5795|\n"
                + "us|New York|40.7128|-74.0060|America/New_York\n"
                + "jp|Tokyo|35.6762|139.6503|Asia/Tokyo\n";
        GeoTable table = GeoTable.parse(text);
        check("geo search: city", table.search("new", 5).size() == 1
                && "New York".equals(table.search("new", 5).get(0).city));
        check("geo search: iso", !table.search("us", 5).isEmpty());
        GeoTable.Point near = table.nearest(35.7d, 139.7d);
        check("geo nearest: Tokyo", near != null && "Tokyo".equals(near.city));
    }

    private static void mapProjectionRoundTrips() {
        double[][] points = {{0, 0}, {35.6762, 139.6503}, {-33.8688, 151.2093},
                {51.5074, -0.1278}, {-45, 179.9}};
        for (double[] point : points) {
            double lat = MapProjection.latitudeAt(MapProjection.y(point[0]));
            double lng = MapProjection.longitudeAt(MapProjection.x(point[1]));
            check("map projection roundtrip", Math.abs(lat - point[0]) < 1.0E-9d
                    && Math.abs(lng - point[1]) < 1.0E-9d, lat + "," + lng);
        }
        check("map projection clamps latitude", MapProjection.latitudeAt(0.0d)
                == MapProjection.MAX_LATITUDE);
        check("map projection wraps longitude",
                Math.abs(MapProjection.longitudeAt(1.0d) + 180.0d) < 1.0E-9d);
    }

    private static void permissionFactsDistinguishStates() {
        PermissionFacts facts = new PermissionFacts.Builder()
                .put("location.fine", true, false)
                .put("location.coarse", true, true)
                .approximate("location.fine")
                .put("calendar", false, false)
                .appOp("location.fine", "ignore")
                .readable(true)
                .build();
        check("permission: readable", facts.readable());
        check("permission: approximate", facts.state("location.fine")
                == PermissionFacts.State.APPROXIMATE);
        check("permission: granted", facts.state("location.coarse")
                == PermissionFacts.State.GRANTED);
        check("permission: not requested", facts.state("calendar")
                == PermissionFacts.State.NOT_REQUESTED);
        check("permission: appops", "ignore".equals(facts.appOp("location.fine")));
        check("permission: empty unreadable", !PermissionFacts.EMPTY.readable());
    }

    private static void locationFactsExposeModeAndNoCoordinates() {
        LocationFacts facts = new LocationFacts.Builder()
                .mode(LocationFacts.Mode.HIGH)
                .providers("gps,network")
                .gps(true)
                .network(true)
                .mock(false)
                .readable(true)
                .build();
        check("location facts: mode label", "high-accuracy".equals(facts.modeLabel()));
        check("location facts: providers", facts.gps() && facts.network());
        check("location facts: no coordinate API",
                !hasMethod(LocationFacts.class, "latitude")
                        && !hasMethod(LocationFacts.class, "longitude"));
    }

    private static boolean hasMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void wifiAndBleProfilesAreDeterministic() {
        java.util.List<WifiProfile.Ap> first = WifiProfile.forRegion(
                35.6762d, 139.6503d, "Tokyo", 42L, 8);
        java.util.List<WifiProfile.Ap> second = WifiProfile.forRegion(
                35.6762d, 139.6503d, "Tokyo", 42L, 8);
        check("wifi: deterministic size",
                first.size() == second.size() && first.size() >= 3,
                first.size() + "/" + second.size());
        check("wifi: deterministic bssid",
                first.get(1).bssid.equals(second.get(1).bssid));
        check("wifi: one connected", first.get(0).connected);
        check("wifi: valid bssid",
                first.get(0).bssid.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"));
        check("wifi: json shape", WifiProfile.json(first).startsWith("[{")
                && WifiProfile.json(first).endsWith("}]"));
        java.util.List<BleProfile.Device> ble = BleProfile.forRegion(42L, 4);
        check("ble: deterministic size", ble.size() >= 2, "size=" + ble.size());
        check("ble: valid address",
                ble.get(0).address.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"));
        check("ble: json shape", BleProfile.json(ble).contains("\"rssi\":"));
        java.util.List<WifiProfile.Ap> otherWifi = WifiProfile.forRegion(
                35.6762d, 139.6503d, "Tokyo", 43L, 8);
        java.util.Set<String> firstBssid = new java.util.HashSet<>();
        for (WifiProfile.Ap ap : first) {
            firstBssid.add(ap.bssid);
        }
        boolean sharedBssid = false;
        for (WifiProfile.Ap ap : otherWifi) {
            if (firstBssid.contains(ap.bssid)) {
                sharedBssid = true;
            }
        }
        check("wifi: different seed has different bssids", !sharedBssid);
        java.util.List<BleProfile.Device> otherBle = BleProfile.forRegion(43L, 4);
        java.util.Set<String> firstBle = new java.util.HashSet<>();
        for (BleProfile.Device device : ble) {
            firstBle.add(device.address);
        }
        boolean sharedBle = false;
        for (BleProfile.Device device : otherBle) {
            if (firstBle.contains(device.address)) {
                sharedBle = true;
            }
        }
        check("ble: different seed has different addresses", !sharedBle);
        profileIdentityIsFieldBased();
    }

    private static void profileIdentityIsFieldBased() {
        String[] pixel = {"Pixel 9", "Google", "google", "Pixel 9", "tokay", "tokay"};
        String[] samsung = {"Galaxy S24U", "samsung", "samsung", "SM-S928B", "e3q", "e3q"};
        String a = ProfileIdentity.of(pixel);
        String b = ProfileIdentity.of(pixel);
        check("identity: stable by fields", a.equals(b));
        check("identity: model changes identity",
                !a.equals(ProfileIdentity.of(samsung)));
        check("identity: key is short hex",
                ProfileIdentity.key(a).matches("[0-9a-f]{16}"));
        check("identity: unknown fallback",
                "unknown".equals(ProfileIdentity.of(null)));
        check("identity: usable only with fields",
                ProfileIdentity.usable(a)
                        && !ProfileIdentity.usable(ProfileIdentity.of(null))
                        && !ProfileIdentity.usable(ProfileIdentity.of(new String[0])));
    }

    private static void checklistsIncludePermissionRows() {
        boolean claude = false;
        for (CheckItem item : ClaudeChecklist.specs()) {
            claude |= "permission.location.fine".equals(item.id())
                    && "permission".equals(item.channel());
        }
        boolean chatgpt = false;
        for (CheckItem item : ChatGptChecklist.specs()) {
            chatgpt |= "location.appops".equals(item.id());
        }
        check("checklists: claude permission row", claude);
        check("checklists: chatgpt location row", chatgpt);
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
        check("location: jitter inside profile radius", distance <= 5.01d,
                "distance=" + distance);
        check("location: profile accuracy band",
                first.accuracy >= 3.99f && first.accuracy <= 5.2f,
                "accuracy=" + first.accuracy);
        check("location: altitude/speed present",
                first.hasAltitude && first.hasSpeed);
        check("location: satellite count in range",
                first.satellites >= 8 && first.satellites <= 24,
                "sat=" + first.satellites);
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

    private static void checklistsIncludeIpv6SpoofAndLinkFallback() {
        boolean claudeV6 = false;
        for (CheckItem item : ClaudeChecklist.specs()) {
            claudeV6 |= "target.ipv6_spoof".equals(item.id());
        }
        boolean chatgptV6 = false;
        for (CheckItem item : ChatGptChecklist.specs()) {
            chatgptV6 |= "target.ipv6_spoof".equals(item.id());
        }
        check("checklists: claude ipv6_spoof row", claudeV6);
        check("checklists: chatgpt ipv6_spoof row", chatgptV6);

        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("target_installed", "true");
        data.put("target_running", "true");
        Map<String, String> hook = new LinkedHashMap<String, String>();
        hook.put("armed", "1");
        hook.put("iface_ips", "192.168.1.25,");
        hook.put("link_addrs", "wlan0|192.168.1.25/24,2003:d4:8f12:3a01::7/64,");
        hook.put("ipv6_spoof", "2003:d4:8f12:3a01::7");
        RiskModel gptModel = ChatGptChecklist.build(
                data, hook, base().build(), "Europe/Berlin", "DE", 1700000000000L, true);
        CheckItem netItem = gptModel.item("target.network_addresses");
        CheckItem spoofItem = gptModel.item("target.ipv6_spoof");
        check("chatgpt link_addrs fallback populates global v6",
                netItem != null && netItem.value() != null
                        && netItem.value().contains("2003:d4:8f12:3a01::7"),
                netItem == null ? "null" : netItem.value());
        check("chatgpt ipv6_spoof item read",
                spoofItem != null && "2003:d4:8f12:3a01::7".equals(spoofItem.value()),
                spoofItem == null ? "null" : spoofItem.value());
    }

    // ---- Android 13-17 persona synthesis & cross-generation coherence ------------------

    private static void personaSynthVaryIsIsomorphic() {
        String[] pixel = {"Pixel 9", "Google", "google", "Pixel 9", "tokay", "tokay",
                "tokay", "tokay",
                "google/tokay/tokay:15/AP3A.241005.015/12270533:user/release-keys",
                "AP3A.241005.015", "12270533", "15", "35", "2024-10-05"};
        String[] oneplus = {"OnePlus 12", "OnePlus", "OnePlus", "CPH2581", "OP5959L1",
                "CPH2581EE", "qcom", "kalama",
                "OnePlus/CPH2581EE/OP5959L1:15/UKQ1.230924.001/R.1e31d5:user/release-keys",
                "UKQ1.230924.001", "R.1e31d5", "15", "35", "2024-10-05"};
        for (long seed = 1L; seed <= 25L; seed++) {
            String[] p = PersonaSynth.cross(pixel, seed);
            check("aosp .015 suffix stays 3 digits (seed=" + seed + ")",
                    p[9].matches("AP3A\\.241005\\.\\d{3}"), p[9]);
            check("pixel incremental stays 8 digits (seed=" + seed + ")",
                    p[10].matches("[1-9]\\d{7}"), p[10]);

            String[] s = PersonaSynth.cross(SAMSUNG_ROW, seed);
            check("samsung build id preserves dots/case/digits (seed=" + seed + ")",
                    s[9].matches("AP3A\\.240905\\.01\\d\\.[A-Z]\\d"), s[9]);
            check("samsung incremental keeps uppercase/digit classes (seed=" + seed + ")",
                    s[10].matches("S928BXXS5[A-Z]{3}\\d"), s[10]);

            String[] op = PersonaSynth.cross(oneplus, seed);
            check("oneplus R.1e31d5 stays lowercase hex (seed=" + seed + ")",
                    op[10].matches("R\\.1e[0-9a-f]{4}"), op[10]);
            check("oneplus build id keeps .001 numeric suffix (seed=" + seed + ")",
                    op[9].matches("UKQ1\\.230924\\.\\d{3}"), op[9]);
        }
    }

    private static void personaSynthAlignsAcrossApi33To37() {
        String[] pixelTemplate = {"Pixel 9 Pro", "Google", "google", "Pixel 9 Pro",
                "caiman", "caiman", "caiman", "caiman",
                "google/caiman/caiman:15/AP3A.241005.015/12270533:user/release-keys",
                "AP3A.241005.015", "12270533", "15", "35", "2024-10-05"};
        int[] sdks = {33, 34, 35, 36, 37};
        String[] releases = {"13", "14", "15", "16", "17"};
        String[] prefixes = {"TQ3A", "AP2A", "AP3A", "BP3A", "CP1A"};
        int[] minYears = {2023, 2024, 2024, 2025, 2026};
        int[] maxYears = {2023, 2024, 2025, 2026, 2026};

        for (int i = 0; i < sdks.length; i++) {
            int sdk = sdks[i];
            String rel = releases[i];
            String[] aligned = PersonaSynth.alignToHostSdk(pixelTemplate, sdk, rel, 700L + i);
            check("align api " + sdk + ": sdk anchored",
                    String.valueOf(sdk).equals(aligned[12]), aligned[12]);
            check("align api " + sdk + ": release anchored",
                    rel.equals(aligned[11]), aligned[11]);
            check("align api " + sdk + ": fingerprint release segment",
                    aligned[8].contains(":" + rel + "/"), aligned[8]);
            check("align api " + sdk + ": build prefix",
                    aligned[9].startsWith(prefixes[i] + "."), aligned[9]);
            check("align api " + sdk + ": security patch format",
                    aligned[13].matches("\\d{4}-\\d{2}-\\d{2}"), aligned[13]);
            int year = Integer.parseInt(aligned[13].substring(0, 4));
            check("align api " + sdk + ": security patch year valid",
                    year >= minYears[i] && year <= maxYears[i], aligned[13]);
            String idYm = "20" + aligned[9].substring(5, 7) + "-" + aligned[9].substring(7, 9);
            check("align api " + sdk + ": build ID and patch share YYYY-MM",
                    aligned[13].startsWith(idYm + "-"), aligned[9] + " vs " + aligned[13]);

            BuildValues bv = fromRow(aligned).toBuilder()
                    .release(aligned[11])
                    .sdkInt(sdk)
                    .sdkIntFull(sdk >= 36 ? sdk * 100000 : 0)
                    .build();
            Snapshot snapshot = base().buildValues(bv).build();
            expect("align api " + sdk + ": passes fingerprint coherence",
                    new FingerprintCoherenceProbe().run(snapshot), Status.PASS, Reason.OK);
        }

        // Also verify Samsung suffix (.A2) is preserved across API remapping (e.g. 35 -> 36).
        String[] samsung36 = PersonaSynth.alignToHostSdk(SAMSUNG_ROW, 36, "16", 99L);
        check("samsung api 36 preserves .A2 suffix",
                samsung36[9].startsWith("BP3A.") && samsung36[9].endsWith(".A2"), samsung36[9]);
        check("samsung api 36 fingerprint carries :16/",
                samsung36[8].contains(":16/BP3A."), samsung36[8]);
    }

    private static void fingerprintCoherenceDetectsCrossGenMismatches() {
        BuildValues coherent16 = BuildValues.builder()
                .manufacturer("Google")
                .brand("google")
                .model("Pixel 10 Pro")
                .device("blazer")
                .product("blazer")
                .type("user")
                .buildId("BP3A.250905.014")
                .incremental("13873947")
                .display("BP3A.250905.014")
                .fingerprint("google/blazer/blazer:16/BP3A.250905.014/13873947:user/release-keys")
                .tags("release-keys")
                .release("16")
                .sdkInt(36)
                .sdkIntFull(3600000)
                .time(1757000000000L)
                .build();
        expect("coherent Android 16 build passes",
                new FingerprintCoherenceProbe().run(base().buildValues(coherent16).build()),
                Status.PASS, Reason.OK);

        // fp.release (:16/) vs Build.VERSION.RELEASE (15)
        Snapshot fpRelMismatch = base()
                .buildValues(coherent16.toBuilder().release("15").sdkInt(35).sdkIntFull(0).build())
                .build();
        expect("fingerprint release mismatch fails",
                new FingerprintCoherenceProbe().run(fpRelMismatch),
                Status.FAIL, Reason.INCONSISTENT);

        // SDK_INT (35) vs Build.VERSION.RELEASE (16)
        Snapshot sdkRelMismatch = base()
                .buildValues(coherent16.toBuilder().sdkInt(35).sdkIntFull(0).build())
                .build();
        expect("sdk_int vs release mismatch fails",
                new FingerprintCoherenceProbe().run(sdkRelMismatch),
                Status.FAIL, Reason.INCONSISTENT);

        // SDK_INT (35) vs SDK_INT_FULL (3600000)
        Snapshot sdkFullMismatch = base()
                .buildValues(coherent16.toBuilder().sdkInt(35).sdkIntFull(3600000).build())
                .build();
        expect("sdk_int vs sdk_int_full mismatch fails",
                new FingerprintCoherenceProbe().run(sdkFullMismatch),
                Status.FAIL, Reason.INCONSISTENT);

        // Empty release field, but sdk_int (36) disagrees with fingerprint release (:14/)
        Snapshot fpSdkMismatch = base()
                .buildValues(samsung().toBuilder().sdkInt(36).build())
                .build();
        expect("sdk_int vs fingerprint release mismatch fails",
                new FingerprintCoherenceProbe().run(fpSdkMismatch),
                Status.FAIL, Reason.INCONSISTENT);
    }

    private static void propCoherenceDetectsSdkAndReleaseMismatches() {
        BuildValues bv16 = samsung().toBuilder()
                .release("16")
                .sdkInt(36)
                .sdkIntFull(3600001)
                .build();
        Snapshot coherent = base()
                .buildValues(bv16)
                .prop("ro.build.version.release", Text.of("16"))
                .prop("ro.build.version.release_or_codename", Text.of("16"))
                .prop("ro.build.version.sdk", Text.of("36"))
                .prop("ro.build.version.sdk_full", Text.of("36.1"))
                .build();
        expect("coherent sdk/sdk_full/release props pass",
                new PropCoherenceProbe().run(coherent), Status.PASS, Reason.OK);

        // ro.build.version.sdk disagrees with Build.VERSION.SDK_INT
        Snapshot sdkMismatch = base()
                .buildValues(bv16)
                .prop("ro.build.version.sdk", Text.of("35"))
                .build();
        expect("ro.build.version.sdk mismatch fails",
                new PropCoherenceProbe().run(sdkMismatch), Status.FAIL, Reason.INCONSISTENT);

        // ro.build.version.sdk_full disagrees with SDK_INT_FULL
        Snapshot sdkFullMismatch = base()
                .buildValues(bv16)
                .prop("ro.build.version.sdk_full", Text.of("36.0"))
                .build();
        expect("ro.build.version.sdk_full minor mismatch fails",
                new PropCoherenceProbe().run(sdkFullMismatch), Status.FAIL, Reason.INCONSISTENT);

        // ro.build.version.sdk_full disagrees with SDK_INT when sdkIntFull is unset
        Snapshot sdkFullVsSdk = base()
                .buildValues(samsung().toBuilder().sdkInt(35).build())
                .prop("ro.build.version.sdk_full", Text.of("36.0"))
                .build();
        expect("ro.build.version.sdk_full vs sdk_int mismatch fails",
                new PropCoherenceProbe().run(sdkFullVsSdk), Status.FAIL, Reason.INCONSISTENT);

        // ro.build.version.release_or_codename disagrees with ro.build.version.release
        Snapshot relCodenameMismatch = base()
                .buildValues(bv16)
                .prop("ro.build.version.release", Text.of("16"))
                .prop("ro.build.version.release_or_codename", Text.of("15"))
                .build();
        expect("ro.build.version.release_or_codename mismatch fails",
                new PropCoherenceProbe().run(relCodenameMismatch),
                Status.FAIL, Reason.INCONSISTENT);

        // Developer preview build where codename != REL should not false-positive
        Snapshot preview = base()
                .buildValues(samsung().toBuilder().release("17").sdkInt(37).build())
                .prop("ro.build.version.release", Text.of("17"))
                .prop("ro.build.version.codename", Text.of("CinnamonBun"))
                .prop("ro.build.version.release_or_codename", Text.of("CinnamonBun"))
                .prop("ro.build.version.sdk", Text.of("37"))
                .build();
        expect("preview codename release_or_codename passes",
                new PropCoherenceProbe().run(preview), Status.PASS, Reason.OK);

        // target.sdk checklist row reads self-check fields
        Map<String, String> hook = claudeHook();
        hook.put("sdk_int", "36");
        hook.put("sdk_int_full", "3600000");
        hook.put("release", "16");
        hook.put("sdk_prop", "36");
        hook.put("sdk_full_prop", "36.0");
        RiskModel claudeModel = ClaudeChecklist.build(claudeData(), hook, claudeSnapshot(),
                "Asia/Tokyo", "JP", true);
        CheckItem sdkItem = claudeModel.item("target.sdk");
        check("claude target.sdk populated",
                sdkItem != null && sdkItem.value() != null
                        && sdkItem.value().contains("API 36 (Android 16)")
                        && sdkItem.value().contains("full=3600000"),
                sdkItem == null ? "null" : sdkItem.value());
    }

    private static void auditedBaselinesDetectVersionDrift() {
        String raw = "# comment\n\nclaude=26111319|1.261113.19\nchatgpt=2600100|1.2026.272\n"
                + "bad_line_no_eq\nbad_code=abc|1.0\nzero=0|0.0\n";
        Map<String, AuditedBaselines.Baseline> map = AuditedBaselines.parse(raw);
        check("audited baselines count", map.size() == 2, "got=" + map.size());
        AuditedBaselines.Baseline claude = map.get("claude");
        check("claude baseline parsed",
                claude != null && claude.versionCode() == 26111319L
                        && "1.261113.19".equals(claude.versionName()),
                claude == null ? "null" : claude.versionCode() + "|" + claude.versionName());

        check("same versionCode matches",
                AuditedBaselines.assess(true, 26111319L, "1.261113.19", claude)
                        == AuditedBaselines.Drift.MATCHED);
        check("higher versionCode is UPDATED",
                AuditedBaselines.assess(true, 26111400L, "1.261114.0", claude)
                        == AuditedBaselines.Drift.UPDATED);
        check("lower versionCode is OLDER",
                AuditedBaselines.assess(true, 26111200L, "1.261112.0", claude)
                        == AuditedBaselines.Drift.OLDER);
        check("missing baseline is UNAUDITED",
                AuditedBaselines.assess(true, 26111319L, "1.261113.19", null)
                        == AuditedBaselines.Drift.UNAUDITED);
        check("uninstalled target is UNKNOWN",
                AuditedBaselines.assess(false, 26111319L, "1.261113.19", claude)
                        == AuditedBaselines.Drift.UNKNOWN);
        check("fallback versionName match when code=0",
                AuditedBaselines.assess(true, 0L, "1.261113.19", claude)
                        == AuditedBaselines.Drift.MATCHED);
        check("fallback versionName mismatch when code=0 is UPDATED",
                AuditedBaselines.assess(true, 0L, "1.261114.0", claude)
                        == AuditedBaselines.Drift.UPDATED);

        String[] s25 = PersonaSynth.socFor("samsung", "SM-S938B", "pa3q", "qcom", "sun");
        check("S25U SoC is QTI SM8750",
                "QTI".equals(s25[0]) && "SM8750".equals(s25[1]) && "SM-S938B".equals(s25[2]),
                Arrays.toString(s25));
        String[] s23 = PersonaSynth.socFor("samsung", "SM-S918B", "dm3q", "qcom", "kalama");
        check("S23U SoC is QTI SM8550",
                "QTI".equals(s23[0]) && "SM8550".equals(s23[1]), Arrays.toString(s23));
        String[] s24 = PersonaSynth.socFor("samsung", "SM-S928B", "e3q", "qcom", "kalama");
        check("S24U SoC is QTI SM8650",
                "QTI".equals(s24[0]) && "SM8650".equals(s24[1]), Arrays.toString(s24));
        String[] p9 = PersonaSynth.socFor("Google", "Pixel 9 Pro", "caiman", "caiman", "caiman");
        check("Pixel 9 Pro SoC is Google Tensor G4",
                "Google".equals(p9[0]) && "Tensor G4".equals(p9[1]) && "GR83Y".equals(p9[2]),
                Arrays.toString(p9));
        String[] a55 = PersonaSynth.socFor("samsung", "SM-A556E", "a55x", "s5e8845", "erd8845");
        check("Galaxy A55 SoC is Samsung Exynos 1480",
                "Samsung".equals(a55[0]) && "Exynos 1480".equals(a55[1]), Arrays.toString(a55));
        String[] n2a = PersonaSynth.socFor("Nothing", "A142", "Aerodactyl", "mt6886", "mt6886");
        check("Nothing 2a SoC is Mediatek MT6886",
                "Mediatek".equals(n2a[0]) && "MT6886".equals(n2a[1]), Arrays.toString(n2a));

        String ad1 = PersonaSynth.advertisingId("fedcba9876543210");
        String ad1Again = PersonaSynth.advertisingId("fedcba9876543210");
        String ad2 = PersonaSynth.advertisingId("0123456789abcdef");
        check("advertisingId is valid UUIDv4",
                ad1.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                ad1);
        check("advertisingId is deterministic", ad1.equals(ad1Again), ad1 + " vs " + ad1Again);
        check("advertisingId differs across seeds", !ad1.equals(ad2), ad1 + " vs " + ad2);
        check("advertisingId empty for blank seed", PersonaSynth.advertisingId("  ").isEmpty());
    }

    private static void simPolicyEnforcesRestrictedCatalogAndDualSimSafety() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put(Tables.MCC, "234=gb\n310=us\n412=af\n440=jp\n450=kr\n454=hk\n460=cn\n734=ve\n");
        raw.put(Tables.CARRIERS, "23410=O2 - UK|giffgaff\n44010=docomo\n");
        raw.put(Tables.RESTRICTED_REGIONS,
                "# catalog 2026-11-19\n"
                + "cn=all|460,461|China Mainland\n"
                + "hk=all|454|Hong Kong\n"
                + "ru=all|250|Russia\n"
                + "af=claude|412|Afghanistan\n"
                + "ve=chatgpt|734|Venezuela\n");
        Tables tables = Tables.parse(raw);

        // 1. Non-restricted UK giffgaff physical SIM -> preserved.
        SimPolicy.Decision uk = SimPolicy.evaluate(tables, "claude", true,
                "23410", "gb", "giffgaff", "giffgaff",
                Collections.singletonList("23410"), Collections.singletonList("gb"));
        check("UK giffgaff preserved", uk.preserveRealSim && uk.simEligible
                && SimPolicy.REASON_PRESERVED.equals(uk.reason)
                && "gb".equals(uk.resolvedIso) && "giffgaff".equals(uk.resolvedCarrier),
                uk.reason + "/" + uk.resolvedIso + "/" + uk.resolvedCarrier);

        // 2. Placeholder carrier ("No service") cleanly falls back to display name.
        SimPolicy.Decision ukNoSvc = SimPolicy.evaluate(tables, "chatgpt", true,
                "23410", "gb", "No service", "giffgaff",
                Collections.singletonList("23410"), Collections.singletonList("gb"));
        check("No-service placeholder falls back to display name",
                ukNoSvc.preserveRealSim && "giffgaff".equals(ukNoSvc.resolvedCarrier),
                ukNoSvc.resolvedCarrier);

        // 3. Restricted China SIM (46000) with stale spoofed ISO "kr" -> STILL restricted!
        SimPolicy.Decision cnStale = SimPolicy.evaluate(tables, "claude", true,
                "46000", "kr", "CMCC", "CMCC",
                Collections.singletonList("46000"), Collections.singletonList("kr"));
        check("China MCC with stale kr ISO stays restricted",
                !cnStale.preserveRealSim && !cnStale.simEligible
                        && SimPolicy.REASON_RESTRICTED_SIM.equals(cnStale.reason)
                        && "cn".equals(cnStale.resolvedIso),
                cnStale.reason + "/" + cnStale.resolvedIso);

        // 4. Dual-SIM: UK giffgaff active alongside active China CMCC -> denied (peer slot).
        SimPolicy.Decision dualLeak = SimPolicy.evaluate(tables, "claude", true,
                "23410", "gb", "giffgaff", "giffgaff",
                Arrays.asList("46000", "23410"), Arrays.asList("cn", "gb"));
        check("Dual-SIM with active CN peer denies preservation",
                !dualLeak.preserveRealSim && !dualLeak.simEligible
                        && SimPolicy.REASON_RESTRICTED_PEER.equals(dualLeak.reason),
                dualLeak.reason);

        // 5. Target-scoped restrictions: af restricted on claude, ve restricted on chatgpt.
        SimPolicy.Decision afClaude = SimPolicy.evaluate(tables, "claude", true,
                "41201", "af", "AWCC", "AWCC", null, null);
        SimPolicy.Decision veChatgpt = SimPolicy.evaluate(tables, "chatgpt", true,
                "73401", "ve", "Digitel", "Digitel", null, null);
        SimPolicy.Decision veClaude = SimPolicy.evaluate(tables, "claude", true,
                "73401", "ve", "Digitel", "Digitel", null, null);
        check("AF restricted for claude", !afClaude.preserveRealSim
                && SimPolicy.REASON_RESTRICTED_SIM.equals(afClaude.reason), afClaude.reason);
        check("VE restricted for chatgpt", !veChatgpt.preserveRealSim
                && SimPolicy.REASON_RESTRICTED_SIM.equals(veChatgpt.reason), veChatgpt.reason);
        check("VE eligible for claude", veClaude.preserveRealSim, veClaude.reason);

        // 6. User opt-out disables preservation even on an eligible SIM.
        SimPolicy.Decision optOut = SimPolicy.evaluate(tables, "claude", false,
                "44010", "jp", "docomo", "docomo", null, null);
        check("Opt-out disables preservation while keeping simEligible=true",
                !optOut.preserveRealSim && optOut.simEligible
                        && SimPolicy.REASON_DISABLED.equals(optOut.reason),
                optOut.reason);

        // 7. Missing catalog fails closed.
        SimPolicy.Decision noCat = SimPolicy.evaluate(Tables.EMPTY, "claude", true,
                "23410", "gb", "giffgaff", "giffgaff", null, null);
        check("Empty catalog fails closed",
                !noCat.preserveRealSim && SimPolicy.REASON_MISSING_CATALOG.equals(noCat.reason),
                noCat.reason);

        // 8. No SIM fails closed.
        SimPolicy.Decision noSim = SimPolicy.evaluate(tables, "claude", true,
                "", "", "", "", null, null);
        check("No SIM fails closed",
                !noSim.preserveRealSim && SimPolicy.REASON_NO_SIM.equals(noSim.reason),
                noSim.reason);
    }

    private static void personaMatchEngineDetectsLeaksTaintAndDrift() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put(Tables.MCC, "234=gb\n310=us\n460=cn\n454=hk\n");
        raw.put(Tables.RESTRICTED_REGIONS,
                "# catalog 2026-11-19\n"
                + "cn=all|460,461|China Mainland\n"
                + "hk=all|454|Hong Kong\n");
        Tables tables = Tables.parse(raw);

        PersonaMatchEngine.PersonaSnapshot expected = new PersonaMatchEngine.PersonaSnapshot(
                1L, "claude", "0", 2000L,
                "us", "23410", "gb", "giffgaff", true, "23410", "gb",
                "Europe/London", 0, true, "en-GB",
                "Pixel 9 Pro", "Google", "caiman", "caiman", "caiman",
                "google/caiman/caiman:15/AP3A", "AP3A", "35",
                "0123456789abcdef", "2a00:23c8::/48", "us", "London", "dig1");

        // 1. Clean 100% match including keepRealSim allowing simIso ("gb") on country.
        List<PersonaMatchEngine.UploadRecord> cleanRecs = Arrays.asList(
                new PersonaMatchEngine.UploadRecord("country", "gb", "Firelog", 1500L, 1900L, 2, 1L),
                new PersonaMatchEngine.UploadRecord("mcc_mnc", "23410", "Firelog", 1500L, 1900L, 2, 1L),
                new PersonaMatchEngine.UploadRecord("locale", "en_GB", "Firelog", 1500L, 1900L, 1, 1L),
                new PersonaMatchEngine.UploadRecord("tz_offset", "3600s", "Firelog", 1500L, 1900L, 1, 1L),
                new PersonaMatchEngine.UploadRecord("model", "Pixel 9 Pro", "Firelog", 1500L, 1900L, 1, 1L),
                new PersonaMatchEngine.UploadRecord("firebase_fid",
                        "c9d4e812-7a01-4c88-b310-6f82a4e910dc", "Firebase", 1500L, 1900L, 1, 1L));
        PersonaMatchEngine.MatchReport cleanReport = PersonaMatchEngine.evaluate(
                tables, "claude", expected, cleanRecs);
        check("Clean report scores 100%",
                cleanReport.matchPercent == 100 && cleanReport.leakCount == 0
                        && cleanReport.taintedCount == 0 && cleanReport.driftCount == 0,
                cleanReport.matchPercent + "% leaks=" + cleanReport.leakCount
                        + " drift=" + cleanReport.driftCount);

        // 2. Historical CN leak + tainted old ID + clean rotated ID + device drift.
        String oldFid = "8f3a1c09-4e2b-4d91-9a11-7b2c9e03f118";
        String freshSeg = "e71a9b04-2d13-48f7-88a1-33d91f20b881";
        List<PersonaMatchEngine.UploadRecord> mixedRecs = Arrays.asList(
                new PersonaMatchEngine.UploadRecord("mcc_mnc", "23410", "Firelog", 3000L, 4000L, 1, 1L),
                new PersonaMatchEngine.UploadRecord("mcc_mnc", "46000", "Firelog", 1000L, 2000L, 3, 0L),
                new PersonaMatchEngine.UploadRecord("model", "SM-S938B", "Firelog", 1000L, 4000L, 2, 1L),
                new PersonaMatchEngine.UploadRecord("tz_offset", "28800", "Firelog", 1000L, 4000L, 1, 1L),
                new PersonaMatchEngine.UploadRecord("firebase_fid", oldFid, "Firebase", 1000L, 4000L, 5, 1L),
                new PersonaMatchEngine.UploadRecord("segment_anonymous", freshSeg, "Segment", 3000L, 4000L, 1, 1L));
        PersonaMatchEngine.MatchReport mixed = PersonaMatchEngine.evaluate(
                tables, "claude", expected, mixedRecs);
        check("Historical 46000 escalates mcc_mnc to RESTRICTED_LEAK",
                mixed.leakCount == 1 && mixed.latestRestrictedLeakAt == 2000L,
                "leaks=" + mixed.leakCount + " at=" + mixed.latestRestrictedLeakAt);
        check("Old firebase_fid predating leak is TAINTED_IDENTITY",
                mixed.taintedCount == 1, "tainted=" + mixed.taintedCount);
        check("Device model + tz_offset mismatches counted as DRIFT",
                mixed.driftCount == 2, "drift=" + mixed.driftCount);
        check("Most severe row (RESTRICTED_LEAK) pinned first",
                !mixed.rows.isEmpty()
                        && PersonaMatchEngine.VERDICT_RESTRICTED_LEAK.equals(mixed.rows.get(0).verdict)
                        && "mcc_mnc".equals(mixed.rows.get(0).fieldKey),
                mixed.rows.isEmpty() ? "empty" : mixed.rows.get(0).fieldKey + "/" + mixed.rows.get(0).verdict);
        check("Second row is TAINTED_IDENTITY (firebase_fid) with masked prefix",
                mixed.rows.size() >= 2
                        && PersonaMatchEngine.VERDICT_TAINTED_IDENTITY.equals(mixed.rows.get(1).verdict)
                        && "8f3a1c09...".equals(mixed.rows.get(1).maskedLatestValue),
                mixed.rows.size() < 2 ? "short" : mixed.rows.get(1).maskedLatestValue);

        // 3. Exported Markdown masks full UUID when maskIds=true.
        String mdMasked = PersonaMatchEngine.exportMarkdown(mixed, expected, true);
        check("Masked markdown hides full UUID and includes prefix",
                mdMasked.contains("8f3a1c09...") && !mdMasked.contains(oldFid)
                        && mdMasked.contains("RESTRICTED_LEAK"),
                "md len=" + mdMasked.length());
        check("parseOffsetSeconds handles seconds and UTC strings",
                Integer.valueOf(28800).equals(PersonaMatchEngine.parseOffsetSeconds("28800"))
                        && Integer.valueOf(-18000).equals(PersonaMatchEngine.parseOffsetSeconds("UTC-5"))
                        && Integer.valueOf(19800).equals(PersonaMatchEngine.parseOffsetSeconds("+05:30")),
                "offset parse");
    }

    private static void bodyguardPolicyAndDiagnosticClassifierWork() {
        check("Bodyguard mode defaults to OBSERVE",
                BodyguardPolicy.MODE_OBSERVE.equals(BodyguardPolicy.normalizeMode(null))
                        && BodyguardPolicy.MODE_OBSERVE.equals(BodyguardPolicy.normalizeMode("bogus"))
                        && BodyguardPolicy.MODE_BLOCK_KILL.equals(
                                BodyguardPolicy.normalizeMode(BodyguardPolicy.MODE_BLOCK_KILL)),
                "mode normalization");

        List<BodyguardPolicy.SignalEntry> entries = Arrays.asList(
                new BodyguardPolicy.SignalEntry("meta:country", BodyguardPolicy.GATE_TELEMETRY_KEY,
                        BodyguardPolicy.STATUS_HOOKED, "TelephonyHooks", "26111719", false),
                new BodyguardPolicy.SignalEntry("prop:ro.ril.oem.imei", BodyguardPolicy.GATE_SYS_PROP,
                        BodyguardPolicy.STATUS_CUSTOM, "user", "26111719", true),
                new BodyguardPolicy.SignalEntry("api:TelephonyManager.getImei",
                        BodyguardPolicy.GATE_TRIPWIRE_API, BodyguardPolicy.STATUS_TRIPWIRE,
                        "BodyguardHooks", "26111719", false));
        check("Filter CUSTOM returns only user-whitelisted entries",
                BodyguardPolicy.filter(entries, "CUSTOM", "").size() == 1,
                "custom filter");
        check("Filter by query matches key case-insensitively",
                BodyguardPolicy.filter(entries, "ALL", "IMEI").size() == 2,
                "query filter");
        Map<String, Integer> counts = BodyguardPolicy.gateCounts(entries);
        check("Gate counts covers all 8 gates",
                counts.size() == 8
                        && Integer.valueOf(0).equals(counts.get(BodyguardPolicy.GATE_IP_FLIP))
                        && Integer.valueOf(1).equals(counts.get(BodyguardPolicy.GATE_TELEMETRY_KEY))
                        && Integer.valueOf(1).equals(counts.get(BodyguardPolicy.GATE_SYS_PROP))
                        && Integer.valueOf(1).equals(counts.get(BodyguardPolicy.GATE_TRIPWIRE_API))
                        && Integer.valueOf(0).equals(counts.get(BodyguardPolicy.GATE_EGRESS_HOST)),
                counts.toString());

        // Classify a real ClaudeChecklist model into 6 orthogonal dimensions.
        RiskModel claudeModel = ClaudeChecklist.build(
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                base().build(), "Europe/London", "GB", false);
        Map<String, DiagnosticClassifier.DimensionGroup> dims =
                DiagnosticClassifier.classify(claudeModel);
        check("DiagnosticClassifier produces all 6 dimensions",
                dims.size() == 6
                        && !dims.get(DiagnosticClassifier.DIM_STEALTH).entries.isEmpty()
                        && !dims.get(DiagnosticClassifier.DIM_INTEGRITY).entries.isEmpty()
                        && !dims.get(DiagnosticClassifier.DIM_NETLOC).entries.isEmpty()
                        && !dims.get(DiagnosticClassifier.DIM_IDENTITY).entries.isEmpty()
                        && !dims.get(DiagnosticClassifier.DIM_ACCOUNT).entries.isEmpty()
                        && !dims.get(DiagnosticClassifier.DIM_PERMS).entries.isEmpty(),
                dims.keySet().toString());
        boolean hasExcluded = false;
        for (DiagnosticClassifier.DimensionGroup g : dims.values()) {
            for (DiagnosticClassifier.Entry e : g.entries) {
                if (e.item.id().startsWith("transport.datatransport.")
                        || "system.sim_operator".equals(e.item.id())
                        || "target.sdk".equals(e.item.id())
                        || "target.locale".equals(e.item.id())) {
                    hasExcluded = true;
                }
            }
        }
        check("DiagnosticClassifier excludes duplicated region, Firelog and target preview fields",
                !hasExcluded);
        check("Zero-residue unread self-check items do not trigger false Stealth warnings",
                dims.get(DiagnosticClassifier.DIM_STEALTH).warnCount == 0,
                "stealth warns=" + dims.get(DiagnosticClassifier.DIM_STEALTH).warnCount);

        // Clean stopped-process stale self-check snapshots must not trigger false amber warnings,
        // while a genuine stale root finding must still escalate to RISK.
        Map<String, String> cleanStaleSelf = new LinkedHashMap<String, String>();
        cleanStaleSelf.put("su_paths", "0/10");
        cleanStaleSelf.put("su_packages", "0/17");
        cleanStaleSelf.put("su_props", "clean");
        cleanStaleSelf.put("su_mounts", "0/7");
        cleanStaleSelf.put("net_view", "clean");
        cleanStaleSelf.put("iface_ips", "192.168.1.2");
        cleanStaleSelf.put("ipv6_spoof", "2405:dc00:8000::1");
        RiskModel stoppedClean = ClaudeChecklist.build(
                Collections.<String, String>emptyMap(), cleanStaleSelf,
                base().build(), "Australia/Melbourne", "AU", false);
        Map<String, DiagnosticClassifier.DimensionGroup> stoppedDims =
                DiagnosticClassifier.classify(stoppedClean);
        check("Clean stale self-check items on stopped target stay silent without false warnings",
                stoppedDims.get(DiagnosticClassifier.DIM_STEALTH).warnCount == 0
                        && stoppedDims.get(DiagnosticClassifier.DIM_STEALTH).riskCount == 0
                        && stoppedDims.get(DiagnosticClassifier.DIM_NETLOC).warnCount == 0,
                "stealthW=" + stoppedDims.get(DiagnosticClassifier.DIM_STEALTH).warnCount
                        + " netlocW=" + stoppedDims.get(DiagnosticClassifier.DIM_NETLOC).warnCount);

        Map<String, String> dirtyStaleSelf = new LinkedHashMap<String, String>(cleanStaleSelf);
        dirtyStaleSelf.put("su_paths", "2/10");
        RiskModel stoppedDirty = ClaudeChecklist.build(
                Collections.<String, String>emptyMap(), dirtyStaleSelf,
                base().build(), "Australia/Melbourne", "AU", false);
        Map<String, DiagnosticClassifier.DimensionGroup> dirtyDims =
                DiagnosticClassifier.classify(stoppedDirty);
        check("Genuine stale root finding still escalates to RISK when target is stopped",
                dirtyDims.get(DiagnosticClassifier.DIM_STEALTH).riskCount >= 1,
                "stealthR=" + dirtyDims.get(DiagnosticClassifier.DIM_STEALTH).riskCount);

        // Language-only Firelog/datatransport locale ("en") matches full persona tag ("en-AU"),
        // whereas language or country conflict still flags DRIFT.
        PersonaMatchEngine.PersonaSnapshot auSnap = new PersonaMatchEngine.PersonaSnapshot(
                1L, "claude", "0", 1000L, "au", "50502", "au", "Telstra", false,
                "50502", "au", "Australia/Melbourne", 39600, true, "en-AU",
                "SM-S938B", "samsung", "pa3q", "pa3qxxx", "qcom",
                "fp", "AP3A.240905.019.Z6", "35", "aid", "", "au", "Melbourne", "d");
        PersonaMatchEngine.MatchReport langOnlyOk = PersonaMatchEngine.evaluate(
                null, "claude", auSnap,
                Collections.singletonList(new PersonaMatchEngine.UploadRecord(
                        "locale", "en", "hook-journal", 1000L, 1000L, 1, 1L)));
        PersonaMatchEngine.MatchReport countryConflict = PersonaMatchEngine.evaluate(
                null, "claude", auSnap,
                Collections.singletonList(new PersonaMatchEngine.UploadRecord(
                        "locale", "en-US", "hook-journal", 1000L, 1000L, 1, 1L)));
        PersonaMatchEngine.MatchReport langConflict = PersonaMatchEngine.evaluate(
                null, "claude", auSnap,
                Collections.singletonList(new PersonaMatchEngine.UploadRecord(
                        "locale", "ja", "hook-journal", 1000L, 1000L, 1, 1L)));
        check("PersonaMatchEngine matches language-only locale against full BCP-47 persona tag",
                langOnlyOk.driftCount == 0 && langOnlyOk.matchedCount == 1
                        && countryConflict.driftCount == 1 && langConflict.driftCount == 1,
                "ok=" + langOnlyOk.driftCount + " cc=" + countryConflict.driftCount
                        + " lang=" + langConflict.driftCount);
    }

    private static void bodyguardEventCodecBoundsAndClassifies() {
        byte[] encoded = BodyguardEventCodec.encode(
                "KILLED", BodyguardPolicy.GATE_TELEMETRY_KEY,
                "meta:device_attest_v2", "c.a.c.telemetry.k$a", "26111719");
        check("Encoded event fits within 128-byte AMS limit", encoded.length <= 128,
                "len=" + encoded.length);
        check("isBodyguardSummary identifies CWBG1 frames and ignores normal SDK state bytes",
                BodyguardEventCodec.isBodyguardSummary(encoded)
                        && !BodyguardEventCodec.isBodyguardSummary(null)
                        && !BodyguardEventCodec.isBodyguardSummary(new byte[0])
                        && !BodyguardEventCodec.isBodyguardSummary(
                                "sentry-session-123".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        BodyguardEventCodec.Event dec = BodyguardEventCodec.decode(
                new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
        check("Decoded event matches fields",
                dec != null && "KILLED".equals(dec.action)
                        && BodyguardPolicy.GATE_TELEMETRY_KEY.equals(dec.gate)
                        && "meta:device_attest_v2".equals(dec.signalKey)
                        && "c.a.c.telemetry.k$a".equals(dec.callerShort)
                        && "26111719".equals(dec.version),
                dec == null ? "null" : dec.signalKey);

        // Oversized key + caller still strictly <= 128 bytes.
        byte[] huge = BodyguardEventCodec.encode(
                "KILLED", BodyguardPolicy.GATE_SENSITIVE_PATH,
                "path:/very/long/unknown/hardware/identifier/path/that/exceeds/one/hundred/bytes",
                "com.extremely.deeply.nested.thirdparty.risk.sdk.CollectorImpl.readSecret",
                "26111719");
        check("Oversized event strictly bounded <= 128B", huge.length <= 128,
                "len=" + huge.length);

        // Hex decode path.
        StringBuilder hex = new StringBuilder();
        for (byte b : encoded) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        }
        BodyguardEventCodec.Event fromHex = BodyguardEventCodec.decode(hex.toString());
        check("Hex decode recovers CWBG1 event",
                fromHex != null && "meta:device_attest_v2".equals(fromHex.signalKey),
                fromHex == null ? "null" : fromHex.signalKey);

        // Caller attribution & gate classifiers.
        check("Framework frames ignored; target/SDK frames attributed",
                BodyguardEventCodec.isFrameworkFrame("android.app.ActivityThread")
                        && BodyguardEventCodec.isFrameworkFrame("androidx.core.app.ActivityCompat")
                        && BodyguardEventCodec.isFrameworkFrame("de.robv.android.xposed.XposedBridge")
                        && BodyguardEventCodec.isFrameworkFrame("org.matrix.vector.legacy.LegacyDelegateImpl")
                        && BodyguardEventCodec.isFrameworkFrame("Vector_")
                        && BodyguardEventCodec.isFrameworkFrame("org.lsposed.lspd.hooker.Hooker")
                        && !BodyguardEventCodec.isFrameworkFrame("com.sift.api.DeviceInfo")
                        && !BodyguardEventCodec.isFrameworkFrame("com.anthropic.claude.Main"));
        check("abbreviateCaller compacts package segments",
                "c.s.a.DeviceInfo.read".equals(
                        BodyguardEventCodec.abbreviateCaller("com.sift.api.DeviceInfo", "read")));
        check("Sensitive prop / settings / path classifiers avoid benign noise",
                BodyguardEventCodec.isSensitivePropKey("ro.ril.oem.imei")
                        && !BodyguardEventCodec.isSensitivePropKey("debug.hwui.renderer")
                        && BodyguardEventCodec.isSensitiveSettingsKey("bluetooth_address")
                        && !BodyguardEventCodec.isSensitiveSettingsKey("default_input_method")
                        && BodyguardEventCodec.isSensitivePath("/sys/class/net/wlan0/address")
                        && BodyguardEventCodec.isSensitivePath("/proc/net/arp")
                        && !BodyguardEventCodec.isSensitivePath("/data/local/tmp/foo")
                        && !BodyguardEventCodec.isSensitivePath("relative/path"));
        check("Builtin allowed settings cover spoofed device_name/bluetooth_name without exposing oaid/imei",
                BodyguardEventCodec.isBuiltinAllowedSettingsKey("device_name")
                        && BodyguardEventCodec.isBuiltinAllowedSettingsKey("bluetooth_name")
                        && BodyguardEventCodec.isBuiltinAllowedSettingsKey("android_id")
                        && !BodyguardEventCodec.isBuiltinAllowedSettingsKey("oaid")
                        && !BodyguardEventCodec.isBuiltinAllowedSettingsKey("bluetooth_address")
                        && !BodyguardEventCodec.isBuiltinAllowedSettingsKey("imei"));
        check("Trusted dynamic loader filter allows GMS Dynamite/Chimera and flags app/SDK loaders",
                BodyguardEventCodec.isTrustedDynamicLoaderCaller("com.google.android.gms.dynamite.DynamiteModule")
                        && BodyguardEventCodec.isTrustedDynamicLoaderCaller("com.google.android.gms.chimera.Loader")
                        && !BodyguardEventCodec.isTrustedDynamicLoaderCaller("com.sift.api.DynamicLoader")
                        && !BodyguardEventCodec.isTrustedDynamicLoaderCaller("com.anthropic.claude.DexLoader"));
        check("RootArtifactPolicy parses getprop subprocess commands cleanly",
                "ro.build.fingerprint".equals(RootArtifactPolicy.parseGetpropCommand(
                        Arrays.asList("/system/bin/getprop", "ro.build.fingerprint")))
                        && "".equals(RootArtifactPolicy.parseGetpropCommand("getprop"))
                        && "ro.csc.country_code".equals(RootArtifactPolicy.parseGetpropCommand(
                                new String[]{"getprop", "-T", "ro.csc.country_code"}))
                        && RootArtifactPolicy.parseGetpropCommand(
                                Arrays.asList("/system/bin/sh", "-c", "id")) == null);
        check("RootArtifactPolicy matches bare mount probes only",
                RootArtifactPolicy.isMountCommand("mount")
                        && RootArtifactPolicy.isMountCommand(Collections.singletonList("/system/bin/mount"))
                        && !RootArtifactPolicy.isMountCommand(Arrays.asList("mount", "-o", "remount")));

        // Phase 4: system_server anti-restart lock state machine.
        check("Lock state machine handles auto-lock, persisted lock, unlock, and re-lock",
                !BodyguardPolicy.isLockEffective(0L, 0L, 0L)
                        && BodyguardPolicy.isLockEffective(1000L, 0L, 0L)
                        && BodyguardPolicy.isLockEffective(0L, 1500L, 1000L)
                        && !BodyguardPolicy.isLockEffective(1000L, 1500L, 2000L)
                        && BodyguardPolicy.isLockEffective(2500L, 0L, 2000L));

        check("isAppStampChanged detects in-place APK upgrades without firing on first run",
                !BodyguardPolicy.isAppStampChanged("", "1:1000")
                        && !BodyguardPolicy.isAppStampChanged(null, "1:1000")
                        && !BodyguardPolicy.isAppStampChanged("1:1000", "1:1000")
                        && !BodyguardPolicy.isAppStampChanged("1:1000", "")
                        && BodyguardPolicy.isAppStampChanged("1:1000", "1:2000")
                        && BodyguardPolicy.isAppStampChanged("1:1000", "2:2000"));

        java.util.Set<String> hostAllow = new java.util.LinkedHashSet<String>(
                Arrays.asList("anthropic.com", "claude.ai", "sentry.io"));
        java.util.Set<String> customHostAllow = new java.util.LinkedHashSet<String>(
                Collections.singletonList("host:custom-cdn.example.net"));
        check("EGRESS_HOST allowlist matches exact/subdomains, rejects lookalikes & unknown trackers",
                BodyguardPolicy.ALL_GATES.contains(BodyguardPolicy.GATE_EGRESS_HOST)
                        && BodyguardPolicy.ALL_GATES.contains(BodyguardPolicy.GATE_IP_FLIP)
                        && BodyguardPolicy.ALL_GATES.size() == 8
                        && BodyguardEventCodec.isHostAllowed("api.anthropic.com", hostAllow, customHostAllow)
                        && BodyguardEventCodec.isHostAllowed("API.CLAUDE.AI:443.", hostAllow, customHostAllow)
                        && BodyguardEventCodec.isHostAllowed("o1158394.ingest.us.sentry.io", hostAllow, customHostAllow)
                        && BodyguardEventCodec.isHostAllowed("sub.custom-cdn.example.net", hostAllow, customHostAllow)
                        && BodyguardEventCodec.isHostAllowed("198.18.0.42", hostAllow, customHostAllow)
                        && BodyguardEventCodec.isHostAllowed("[::1]", hostAllow, customHostAllow)
                        && BodyguardEventCodec.isHostAllowed("localhost", hostAllow, customHostAllow)
                        && BodyguardEventCodec.isHostAllowed("appassets.androidplatform.net", hostAllow, customHostAllow)
                        && !BodyguardEventCodec.isHostAllowed("notanthropic.com", hostAllow, customHostAllow)
                        && !BodyguardEventCodec.isHostAllowed("evil-tracker.xyz", hostAllow, customHostAllow)
                        && !BodyguardEventCodec.isHostAllowed("anthropic.com.evil.io", hostAllow, customHostAllow)
                        && "api.anthropic.com".equals(BodyguardEventCodec.extractHttpUrlHost("https://api.anthropic.com:443/v1/messages"))
                        && "evil.tracker.io".equals(BodyguardEventCodec.extractHttpUrlHost("wss://u@evil.tracker.io/stream?x=1"))
                        && BodyguardEventCodec.extractHttpUrlHost("file:///data/local/tmp/a").isEmpty()
                        && BodyguardEventCodec.extractHttpUrlHost("jar:file:///system/a.jar!/b").isEmpty());
    }

    private static void ipGuardPolicyEvaluatesAndBuildsScripts() {
        IpGuardPolicy.Config def = IpGuardPolicy.Config.defaults();
        check("IpGuardPolicy defaults match approved strict configuration",
                def.killOnDrop
                        && def.killOnRestricted
                        && IpGuardPolicy.DRIFT_KILL.equals(def.driftPolicy)
                        && def.pollEveryMin
                        && def.needsCountryCheck()
                        && def.anyEnabled());

        java.util.Set<String> restricted = new java.util.LinkedHashSet<String>(
                Arrays.asList("CN", "HK", "MO", "RU"));

        IpGuardPolicy.Verdict vpnDrop = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US", "CN", true, false, "", restricted);
        check("Proxy drop triggers KILLED net:vpn-down in BLOCK_KILL",
                vpnDrop.isKill() && "net:vpn-down".equals(vpnDrop.signalKey), vpnDrop.signalKey);

        IpGuardPolicy.Verdict softRouter = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US", "US", false, false, "", restricted);
        check("Soft-router / overseas direct (expectProxy=false) does not false-kill when US matches",
                softRouter.isAllow(), softRouter.action + ":" + softRouter.signalKey);

        IpGuardPolicy.Verdict directBypass = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US", "US", true, true,
                "api.anthropic.com", restricted);
        check("Direct split-tunnel bypass kills immediately",
                directBypass.isKill()
                        && "egress:direct:api.anthropic.com".equals(directBypass.signalKey),
                directBypass.signalKey);

        IpGuardPolicy.Verdict restFlip = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US", "HK", true, true, "", restricted);
        check("Restricted region flip kills with egress:US->HK",
                restFlip.isKill()
                        && "egress:US->HK".equals(restFlip.signalKey)
                        && "HK".equals(IpGuardPolicy.parseTargetIsoFromSignal(restFlip.signalKey)),
                restFlip.signalKey);

        IpGuardPolicy.Verdict driftKill = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US", "JP", true, true, "", restricted);
        check("Non-restricted country drift kills under default DRIFT_KILL",
                driftKill.isKill()
                        && "egress:US->JP".equals(driftKill.signalKey)
                        && "US".equals(IpGuardPolicy.parseExpectedIsoFromSignal(driftKill.signalKey))
                        && "JP".equals(IpGuardPolicy.parseTargetIsoFromSignal(driftKill.signalKey)),
                driftKill.signalKey);

        IpGuardPolicy.Config warnCfg = new IpGuardPolicy.Config(
                true, true, IpGuardPolicy.DRIFT_WARN, false);
        IpGuardPolicy.Verdict driftWarn = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_BLOCK_KILL, warnCfg, "US", "JP", true, true, "", restricted);
        check("Country drift warns without killing when configured to DRIFT_WARN",
                driftWarn.isWarn() && "egress:US->JP".equals(driftWarn.signalKey),
                driftWarn.action);

        IpGuardPolicy.Verdict sameCountry = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US", "US", true, true, "", restricted);
        check("Same-country IP rotation (US->US) is always allowed",
                sameCountry.isAllow(), sameCountry.action);

        IpGuardPolicy.Verdict targetSameIp = IpGuardPolicy.evaluateForTarget(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US",
                "1.1.1.1", "US", "1.1.1.1", "US", true, true, "", restricted);
        check("Target process unchanged IP is allowed cleanly",
                targetSameIp.isAllow(), targetSameIp.action);

        IpGuardPolicy.Verdict targetSameCcFlip = IpGuardPolicy.evaluateForTarget(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US",
                "1.1.1.1", "US", "2.2.2.2", "US", true, true, "", restricted);
        check("Target process same-country IP flip triggers circuit-breaker KILL in BLOCK_KILL mode",
                targetSameCcFlip.isKill()
                        && "ip:flip:1.1.1.1->2.2.2.2".equals(targetSameCcFlip.signalKey)
                        && "1.1.1.1".equals(IpGuardPolicy.parsePrevIpFromFlipSignal(targetSameCcFlip.signalKey))
                        && IpGuardPolicy.isIpFlipSignal(targetSameCcFlip.signalKey),
                targetSameCcFlip.action + ":" + targetSameCcFlip.signalKey);

        IpGuardPolicy.Verdict targetSameCcObserve = IpGuardPolicy.evaluateForTarget(
                BodyguardPolicy.MODE_OBSERVE, def, "US",
                "1.1.1.1", "US", "2.2.2.2", "US", true, true, "", restricted);
        check("Target process same-country IP flip emits OBSERVED alert in OBSERVE mode",
                targetSameCcObserve.isWarn()
                        && "ip:flip:1.1.1.1->2.2.2.2".equals(targetSameCcObserve.signalKey),
                targetSameCcObserve.action + ":" + targetSameCcObserve.signalKey);

        IpGuardPolicy.Verdict targetCountryFlip = IpGuardPolicy.evaluateForTarget(
                BodyguardPolicy.MODE_BLOCK_KILL, def, "US",
                "1.1.1.1", "US", "3.3.3.3", "JP", true, true, "", restricted);
        check("Target process country flip kills against target's last country",
                targetCountryFlip.isKill()
                        && "egress:US->JP".equals(targetCountryFlip.signalKey),
                targetCountryFlip.signalKey);

        IpGuardPolicy.Verdict offMode = IpGuardPolicy.evaluate(
                BodyguardPolicy.MODE_OFF, def, "US", "HK", true, false, "api.anthropic.com", restricted);
        check("Bodyguard MODE_OFF never blocks IP flips",
                offMode.isAllow(), offMode.action);

        check("Tunnel interface classifier recognizes modern VPN/WG/Tailscale devices",
                IpGuardPolicy.isTunnelInterfaceName("tun0")
                        && IpGuardPolicy.isTunnelInterfaceName("wg0")
                        && IpGuardPolicy.isTunnelInterfaceName("tailscale0")
                        && !IpGuardPolicy.isTunnelInterfaceName("wlan0")
                        && !IpGuardPolicy.isTunnelInterfaceName("rmnet_data0"));

        String syncCmd = IpGuardPolicy.shellSyncCommand(
                BodyguardPolicy.MODE_BLOCK_KILL, def,
                "com.walter.claudewilson", "claude", "com.anthropic.claude", "Claude",
                "US", restricted);
        check("IP Guard sync script writes conf and launches persistent Bridge guard daemon",
                syncCmd.contains("ip-guard.conf")
                        && syncCmd.contains("com.walter.claudewilson.Bridge guard")
                        && syncCmd.contains("APK_PATH=$APK")
                        && syncCmd.contains("EXPECT_TUN=$EXP_TUN")
                        && syncCmd.contains("POLL_MIN=1")
                        && syncCmd.contains("RESTRICTED_CSV=CN,HK,MO,RU")
                        && syncCmd.contains("ip-guard.pid"));

        String stopCmd = IpGuardPolicy.shellStopCommand();
        check("IP Guard stop command cleans Bridge guard daemon, children, and iptables chain",
                stopCmd.contains("ip-guard.pid")
                        && stopCmd.contains("Bridge guard")
                        && stopCmd.contains("wm_set_resumed_activity")
                        && stopCmd.contains("cw_bg_drop")
                        && stopCmd.contains("-X cw_bg_drop"));

        check("Event logcat parser extracts resumed package from OneUI and AOSP formats",
                "com.follow.clash".equals(IpGuardPolicy.parseResumedPackageFromEventLine(
                        "11-22 00:17:34.303 32117 32339 I wm_set_resumed_activity: [0,com.follow.clash/.MainActivity,resumeTopActivity]"))
                        && "com.v2ray.ang".equals(IpGuardPolicy.parseResumedPackageFromEventLine(
                        "11-22 00:17:34.303 1000 1001 I am_focused_activity: [0,12345,com.v2ray.ang/.ui.MainActivity]"))
                        && "".equals(IpGuardPolicy.parseResumedPackageFromEventLine("unrelated log line")));

        check("Process start parser extracts base package from am_proc_start lines",
                "com.anthropic.claude".equals(IpGuardPolicy.parseStartedPackageFromProcStartLine(
                        "11-22 01:38:41.544 32117 32367 I am_proc_start: [0,15111,10391,com.anthropic.claude,next-top-activity,{com.anthropic.claude/com.anthropic.claude.mainactivity.MainActivity}]"))
                        && "com.tencent.mm".equals(IpGuardPolicy.parseStartedPackageFromProcStartLine(
                        "11-22 01:38:42.245 32117 32367 I am_proc_start: [0,15301,10372,com.tencent.mm:appbrand1,service,{...}]"))
                        && "".equals(IpGuardPolicy.parseStartedPackageFromProcStartLine("unrelated")));

        check("Connectivity parser extracts active VpnService owner package",
                "com.follow.clash".equals(IpGuardPolicy.parseVpnPackageFromConnectivity(
                        "NetworkAgentInfo{ni{VPN CONNECTED extra: VPN:com.follow.clash}}"))
                        && "".equals(IpGuardPolicy.parseVpnPackageFromConnectivity(
                        "NetworkAgentInfo{ni{WIFI CONNECTED extra: }}")));

        check("Proxy app classifier detects dynamic VPN host and known proxy apps only",
                IpGuardPolicy.isKnownProxyPackage("com.custom.myvpn", "com.custom.myvpn")
                        && IpGuardPolicy.isKnownProxyPackage("com.follow.clash", "")
                        && IpGuardPolicy.isKnownProxyPackage("io.nekohasekai.sfa", "")
                        && IpGuardPolicy.isKnownProxyPackage("com.v2ray.ang", "")
                        && !IpGuardPolicy.isKnownProxyPackage("com.anthropic.claude", "com.follow.clash")
                        && !IpGuardPolicy.isKnownProxyPackage("com.sec.android.app.launcher", "com.follow.clash"));

        check("Netlink classifier separates tunnel drops, route/Wi-Fi/SIM changes, and loopback noise",
                IpGuardPolicy.classifyNetlinkLine("Deleted 42: tun0: <POINTOPOINT> state DOWN")
                        == IpGuardPolicy.NETLINK_TUN_DROP
                        && IpGuardPolicy.classifyNetlinkLine("default via 192.168.1.1 dev wlan0 proto dhcp")
                        == IpGuardPolicy.NETLINK_ROUTE_CHANGE
                        && IpGuardPolicy.classifyNetlinkLine("default via 10.0.0.1 dev rmnet_data0")
                        == IpGuardPolicy.NETLINK_ROUTE_CHANGE
                        && IpGuardPolicy.classifyNetlinkLine("1: lo: <LOOPBACK,UP> state UNKNOWN")
                        == IpGuardPolicy.NETLINK_IGNORE
                        && IpGuardPolicy.classifyNetlinkLine("fe80::/64 dev wlan0 proto kernel")
                        == IpGuardPolicy.NETLINK_IGNORE);
    }

    private static void personasCatalogAssetIsValidAndSelfConsistent() {
        java.nio.file.Path path = java.nio.file.Paths.get("assets", "catalog", "personas.txt");
        if (!java.nio.file.Files.isRegularFile(path)) {
            path = java.nio.file.Paths.get("app", "assets", "catalog", "personas.txt");
        }
        String text = "";
        try {
            text = new String(java.nio.file.Files.readAllBytes(path),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            check("personas.txt readable", false, e.toString());
            return;
        }
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put(Tables.PERSONAS, text);
        List<String[]> rows = Tables.parse(raw).personas();
        check("personas.txt contains >= 100 verified certified devices",
                rows.size() >= 100, "count=" + rows.size());
        Set<String> seenLabels = new LinkedHashSet<String>();
        boolean allValid = true;
        String badRow = "";
        for (String[] r : rows) {
            if (r.length != 14) {
                allValid = false;
                badRow = "cols=" + r.length;
                break;
            }
            for (String c : r) {
                if (c == null || c.trim().isEmpty()) {
                    allValid = false;
                    badRow = "empty in " + r[0];
                    break;
                }
            }
            if (!allValid) break;
            if (!seenLabels.add(r[0].toLowerCase(java.util.Locale.ROOT))) {
                allValid = false;
                badRow = "dup label " + r[0];
                break;
            }
            int expectedSdk = PersonaSynth.sdkForRelease(r[11]);
            if (expectedSdk <= 0 || !String.valueOf(expectedSdk).equals(r[12])) {
                allValid = false;
                badRow = "sdk mismatch " + r[0] + " rel=" + r[11] + " sdk=" + r[12];
                break;
            }
            if (!r[13].matches("\\d{4}-\\d{2}-\\d{2}")) {
                allValid = false;
                badRow = "bad patch " + r[0] + " " + r[13];
                break;
            }
            String fpSuffix = ":" + r[11] + "/" + r[9] + "/" + r[10] + ":user/release-keys";
            if (!r[8].endsWith(fpSuffix) || r[5].toLowerCase(java.util.Locale.ROOT).contains("_beta")) {
                allValid = false;
                badRow = "fp/beta mismatch " + r[0] + " " + r[8];
                break;
            }
        }
        check("Every personas.txt row has 14 self-consistent official release-keys fields",
                allValid, badRow);

        String sampleIpLink = "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 link/loopback\n"
                + "10: rmnet_data1: <UP,LOWER_UP> mtu 1500 link/[530]\n"
                + "26: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 link/ether\n"
                + "59: netproxy: <POINTOPOINT,MULTICAST,NOARP,UP,LOWER_UP> mtu 9000 link/none\n"
                + "60: myvpn0@NONE: <POINTOPOINT,UP> mtu 1500 link/none\n";
        String sampleTunFlags = "/sys/class/net/netproxy/tun_flags\n/sys/class/net/custom_box/tun_flags\n";
        List<String> discoveredTunnels = NetworkRules.parseTunnelInterfaces(sampleIpLink, sampleTunFlags);
        check("parseTunnelInterfaces dynamically detects netproxy/custom TUN without hiding wlan0 or rmnet",
                discoveredTunnels.contains("netproxy")
                        && discoveredTunnels.contains("custom_box")
                        && discoveredTunnels.contains("myvpn0")
                        && !discoveredTunnels.contains("wlan0")
                        && !discoveredTunnels.contains("rmnet_data1")
                        && !discoveredTunnels.contains("lo"),
                discoveredTunnels.toString());

        String rawUa = "Mozilla/5.0 (Linux; Android 15; MI 6 Build/AP3A.240905.015; wv) AppleWebKit/537.36";
        String rwUa = BodyguardEventCodec.rewriteUserAgent(rawUa, "15", "Pixel 9 Pro");
        String reducedUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36";
        check("rewriteUserAgent updates model while preserving Build suffix and Chromium K reduction",
                rwUa.contains("Android 15; Pixel 9 Pro Build/AP3A.240905.015")
                        && !rwUa.contains("MI 6")
                        && BodyguardEventCodec.rewriteUserAgent(reducedUa, "15", "Pixel 9 Pro")
                                .contains("Android 15; K)"));

        check("Fail-closed guard mode kills without persona in BLOCK_KILL only",
                BodyguardPolicy.shouldFailClosedWithoutPersona("BLOCK_KILL")
                        && !BodyguardPolicy.shouldFailClosedWithoutPersona("OBSERVE")
                        && !BodyguardPolicy.shouldFailClosedWithoutPersona("OFF")
                        && !BodyguardPolicy.shouldFailClosedWithoutPersona("")
                        && !BodyguardPolicy.shouldFailClosedWithoutPersona(null)
                        && BodyguardPolicy.MODE_OBSERVE.equals(BodyguardPolicy.parseGuardMode("OBSERVE\n")));
    }

    private static void personaEditRulesAndGeoCitiesWork() {
        check("validOperatorCode accepts 5-6 digits only",
                PersonaEditRules.validOperatorCode("44020")
                        && PersonaEditRules.validOperatorCode("310260")
                        && !PersonaEditRules.validOperatorCode("440")
                        && !PersonaEditRules.validOperatorCode("4402a"));
        check("validCarrierName enforces printable length",
                PersonaEditRules.validCarrierName("SoftBank")
                        && !PersonaEditRules.validCarrierName("")
                        && !PersonaEditRules.validCarrierName("bad\nname"));
        check("mccMatchesRegion validates multi-MCC lists",
                PersonaEditRules.mccMatchesRegion("310260", "310/311")
                        && PersonaEditRules.mccMatchesRegion("311480", "310/311")
                        && !PersonaEditRules.mccMatchesRegion("44020", "310/311"));
        check("carrierIdFor resolves curated carriers",
                PersonaEditRules.carrierIdFor("44010", "docomo") == 1185
                        && PersonaEditRules.carrierIdFor("310260", "T-Mobile") == 1
                        && PersonaEditRules.carrierIdFor("44020", "SoftBank") == -1);
        String randAid = PersonaEditRules.randomAid(new java.util.Random(42L));
        check("randomAid generates valid 16-hex lowercase id",
                PersonaEditRules.validAid(randAid)
                        && !PersonaEditRules.validAid("ZZZZZZZZZZZZZZZZ")
                        && !PersonaEditRules.validAid("1234"));
        long epoch = 1700000000000L;
        check("tzOffsetDiffers detects cross-zone offsets accurately",
                PersonaEditRules.tzOffsetDiffers("America/New_York", "America/Los_Angeles", epoch)
                        && !PersonaEditRules.tzOffsetDiffers("Asia/Tokyo", "Asia/Tokyo", epoch)
                        && !PersonaEditRules.tzOffsetDiffers("Asia/Tokyo", "Asia/Seoul", epoch));
        GeoTable gt = GeoTable.parse(
                "us||39.8|-98.5|\n"
                + "us|New York|40.71|-74.00|America/New_York\n"
                + "us|Los Angeles|34.05|-118.24|America/Los_Angeles\n"
                + "jp|Tokyo|35.67|139.65|Asia/Tokyo\n");
        List<GeoTable.Point> usCities = gt.citiesForIso("US");
        check("GeoTable.citiesForIso returns city rows only",
                usCities.size() == 2 && "New York".equals(usCities.get(0).city)
                        && "Los Angeles".equals(usCities.get(1).city));
    }

    private static void preflightVerdictPrioritizesBlockersAndWarnings() {
        // Clean ready state (stopped)
        PreflightVerdict ready = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                true, "JP", 0, false, false, true, "JP", false, false, false, "JP"));
        check("Clean JP persona evaluates READY with NONE action",
                ready.level == PreflightVerdict.Level.READY
                        && !ready.targetRunning
                        && ready.issues.isEmpty()
                        && ready.primaryAction == PreflightVerdict.PrimaryAction.NONE);

        // Clean running state (target already launched under applied KR persona) must NOT block
        PreflightVerdict runningClean = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                true, "KR", 0, false, true, true, "KR", false, false, false, "KR"));
        check("Clean running KR persona evaluates READY without TARGET_RUNNING blocker",
                runningClean.level == PreflightVerdict.Level.READY
                        && runningClean.targetRunning
                        && runningClean.issues.isEmpty()
                        && runningClean.primaryAction == PreflightVerdict.PrimaryAction.NONE);

        // Clean running state with unprobed exit evaluates CAUTION (not BLOCKED)
        PreflightVerdict runningUnprobed = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                true, "KR", 0, false, true, false, "", false, false, false, "KR"));
        check("Clean running KR persona with unprobed exit evaluates CAUTION, not BLOCKED",
                runningUnprobed.level == PreflightVerdict.Level.CAUTION
                        && runningUnprobed.blockerCount == 0
                        && runningUnprobed.primaryAction == PreflightVerdict.PrimaryAction.PROBE_NETWORK);

        // Running target under active network leak blocks with STOP_TARGET
        PreflightVerdict runningLeak = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                true, "KR", 0, false, true, true, "KR", false, false, true, "KR"));
        check("Running target with active network leak blocks and prioritizes STOP_TARGET",
                runningLeak.level == PreflightVerdict.Level.BLOCKED
                        && runningLeak.blockerCount == 1
                        && runningLeak.primaryAction == PreflightVerdict.PrimaryAction.STOP_TARGET);

        // Target running with drifted persona beats all other blockers/warnings
        PreflightVerdict running = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                true, "JP", 2, false, true, true, "US", false, true, false, "US"));
        check("Running drifted target evaluates BLOCKED and prioritizes STOP_TARGET",
                running.level == PreflightVerdict.Level.BLOCKED
                        && running.blockerCount == 2
                        && running.primaryAction == PreflightVerdict.PrimaryAction.STOP_TARGET);

        // Native restricted SIM blocks and suggests PICK_REGION
        PreflightVerdict nativeBan = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                false, "", 0, true, false, false, "", false, false, false, ""));
        check("Native restricted SIM evaluates BLOCKED with PICK_REGION",
                nativeBan.level == PreflightVerdict.Level.BLOCKED
                        && nativeBan.primaryAction == PreflightVerdict.PrimaryAction.PICK_REGION);

        // Caution: datacenter IP + store mismatch + egress mismatch
        PreflightVerdict caution = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                true, "JP", 0, false, false, true, "US", false, true, false, "US"));
        check("Egress mismatch + datacenter + store evaluates CAUTION with ALIGN_EGRESS",
                caution.level == PreflightVerdict.Level.CAUTION
                        && caution.warningCount == 3
                        && caution.primaryAction == PreflightVerdict.PrimaryAction.ALIGN_EGRESS);

        // Unprobed exit on active persona recommends PROBE_NETWORK
        PreflightVerdict unprobed = PreflightVerdict.evaluate(new PreflightVerdict.Input(
                true, "JP", 0, false, false, false, "", false, false, false, "JP"));
        check("Unprobed exit evaluates CAUTION with PROBE_NETWORK",
                unprobed.level == PreflightVerdict.Level.CAUTION
                        && unprobed.primaryAction == PreflightVerdict.PrimaryAction.PROBE_NETWORK);
    }

    private static void ipv6PolicyDefaultsOffAndBuildsWatchdogCommands() {
        check("Ipv6Policy defaults null/empty/unknown modes to off",
                Ipv6Policy.MODE_OFF.equals(Ipv6Policy.parseMode(null))
                        && Ipv6Policy.MODE_OFF.equals(Ipv6Policy.parseMode(""))
                        && Ipv6Policy.MODE_OFF.equals(Ipv6Policy.parseMode("unknown"))
                        && Ipv6Policy.isDisabled(null)
                        && Ipv6Policy.isDisabled("off"));
        check("Ipv6Policy parses spoof/on/true as enabled",
                Ipv6Policy.MODE_SPOOF.equals(Ipv6Policy.parseMode("spoof"))
                        && Ipv6Policy.MODE_SPOOF.equals(Ipv6Policy.parseMode("ON"))
                        && !Ipv6Policy.isDisabled("spoof"));
        String offCmd = Ipv6Policy.shellSyncCommand(false);
        check("Ipv6Policy disable command exempts IMS/lo/all/default while disabling internet IPv6 and starting watchdog",
                offCmd.contains(Ipv6Policy.MODE_PATH)
                        && offCmd.contains("mv -f ")
                        && offCmd.contains("echo 0 > /proc/sys/net/ipv6/conf/default/disable_ipv6")
                        && offCmd.contains("echo 0 > /proc/sys/net/ipv6/conf/all/disable_ipv6")
                        && !offCmd.contains("echo 1 > /proc/sys/net/ipv6/conf/all/disable_ipv6")
                        && !offCmd.contains("echo 1 > /proc/sys/net/ipv6/conf/default/disable_ipv6")
                        && offCmd.contains("extra: ims")
                        && offCmd.contains("rmnet_ipa*")
                        && offCmd.contains("epdg*")
                        && offCmd.contains("r_rmnet_data*")
                        && offCmd.contains("ipsec*")
                        && offCmd.contains("xfrm*")
                        && offCmd.contains("conf/lo/disable_ipv6")
                        && offCmd.contains("ip -6 monitor address link")
                        && offCmd.contains("ip -6 addr flush dev")
                        && offCmd.contains("cmp -s ")
                        && offCmd.contains("SCRIPT_CHANGED")
                        && !offCmd.contains("\\bINTERNET\\b")
                        && !offCmd.contains("\\b(IMS|EIMS)\\b")
                        && offCmd.contains("[:& ]INTERNET([]& ]|$)")
                        && offCmd.contains("[:& ](IMS|EIMS)([]& ]|$)")
                        && offCmd.contains(Ipv6Policy.GUARD_PID_PATH)
                        && offCmd.contains(Ipv6Policy.LEGACY_PID_PATH)
                        && offCmd.contains("/proc/$P/cmdline")
                        && offCmd.contains("99-disable-ipv6\\.sh"));
        String onCmd = Ipv6Policy.shellSyncCommand(true);
        check("Ipv6Policy restore command stops all watchdogs including legacy 99-disable-ipv6 and restores disable_ipv6=0",
                onCmd.contains(Ipv6Policy.MODE_SPOOF)
                        && onCmd.contains(Ipv6Policy.GUARD_PID_PATH)
                        && onCmd.contains(Ipv6Policy.LEGACY_PID_PATH)
                        && onCmd.contains("/proc/$P/cmdline")
                        && onCmd.contains("99-disable-ipv6\\.sh")
                        && onCmd.contains("ipv6-guard\\.sh")
                        && onCmd.contains("echo 0 > /proc/sys/net/ipv6/conf/default/disable_ipv6")
                        && onCmd.contains("echo 0 > /proc/sys/net/ipv6/conf/all/disable_ipv6"));
        check("BodyguardPolicy blocks background process spawns only and never short-circuits activity spawns",
                BodyguardPolicy.shouldBlockProcessStart("service")
                        && BodyguardPolicy.shouldBlockProcessStart("broadcast")
                        && BodyguardPolicy.shouldBlockProcessStart("content provider")
                        && BodyguardPolicy.shouldBlockProcessStart("job")
                        && !BodyguardPolicy.shouldBlockProcessStart("activity")
                        && !BodyguardPolicy.shouldBlockProcessStart("top-activity")
                        && !BodyguardPolicy.shouldBlockProcessStart("next-top-activity")
                        && !BodyguardPolicy.shouldBlockProcessStart("")
                        && !BodyguardPolicy.shouldBlockProcessStart(null));
        String uninstallCmd = IpGuardPolicy.shellUninstallCleanupCommand(
                java.util.Arrays.asList("claude", "chatgpt"));
        check("IpGuardPolicy uninstall cleanup unblocks iptables, unlocks SystemBodyguardHooks, disarms guard.mode, and restores IPv6",
                uninstallCmd.contains(IpGuardPolicy.IPTABLES_CHAIN)
                        && uninstallCmd.contains("claude.unlock")
                        && uninstallCmd.contains("chatgpt.unlock")
                        && uninstallCmd.contains("claude.lock")
                        && uninstallCmd.contains("chatgpt.lock")
                        && uninstallCmd.contains("guard.mode")
                        && uninstallCmd.contains(BodyguardPolicy.MODE_OFF)
                        && uninstallCmd.contains(Ipv6Policy.MODE_SPOOF));
    }

    private static void diagnosticArchiveParserSplitsStagingCachesCleanly() {
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("sentry_breadcrumbs",
                "BINHDR  {\"timestamp\":\"2026-11-20T14:42:51.756Z\",\"category\":\"network.event\","
                        + "\"data\":{\"vpn_active\":false,\"network_type\":\"wifi\"}}  "
                        + "\u0001{{\"timestamp\":\"2026-11-20T14:42:52.683Z\",\"category\":\"http\",\"type\":\"http\","
                        + "\"data\":{\"http.start_timestamp\":1795185772034,\"http.end_timestamp\":1795185772683,"
                        + "\"status_code\":200,\"method\":\"GET\",\"cf_ray\":\"a5d8394ccb12eaa3-ICN\","
                        + "\"request_id\":\"req_011ChEMepnb7qYyhumhRPvGU\","
                        + "\"url\":\"https://claude.ai/bootstrap?secret=do_not_keep\"}} "
                        + "{\"category\":\"Analytics Event\",\"message\":\"claudeai.networking.cronet.request_success\","
                        + "\"data\":{\"endpoint\":\"/bootstrap\",\"protocol\":\"h2\",\"tls_diverted\":\"false\"}} "
                        + "{\"category\":\"Analytics Event\",\"message\":\"claudeai.app.process_started\","
                        + "\"data\":{\"sign_in_browser\":\"chrome\",\"webview_package\":\"com.android.webview\",\"webview_version\":\"152.0\"}}");
        raw.put("sentry_tags_full", "{\"active_screen\":\"Welcome\",\"app_start_destination\":\"login_app\"}");
        raw.put("sentry_envelopes", "2\n");
        raw.put("datadog_view",
                "   HDR{\"date\":1795185781697,\"os\":{\"name\":\"Android\"},\"application\":{\"current_locale\":\"ko-KR\"},"
                        + "\"service\":\"claude-android\",\"view\":{\"name\":\"Background\","
                        + "\"error\":{\"count\":0},\"crash\":{\"count\":0},\"resource\":{\"count\":5}}}");
        raw.put("bootstrap_cache",
                "{\"response\":{\"growthbook\":{\"features\":{"
                        + "\"k1\":{\"defaultValue\":true,\"rules\":[{\"id\":\"r1\"}]},"
                        + "\"k2\":{\"defaultValue\":false}},\"hashing_algorithm\":\"sha256\"}},"
                        + "\"cachedAtMillis\":1795185772687}");

        DiagnosticArchiveParser.Bundle b = DiagnosticArchiveParser.parse("claude", raw, 1795185800000L);
        check("DiagnosticArchiveParser extracts HTTP event with query stripped, POP, and cronet enrichment",
                b.httpEvents.size() == 1
                        && "GET".equals(b.httpEvents.get(0).method)
                        && "claude.ai".equals(b.httpEvents.get(0).host)
                        && "/bootstrap".equals(b.httpEvents.get(0).path)
                        && !b.httpEvents.get(0).path.contains("secret")
                        && b.httpEvents.get(0).statusCode == 200
                        && b.httpEvents.get(0).durationMs == 649L
                        && "ICN".equals(b.httpEvents.get(0).edgePop)
                        && "h2".equals(b.httpEvents.get(0).protocol)
                        && "false".equals(b.httpEvents.get(0).tlsDiverted));
        check("DiagnosticArchiveParser extracts Datadog current_locale",
                "ko-KR".equals(b.datadogLocale));

        Map<String, String> sigMap = new LinkedHashMap<String, String>();
        for (DiagnosticArchiveParser.DimSignal s : b.dimSignals) {
            sigMap.put(s.signalKey, s.signalValue);
        }
        check("DiagnosticArchiveParser extracts multi-dim staging signals across network/runtime/datadog/response/crash",
                "false".equals(sigMap.get("vpn_active"))
                        && "wifi".equals(sigMap.get("network_type"))
                        && "ICN".equals(sigMap.get("cf_edge_pop"))
                        && "chrome".equals(sigMap.get("sign_in_browser"))
                        && "ko-KR".equals(sigMap.get("datadog_locale"))
                        && "Background".equals(sigMap.get("datadog_view"))
                        && "1/2 on · 1 rules".equals(sigMap.get("bootstrap_features"))
                        && "2".equals(sigMap.get("pending_envelopes")));

        StringBuilder md = new StringBuilder();
        DiagnosticArchiveParser.appendMarkdown(md, b.httpEvents, b.dimSignals);
        String mdText = md.toString();
        check("DiagnosticArchiveParser Markdown export includes HTTP and staging sections without query secrets",
                mdText.contains("## Target HTTP Response Archive")
                        && mdText.contains("## Target Staging Diagnostics")
                        && mdText.contains("/bootstrap")
                        && !mdText.contains("do_not_keep"));
    }

    private static void coordinateParserAndRadioOverridesWork() {
        CoordinateParser.LatLng plain = CoordinateParser.parse("35.6895, 139.6917");
        check("CoordinateParser parses plain decimal pair",
                plain != null && Math.abs(plain.latitude - 35.6895) < 1e-6
                        && Math.abs(plain.longitude - 139.6917) < 1e-6);

        CoordinateParser.LatLng gmaps = CoordinateParser.parse(
                "https://www.google.com/maps/place/Tokyo/@35.6762,139.6503,14z/data=!3m1!4b1");
        check("CoordinateParser parses Google Maps @lat,lng URL",
                gmaps != null && Math.abs(gmaps.latitude - 35.6762) < 1e-6
                        && Math.abs(gmaps.longitude - 139.6503) < 1e-6);

        CoordinateParser.LatLng g3d4d = CoordinateParser.parse(
                "https://www.google.com/maps/place/x/@35.0,139.0,12z/data=!3d37.7749!4d-122.4194");
        check("CoordinateParser prefers !3d!4d pin over viewport center",
                g3d4d != null && Math.abs(g3d4d.latitude - 37.7749) < 1e-6
                        && Math.abs(g3d4d.longitude + 122.4194) < 1e-6);

        CoordinateParser.LatLng geo = CoordinateParser.parse("geo:51.5074,-0.1278?z=15");
        check("CoordinateParser parses geo: URI",
                geo != null && Math.abs(geo.latitude - 51.5074) < 1e-6
                        && Math.abs(geo.longitude + 0.1278) < 1e-6);

        CoordinateParser.LatLng dms = CoordinateParser.parse("35\u00B041'22\"N 139\u00B041'30\"E");
        check("CoordinateParser parses DMS notation",
                dms != null && dms.latitude > 35.68 && dms.latitude < 35.70
                        && dms.longitude > 139.68 && dms.longitude < 139.70);

        check("CoordinateParser rejects out-of-range coordinates",
                CoordinateParser.parse("95.0, 200.0") == null
                        && CoordinateParser.parse("not a coord") == null);

        check("WifiProfile validates MAC addresses",
                WifiProfile.isValidMac("3C:84:6A:12:34:56")
                        && WifiProfile.isValidMac(WifiProfile.randomMac(42L))
                        && !WifiProfile.isValidMac("3C:84:6A:ZZ:34:56")
                        && !WifiProfile.isValidMac("invalid"));

        List<WifiProfile.Ap> baseWifi = WifiProfile.forRegion(35.6762, 139.6503, "Tokyo", 123L, 6);
        List<WifiProfile.Ap> overriddenWifi = WifiProfile.applyOverrides(
                baseWifi, "Shinjuku-5G", "ac:84:c6:11:22:33");
        check("WifiProfile.applyOverrides replaces connected SSID and uppercases BSSID",
                !overriddenWifi.isEmpty()
                        && "Shinjuku-5G".equals(overriddenWifi.get(0).ssid)
                        && "AC:84:C6:11:22:33".equals(overriddenWifi.get(0).bssid));

        List<BleProfile.Device> baseBle = BleProfile.forRegion(123L, 4);
        List<BleProfile.Device> renamedBle = BleProfile.applyOverrides(baseBle, "AirPods Pro 2", false);
        List<BleProfile.Device> emptyBle = BleProfile.applyOverrides(baseBle, "AirPods Pro 2", true);
        check("BleProfile.applyOverrides supports bonded rename and clean empty mode",
                !renamedBle.isEmpty() && "AirPods Pro 2".equals(renamedBle.get(0).name)
                        && emptyBle.isEmpty());

        SensorProfile night = SensorProfile.forPreset(
                123L, 35.6762, 139.6503, 14, null, SensorProfile.PRESET_INDOOR_NIGHT, 50.0);
        SensorProfile outdoor = SensorProfile.forPreset(
                123L, 35.6762, 139.6503, 2, null, SensorProfile.PRESET_OUTDOOR_DAY, 1500.0);
        double gNight = Math.sqrt(night.accelerometer[0] * night.accelerometer[0]
                + night.accelerometer[1] * night.accelerometer[1]
                + night.accelerometer[2] * night.accelerometer[2]);
        check("SensorProfile.forPreset preserves gravity magnitude, lighting, and barometric physics",
                Math.abs(gNight - 9.81) < 0.25
                        && night.light < 50.0f
                        && outdoor.light > 2000.0f
                        && outdoor.pressure < night.pressure);

        String locJson = LocationPayload.json(true, 35.6762, 139.6503, 7L, "Tokyo", "coarse", 45.0);
        check("LocationPayload serializes accuracyMode and altitude",
                locJson.contains("\"accuracyMode\":\"coarse\"")
                        && locJson.contains("\"altitude\":45.0"));

        // AppVisibilityPolicy invariants: root always hidden, essentials always visible
        Set<String> allow = new LinkedHashSet<String>(Arrays.asList(
                "com.google.android.youtube", "me.weishu.kernelsu"));
        check("AppVisibilityPolicy ALLOWLIST keeps target, GMS, Chrome and allowlisted apps visible",
                !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow,
                        "com.anthropic.claude", "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow,
                        "com.google.android.gms", "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow,
                        "com.android.chrome", "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow,
                        "com.google.android.youtube", "com.anthropic.claude"));
        check("AppVisibilityPolicy ALLOWLIST hides unlisted apps and ALWAYS hides root/control apps",
                AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow,
                        "com.tencent.mm", "com.anthropic.claude")
                        && AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow,
                        "me.weishu.kernelsu", "com.anthropic.claude")
                        && AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow,
                        "com.walter.claudewilson", "com.anthropic.claude"));
        Set<String> deny = new LinkedHashSet<String>(Arrays.asList(
                "com.tencent.mm", "com.android.vending"));
        check("AppVisibilityPolicy DENYLIST hides selected apps but protects essential Play Store",
                AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_DENYLIST, deny,
                        "com.tencent.mm", "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_DENYLIST, deny,
                        "com.android.vending", "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_DENYLIST, deny,
                        "com.spotify.music", "com.anthropic.claude"));
        check("AppVisibilityPolicy flags proxy/domestic/root packages as sensitive",
                AppVisibilityPolicy.isSensitivePackage("com.github.kr328.clash")
                        && AppVisibilityPolicy.isSensitivePackage("com.tencent.mm")
                        && !AppVisibilityPolicy.isSensitivePackage("com.spotify.music"));
        check("AppVisibilityPolicy HMA excludeSystemApps keeps OEM system apps visible but hides user & forced-hidden apps",
                !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow, true,
                        "com.qualcomm.qti.telephonyservice", true, "com.anthropic.claude")
                        && AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow, false,
                        "com.qualcomm.qti.telephonyservice", true, "com.anthropic.claude")
                        && AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow, true,
                        "com.tencent.mm", false, "com.anthropic.claude")
                        && AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow, true,
                        "com.tsng.hidemyapplist", true, "com.anthropic.claude")
                        && AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow, true,
                        "com.rifsxd.ksunext", false, "com.anthropic.claude"));
        check("AppVisibilityPolicy categorizes root/proxy/domestic/dev packages cleanly",
                AppVisibilityPolicy.categoryOf("com.tsng.hidemyapplist") == AppVisibilityPolicy.CAT_ROOT
                        && AppVisibilityPolicy.categoryOf("com.follow.clash") == AppVisibilityPolicy.CAT_PROXY
                        && AppVisibilityPolicy.categoryOf("com.tencent.mm") == AppVisibilityPolicy.CAT_DOMESTIC
                        && AppVisibilityPolicy.categoryOf("com.termux") == AppVisibilityPolicy.CAT_DEV
                        && AppVisibilityPolicy.categoryOf("com.spotify.music") == AppVisibilityPolicy.CAT_NONE);
        check("AppVisibilityPolicy.extractPackageFromPath parses data/user/external/apk paths safely",
                "com.tencent.mm".equals(AppVisibilityPolicy.extractPackageFromPath("/data/data/com.tencent.mm/shared_prefs"))
                        && "com.tencent.mm".equals(AppVisibilityPolicy.extractPackageFromPath("/data/user/0/com.tencent.mm"))
                        && "com.follow.clash".equals(AppVisibilityPolicy.extractPackageFromPath("/storage/emulated/0/Android/data/com.follow.clash/files"))
                        && "com.follow.clash".equals(AppVisibilityPolicy.extractPackageFromPath("/sdcard/Android/obb/com.follow.clash/a.obb"))
                        && "com.tencent.mm".equals(AppVisibilityPolicy.extractPackageFromPath("/data/app/~~abc123==/com.tencent.mm-xyz==/base.apk"))
                        && AppVisibilityPolicy.extractPackageFromPath("/system/bin/sh") == null
                        && AppVisibilityPolicy.extractPackageFromPath("/proc/self/maps") == null);
        check("AppVisibilityPolicy.isHiddenPmCommand blocks pm list and hidden pm path probes",
                AppVisibilityPolicy.isHiddenPmCommand("pm list packages",
                        AppVisibilityPolicy.MODE_ALLOWLIST, allow, true, "com.anthropic.claude")
                        && AppVisibilityPolicy.isHiddenPmCommand(new String[]{"cmd", "package", "list", "packages"},
                        AppVisibilityPolicy.MODE_ALLOWLIST, allow, true, "com.anthropic.claude")
                        && AppVisibilityPolicy.isHiddenPmCommand(new String[]{"sh", "-c", "pm list packages -3"},
                        AppVisibilityPolicy.MODE_DEFAULT, allow, true, "com.anthropic.claude")
                        && AppVisibilityPolicy.isHiddenPmCommand("pm path com.tencent.mm",
                        AppVisibilityPolicy.MODE_ALLOWLIST, allow, true, "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHiddenPmCommand("pm path com.google.android.youtube",
                        AppVisibilityPolicy.MODE_ALLOWLIST, allow, true, "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHiddenPmCommand("ls /sdcard",
                        AppVisibilityPolicy.MODE_ALLOWLIST, allow, true, "com.anthropic.claude"));

        Set<String> extra = new LinkedHashSet<String>(Arrays.asList(
                "org.lineageos.updater", "com.android.vending"));
        check("AppVisibilityPolicy extraHidden hides Custom ROM system apps even when excludeSystemApps=true, while protecting essentials",
                AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow, extra, true,
                        "org.lineageos.updater", true, "com.anthropic.claude")
                        && !AppVisibilityPolicy.isHidden(AppVisibilityPolicy.MODE_ALLOWLIST, allow, extra, true,
                        "com.android.vending", true, "com.anthropic.claude")
                        && AppVisibilityPolicy.isRootManagerPackage("com.sukisu.ultra")
                        && AppVisibilityPolicy.isDetectorPackage("io.github.vvb2060.mahoshojo")
                        && AppVisibilityPolicy.isCustomRomPackage("org.lineageos.aperture")
                        && AppVisibilityPolicy.isSuspiciousPackage("com.follow.clash"));
        String filteredImes = AppVisibilityPolicy.filterEnabledInputMethods(
                "com.sohu.inputmethod.sogou/.SogouIME:com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
                AppVisibilityPolicy.MODE_ALLOWLIST, allow, extra, true, "com.anthropic.claude");
        check("AppVisibilityPolicy sanitizes Settings.Secure IME lists and default IME",
                !filteredImes.contains("sogou")
                        && filteredImes.contains("com.google.android.inputmethod.latin")
                        && AppVisibilityPolicy.FALLBACK_IME.equals(
                        AppVisibilityPolicy.sanitizeDefaultInputMethod(
                                "com.sohu.inputmethod.sogou/.SogouIME",
                                AppVisibilityPolicy.MODE_ALLOWLIST, allow, extra, true, "com.anthropic.claude")));

        check("NetworkRules.uidInRanges parses VPN UidRanges and catches excluded target UID",
                !NetworkRules.uidInRanges(10229, "[0-10228, 10230-99999]")
                        && NetworkRules.uidInRanges(10228, "[0-10228, 10230-99999]")
                        && NetworkRules.uidInRanges(10229, "{0-99999}")
                        && NetworkRules.uidInRanges(10229, "10229"));
        check("NetworkRules detects Private DNS DoT tunnel bypass and domestic resolvers",
                NetworkRules.isPrivateDnsBypassRisk("hostname", "dns.alidns.com")
                        && NetworkRules.isDomesticPrivateDns("dns.alidns.com")
                        && !NetworkRules.isPrivateDnsBypassRisk("off", "dns.alidns.com")
                        && !NetworkRules.isPrivateDnsBypassRisk("opportunistic", "one.one.one.one"));
        check("NetworkRules.classifyV6 catches local leaks AND dual-stack v4/v6 cross-country mismatches",
                NetworkRules.classifyV6("none", "", "", "JP", false) == NetworkRules.V6_CLEAN_NONE
                        && NetworkRules.classifyV6("240e:123::1", "CN", "wlan0=240e:123::1", "JP", true)
                                == NetworkRules.V6_LEAK_LOCAL
                        && NetworkRules.classifyV6("2a06:98c0::1", "HK", "wlan0=240e:123::1", "JP", true)
                                == NetworkRules.V6_LEAK_CROSS_COUNTRY
                        && NetworkRules.classifyV6("2606:4700::1", "US", "", "JP", false)
                                == NetworkRules.V6_LEAK_CROSS_COUNTRY
                        && NetworkRules.classifyV6("2400:2410::1", "JP", "", "JP", false)
                                == NetworkRules.V6_TUNNEL_ALIGNED);
        check("NetworkRules.isDatacenterOrg combines geoip hosting flag and ASN keywords",
                NetworkRules.isDatacenterOrg("AS2516 KDDI", false, true)
                        && NetworkRules.isDatacenterOrg("AS13335 Cloudflare", false, false)
                        && !NetworkRules.isDatacenterOrg("AS2516 KDDI", false, false));

        java.util.Map<String, java.util.List<String>> testOui =
                new java.util.LinkedHashMap<String, java.util.List<String>>();
        testOui.put("EFM", java.util.Collections.singletonList("64:E5:99"));
        testOui.put("AVM", java.util.Collections.singletonList("00:1C:4A"));
        testOui.put("SERCOMM", java.util.Collections.singletonList("24:7F:20"));
        testOui.put("BOSE", java.util.Collections.singletonList("04:52:C7"));
        testOui.put("GOOGLE", java.util.Collections.singletonList("F4:F5:D8"));
        testOui.put("GENERIC", java.util.Collections.singletonList("B0:BE:76"));
        java.util.List<WifiProfile.Template> krTpls = Arrays.asList(
                new WifiProfile.Template("iptime-{H4}", "WPA2", 5, 5),
                new WifiProfile.Template("FRITZ!Box 7590 {H3}", "WPA3", 5, 5),
                new WifiProfile.Template("JioFiber-{H4}_5G", "WPA3", 5, 5));
        Set<String> primaryPrefixes = new LinkedHashSet<String>();
        boolean wifiUniqueAndOuiOk = true;
        for (long s = 1; s <= 12; s++) {
            java.util.List<WifiProfile.Ap> aps = WifiProfile.forRegion(
                    37.5665, 126.9780, "Seoul", s * 7919L, 6, krTpls, testOui);
            if (!aps.isEmpty()) {
                primaryPrefixes.add(aps.get(0).ssid.split("[- ]")[0]);
            }
            Set<String> seenSsid = new LinkedHashSet<String>();
            for (WifiProfile.Ap ap : aps) {
                if (!seenSsid.add(ap.ssid)) wifiUniqueAndOuiOk = false;
                if (ap.ssid.startsWith("iptime") && !ap.bssid.startsWith("64:E5:99:")) wifiUniqueAndOuiOk = false;
                if (ap.ssid.startsWith("FRITZ!Box") && !ap.bssid.startsWith("00:1C:4A:")) wifiUniqueAndOuiOk = false;
                if (ap.ssid.startsWith("JioFiber") && !ap.bssid.startsWith("24:7F:20:")) wifiUniqueAndOuiOk = false;
            }
        }
        check("WifiProfile varies primary connected AP, deduplicates SSIDs, and maps regional OUIs",
                wifiUniqueAndOuiOk && primaryPrefixes.size() >= 2);

        java.util.List<BleProfile.Template> bleTpls = Arrays.asList(
                new BleProfile.Template("Bose QuietComfort Ultra", -2, 4),
                new BleProfile.Template("Pixel Buds Pro 2", -1, 4),
                new BleProfile.Template("Sony WH-1000XM5", -2, 4),
                new BleProfile.Template("GARMIN Forerunner 265", -3, 4));
        java.util.List<BleProfile.Device> bleDevs = BleProfile.forRegion(42L, 4, bleTpls, testOui);
        Set<String> seenBleNames = new LinkedHashSet<String>();
        boolean bleOk = !bleDevs.isEmpty();
        for (BleProfile.Device d : bleDevs) {
            if (!seenBleNames.add(d.name)) bleOk = false;
            if (d.name.contains("Bose") && !d.address.startsWith("04:52:C7:")) bleOk = false;
            if (d.name.contains("Pixel") && !d.address.startsWith("F4:F5:D8:")) bleOk = false;
        }
        check("BleProfile deduplicates device names and maps expanded brand OUIs", bleOk);
    }

    private static void personaSaltLayersAndMemoryRulesWork() {
        // 1. Default model (modelSeed == 0L) across two distinct spaces must not collide in PersonaSynth.cross.
        long spaceA = 0x1122334455667788L;
        long spaceB = 0x8877665544332211L;
        long crossA = PersonaSalt.deviceCrossSeed(spaceA, 0L);
        long crossB = PersonaSalt.deviceCrossSeed(spaceB, 0L);
        check("deviceCrossSeed is non-zero and isolates spaces even when modelSeed=0",
                crossA != 0L && crossB != 0L && crossA != crossB);

        String[] pixelTemplate = {
                "Google Pixel 8", "Google", "google", "Pixel 8", "shiba", "shiba",
                "shiba", "shiba",
                "google/shiba/shiba:15/AP3A.241005.015/12366759:user/release-keys",
                "AP3A.241005.015", "12366759", "15", "35", "2024-10-05"
        };
        String[] variantA = PersonaSynth.cross(pixelTemplate, crossA);
        String[] variantB = PersonaSynth.cross(pixelTemplate, crossB);
        check("PersonaSynth.cross diverges between two spaces on default model",
                !variantA[8].equals(variantB[8]) || !variantA[10].equals(variantB[10]));
        check("PersonaSynth.cross is deterministic for same spaceSeed",
                Arrays.equals(variantA, PersonaSynth.cross(pixelTemplate, crossA)));

        // 2. profileSeed must mix region ISO (case-insensitive) and isolate across regions/spaces/models.
        long saltM1 = 0x55AA55AA12345678L;
        long saltM2 = 0xAA55AA5587654321L;
        long profJpA = PersonaSalt.profileSeed(crossA, saltM1, "jp");
        long profJpUpperA = PersonaSalt.profileSeed(crossA, saltM1, "JP");
        long profUsA = PersonaSalt.profileSeed(crossA, saltM1, "us");
        long profJpB = PersonaSalt.profileSeed(crossB, saltM1, "jp");
        long profJpM2 = PersonaSalt.profileSeed(crossA, saltM2, "jp");
        check("profileSeed is deterministic and case-insensitive on ISO",
                profJpA != 0L && profJpA == profJpUpperA);
        check("profileSeed isolates across region switches (JP vs US)", profJpA != profUsA);
        check("profileSeed isolates across spaces (spaceA vs spaceB)", profJpA != profJpB);
        check("profileSeed isolates across model identities", profJpA != profJpM2);

        // 3. Domain separation for Wi-Fi, BLE, and initial region pick seeds.
        long wifi0 = PersonaSalt.wifiSeed(profJpA, 0L);
        long wifi1 = PersonaSalt.wifiSeed(profJpA, 1L);
        long ble0 = PersonaSalt.bleSeed(profJpA, 0L);
        long ble1 = PersonaSalt.bleSeed(profJpA, 1L);
        check("wifiSeed and bleSeed are domain-separated and respond to reroll steps",
                wifi0 != ble0 && wifi0 != wifi1 && ble0 != ble1 && wifi1 != ble1);

        long pickJpA = PersonaSalt.regionPickSeed(spaceA, "jp");
        long pickJpB = PersonaSalt.regionPickSeed(spaceB, "jp");
        long pickUsA = PersonaSalt.regionPickSeed(spaceA, "us");
        check("regionPickSeed is space- and region-specific",
                pickJpA != pickJpB && pickJpA != pickUsA);

        // 4. Legacy EnvSession migration must never contaminate blank Native spaces.
        check("legacy migration runs once on clean upgrade",
                PersonaSalt.shouldMigrateLegacyRegion(false, false, null, "jp"));
        check("legacy migration blocked when already migrated",
                !PersonaSalt.shouldMigrateLegacyRegion(true, false, null, "jp"));
        check("legacy migration blocked when another space already has a bound region",
                !PersonaSalt.shouldMigrateLegacyRegion(false, true, null, "jp"));
        check("legacy migration skipped when current space already has region",
                !PersonaSalt.shouldMigrateLegacyRegion(false, false, "us", "jp"));
        check("legacy migration skipped when legacy ISO is absent",
                !PersonaSalt.shouldMigrateLegacyRegion(false, false, null, null));

        // 5. regionApplied must match both active user and ISO, preventing cross-space false greens.
        check("regionApplied true for matching space user and ISO",
                PersonaSalt.isRegionAppliedForSpace("10", "jp", "10", "JP", true, false));
        check("regionApplied false when another space applied the same ISO",
                !PersonaSalt.isRegionAppliedForSpace("0", "jp", "10", "jp", true, false));
        check("regionApplied false when session is partial",
                !PersonaSalt.isRegionAppliedForSpace("10", "jp", "10", "jp", true, true));
        check("regionApplied backward-compatible when legacy activeUser is missing",
                PersonaSalt.isRegionAppliedForSpace("0", "jp", null, "jp", true, false));

        // 6. Restored zone matches saved zone -> do not overwrite saved custom coordinates.
        check("shouldUpdateRestoredZone skips identical zone",
                !PersonaSalt.shouldUpdateRestoredZone("Asia/Tokyo", "Asia/Tokyo"));
        check("shouldUpdateRestoredZone detects actual zone change",
                PersonaSalt.shouldUpdateRestoredZone("America/Los_Angeles", "America/New_York"));
        check("shouldUpdateRestoredZone ignores blank restored zone",
                !PersonaSalt.shouldUpdateRestoredZone("", "Asia/Tokyo"));

        // 7. Salt version pinning: legacy spaces stay v1 across upgrades, brand-new spaces get v2.
        check("resolveSaltVersion keeps legacy space on v1 and assigns fresh space v2",
                PersonaSalt.resolveSaltVersion(0, true) == PersonaSalt.SALT_V1
                        && PersonaSalt.resolveSaltVersion(0, false) == PersonaSalt.SALT_V2
                        && PersonaSalt.resolveSaltVersion(PersonaSalt.SALT_V1, false) == PersonaSalt.SALT_V1
                        && PersonaSalt.resolveSaltVersion(PersonaSalt.SALT_V2, true) == PersonaSalt.SALT_V2);
        check("v1 salt formulas remain byte-for-byte compatible with legacy spaces",
                PersonaSalt.deviceCrossSeed(PersonaSalt.SALT_V1, spaceA, 0L) == 0L
                        && PersonaSalt.profileSeed(PersonaSalt.SALT_V1, 0L, saltM1, "au") == saltM1
                        && PersonaSalt.profileSeed(PersonaSalt.SALT_V1, 0L, saltM1, "jp") == saltM1
                        && PersonaSalt.wifiSeed(PersonaSalt.SALT_V1, saltM1, 2L)
                                == (saltM1 ^ (2L * 0x9E3779B97F4A7C15L))
                        && PersonaSalt.bleSeed(PersonaSalt.SALT_V1, saltM1, 2L)
                                == (saltM1 ^ (2L * 0xBF58476D1CE4E5B9L)));

        // 8. Build-pin freezing & legacy historical adoption.
        String[] s25Base = {
                "samsung SM-S938B", "samsung", "samsung", "SM-S938B", "pa3q", "pa3qxxx",
                "qcom", "sun",
                "samsung/pa3qxxx/pa3q:15/AP3A.240905.015.A2/S938BXXS1AYC2_OXM1AYC2:user/release-keys",
                "AP3A.240905.015.A2", "S938BXXS1AYC2", "15", "35", "2025-03-01"
        };
        String legacyFp = "samsung/pa3qxxx/pa3q:15/AP3A.240905.01Y.QN/S938BXXS1L939:user/release-keys";
        String[] adopted = PersonaSalt.applyBuildPin(s25Base, legacyFp, "AP3A.240905.01Y.QN", "", "");
        check("applyBuildPin adopts matching historical fingerprint and extracts incremental + patch",
                adopted != null
                        && legacyFp.equals(adopted[8])
                        && "AP3A.240905.01Y.QN".equals(adopted[9])
                        && "S938BXXS1L939".equals(adopted[10])
                        && "2024-09-05".equals(adopted[13]));
        check("applyBuildPin rejects cross-model or cross-release pin",
                PersonaSalt.applyBuildPin(pixelTemplate, legacyFp, "AP3A.240905.01Y.QN", "", "") == null
                        && PersonaSalt.applyBuildPin(s25Base,
                                "samsung/pa3qxxx/pa3q:16/BP3A.250905.010/S938BXXS2ABC1:user/release-keys",
                                "BP3A.250905.010", "", "") == null);
    }

    private static void atomicTextFilePersistsAndReplacesCleanly() {
        java.io.File dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("cw-atomic-test-").toFile();
            java.io.File target = new java.io.File(dir, "persona-state.json");
            java.io.File tmp = new java.io.File(dir, "persona-state.json.tmp");
            check("AtomicTextFile returns null for missing file",
                    AtomicTextFile.readUtf8(target) == null);

            String payload1 = "{\"space_iso.0\":\"jp\",\"root_space_seed.0\":\"123456789\"}";
            AtomicTextFile.writeUtf8(target, payload1);
            check("AtomicTextFile round-trips initial UTF-8 payload",
                    payload1.equals(AtomicTextFile.readUtf8(target)) && !tmp.exists());

            String payload2 = "{\"space_iso.10\":\"us\",\"note\":\"utf8-ok\"}";
            AtomicTextFile.writeUtf8(target, payload2);
            check("AtomicTextFile atomically replaces existing file with shorter payload",
                    payload2.equals(AtomicTextFile.readUtf8(target)) && !tmp.exists());
            check("NetworkRules parses Android 15 angle-bracketed Uids ranges",
                    NetworkRules.uidInRanges(10391, "<{0-10371, 10390-10393, 20595-99999}>")
                            && !NetworkRules.uidInRanges(10372, "<{0-10371, 10390-10393}>"));
            check("NetworkRules covers modern proxy TUN prefixes without matching WiFi/cellular",
                    NetworkRules.isKnownTunnelPrefix("tun0")
                            && NetworkRules.isKnownTunnelPrefix("Meta")
                            && NetworkRules.isKnownTunnelPrefix("sing-box0")
                            && NetworkRules.isKnownTunnelPrefix("awg0")
                            && NetworkRules.isKnownTunnelPrefix("flclash")
                            && NetworkRules.isKnownTunnelPrefix("netproxy")
                            && !NetworkRules.isKnownTunnelPrefix("wlan0")
                            && !NetworkRules.isKnownTunnelPrefix("rmnet_data0")
                            && !NetworkRules.isKnownTunnelPrefix("metadata0"));
            check("NetworkRules.isTunnelPeerOrPrivateDns flags sing-box/mihomo/v2ray/tailscale DNS only",
                    NetworkRules.isTunnelPeerOrPrivateDns(new byte[]{(byte) 172, 19, 0, 2})
                            && NetworkRules.isTunnelPeerOrPrivateDns(new byte[]{(byte) 198, 18, 0, 2})
                            && NetworkRules.isTunnelPeerOrPrivateDns(new byte[]{10, 10, 14, 2})
                            && NetworkRules.isTunnelPeerOrPrivateDns(new byte[]{26, 26, 26, 2})
                            && NetworkRules.isTunnelPeerOrPrivateDns(new byte[]{100, 100, 100, 100})
                            && !NetworkRules.isTunnelPeerOrPrivateDns(new byte[]{1, 1, 1, 1})
                            && !NetworkRules.isTunnelPeerOrPrivateDns(new byte[]{8, 8, 8, 8}));
            String rawNc = "[ Transports: WIFI|VPN Capabilities: NOT_METERED&INTERNET&TRUSTED&VALIDATED "
                    + "TransportInfo: <VpnTransportInfo{type=1, sessionId=FlClash, bypassable=true}> OwnerUid: 10049 ]";
            String cleanNc = NetworkRules.sanitizeCapabilitiesString(rawNc);
            check("NetworkRules.sanitizeCapabilitiesString strips VPN transport and VpnTransportInfo",
                    cleanNc.contains("Transports: WIFI ")
                            && cleanNc.contains("NOT_VPN")
                            && !cleanNc.contains("WIFI|VPN")
                            && !cleanNc.contains("VpnTransportInfo")
                            && !cleanNc.contains("FlClash"),
                    cleanNc);
            String rawNi = "[type: VPN[], state: CONNECTED/CONNECTED, extra: VPN:com.follow.clash, failover: false]";
            String cleanNi = NetworkRules.sanitizeNetworkInfoString(rawNi);
            check("NetworkRules.sanitizeNetworkInfoString rewrites VPN type and strips proxy package",
                    cleanNi.contains("type: WIFI[")
                            && !cleanNi.contains("VPN:")
                            && !cleanNi.contains("com.follow.clash"),
                    cleanNi);
            check("AppVisibilityPolicy detects modern proxy clients including SFA, NekoBox, WARP, NetProxy",
                    AppVisibilityPolicy.categoryOf("io.nekohasekai.sfa") == AppVisibilityPolicy.CAT_PROXY
                            && AppVisibilityPolicy.categoryOf("moe.nb4a") == AppVisibilityPolicy.CAT_PROXY
                            && AppVisibilityPolicy.categoryOf("com.follow.clash") == AppVisibilityPolicy.CAT_PROXY
                            && AppVisibilityPolicy.categoryOf("com.cloudflare.onedotonedotonedotone") == AppVisibilityPolicy.CAT_PROXY
                            && AppVisibilityPolicy.categoryOf("com.netproxy.app") == AppVisibilityPolicy.CAT_PROXY
                            && AppVisibilityPolicy.categoryOf("com.anthropic.claude") == AppVisibilityPolicy.CAT_NONE
                            && AppVisibilityPolicy.categoryOf("com.android.chrome") == AppVisibilityPolicy.CAT_NONE);
        } catch (Exception e) {
            check("AtomicTextFile I/O succeeded", false, e.toString());
        } finally {
            if (dir != null) {
                java.io.File[] children = dir.listFiles();
                if (children != null) {
                    for (java.io.File c : children) {
                        c.delete();
                    }
                }
                dir.delete();
            }
        }
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
