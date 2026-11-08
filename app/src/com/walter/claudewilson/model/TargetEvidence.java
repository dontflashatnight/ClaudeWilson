package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only, name-level inventory of one target's local stores.
 *
 * <p>No file contents, identifiers or tokens are carried here. The facts answer two questions the
 * UI may ask safely: which fixed store directories exist, and which configured marker words occur
 * in file names. A marker hit is an inventory signal, never proof that the service reads that
 * value; a miss is not proof of absence.
 */
public final class TargetEvidence {
    public static final int SU_UNKNOWN = 0;
    public static final int SU_HIDDEN = 1;
    public static final int SU_VISIBLE = 2;

    public static final TargetEvidence EMPTY = new TargetEvidence(false, false, false, false, false,
            "", "", "", "", "not collected", Collections.<String>emptyList(),
            Collections.<String>emptyList(), Collections.<String>emptyList(),
            Collections.<String>emptyList(), false, false, false, false, 0, SU_UNKNOWN);

    private final boolean collected;
    private final boolean installed;
    private final boolean dataRequested;
    private final boolean dataReadable;
    private final boolean configPresent;
    private final String version;
    private final String installer;
    private final String uid;
    private final String source;
    private final String note;
    private final List<String> splits;
    private final List<String> stores;
    private final List<String> identityKinds;
    private final List<String> integrityKinds;
    private final boolean webViewStore;
    private final boolean cookieStore;
    private final boolean localStorageStore;
    private final boolean indexedDbStore;
    private final int filesSeen;
    private final int suView;

    private TargetEvidence(boolean collected, boolean installed, boolean dataRequested,
                           boolean dataReadable, boolean configPresent, String version,
                           String installer, String uid, String source, String note,
                           List<String> splits, List<String> stores, List<String> identityKinds,
                           List<String> integrityKinds, boolean webViewStore, boolean cookieStore,
                           boolean localStorageStore, boolean indexedDbStore, int filesSeen,
                           int suView) {
        this.collected = collected;
        this.installed = installed;
        this.dataRequested = dataRequested;
        this.dataReadable = dataReadable;
        this.configPresent = configPresent;
        this.version = version == null ? "" : version;
        this.installer = installer == null ? "" : installer;
        this.uid = uid == null ? "" : uid;
        this.source = source == null ? "" : source;
        this.note = note == null ? "" : note;
        this.splits = copy(splits);
        this.stores = copy(stores);
        this.identityKinds = copy(identityKinds);
        this.integrityKinds = copy(integrityKinds);
        this.webViewStore = webViewStore;
        this.cookieStore = cookieStore;
        this.localStorageStore = localStorageStore;
        this.indexedDbStore = indexedDbStore;
        this.filesSeen = filesSeen;
        this.suView = suView;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(values == null
                ? new ArrayList<String>() : new ArrayList<String>(values));
    }

    public boolean collected() {
        return collected;
    }

    public boolean installed() {
        return installed;
    }

    /** True only when a data directory was actually enumerated. */
    public boolean dataReadable() {
        return dataReadable;
    }

    /** True only while a payload for this target exists; a self-check without it is stale. */
    public boolean configPresent() {
        return configPresent;
    }

    /** True when this target's spec asks for a local inventory at all. */
    public boolean dataRequested() {
        return dataRequested;
    }

    public String version() {
        return version;
    }

    public String installer() {
        return installer;
    }

    public String uid() {
        return uid;
    }

    /** {@code pm} or {@code root}; the evidence path that produced this record. */
    public String source() {
        return source;
    }

    public String note() {
        return note;
    }

    public List<String> splits() {
        return splits;
    }

    public List<String> stores() {
        return stores;
    }

    public List<String> identityKinds() {
        return identityKinds;
    }

    public List<String> integrityKinds() {
        return integrityKinds;
    }

    public boolean webViewStore() {
        return webViewStore;
    }

    public boolean cookieStore() {
        return cookieStore;
    }

    public boolean localStorageStore() {
        return localStorageStore;
    }

    public boolean indexedDbStore() {
        return indexedDbStore;
    }

    /** Number of file names seen, bounded by the collector; not a count of records. */
    public int filesSeen() {
        return filesSeen;
    }

    /** {@link #SU_HIDDEN}, {@link #SU_VISIBLE}, or {@link #SU_UNKNOWN} from the target self-check. */
    public int suView() {
        return suView;
    }

    public boolean hasIdentitySignals() {
        return !identityKinds.isEmpty();
    }

    public boolean hasIntegritySignals() {
        return !integrityKinds.isEmpty();
    }

    public static final class Builder {
        private boolean collected;
        private boolean installed;
        private boolean dataRequested;
        private boolean dataReadable;
        private boolean configPresent;
        private String version = "";
        private String installer = "";
        private String uid = "";
        private String source = "";
        private String note = "";
        private final List<String> splits = new ArrayList<String>();
        private final List<String> stores = new ArrayList<String>();
        private final List<String> identityKinds = new ArrayList<String>();
        private final List<String> integrityKinds = new ArrayList<String>();
        private boolean webViewStore;
        private boolean cookieStore;
        private boolean localStorageStore;
        private boolean indexedDbStore;
        private int filesSeen;
        private int suView = SU_UNKNOWN;

        public Builder collected(boolean value) { collected = value; return this; }
        public Builder installed(boolean value) { installed = value; return this; }
        public Builder dataRequested(boolean value) { dataRequested = value; return this; }
        public Builder dataReadable(boolean value) { dataReadable = value; return this; }
        public Builder configPresent(boolean value) { configPresent = value; return this; }
        public Builder version(String value) { version = value == null ? "" : value; return this; }
        public Builder installer(String value) { installer = value == null ? "" : value; return this; }
        public Builder uid(String value) { uid = value == null ? "" : value; return this; }
        public Builder source(String value) { source = value == null ? "" : value; return this; }
        public Builder note(String value) { note = value == null ? "" : value; return this; }

        public Builder split(String value) {
            if (value != null && !value.isEmpty() && !splits.contains(value)) {
                splits.add(value);
            }
            return this;
        }

        public Builder store(String value) {
            if (value != null && !value.isEmpty() && !stores.contains(value)) {
                stores.add(value);
            }
            return this;
        }

        public Builder identityKind(String value) {
            if (value != null && !value.isEmpty() && !identityKinds.contains(value)) {
                identityKinds.add(value);
            }
            return this;
        }

        public Builder integrityKind(String value) {
            if (value != null && !value.isEmpty() && !integrityKinds.contains(value)) {
                integrityKinds.add(value);
            }
            return this;
        }

        public Builder webViewStore(boolean value) { webViewStore = value; return this; }
        public Builder cookieStore(boolean value) { cookieStore = value; return this; }
        public Builder localStorageStore(boolean value) { localStorageStore = value; return this; }
        public Builder indexedDbStore(boolean value) { indexedDbStore = value; return this; }
        public Builder filesSeen(int value) { filesSeen = value; return this; }
        public Builder suView(int value) { suView = value; return this; }

        public TargetEvidence build() {
            return new TargetEvidence(collected, installed, dataRequested, dataReadable,
                    configPresent, version, installer, uid, source, note, splits, stores,
                    identityKinds, integrityKinds, webViewStore, cookieStore, localStorageStore,
                    indexedDbStore, filesSeen, suView);
        }
    }
}
