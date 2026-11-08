package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** TLS trust facts: what the system trust store holds, and what the user store holds. */
public final class TlsFacts {
    public static final TlsFacts EMPTY =
            new TlsFacts(false, Collections.<String>emptyList(), false,
                    Collections.<String>emptyList());

    private final boolean issuersReadable;
    private final List<String> issuers;
    private final boolean userCaReadable;
    private final List<String> userCaFiles;

    public TlsFacts(boolean issuersReadable, List<String> issuers, boolean userCaReadable,
                    List<String> userCaFiles) {
        this.issuersReadable = issuersReadable;
        this.issuers = Collections.unmodifiableList(
                issuers == null ? new ArrayList<String>() : new ArrayList<String>(issuers));
        this.userCaReadable = userCaReadable;
        this.userCaFiles = Collections.unmodifiableList(
                userCaFiles == null ? new ArrayList<String>() : new ArrayList<String>(userCaFiles));
    }

    /** True when the trust store could be enumerated at all. */
    public boolean issuersReadable() {
        return issuersReadable;
    }

    /** Subject DNs of every accepted issuer, with whitespace after commas removed. */
    public List<String> issuers() {
        return issuers;
    }

    public boolean userCaReadable() {
        return userCaReadable;
    }

    public List<String> userCaFiles() {
        return userCaFiles;
    }
}
