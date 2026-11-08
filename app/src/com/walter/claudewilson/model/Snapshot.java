package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the probes are allowed to look at: one collected, immutable view of the device.
 *
 * <p>Collection happens once in the platform layer, so probes have no order dependency on each
 * other and the same snapshot can be replayed against different rule versions. Every collection
 * that can fail carries its own "did this actually work" flag, because a probe that cannot tell
 * missing from unreadable can only guess.
 */
public final class Snapshot {
    private final BuildValues build;
    private final SimValues sim;
    private final Map<String, Text> props;
    private final List<Mount> mounts;
    private final boolean mountsReadable;
    private final List<String> packages;
    private final Map<String, String> labels;
    private final boolean packagesReadable;
    private final Text selinuxEnforce;
    private final Text androidId;
    private final Text installer;
    private final Text targetVersion;
    private final boolean targetInstalled;
    private final boolean targetChecked;
    private final Text hiddenApiPolicy;
    private final Set<String> installedPackages;
    private final boolean installedReadable;
    private final List<Account> accounts;
    private final boolean accountsReadable;
    private final PlatformFacts platform;
    private final TlsFacts tls;
    private final NetworkFacts network;
    private final TargetEvidence targetEvidence;
    private final Tables tables;
    private final long capturedAt;

    private Snapshot(Builder b) {
        this.build = b.build;
        this.sim = b.sim;
        this.props = Collections.unmodifiableMap(new LinkedHashMap<String, Text>(b.props));
        this.mounts = Collections.unmodifiableList(new ArrayList<Mount>(b.mounts));
        this.mountsReadable = b.mountsReadable;
        this.packages = Collections.unmodifiableList(new ArrayList<String>(b.packages));
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.labels));
        this.packagesReadable = b.packagesReadable;
        this.selinuxEnforce = b.selinuxEnforce;
        this.androidId = b.androidId;
        this.installer = b.installer;
        this.targetVersion = b.targetVersion;
        this.targetInstalled = b.targetInstalled;
        this.targetChecked = b.targetChecked;
        this.hiddenApiPolicy = b.hiddenApiPolicy;
        this.installedPackages = Collections.unmodifiableSet(
                new LinkedHashSet<String>(b.installedPackages));
        this.installedReadable = b.installedReadable;
        this.accounts = Collections.unmodifiableList(new ArrayList<Account>(b.accounts));
        this.accountsReadable = b.accountsReadable;
        this.platform = b.platform;
        this.tls = b.tls;
        this.network = b.network;
        this.targetEvidence = b.targetEvidence;
        this.tables = b.tables;
        this.capturedAt = b.capturedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BuildValues build() {
        return build;
    }

    public SimValues sim() {
        return sim;
    }

    /** A property that was never collected reads as unreadable, not as empty. */
    public Text prop(String key) {
        Text value = props.get(key);
        return value == null ? Text.unreadable() : value;
    }

    public List<Mount> mounts() {
        return mounts;
    }

    /** False when the mount table itself could not be read; rules must then say unknown. */
    public boolean mountsReadable() {
        return mountsReadable;
    }

    public List<String> packages() {
        return packages;
    }

    /** False when the package list could not be enumerated at all. */
    public boolean packagesReadable() {
        return packagesReadable;
    }

    public String labelOf(String packageName) {
        String label = labels.get(packageName);
        return label == null ? "" : label;
    }

    /** Package name to label map, for a fast snapshot that reuses the last census. */
    public Map<String, String> packageLabels() {
        return labels;
    }

    /** Raw value of the SELinux enforcement switch; unreadable when neither source answered. */
    public Text selinuxEnforce() {
        return selinuxEnforce;
    }

    /** This app's own ANDROID_ID. */
    public Text androidId() {
        return androidId;
    }

    /** Installing package of the target application. */
    public Text installer() {
        return installer;
    }

    public Text targetVersion() {
        return targetVersion;
    }

    public boolean targetInstalled() {
        return targetInstalled;
    }

    /** False when the target's package state was never collected, which is not "not installed". */
    public boolean targetChecked() {
        return targetChecked;
    }

    /** Value of the hidden-API policy flag; unreadable when the settings read failed. */
    public Text hiddenApiPolicy() {
        return hiddenApiPolicy;
    }

    /** Every installed package name, lower-cased. Empty when the enumeration failed. */
    public Set<String> installedPackages() {
        return installedPackages;
    }

    public boolean installedReadable() {
        return installedReadable;
    }

    public List<Account> accounts() {
        return accounts;
    }

    public boolean accountsReadable() {
        return accountsReadable;
    }

    public PlatformFacts platform() {
        return platform;
    }

    public TlsFacts tls() {
        return tls;
    }

    public NetworkFacts network() {
        return network;
    }

    /** Name-level target inventory; EMPTY when it was not collected for this scan. */
    public TargetEvidence targetEvidence() {
        return targetEvidence;
    }

    public Tables tables() {
        return tables;
    }

    public long capturedAt() {
        return capturedAt;
    }

    public static final class Builder {
        private BuildValues build = BuildValues.EMPTY;
        private SimValues sim = SimValues.EMPTY;
        private final Map<String, Text> props = new LinkedHashMap<String, Text>();
        private final List<Mount> mounts = new ArrayList<Mount>();
        private boolean mountsReadable;
        private final List<String> packages = new ArrayList<String>();
        private final Map<String, String> labels = new LinkedHashMap<String, String>();
        private boolean packagesReadable;
        private Text selinuxEnforce = Text.unreadable();
        private Text androidId = Text.unreadable();
        private Text installer = Text.unreadable();
        private Text targetVersion = Text.unreadable();
        private boolean targetInstalled;
        private boolean targetChecked;
        private Text hiddenApiPolicy = Text.unreadable();
        private final Set<String> installedPackages = new LinkedHashSet<String>();
        private boolean installedReadable;
        private final List<Account> accounts = new ArrayList<Account>();
        private boolean accountsReadable;
        private PlatformFacts platform = PlatformFacts.EMPTY;
        private TlsFacts tls = TlsFacts.EMPTY;
        private NetworkFacts network = NetworkFacts.EMPTY;
        private TargetEvidence targetEvidence = TargetEvidence.EMPTY;
        private Tables tables = Tables.EMPTY;
        private long capturedAt;

        public Builder buildValues(BuildValues value) {
            this.build = value == null ? BuildValues.EMPTY : value;
            return this;
        }

        public Builder sim(SimValues value) {
            this.sim = value == null ? SimValues.EMPTY : value;
            return this;
        }

        public Builder prop(String key, Text value) {
            props.put(key, value == null ? Text.unreadable() : value);
            return this;
        }

        public Builder mounts(List<Mount> rows, boolean readable) {
            mounts.clear();
            if (rows != null) {
                mounts.addAll(rows);
            }
            mountsReadable = readable;
            return this;
        }

        public Builder packages(List<String> names, Map<String, String> packageLabels,
                                boolean readable) {
            packages.clear();
            if (names != null) {
                packages.addAll(names);
            }
            labels.clear();
            if (packageLabels != null) {
                labels.putAll(packageLabels);
            }
            packagesReadable = readable;
            return this;
        }

        public Builder tables(Tables value) {
            this.tables = value == null ? Tables.EMPTY : value;
            return this;
        }

        public Builder selinuxEnforce(Text value) {
            this.selinuxEnforce = value == null ? Text.unreadable() : value;
            return this;
        }

        public Builder androidId(Text value) {
            this.androidId = value == null ? Text.unreadable() : value;
            return this;
        }

        public Builder targetInstall(Text installerName, Text version, boolean installed) {
            this.installer = installerName == null ? Text.unreadable() : installerName;
            this.targetVersion = version == null ? Text.unreadable() : version;
            this.targetInstalled = installed;
            this.targetChecked = true;
            return this;
        }

        public Builder hiddenApiPolicy(Text value) {
            this.hiddenApiPolicy = value == null ? Text.unreadable() : value;
            return this;
        }

        public Builder installedPackages(List<String> names, boolean readable) {
            installedPackages.clear();
            if (names != null) {
                for (String name : names) {
                    installedPackages.add(name.toLowerCase(java.util.Locale.ROOT));
                }
            }
            installedReadable = readable;
            return this;
        }

        public Builder accounts(List<Account> entries, boolean readable) {
            accounts.clear();
            if (entries != null) {
                accounts.addAll(entries);
            }
            accountsReadable = readable;
            return this;
        }

        public Builder platform(PlatformFacts value) {
            this.platform = value == null ? PlatformFacts.EMPTY : value;
            return this;
        }

        public Builder tls(TlsFacts value) {
            this.tls = value == null ? TlsFacts.EMPTY : value;
            return this;
        }

        public Builder network(NetworkFacts value) {
            this.network = value == null ? NetworkFacts.EMPTY : value;
            return this;
        }

        public Builder targetEvidence(TargetEvidence value) {
            this.targetEvidence = value == null ? TargetEvidence.EMPTY : value;
            return this;
        }

        public Builder capturedAt(long value) {
            this.capturedAt = value;
            return this;
        }

        public Snapshot build() {
            return new Snapshot(this);
        }
    }
}
