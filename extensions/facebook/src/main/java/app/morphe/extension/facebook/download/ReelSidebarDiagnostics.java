/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;

/**
 * A bounded clue for a Reels layout that reaches neither known sidebar (#18). With Debug logging
 * and reel downloads on, a real reel start plans a read a moment later. A known sidebar built
 * shortly before the start, or before the read, cancels it. Only component names and their parent
 * paths around numeric count nodes reach the log. Text, descriptions, resource ids and count
 * values are never retained. This observes the layout; it doesn't add a button to it.
 */
public final class ReelSidebarDiagnostics {
    static final long DELAY_MS = 1_500;
    static final long GAP_MS = 15_000;
    static final long SIDEBAR_WINDOW_MS = 5_000;
    static final int READS_MAX = 3;
    static final int WINDOWS_MAX = 3;
    static final int VIEWS_MAX = 400;
    static final int TREES_MAX = 4;
    static final int DEPTH_MAX = 40;
    static final int DESCRIPTION_CHARS_MAX = 32_768;
    static final int LINES_MAX = 256;
    static final int LINE_CHARS_MAX = 512;
    static final int PATHS_MAX = 12;
    static final int PARENTS_MAX = 8;
    static final long BUDGET_MS = 20;
    static final String SCHEDULED = "missing sidebar layout scheduled";
    static final String SAMPLED = "missing sidebar layout sampled";
    static final String LABEL = "Reel sidebar layout";

    private static final String SOURCE = "ReelSidebarDiagnostics";
    private static final String MOUNTING_VIEW = "com.facebook.litho.BaseMountingView";
    private static final String HELPER = "com.facebook.litho.LithoViewTestHelper";
    private static final String TEXT = " text=\"";
    private static final Pattern COMPONENT = Pattern.compile("litho\\.[A-Za-z_$][A-Za-z0-9_.$]{0,63}");
    private static final Pattern COUNT = Pattern.compile("[\\p{Nd}][\\p{Nd}., \\u00a0\\u202f]*[KMBkmb]?\\+?");

    private static long generation = -1;
    private static long sidebarSequence;
    private static long lastSidebar = -SIDEBAR_WINDOW_MS;
    private static long lastRead = -GAP_MS;
    private static int reads;

    private ReelSidebarDiagnostics() {}

    /** Filled by Download any reel with the player's params getter and its isFbShorts field. */
    public static boolean isReel(Object player) {
        return false;
    }

    /** From the kept player-start tracker. Other videos, off, Pause and unready settings schedule nothing. */
    public static void started(Object player) {
        try {
            if (player != null && enabled() && isReel(player)) reelStarted();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic reel check", failure);
        }
    }

    /** Both existing sidebar hooks call this, without keeping a player or anything on its screen. */
    static synchronized void sidebarObserved() {
        try {
            resetIfCleared();
            sidebarSequence++;
            lastSidebar = SystemClock.uptimeMillis();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic observation", failure);
        }
    }

    private static boolean enabled() {
        return Utils.settingsReady() && BaseSettings.DEBUG.get() && Settings.DOWNLOAD_REELS.get()
                && !HushfacebookPause.isPaused();
    }

    /** The model check is separate so host tests exercise the same scheduling and capture path. */
    static synchronized void reelStarted() {
        try {
            if (!enabled()) return;
            resetIfCleared();
            long now = SystemClock.uptimeMillis();
            if (reads >= READS_MAX || now - lastRead < GAP_MS || now - lastSidebar < SIDEBAR_WINDOW_MS) return;
            reads++;
            lastRead = now;
            long observed = sidebarSequence;
            long plannedGeneration = generation;
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SCHEDULED);
            Utils.runOnMainThreadDelayed(() -> readIfMissing(plannedGeneration, observed), DELAY_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic schedule", failure);
        }
    }

    private static void resetIfCleared() {
        long current = HookStatus.generation();
        if (generation == current) return;
        generation = current;
        sidebarSequence = 0;
        lastSidebar = -SIDEBAR_WINDOW_MS;
        lastRead = -GAP_MS;
        reads = 0;
    }

    private static void readIfMissing(long plannedGeneration, long observed) {
        try {
            synchronized (ReelSidebarDiagnostics.class) {
                if (plannedGeneration != HookStatus.generation() || sidebarSequence != observed || !enabled()) return;
            }
            Reading reading = read(AppLock.windowsForDiagnostics(), SystemClock.elapsedRealtime() + BUDGET_MS);
            synchronized (ReelSidebarDiagnostics.class) {
                if (plannedGeneration != HookStatus.generation() || sidebarSequence != observed || !enabled()) return;
            }
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SAMPLED);
            String body = LABEL + ": neither known sidebar observed near a reel start; "
                    + reading.views + " views, " + reading.trees + " Litho trees, " + reading.paths.size()
                    + " count paths" + (reading.limited ? " (scan limit reached)" : "")
                    + (reading.unavailable ? " (Litho description unavailable)" : "")
                    + (reading.paths.isEmpty() ? "" : "\n" + String.join("\n", reading.paths));
            Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE, () -> body);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic read", failure);
        }
    }

    static final class Reading {
        int views;
        int trees;
        boolean limited;
        boolean unavailable;
        final Set<String> paths = new LinkedHashSet<>();
    }

    /** No text or accessibility properties of Android views are read. A deadline stops our walk and parser. */
    static Reading read(List<View> windows, long deadline) {
        Reading reading = new Reading();
        if (windows == null || windows.isEmpty()) return reading;
        if (SystemClock.elapsedRealtime() >= deadline) {
            reading.limited = true;
            return reading;
        }
        try {
            Class<?> mounting = Class.forName(MOUNTING_VIEW, false, ReelSidebarDiagnostics.class.getClassLoader());
            Method describe = Class.forName(HELPER).getMethod("viewToString", mounting);
            List<View> trees = new ArrayList<>();
            for (int i = 0; i < windows.size() && i < WINDOWS_MAX; i++) {
                collect(windows.get(i), mounting, trees, reading, 0, deadline);
            }
            if (windows.size() > WINDOWS_MAX) reading.limited = true;
            reading.trees = trees.size();
            for (View tree : trees) {
                if (SystemClock.elapsedRealtime() >= deadline || reading.paths.size() >= PATHS_MAX) {
                    reading.limited = true;
                    break;
                }
                // Litho's kept helper is synchronous. Check the deadline again after each call;
                // neither its implementation nor the UI thread can safely be interrupted.
                Object description = describe.invoke(null, tree);
                if (SystemClock.elapsedRealtime() >= deadline) {
                    reading.limited = true;
                    break;
                }
                if (description instanceof String) {
                    paths((String) description, reading, deadline);
                }
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            reading.unavailable = true;
        }
        return reading;
    }

    private static void collect(View view, Class<?> mounting, List<View> trees, Reading reading, int depth, long deadline) {
        if (reading.views >= VIEWS_MAX || depth >= DEPTH_MAX || SystemClock.elapsedRealtime() >= deadline) {
            reading.limited = true;
            return;
        }
        reading.views++;
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (mounting.isInstance(view)) {
            if (trees.size() < TREES_MAX) trees.add(view);
            else reading.limited = true;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (reading.views >= VIEWS_MAX || SystemClock.elapsedRealtime() >= deadline) {
                reading.limited = true;
                break;
            }
            collect(group.getChildAt(i), mounting, trees, reading, depth + 1, deadline);
        }
    }

    /** Reads only a bounded prefix. Numeric text classifies a count; only static component names survive. */
    static void paths(String description, Reading reading, long deadline) {
        int limit = Math.min(description.length(), DESCRIPTION_CHARS_MAX);
        if (description.length() > limit) reading.limited = true;
        List<String> parents = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        int at = 0;
        int lines = 0;
        while (at < limit && lines++ < LINES_MAX && reading.paths.size() < PATHS_MAX) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                reading.limited = true;
                break;
            }
            int end = at;
            while (end < limit && description.charAt(end) != '\n') end++;
            String line = description.substring(at, Math.min(end, at + LINE_CHARS_MAX));
            at = end + 1;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            int brace = line.indexOf('{', indent);
            if (brace < 0 || brace - indent > 70) continue;
            String component = line.substring(indent, brace);
            if (!COMPONENT.matcher(component).matches()) continue;
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                parents.remove(parents.size() - 1);
                indents.remove(indents.size() - 1);
            }
            if (parents.size() >= DEPTH_MAX) {
                reading.limited = true;
                continue;
            }
            parents.add(component);
            indents.add(indent);
            int text = line.indexOf(TEXT, brace);
            if (text < 0) continue;
            int start = text + TEXT.length();
            int quote = line.indexOf('"', start);
            if (quote < start || quote - start > 24 || !COUNT.matcher(line.substring(start, quote)).matches()) continue;
            int first = Math.max(0, parents.size() - PARENTS_MAX);
            reading.paths.add(String.join(" > ", parents.subList(first, parents.size())) + " [count]");
        }
        if (at < limit) reading.limited = true;
    }
}
