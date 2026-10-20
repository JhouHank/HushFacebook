/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Long press opens comments, on a feed post (issue #179). Reels have a long-press handler to hook
 * (see {@link ReelGestures}); a feed post has none, so this one times the press itself.
 *
 * <p>Every touch on a Facebook screen comes here first, from FbFragmentActivity's dispatchTouchEvent.
 * With the switch on, a finger that goes down starts a timer of the system's long-press length, and
 * moving past the touch slop, a second finger, a lift or a cancel stops it. When it runs out, the
 * press must sit in a feed post: a row of a list that scrolls up and down, not a full-size page like
 * a reel's, with nothing between the finger and the row that has a long press of its own (the post
 * text and the profile pictures do, as do a carousel's tiles) and no carousel or pager in the way.
 *
 * <p>The row's Comment button is found by the footer's order, since its label is translated and
 * 583 has no resource names: the lowest line of three or more clickable views sharing one top and
 * bottom, the first three touching, in reading order (right to left in RTL). Like comes first and
 * Comment second. Clickable views are taken whole and nested lists and scrollers aren't searched,
 * so a People You May Know carousel's Add friend buttons are never candidates. The button is
 * clicked, and the rest of the gesture goes to Facebook as a cancel so the lift doesn't also open
 * the photo it landed on. A post without that footer line is left to Facebook.
 *
 * <p>A post whose author turned comments off has no Comment button, so its second footer button is
 * whatever follows Like there (Share or Send), which opens its own sheet and posts nothing. With the
 * switch off, with Hushfacebook paused or when anything in here fails, touches go through untouched.
 */
public final class PostLongPress {
    private static final String FAMILY = FamilyNames.REEL_GESTURES;

    /** Counted for each press held on a feed post. */
    static final String HELD = "feed post held";
    /** Counted for each feed post whose comments a held press opened. */
    static final String OPENED = "feed post comments opened";
    /** Counted for each held post with no footer line to find the Comment button in. */
    static final String NO_BUTTON = "no Comment button in the row";

    /** The kept name of the list every feed is drawn in; Facebook's own lists extend it. */
    static final String LIST = "androidx.recyclerview.widget.RecyclerView";
    private static final String PAGER = "androidx.viewpager.widget.ViewPager";

    /** How far apart, in pixels, two footer buttons may sit and still count as touching. */
    private static final int TOUCHING = 2;
    /** The fewest buttons a footer line has: Like, Comment and Share. */
    private static final int FOOTER = 3;

    private static Handler main;
    private static WeakReference<View> pressedRoot = new WeakReference<>(null);
    private static float downX;
    private static float downY;
    private static int slop;
    private static boolean timing;
    /** The press opened comments, so every later event of the gesture becomes a cancel. */
    private static boolean swallowing;
    private static final Runnable FIRE = PostLongPress::fire;

    private PostLongPress() {
    }

    /** Injection point, first thing in FbFragmentActivity.dispatchTouchEvent. */
    public static void touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (activity == null || event == null) return;
            Window window = activity.getWindow();
            touch(window == null ? null : window.getDecorView(), event);
        } catch (Throwable failure) {
            stop();
            swallowing = false;
            HookStatus.threw(FAMILY, "feed long press", failure);
        }
    }

    /** One touch on the window whose top view is [root]. */
    static void touch(@Nullable View root, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swallowing = false;
            stop();
            if (root == null || !Utils.settingsReady() || !Settings.LONG_PRESS_OPENS_COMMENTS.get()) return;
            HookStatus.invoked(FAMILY);
            pressedRoot = new WeakReference<>(root);
            downX = event.getRawX();
            downY = event.getRawY();
            slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
            timing = true;
            main().postDelayed(FIRE, ViewConfiguration.getLongPressTimeout());
            return;
        }
        if (swallowing) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false;
            event.setAction(MotionEvent.ACTION_CANCEL);
            return;
        }
        if (!timing) return;
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > slop) stop();
        } else {
            stop();
        }
    }

    private static void fire() {
        if (!timing) return;
        timing = false;
        View root = pressedRoot.get();
        pressedRoot = new WeakReference<>(null);
        try {
            if (root == null || !Settings.LONG_PRESS_OPENS_COMMENTS.get()) return;
            View row = rowAt(root, Math.round(downX), Math.round(downY));
            if (row == null) return;
            HookStatus.counted(FAMILY, HELD);
            View comment = commentButton(row);
            if (comment == null) {
                HookStatus.counted(FAMILY, NO_BUTTON);
                Logger.printDebug(() -> "Feed long press: " + NO_BUTTON);
                return;
            }
            if (!comment.performClick()) {
                HookStatus.threw(FAMILY, "feed long press", new IllegalStateException("the Comment button took no click"));
                return;
            }
            swallowing = true;
            HookStatus.counted(FAMILY, OPENED);
            Logger.printDebug(() -> "Feed long press: " + OPENED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed long press", failure);
        }
    }

    private static void stop() {
        timing = false;
        pressedRoot = new WeakReference<>(null);
        if (main != null) main.removeCallbacks(FIRE);
    }

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /**
     * The feed row under the screen point ([x], [y]) inside [root], or null when the press there is
     * Facebook's: on something with its own long press, inside a carousel, pager or scroller, on a
     * full-size page, or in no list at all.
     */
    @Nullable
    static View rowAt(View root, int x, int y) {
        View view = hit(root, x, y);
        while (view != null) {
            if (view.isLongClickable()) return null;
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return null;
            View up = (View) parent;
            if (isNamed(up, LIST)) {
                if (!up.canScrollVertically(1) && !up.canScrollVertically(-1)) return null;
                if (view.getWidth() == up.getWidth() && view.getHeight() == up.getHeight()) return null;
                return view;
            }
            if (isScroller(up)) return null;
            view = up;
        }
        return null;
    }

    /** The deepest shown view at the screen point ([x], [y]) in [view], the last drawn first. */
    @Nullable
    static View hit(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE || !contains(view, x, y)) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = hit(group.getChildAt(i), x, y);
                if (found != null) return found;
            }
        }
        return view;
    }

    /** [row]'s Comment button: the second in reading order of its lowest footer line, or null. */
    @Nullable
    static View commentButton(View row) {
        List<View> clickables = new ArrayList<>();
        collect(row, clickables);
        Map<Long, List<View>> lines = new LinkedHashMap<>();
        for (View view : clickables) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            long key = ((long) at[1] << 32) | ((at[1] + view.getHeight()) & 0xffffffffL);
            List<View> line = lines.get(key);
            if (line == null) lines.put(key, line = new ArrayList<>());
            line.add(view);
        }
        boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        View found = null;
        int lowest = Integer.MIN_VALUE;
        for (List<View> line : lines.values()) {
            if (line.size() < FOOTER) continue;
            List<int[]> spans = new ArrayList<>();
            for (View view : line) {
                int[] at = new int[2];
                view.getLocationOnScreen(at);
                spans.add(new int[] {at[0], at[0] + view.getWidth(), at[1] + view.getHeight(), spans.size()});
            }
            spans.sort((a, b) -> rtl ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));
            boolean touching = true;
            for (int i = 1; i < FOOTER && touching; i++) {
                int gap = rtl ? spans.get(i - 1)[0] - spans.get(i)[1] : spans.get(i)[0] - spans.get(i - 1)[1];
                touching = Math.abs(gap) <= TOUCHING;
            }
            int bottom = spans.get(0)[2];
            if (touching && bottom > lowest) {
                lowest = bottom;
                found = line.get(spans.get(1)[3]);
            }
        }
        return found;
    }

    /** The shown clickable views under [group], each taken whole, outside nested lists and scrollers. */
    private static void collect(View group, List<View> into) {
        if (!(group instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) group;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (child.isClickable()) {
                into.add(child);
            } else if (!isScroller(child) && !isNamed(child, LIST)) {
                collect(child, into);
            }
        }
    }

    private static boolean contains(View view, int x, int y) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    private static boolean isScroller(View view) {
        return view instanceof ScrollView || view instanceof HorizontalScrollView || isNamed(view, PAGER);
    }

    /** Whether [view]'s class or one it extends is named [name]. */
    static boolean isNamed(View view, String name) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            if (type.getName().equals(name)) return true;
        }
        return false;
    }

    /** For tests: forgets the press being timed and the gesture being cancelled. */
    static void reset() {
        stop();
        swallowing = false;
    }
}
