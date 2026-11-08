package com.walter.claudewilson;

import android.content.Context;
import android.os.SystemClock;
import android.os.UserManager;

import com.walter.claudewilson.model.UserSpaces;

import java.util.Collections;
import java.util.Map;

/**
 * Display names for persona spaces. A numeric user id means nothing to a person, so the UI
 * shows the real Android user name instead: "机主", "Secure Folder", or the ws-* name this
 * app gives the clone profiles it creates.
 *
 * <p>Two sources, cheapest first: the cached result of {@code pm list users} through the
 * shell channel, and {@link UserManager#getUserName()} for the user this app itself runs in --
 * the only name readable without privilege. An id with no known name falls back to the raw
 * digits rather than a made-up label.
 *
 * <p>A refresh only posts a UI update when the parsed map actually changed, and even then it
 * updates the space chip rather than rebuilding the whole screen. The previous form posted a
 * full {@code render()} on every rescan as soon as any name was known, which was one of the
 * unconditional full rebuilds behind the region-switch jank.
 */
final class SpaceNames {
    private static final long REFRESH_TTL_MS = 30_000L;

    private static volatile Map<String, String> names = Collections.emptyMap();
    private static volatile boolean loading;
    private static volatile long lastRefresh;

    private SpaceNames() {}

    /** The display name for a space id; the id itself when no name is known. */
    static String name(Context context, String userId) {
        if (userId == null || userId.isEmpty()) {
            return "";
        }
        String known = names.get(userId);
        if (known != null) {
            return known;
        }
        if (userId.equals(currentUserId())) {
            String self = selfName(context);
            if (self != null && !self.isEmpty()) {
                return self;
            }
        }
        return userId;
    }

    /** All known id to name pairs in list order; the picker lists them as-is. */
    static Map<String, String> all() {
        return names;
    }

    private static String currentUserId() {
        return String.valueOf(android.os.Process.myUid() / 100000);
    }

    private static String selfName(Context context) {
        try {
            UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return users == null ? null : users.getUserName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Adopts names found in a command's output. Non-listing output parses to nothing and is
     * ignored, so every persona op's output can come through here unconditionally.
     */
    static void observe(String output) {
        Map<String, String> parsed = UserSpaces.parse(output);
        if (!parsed.isEmpty() && !parsed.equals(names)) {
            names = parsed;
        }
    }

    /** Fetches the user list once when the shell channel is up and nothing is known yet. */
    static void ensureLoaded() {
        if (loading || !names.isEmpty()) {
            return;
        }
        refresh();
    }

    /** Forces the next {@link #refresh()} to ask again, after a space was created or removed. */
    static void invalidate() {
        lastRefresh = 0L;
    }

    /**
     * Re-fetches the full list, but at most once per {@link #REFRESH_TTL_MS}. A fresh dump
     * replaces the cache so removed spaces vanish; a re-render is posted only when the map
     * actually changed.
     */
    static void refresh() {
        if (RootShell.state() != RootShell.ST_READY) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (loading) {
            return;
        }
        if (!names.isEmpty() && now - lastRefresh < REFRESH_TTL_MS) {
            return;
        }
        loading = true;
        lastRefresh = now;
        new Thread(() -> {
            Map<String, String> parsed = Collections.emptyMap();
            try {
                parsed = UserSpaces.parse(RootShell.run(UserActions.listUsers()));
            } catch (Throwable ignored) {
                // keep the previous map; the TTL stops a tight retry loop
            } finally {
                loading = false;
            }
            if (!parsed.isEmpty() && !parsed.equals(names)) {
                names = parsed;
                ScreenUpdates.post(Dashboard::refreshSpaceLabel);
            }
        }, "space-names").start();
    }
}
