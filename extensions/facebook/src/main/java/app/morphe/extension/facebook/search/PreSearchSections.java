/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * What the rows on the search screen are before anything is typed, for #80's People you may know
 * and For you sections. Read-only and Debug logging only: nothing here changes a row.
 *
 * <p>Facebook draws each row from a server-built entry, and no field it reads names either section.
 * The patch hands over each entry's node and the module tree read off it as the row is drawn. This
 * logs the GraphQL type names of both, read through {@link FeedFilter#typeName}'s checks, and the
 * node's {@code rendered_modules} string, which Facebook hands its own performance logger. Only
 * names survive: a type or module name that isn't letters, underscores and at most four digits is
 * logged as "other", so no id, title or search text gets out. Each distinct line is logged once,
 * up to {@link #MAX_SHAPES}, and after that no entry is read.
 */
public final class PreSearchSections {
    static final int MAX_SHAPES = 32;
    static final int MAX_MODULES = 8;
    private static final int MAX_NAME = 64;
    private static final int MAX_DIGITS = 4;
    static final String PREFIX = "Search screen before typing: ";
    /** A tree keys a field by its name's hashCode. */
    private static final int RENDERED_MODULES = "rendered_modules".hashCode();

    private static final ConcurrentHashMap<Class<?>, Object> STRING_READERS = new ConcurrentHashMap<>();
    private static final Set<String> shapes = new HashSet<>();
    private static boolean budgetNoticed;
    /** Set once the budget is spent, so later entries aren't read at all. */
    private static volatile boolean full;

    private PreSearchSections() {
    }

    /**
     * Injection point, in the pre-search section's item case, right after the module tree is read
     * off an entry's node. Reads both and logs, with Debug logging on. Never throws.
     *
     * @param node   the entry's node, never null where the patch calls this.
     * @param module the module tree the row is drawn from, null when Facebook draws an empty row.
     */
    public static void entry(@Nullable Object node, @Nullable Object module) {
        if (!enabled() || full) return;
        try {
            String nodeType = FeedFilter.typeName(node);
            // A node whose type can't be read may be released, and its strings aren't asked.
            String modules = nodeType == null ? "unread" : modules(node);
            record("node=" + name(nodeType) + " module=" + (module == null ? "none" : name(FeedFilter.typeName(module)))
                    + " rendered_modules=" + modules);
        } catch (Throwable unreadable) {
            record("unreadable entry");
        }
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && BaseSettings.DEBUG.get();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** The node's module names, comma-separated, "none" when it has none, or "unread". */
    private static String modules(Object node) {
        Object found = STRING_READERS.computeIfAbsent(node.getClass(), PreSearchSections::stringReader);
        if (!(found instanceof Method)) return "unread";
        Object value;
        try {
            value = ((Method) found).invoke(node, RENDERED_MODULES);
        } catch (Throwable failure) {
            return "unread";
        }
        if (!(value instanceof String)) return "none";
        String[] parts = ((String) value).split(",", MAX_MODULES + 1);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parts.length && i < MAX_MODULES; i++) names.add(name(parts[i].trim()));
        if (parts.length > MAX_MODULES) names.add("more");
        return String.join(",", names);
    }

    private static Object stringReader(Class<?> type) {
        try {
            Method method = type.getMethod("getCachedNullableString", int.class);
            return method.getReturnType() == String.class ? method : Boolean.FALSE;
        } catch (Throwable none) {
            return Boolean.FALSE;
        }
    }

    /** [name] when it's a name: a letter, then letters, digits and underscores, at most four digits. */
    static String name(@Nullable String name) {
        if (name == null) return "unreadable";
        if (name.isEmpty() || name.length() > MAX_NAME || !Character.isLetter(name.charAt(0))) return "other";
        int digits = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++digits > MAX_DIGITS) return "other";
            } else if (c != '_' && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')) {
                return "other";
            }
        }
        return name;
    }

    private static void record(String shape) {
        synchronized (shapes) {
            if (shapes.contains(shape)) return;
            if (shapes.size() >= MAX_SHAPES) {
                if (budgetNoticed) return;
                budgetNoticed = true;
                full = true;
                shape = "more rows than the log keeps";
            } else {
                shapes.add(shape);
            }
        }
        String line = shape;
        Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, "PreSearchSections", () -> PREFIX + line);
    }

    /** Forgets what was logged, for tests. */
    static void forget() {
        synchronized (shapes) {
            shapes.clear();
            budgetNoticed = false;
            full = false;
        }
    }
}
