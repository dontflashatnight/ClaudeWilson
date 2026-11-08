package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One account entry as plain data: the account name and its authenticator type. */
public final class Account {
    private final String name;
    private final String type;

    public Account(String name, String type) {
        this.name = name == null ? "" : name;
        this.type = type == null ? "" : type;
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    /**
     * Google accounts from `dumpsys account`. Null means the account service did not answer;
     * an empty list is a valid answer meaning no Google account was present.
     */
    public static List<Account> googleFromDumpsys(String dump) {
        if (dump == null || !dump.contains("Accounts:")) {
            return null;
        }
        Set<String> names = new LinkedHashSet<String>();
        for (String line : dump.split("\n")) {
            int typeAt = line.lastIndexOf(", type=com.google}");
            int nameAt = line.indexOf("name=");
            if (typeAt < 0 || nameAt < 0 || nameAt + 5 >= typeAt) {
                continue;
            }
            String name = line.substring(nameAt + 5, typeAt).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        List<Account> accounts = new ArrayList<Account>(names.size());
        for (String name : names) {
            accounts.add(new Account(name, "com.google"));
        }
        return accounts;
    }

    @Override
    public String toString() {
        return name + " <- " + type;
    }
}
