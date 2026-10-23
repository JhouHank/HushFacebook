/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Gives a reel's caption the room to draw its last line whole (#96, #212). 584's Reels footer puts
 * the creator line and the caption in a block 6dp taller at each end than the column holding it: a
 * blank 6dp host on top, nothing below, and the block placed 6dp above the column's top. The row
 * holding that column clips it to the column's own bounds, so the caption's last 6dp never draw
 * and its letters lose their bottoms (y, g, p, Bengali vowel signs), on Facebook as it ships too,
 * and more with a taller font. The S22 tree (dumpsys, 2026-10-23): row 0,0-1080,978, column
 * 0,760-945,944, block 0,-17-945,201 with the caption's text at 160-209 of it.
 *
 * <p>The row lines the column up with the action buttons and leaves a strip under both where
 * nothing draws, so the row is left unclipped and the descenders draw over that strip. A row is
 * unclipped only when it and its child column are Litho hosts, the column holds one host that hangs
 * past both of its ends by at most {@link #MAX_HANG_DP} and no further sideways, and the row has
 * room for what hangs below. When Litho takes the row off the screen it's clipped again, so a host
 * Litho hands to something else comes back as Facebook left it.
 *
 * <p>The scrubber runs a light mount as it takes a reel ({@link
 * app.morphe.extension.facebook.media.ProgressBar#mounts}), whatever the progress bar switches say,
 * often before that reel's footer is laid out. So {@link #mounted} looks through the page the
 * scrubber sits in (its nearest LithoView) and keeps looking after each layout of the window while
 * that page shows. The first reel after a start gets no light mount, as Facebook's registry holds
 * no scrubber for it yet (S22, 2026-10-23: five cold starts, the row clipped and never looked at),
 * but its bar still moves, so the scrubber's progress update hands over its label too ({@link
 * #updated}). Pause Hushfacebook leaves every row as Facebook made it.
 */
public final class CaptionRoom {
    private static final String FAMILY = FamilyNames.PROGRESS_BAR;
    static final String HOOK = "reel caption room";
    /** Counted for each footer row unclipped so its caption draws whole. */
    static final String UNCLIPPED = "Reel caption row unclipped";

    static final String HOST = "com.facebook.litho.ComponentHost";
    static final String PAGE = "com.facebook.litho.LithoView";

    /** The most a column's block may hang past it at either end, in dp. 584's hangs 6dp. */
    static final float MAX_HANG_DP = 12f;
    /** How many levels under the page a footer row is looked for. 584's sits 7 down. */
    static final int MAX_DEPTH = 16;

    /** The pages watched for layouts. */
    private static final Map<View, Boolean> watched = new WeakHashMap<>();
    /** The rows unclipped here, each clipped again when it leaves the window. */
    private static final Map<View, Boolean> unclipped = new WeakHashMap<>();

    private CaptionRoom() {
    }

    /** From the scrubber's light mount, with its time label. */
    public static void mounted(@Nullable View label) {
        try {
            if (label == null || !Utils.settingsReady() || Setting.isPaused()) return;
            View page = pageOf(label);
            if (page == null) return;
            lookThrough(page, "light mount");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * From each run of the scrubber's progress update that moved the bar, with its time label. It
     * runs many times a second, so only a page on screen that nothing watches yet is looked through.
     */
    public static void updated(@Nullable View label) {
        try {
            if (label == null || !Utils.settingsReady() || Setting.isPaused() || !label.isAttachedToWindow()) return;
            View page = pageOf(label);
            if (page == null || watched.containsKey(page)) return;
            lookThrough(page, "progress update");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    private static void lookThrough(View page, String from) {
        HookStatus.invoked(FAMILY);
        if (watch(page)) Logger.printDebug(() -> "Reel caption: the scrubber's " + from + " found the reel page");
        roomIn(page);
    }

    /** [view]'s nearest LithoView ancestor, or null. */
    @Nullable
    static View pageOf(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View up = (View) parent;
            if (PAGE.equals(up.getClass().getName())) return up;
            parent = up.getParent();
        }
        return null;
    }

    /**
     * Looks through [page] again after each layout of its window, until the page is gone. Answers
     * whether this started the watch.
     */
    private static boolean watch(View page) {
        if (watched.containsKey(page) || !page.isAttachedToWindow()) return false;
        ViewTreeObserver tree = page.getViewTreeObserver();
        if (!tree.isAlive()) return false;
        watched.put(page, Boolean.TRUE);
        WeakReference<View> held = new WeakReference<>(page);
        tree.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    View shown = held.get();
                    if (shown == null) {
                        if (tree.isAlive()) tree.removeOnGlobalLayoutListener(this);
                        return;
                    }
                    if (shown.isShown()) roomIn(shown);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, HOOK, failure);
                }
            }
        });
        return true;
    }

    /** Unclips every footer row in [page] whose caption hangs past its column. Answers how many. */
    static int roomIn(View page) {
        int most = Math.round(MAX_HANG_DP * page.getResources().getDisplayMetrics().density);
        return unclip(page, 0, most);
    }

    private static int unclip(View view, int depth, int most) {
        if (!(view instanceof ViewGroup) || depth > MAX_DEPTH) return 0;
        ViewGroup row = (ViewGroup) view;
        int count = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View column = row.getChildAt(i);
            int below = hangsBelow(row, column, most);
            if (below > 0 && row.getClipChildren()) {
                row.setClipChildren(false);
                remember(row);
                count++;
                HookStatus.bound(FAMILY, HOOK);
                HookStatus.counted(FAMILY, UNCLIPPED);
                Logger.printDebug(() -> "Reel caption: the footer row now draws the last " + below + " px its column cut");
            }
            count += unclip(column, depth + 1, most);
        }
        return count;
    }

    /**
     * How far the one block in [column] hangs below it, when [row] should draw that: both are Litho
     * hosts, the block hangs past both of the column's ends by at most [most] pixels and not past its
     * sides, and the row has room above and below the column for it. Zero otherwise.
     */
    static int hangsBelow(ViewGroup row, View column, int most) {
        if (!isHost(row) || !isHost(column) || column.getVisibility() != View.VISIBLE) return 0;
        ViewGroup holder = (ViewGroup) column;
        if (holder.getChildCount() != 1) return 0;
        View block = holder.getChildAt(0);
        if (!isHost(block)) return 0;
        int above = -block.getTop();
        int below = block.getBottom() - holder.getHeight();
        if (above <= 0 || below <= 0 || above > most || below > most) return 0;
        if (block.getLeft() < 0 || block.getRight() > holder.getWidth()) return 0;
        if (column.getTop() - above < 0 || column.getBottom() + below > row.getHeight()) return 0;
        return below;
    }

    private static boolean isHost(View view) {
        return view instanceof ViewGroup && HOST.equals(view.getClass().getName());
    }

    private static void remember(ViewGroup row) {
        if (unclipped.put(row, Boolean.TRUE) != null) return;
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                view.removeOnAttachStateChangeListener(this);
                unclipped.remove(view);
                ((ViewGroup) view).setClipChildren(true);
            }
        });
    }

    /** Forgets every watched page and unclipped row, for tests. */
    static void reset() {
        watched.clear();
        unclipped.clear();
    }
}
