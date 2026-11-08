package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Report;
import com.walter.claudewilson.model.Snapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The one place that knows which probes exist.
 *
 * <p>Adding a check means adding a class and one line here; no orchestrator needs to change and
 * no probe depends on another having run first, because they all read the same snapshot.
 */
public final class ProbeRegistry {

    private ProbeRegistry() {
    }

    public static List<Probe> all() {
        return Collections.unmodifiableList(Arrays.<Probe>asList(
                new MountStateProbe(),
                new BuildTagsProbe(),
                new SelinuxProbe(),
                new EmulatorSignatureProbe(),
                new RootPackagesProbe(),
                new RestrictedPropsProbe(),
                new PackageSuspicionProbe(),
                new FingerprintCoherenceProbe(),
                new PartitionFingerprintProbe(),
                new PropCoherenceProbe(),
                new IdentitySanityProbe(),
                new SimCoherenceProbe(),
                new AndroidIdProbe(),
                new VerifiedBootProbe(),
                new AttestationProbe(),
                new StrongBoxProbe(),
                new BiometricProbe(),
                new GmsProbe(),
                new IntegrityVerdictProbe(),
                new TlsIssuerProbe(),
                new UserCaProbe(),
                new VpnTunnelProbe(),
                new ProxyProbe(),
                new InterfaceAddressProbe(),
                new TargetInstallProbe(),
                new TargetShapeProbe(),
                new TargetIdentityProbe(),
                new TargetAccountChainProbe(),
                new TargetPlayIntegrityProbe(),
                new TargetCaptchaProbe(),
                new TargetDeviceKeyProbe(),
                new TargetVpnViewProbe(),
                new TargetSuViewProbe(),
                new HiddenApiProbe(),
                new AccountsProbe()));
    }

    /** Run every probe over one snapshot and collect the findings. */
    public static Report scan(Snapshot snapshot) {
        List<Probe> probes = all();
        List<Finding> findings = new ArrayList<Finding>(probes.size());
        for (Probe probe : probes) {
            findings.add(probe.run(snapshot));
        }
        return Report.of(findings);
    }
}
