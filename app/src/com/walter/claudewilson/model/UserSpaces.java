package com.walter.claudewilson.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User id to display name pairs parsed from {@code UserInfo{id:name:flags}} rows, the format
 * shared by {@code pm list users} and {@code dumpsys user}. A name can contain spaces and
 * punctuation, so the flags suffix is cut at the LAST colon: everything before it is the name.
 */
public final class UserSpaces {
    private static final Pattern ROW =
            Pattern.compile("UserInfo\\{(\\d+):(.+):[0-9a-fA-F]+\\}");

    private UserSpaces() {}

    /** Id to name, in list order; empty when the output held no UserInfo rows. */
    public static Map<String, String> parse(String output) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        if (output == null) {
            return names;
        }
        Matcher rows = ROW.matcher(output);
        while (rows.find()) {
            names.put(rows.group(1), rows.group(2).trim());
        }
        return names;
    }
}
