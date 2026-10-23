/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Pinch to zoom where Facebook has none of its own: a profile's photos and stories (#220).
 *
 * <p>Every touch on a Facebook screen comes here first, from FbFragmentActivity's dispatchTouchEvent.
 * With the switch on, a second finger landing over something {@link #places} says may be zoomed starts
 * a zoom: a copy of what's on screen there goes into a layer over the window, the pinch scales it about
 * the point under the fingers and moving them drags it, and once fewer than two fingers are down it
 * springs back over the photo and the layer goes away. Facebook's own views never move.
 *
 * <p>Only a story or a profile is zoomed. The news feed has a pinch of its own, and the profile shares
 * its activity with the feed, so a profile is told apart by its view: ProfileFragment hands its root to
 * {@link #profile} as it's made, and a photo has to sit inside one of those. A story is the story
 * viewer's activity. On 584 a profile's photo grid is Litho hosts with no child view, the picture being
 * a drawable mounted straight into the host, so a host that draws no text counts as a photo, the way
 * PostLongPress reads one.
 *
 * <p>From the second finger on, every event of the gesture reaches Facebook as a cancel, so the first
 * finger's tap, hold or swipe never fires: a story doesn't skip ahead or stay paused, and a photo
 * doesn't open. Facebook's own pinch in a story (clear mode) doesn't see this one either. Without two
 * fingers over a zoomable view, nothing changes and Facebook gets every event as it was.
 *
 * <p>The copy is read from the window's last frame, which holds a playing video as well as a photo
 * (a SurfaceView's from its own surface), so a video zooms on the frame it showed when the fingers
 * landed.
 */
public final class PinchZoom {
    private static final String FAMILY = FamilyNames.PINCH_TO_ZOOM;
    /** The most a pinch zooms. */
    static final float MOST = 4f;
    /** How dark the window behind the copy gets at full zoom. */
    static final float SCRIM = 0.75f;
    /** How long the spring back takes, before the system's animation scale. */
    static final long SETTLE_MILLIS = 200;
    /** A view smaller than this many dp either way, an icon or a button, isn't zoomed. */
    static final int SMALLEST_DP = 64;
    /** The story viewer's activity, a kept class name. */
    static final String STORY_VIEWER = "com.facebook.stories.viewer.activity.StoryViewerActivity";

    /** The root views ProfileFragment made, weakly held. Main thread. */
    private static final Map<View, Boolean> profiles = new WeakHashMap<>();

    /** Copies what's on screen inside [area], window coordinates, and hands it to [done] on the main thread. */
    interface Snapshots {
        void take(Activity activity, View target, Rect area, Done done);
    }

    interface Done {
        void accept(@Nullable Bitmap copy);
    }

    /** The view to zoom under the fingers' midpoint at ([x], [y]) in [root], or null for none. */
    interface Places {
        @Nullable View target(Activity activity, View root, float x, float y);
    }

    static volatile Snapshots snapshots = PinchZoom::snapshot;
    static volatile Places places = PinchZoom::mediaUnder;

    /** The gesture took the second finger, so every later event of it becomes a cancel. */
    private static boolean swallowing;
    @Nullable private static Zoom zoom;

    private PinchZoom() {
    }

    /** Injection point, first thing in FbFragmentActivity.dispatchTouchEvent. */
    public static void touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (activity == null || event == null) return;
            handle(activity, event);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch", failure);
            // The layer goes, but a gesture already taken stays a cancel to its end, so Facebook
            // never sees the middle of a gesture whose start it was told was called off.
            drop();
            if (swallowing && event != null) event.setAction(MotionEvent.ACTION_CANCEL);
        }
    }

    /** Injection point, first thing in ProfileFragment.onViewCreated: [view] is a profile's root. */
    public static void profile(@Nullable View view) {
        try {
            if (view != null) profiles.put(view, Boolean.TRUE);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "profile", failure);
        }
    }

    static void handle(Activity activity, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // A new gesture. A layer still springing back from the last one goes at once.
            drop();
            swallowing = false;
            return;
        }
        if (swallowing) {
            follow(event, action);
            event.setAction(MotionEvent.ACTION_CANCEL);
            return;
        }
        if (action != MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() != 2) return;
        if (!Utils.settingsReady() || !Settings.PINCH_TO_ZOOM.get()) return;
        HookStatus.invoked(FAMILY);
        Window window = activity.getWindow();
        View root = window == null ? null : window.getDecorView();
        if (!(root instanceof ViewGroup)) return;
        float x = (event.getX(0) + event.getX(1)) / 2f;
        float y = (event.getY(0) + event.getY(1)) / 2f;
        View target = places.target(activity, root, x, y);
        if (target == null) return;
        Rect area = area(root, target);
        if (area == null) return;
        HookStatus.bound(FAMILY, "pinch");
        Zoom started = new Zoom((ViewGroup) root, area, event);
        zoom = started;
        swallowing = true;
        event.setAction(MotionEvent.ACTION_CANCEL);
        snapshots.take(activity, target, area, copy -> {
            try {
                if (zoom == started) started.show(copy);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "pinch copy", failure);
                if (zoom == started) zoom = null;
                started.remove();
            }
        });
    }

    /** One event of a gesture this took: the zoom follows the fingers until fewer than two are down. */
    private static void follow(MotionEvent event, int action) {
        Zoom live = zoom;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swallowing = false;
            settle();
            return;
        }
        if (live == null || live.settling) return;
        if (action == MotionEvent.ACTION_MOVE) {
            live.move(event, -1);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            live.rebase(event, -1);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerCount() <= 2) settle();
            else live.rebase(event, event.getActionIndex());
        }
    }

    private static void settle() {
        Zoom live = zoom;
        if (live != null) live.settle();
    }

    /** Takes the layer away now, mid spring or not. */
    private static void drop() {
        Zoom live = zoom;
        zoom = null;
        if (live == null) return;
        try {
            live.remove();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch", failure);
        }
    }

    /** [target]'s bounds in [root]'s window, cut to what [root] shows, or null when that's empty. */
    @Nullable
    static Rect area(View root, View target) {
        int[] at = new int[2];
        target.getLocationInWindow(at);
        int[] rootAt = new int[2];
        root.getLocationInWindow(rootAt);
        Rect area = new Rect(at[0], at[1], at[0] + target.getWidth(), at[1] + target.getHeight());
        Rect shown = new Rect(rootAt[0], rootAt[1], rootAt[0] + root.getWidth(), rootAt[1] + root.getHeight());
        if (!area.intersect(shown) || area.isEmpty()) return null;
        return area;
    }

    /**
     * The topmost shown view under ([x], [y]) that draws media, a view {@link #isMedia} names at least
     * {@link #SMALLEST_DP} each way, in the story viewer or inside a profile, and the deepest such one
     * where they nest. Inside a profile or the story viewer, a view on top that holds none, like the
     * story viewer's clear overlay for taps, is looked past to the views under it, and a Litho host on
     * top yields to the media right under it only when that's a picture spanning all of the host, like
     * the band a profile's cover draws over its photo. Above them a view is looked past only when it
     * draws nothing at the point, like the empty layer Facebook keeps over every screen, so a screen
     * Facebook opens over a profile, leaving the profile shown under it, never hands a pinch to a photo
     * it covers. Anywhere else, null.
     */
    @Nullable
    static View mediaUnder(Activity activity, View root, float x, float y) {
        int smallest = Math.round(SMALLEST_DP * root.getResources().getDisplayMetrics().density);
        return mediaUnder(root, x, y, STORY_VIEWER.equals(activity.getClass().getName()), smallest, new int[2], new boolean[1]);
    }

    /** [open] comes back true when the view found is a Litho host the next media under it still decides on. */
    @Nullable
    private static View mediaUnder(View view, float x, float y, boolean zoomable, int smallest, int[] at, boolean[] open) {
        open[0] = false;
        if (!zoomable && profiles.containsKey(view)) zoomable = true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // An open host found on top, kept until the next media under it decides.
            View host = null;
            // The child drawn last is on top.
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (!under(child, x, y, at)) continue;
                View found = mediaUnder(child, x, y, zoomable, smallest, at, open);
                if (found == null) {
                    if (!zoomable && covers(child, x, y, at)) return null;
                    continue;
                }
                if (!zoomable) return found;
                if (host == null) {
                    if (!open[0]) return found;
                    host = found;
                    continue;
                }
                open[0] = false;
                return isPicture(found) && holds(found, host, at) ? found : host;
            }
            if (host != null) {
                open[0] = true;
                return host;
            }
        }
        if (zoomable && isMedia(view) && view.getWidth() >= smallest && view.getHeight() >= smallest) {
            open[0] = !isPicture(view);
            return view;
        }
        return null;
    }

    /** True when [view] is shown and ([x], [y]) is inside it. */
    private static boolean under(View view, float x, float y, int[] at) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return false;
        view.getLocationInWindow(at);
        return x >= at[0] && x < at[0] + view.getWidth() * view.getScaleX()
                && y >= at[1] && y < at[1] + view.getHeight() * view.getScaleY();
    }

    /** True when [outer] spans all of [inner] on screen. */
    private static boolean holds(View outer, View inner, int[] at) {
        inner.getLocationInWindow(at);
        float left = at[0], top = at[1];
        float right = left + inner.getWidth() * inner.getScaleX(), bottom = top + inner.getHeight() * inner.getScaleY();
        outer.getLocationInWindow(at);
        return at[0] <= left && at[1] <= top
                && at[0] + outer.getWidth() * outer.getScaleX() >= right
                && at[1] + outer.getHeight() * outer.getScaleY() >= bottom;
    }

    /**
     * True when [view] draws anything at ([x], [y]), which hides whatever lies under it there: a
     * background or foreground that isn't clear, a view that isn't a bare {@link View}, a layout that
     * draws itself (Litho's hosts do), or a shown child under the point that does.
     */
    static boolean covers(View view, float x, float y, int[] at) {
        if (!clear(view.getBackground()) || !clear(view.getForeground())) return true;
        if (!(view instanceof ViewGroup)) return view.getClass() != View.class;
        if (!view.willNotDraw()) return true;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (under(child, x, y, at) && covers(child, x, y, at)) return true;
        }
        return false;
    }

    /** No drawable, or a color with no alpha. */
    private static boolean clear(@Nullable Drawable drawable) {
        return drawable == null || drawable instanceof ColorDrawable && ((ColorDrawable) drawable).getAlpha() == 0;
    }

    /** A video surface or an ImageView with a picture: media that is a view of its own. */
    static boolean isPicture(View view) {
        if (view instanceof TextureView || view instanceof SurfaceView) return true;
        return view instanceof ImageView && ((ImageView) view).getDrawable() != null;
    }

    /** A video surface, an ImageView with a picture, or a Litho host with no child view that draws no text. */
    static boolean isMedia(View view) {
        if (isPicture(view)) return true;
        if (view instanceof ImageView) return false;
        if (!(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() != 0) return false;
        try {
            Object content = view.getClass().getMethod("getTextContent").invoke(view);
            return content instanceof List && ((List<?>) content).isEmpty();
        } catch (ReflectiveOperationException | SecurityException e) {
            return false;
        }
    }

    /**
     * Reads [area] off the window's last frame, or a SurfaceView's own surface, or draws [target]
     * where neither can be read.
     */
    static void snapshot(Activity activity, View target, Rect area, Done done) {
        Bitmap copy = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
        Handler main = new Handler(Looper.getMainLooper());
        PixelCopy.OnPixelCopyFinishedListener finished = result -> {
            if (result == PixelCopy.SUCCESS) done.accept(copy);
            else done.accept(drawn(target, area, copy));
        };
        // A SurfaceView's picture is a surface of its own, which the window's frame shows as a hole.
        Rect surface = target instanceof SurfaceView ? surfaceArea((SurfaceView) target, area) : null;
        if (surface != null) {
            PixelCopy.request((SurfaceView) target, surface, copy, finished, main);
            return;
        }
        Window window = activity.getWindow();
        if (window != null && target.isHardwareAccelerated()) {
            PixelCopy.request(window, area, copy, finished, main);
            return;
        }
        done.accept(drawn(target, area, copy));
    }

    /** [area], window coordinates, in [view]'s surface, or null when it has no surface to read. */
    @Nullable
    static Rect surfaceArea(SurfaceView view, Rect area) {
        if (!view.getHolder().getSurface().isValid() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Rect frame = view.getHolder().getSurfaceFrame();
        if (frame.isEmpty()) return null;
        int[] at = new int[2];
        view.getLocationInWindow(at);
        // The surface can be sized apart from the view and stretched over it.
        float wide = frame.width() / (float) view.getWidth();
        float high = frame.height() / (float) view.getHeight();
        Rect surface = new Rect(Math.round((area.left - at[0]) * wide), Math.round((area.top - at[1]) * high),
                Math.round((area.right - at[0]) * wide), Math.round((area.bottom - at[1]) * high));
        return surface.intersect(frame) && !surface.isEmpty() ? surface : null;
    }

    /** [target] drawn into [copy], shifted so [area]'s corner is the copy's. */
    @Nullable
    static Bitmap drawn(View target, Rect area, Bitmap copy) {
        try {
            int[] at = new int[2];
            target.getLocationInWindow(at);
            Canvas canvas = new Canvas(copy);
            canvas.translate(at[0] - area.left, at[1] - area.top);
            target.draw(canvas);
            return copy;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch copy", failure);
            return null;
        }
    }

    static boolean active() {
        return swallowing;
    }

    @Nullable
    static Zoom zoom() {
        return zoom;
    }

    static void resetForTests() {
        drop();
        swallowing = false;
        profiles.clear();
        snapshots = PinchZoom::snapshot;
        places = PinchZoom::mediaUnder;
    }

    /** One zoom: the layer over the window and where the fingers started. */
    static final class Zoom {
        final ViewGroup root;
        final Rect area;
        final FrameLayout layer;
        final View scrim;
        final ImageView copy;
        /** Where the copy sits over the photo: its offset in the layer, which lies over [root] at [originX], [originY]. */
        final float homeX;
        final float homeY;
        private final float originX;
        private final float originY;
        boolean settling;
        float scale = 1f;
        private float baseScale = 1f;
        private float startSpan;
        private float startX;
        private float startY;
        private float baseX;
        private float baseY;
        /** A window that closes mid zoom lets go of it, so the copy and the screen behind aren't held. */
        final View.OnAttachStateChangeListener detached = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                try {
                    if (zoom == Zoom.this) {
                        zoom = null;
                        swallowing = false;
                    }
                    remove();
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "pinch", failure);
                }
            }
        };

        Zoom(ViewGroup root, Rect area, MotionEvent event) {
            this.root = root;
            this.area = area;
            Context context = root.getContext();
            layer = new FrameLayout(context);
            layer.setClickable(false);
            layer.setFocusable(false);
            layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            scrim = new View(context);
            scrim.setBackgroundColor(Color.BLACK);
            scrim.setAlpha(0f);
            layer.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            copy = new ImageView(context);
            copy.setScaleType(ImageView.ScaleType.FIT_XY);
            int[] rootAt = new int[2];
            root.getLocationInWindow(rootAt);
            originX = rootAt[0];
            originY = rootAt[1];
            // The copy is laid out at the layer's corner and moved over the photo, so the spring back
            // eases it to this offset, never to the corner.
            homeX = area.left - originX;
            homeY = area.top - originY;
            copy.setTranslationX(homeX);
            copy.setTranslationY(homeY);
            layer.addView(copy, new FrameLayout.LayoutParams(area.width(), area.height()));
            rebase(event, -1);
            root.addOnAttachStateChangeListener(detached);
        }

        /** The copy arrived: the layer goes over the window. With no copy there's nothing to show. */
        void show(@Nullable Bitmap picture) {
            if (picture == null || settling) return;
            copy.setImageBitmap(picture);
            root.addView(layer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            apply();
        }

        /**
         * The fingers changed, so the next move measures from here, at the scale reached so far. The
         * copy now grows about the point of it under the fingers' new middle, and the move that keeps
         * it where it is on screen goes into its translation.
         */
        void rebase(MotionEvent event, int leaving) {
            float[] focus = focus(event, leaving);
            startX = focus[0];
            startY = focus[1];
            startSpan = span(event, leaving, startX, startY);
            float pivotX = copy.getPivotX();
            float pivotY = copy.getPivotY();
            float translationX = copy.getTranslationX();
            float translationY = copy.getTranslationY();
            float underX = pivotX + (startX - originX - translationX - pivotX) / scale;
            float underY = pivotY + (startY - originY - translationY - pivotY) / scale;
            copy.setPivotX(underX);
            copy.setPivotY(underY);
            copy.setTranslationX(translationX + (scale - 1f) * (underX - pivotX));
            copy.setTranslationY(translationY + (scale - 1f) * (underY - pivotY));
            baseScale = scale;
            baseX = copy.getTranslationX();
            baseY = copy.getTranslationY();
        }

        void move(MotionEvent event, int leaving) {
            float[] focus = focus(event, leaving);
            float span = span(event, leaving, focus[0], focus[1]);
            if (startSpan > 0f) scale = Math.max(1f, Math.min(MOST, baseScale * span / startSpan));
            copy.setTranslationX(baseX + focus[0] - startX);
            copy.setTranslationY(baseY + focus[1] - startY);
            apply();
        }

        private void apply() {
            copy.setScaleX(scale);
            copy.setScaleY(scale);
            scrim.setAlpha(SCRIM * Math.min(1f, (scale - 1f) / (MOST - 1f) * 3f));
        }

        /** Springs back to where the photo sits, then takes the layer away. */
        void settle() {
            if (settling) return;
            settling = true;
            scale = 1f;
            if (layer.getParent() == null) {
                if (zoom == this) zoom = null;
                remove();
                return;
            }
            Runnable gone = () -> {
                if (zoom == this) zoom = null;
                try {
                    remove();
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "pinch", failure);
                }
            };
            scrim.animate().alpha(0f).setDuration(SETTLE_MILLIS).start();
            copy.animate().scaleX(1f).scaleY(1f).translationX(homeX).translationY(homeY)
                    .setDuration(SETTLE_MILLIS).withEndAction(gone).start();
        }

        /** Takes the layer away now and lets go of the copy. */
        void remove() {
            settling = true;
            root.removeOnAttachStateChangeListener(detached);
            copy.animate().cancel();
            scrim.animate().cancel();
            if (layer.getParent() == root) root.removeView(layer);
            copy.setImageDrawable(null);
        }

        boolean shown() {
            return layer.getParent() == root;
        }

        /** The pointers' middle, leaving out the one at index [leaving] (lifting), or none for -1. */
        private static float[] focus(MotionEvent event, int leaving) {
            float x = 0f;
            float y = 0f;
            int counted = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == leaving) continue;
                x += event.getX(i);
                y += event.getY(i);
                counted++;
            }
            if (counted == 0) return new float[] {0f, 0f};
            return new float[] {x / counted, y / counted};
        }

        /** How far apart the pointers are: twice their mean distance from ([x], [y]), as ScaleGestureDetector reads it. */
        private static float span(MotionEvent event, int leaving, float x, float y) {
            float total = 0f;
            int counted = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == leaving) continue;
                float dx = event.getX(i) - x;
                float dy = event.getY(i) - y;
                total += (float) Math.hypot(dx, dy);
                counted++;
            }
            return counted == 0 ? 0f : 2f * total / counted;
        }
    }
}
