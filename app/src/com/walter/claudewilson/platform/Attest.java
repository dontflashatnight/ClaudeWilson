package com.walter.claudewilson.platform;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hardware key attestation -- the same artifact the target's trusted-device flow sends as an
 * attestation blob. Generates an EC P-256 key in AndroidKeyStore with a random challenge, then
 * parses extension 1.3.6.1.4.1.11129.2.1.17 for attestationSecurityLevel / deviceLocked /
 * verifiedBootState.
 *
 * <p>Moved here unchanged when the platform checks became probes: this is the only class that
 * touches the crypto API, and the rules receive its output as plain data.
 */
final class Attest {

    private static final String OID = "1.3.6.1.4.1.11129.2.1.17";

    int securityLevel;      // 0=software 1=TEE 2=StrongBox, -1=parse failed

    int verifiedBoot;       // 0=verified 1=selfsigned 2=unverified 3=failed, -1=absent
    boolean deviceLocked;
    boolean rootOfTrustPresent;                 // [704] tag actually parsed (AOSP schema)
    int osVersion = -1;                         // [705]
    int osPatchLevel = -1;                      // [706]
    final List<Integer> teeTags = new ArrayList<>(); // all context tags seen in teeEnforced
    // TEE-claimed device identity fields — what a server reads as hardware truth
    final Map<String, String> deviceProps = new LinkedHashMap<>();
    final Set<String> teeClaimed = new HashSet<>();
    private byte[] challenge;
    private final List<String> chain = new ArrayList<>();

    // attestation-id tags (KeyMint/Keymaster AuthorizationList context tags)
    private static final String[] PROP_TAGS = {
        "brand", "device", "product", "serial", "imei", "meid",
        "manufacturer", "model", "vendor_patch", "boot_patch", "device_unique" };
    private static final Set<String> SENSITIVE =
            new HashSet<>(Arrays.asList("serial", "imei", "meid", "imei2"));

    private static void addProp(Attest a, Tlv e, boolean tee) {
        if (e.tag < 710 || e.tag > 723) return;
        String name = e.tag - 710 < PROP_TAGS.length ? PROP_TAGS[e.tag - 710]
                : (e.tag == 723 ? "imei2" : "tag" + e.tag);
        try {
            byte[] v = unwrap(e.value);
            String s;
            if (v.length == 0) s = "yes";                    // device_unique NULL flag
            else if (e.tag == 718 || e.tag == 719) s = String.valueOf(toInt(v));  // patch ints
            else s = new String(v, java.nio.charset.StandardCharsets.UTF_8);
            if (SENSITIVE.contains(name)) s = "***" + s.substring(Math.max(0, s.length() - 4));
            a.deviceProps.put(name, s);
            if (tee) a.teeClaimed.add(name);
        } catch (Throwable ignored) {}
    }

    private static void scanList(byte[] listValue, Attest a, boolean tee) {
        try {
            Der d = new Der(listValue);
            while (d.more()) {
                Tlv e = d.tlv();
                if (e.tagClass == 0x80) addProp(a, e, tee);
            }
        } catch (Throwable ignored) {}
    }

    String securityLevelName() {
        switch (securityLevel) {
            case 1: return "TEE";
            case 2: return "StrongBox";
            default: return "software";
        }
    }

    String challengeHex() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8 && i < challenge.length; i++)
            s.append(String.format("%02x", challenge[i]));
        return s.toString() + "…";
    }

    // "opt-in" = device-props attestation requested and honoured;
    // "unsupported" = keygen with the flag threw, retried without it (older Keymaster).
    String propsMode = "default";

    static Attest generate(Context ctx) throws Exception {
        Attest a = generate(ctx, true);
        if (a != null) {
            a.propsMode = "opt-in";
            return a;
        }
        a = generate(ctx, false);
        a.propsMode = "unsupported";
        return a;
    }

    private static Attest generate(Context ctx, boolean includeDeviceProps) throws Exception {
        String alias = "el_probe_" + Long.toHexString(System.nanoTime());
        Attest a = new Attest();
        try {
            a.challenge = new byte[32];
            new java.security.SecureRandom().nextBytes(a.challenge);
            KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(a.challenge);
            if (includeDeviceProps) {
                spec.setDevicePropertiesAttestationIncluded(true);
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(spec.build());
            g.generateKeyPair();
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            java.security.cert.Certificate[] certs = ks.getCertificateChain(alias);
            a.securityLevel = -1;
            a.verifiedBoot = -1;
            for (java.security.cert.Certificate c : certs) {
                X509Certificate x = (X509Certificate) c;
                a.chain.add(x.getSubjectX500Principal().getName());
                byte[] ext = x.getExtensionValue(OID);
                if (ext == null) continue;
                parse(ext, a);
                break;
            }
            if (a.chain.isEmpty()) throw new IllegalStateException("empty attestation chain");
            ks.deleteEntry(alias);
        } catch (Exception e) {
            if (includeDeviceProps) return null;
            throw e;
        } finally {
            deleteAlias(alias);
        }
        return a;
    }

    private static void deleteAlias(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(alias);
        } catch (Throwable ignored) {}
    }

    // ---------- minimal DER ----------

    private static final class Der {
        final byte[] b; int p;
        Der(byte[] b) { this.b = b; }
        Tlv tlv() {
            int tagByte = b[p++] & 0xff;
            int tagClass = tagByte & 0xC0;
            boolean constructed = (tagByte & 0x20) != 0;
            int num = tagByte & 0x1F;
            if (num == 0x1F) { num = 0; int nb; do { nb = b[p++] & 0xff; num = (num << 7) | (nb & 0x7f); } while ((nb & 0x80) != 0); }
            int len = b[p++] & 0xff;
            if (len == 0x80) throw new IllegalArgumentException("indefinite length");
            if ((len & 0x80) != 0) { int n = len & 0x7f; len = 0; for (int i = 0; i < n; i++) len = (len << 8) | (b[p++] & 0xff); }
            Tlv t = new Tlv();
            t.tagClass = tagClass; t.constructed = constructed; t.tag = num;
            t.value = Arrays.copyOfRange(b, p, p + len);
            p += len;
            return t;
        }
        boolean more() { return p < b.length; }
    }

    private static final class Tlv {
        int tagClass, tag; boolean constructed; byte[] value;
    }

    private static int toInt(byte[] v) {
        int r = 0;
        for (byte x : v) r = (r << 8) | (x & 0xff);
        return r;
    }

    /** ext = extnValue bytes = OCTET STRING wrapping KeyDescription SEQUENCE. */
    private static void parse(byte[] ext, Attest a) {
        Der d = new Der(ext);
        Tlv outer = d.tlv();                    // OCTET STRING
        Der kd = new Der(outer.value);
        Tlv seq = kd.tlv();                     // KeyDescription SEQUENCE
        Der f = new Der(seq.value);
        f.tlv();                                // attestationVersion
        Tlv lvl = f.tlv();                      // attestationSecurityLevel ENUMERATED
        a.securityLevel = toInt(lvl.value);
        f.tlv(); f.tlv();                       // keymasterVersion, keymasterSecurityLevel
        f.tlv(); f.tlv();                       // challenge, uniqueId
        Tlv sw = f.tlv();                       // softwareEnforced AuthorizationList
        Tlv tee = f.tlv();                      // teeEnforced AuthorizationList
        scanList(sw.value, a, false);
        Der t = new Der(tee.value);
        while (t.more()) {
            Tlv e = t.tlv();
            if (e.tagClass == 0x80) a.teeTags.add(e.tag);
            // AOSP Keymaster schema: rootOfTrust=[704] EXPLICIT,
            // osVersion=[705] osPatchLevel=[706] EXPLICIT INTEGER
            if (e.tagClass != 0x80) continue;
            if (e.tag >= 710) { addProp(a, e, true); continue; }
            try {
                if (e.tag == 704) {
                    a.rootOfTrustPresent = true;
                    Der rot = new Der(unwrap(e.value));
                    rot.tlv();                          // verifiedBootKey OCTET STRING
                    Tlv locked = rot.tlv();             // deviceLocked BOOLEAN
                    a.deviceLocked = locked.value.length > 0 && locked.value[0] != 0;
                    Tlv state = rot.tlv();              // verifiedBootState INTEGER
                    a.verifiedBoot = toInt(state.value);
                } else if (e.tag == 705) {
                    a.osVersion = toInt(unwrap(e.value));
                } else if (e.tag == 706) {
                    a.osPatchLevel = toInt(unwrap(e.value));
                }
            } catch (Throwable ignored) {}
        }
    }

    /** EXPLICIT-tagged field: strip one inner TLV header and return the value bytes. */
    private static byte[] unwrap(byte[] wrapped) {
        Der d = new Der(wrapped);
        Tlv t = d.tlv();
        return t.value;
    }
}
