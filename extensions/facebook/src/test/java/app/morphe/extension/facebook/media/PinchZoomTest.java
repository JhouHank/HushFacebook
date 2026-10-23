/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

import static org.robolectric.Shadows.shadowOf;

/**
 * A second finger over a photo in a profile starts a zoom on a copy of it, the pinch scales and
 * drags the copy, and it springs back once fewer than two fingers are down. Every event from that
 * second finger on reaches Facebook as a cancel. One finger, a pinch over something that isn't
 * media, an icon-sized picture, a photo outside both a profile and the story viewer (the news
 * feed's, which Facebook zooms itself), the switch off or Hushfacebook paused all leave every event
 * as Facebook handed it over.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PinchZoomTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private ImageView photo;
    private View plain;
    private ImageView icon;
    private final List<Bitmap> copies = new ArrayList<>();
    private boolean noCopy;

    @Before
    public void setUp() {
        HookStatus.clear();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.save(true);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        photo = new ImageView(activity);
        photo.setImageDrawable(new ColorDrawable(Color.RED));
        content.addView(photo, new FrameLayout.LayoutParams(600, 600));
        plain = new View(activity);
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(600, 400);
        below.topMargin = 700;
        content.addView(plain, below);
        icon = new ImageView(activity);
        icon.setImageDrawable(new ColorDrawable(Color.BLUE));
        FrameLayout.LayoutParams small = new FrameLayout.LayoutParams(40, 40);
        small.topMargin = 1200;
        content.addView(icon, small);
        activity.setContentView(content);
        layOut(activity);
        // As ProfileFragment.onViewCreated hands its root over.
        PinchZoom.profile(content);
        PinchZoom.snapshots = (screen, target, area, done) -> {
            if (noCopy) {
                done.accept(null);
                return;
            }
            Bitmap copy = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
            copies.add(copy);
            done.accept(copy);
        };
    }

    @After
    public void restore() {
        PauseForTests.resume();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.resetToDefault();
        HookStatus.clear();
    }

    private static void layOut(Activity screen) {
        View decor = screen.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }

    /** Stands in for Litho's ComponentHost, which lists the text drawn in it. */
    public static final class LithoHost extends FrameLayout {
        final List<Object> drawn = new ArrayList<>();

        public LithoHost(android.content.Context context) {
            super(context);
        }

        public List<Object> getTextContent() {
            return drawn;
        }
    }

    private static MotionEvent event(int action, float[][] points) {
        int count = points.length;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0];
            coords[i].y = points[i][1];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
    }

    private static int pointer(int action, int index) {
        return action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    /** Hands [event] to the hook and gives back the action Facebook would see. */
    private int touch(MotionEvent event) {
        PinchZoom.touch(activity, event);
        return event.getActionMasked();
    }

    private ViewGroup decor() {
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    /** Where the copy draws its own point [u] across, in the window: the layer and the decor sit at its corner. */
    private static float drawnX(View copy, float u) {
        return copy.getLeft() + copy.getTranslationX() + copy.getPivotX() + copy.getScaleX() * (u - copy.getPivotX());
    }

    private static float drawnY(View copy, float u) {
        return copy.getTop() + copy.getTranslationY() + copy.getPivotY() + copy.getScaleY() * (u - copy.getPivotY());
    }

    /** The copy's own point drawn at [x] across in the window. */
    private static float underX(View copy, float x) {
        return copy.getPivotX() + (x - copy.getLeft() - copy.getTranslationX() - copy.getPivotX()) / copy.getScaleX();
    }

    private static float underY(View copy, float y) {
        return copy.getPivotY() + (y - copy.getTop() - copy.getTranslationY() - copy.getPivotY()) / copy.getScaleY();
    }

    @Test
    public void theSwitchStartsOff() {
        assertFalse(Settings.PINCH_TO_ZOOM.defaultValue);
    }

    @Test
    public void aPinchOverAPhotoZoomsACopyAndSpringsBack() {
        int children = decor().getChildCount();
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertEquals("the second finger reached Facebook", MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull("no zoom started", zoom);
        assertTrue("the copy isn't over the window", zoom.shown());
        assertEquals(children + 1, decor().getChildCount());
        assertEquals("the copy wasn't the photo's size", 600, copies.get(0).getWidth());
        int[] at = new int[2];
        photo.getLocationInWindow(at);
        assertEquals("the copy isn't where the photo is", at[0], zoom.area.left);
        assertEquals("the copy isn't where the photo is", at[1], zoom.area.top);
        assertEquals("the copy isn't where the photo is", at[1], zoom.copy.getY(), 0.01f);
        assertEquals("the copy doesn't grow about the fingers", 300f, zoom.copy.getPivotX(), 0.01f);

        // Twice as far apart: twice the size, and the fingers' middle didn't move.
        assertEquals("a move reached Facebook", MotionEvent.ACTION_CANCEL,
                touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}})));
        assertEquals(2f, zoom.copy.getScaleX(), 0.01f);
        assertEquals(0f, zoom.copy.getTranslationX(), 0.01f);
        assertTrue("the window behind didn't darken", zoom.scrim.getAlpha() > 0f);

        // Dragged 50 to the right, and spread far past the most it zooms.
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{0, 300}, {700, 300}}));
        assertEquals(PinchZoom.MOST, zoom.copy.getScaleX(), 0.01f);
        assertEquals(50f, zoom.copy.getTranslationX(), 0.01f);

        // One finger lifts: the copy springs back and the layer goes.
        assertEquals(MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), new float[][] {{0, 300}, {700, 300}})));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertFalse("the layer stayed after the spring back", zoom.shown());
        assertEquals(children, decor().getChildCount());
        assertNull(PinchZoom.zoom());
        // The last finger's own moves and lift stay cancels, so nothing under it fires.
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{10, 300}})));
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_UP, new float[][] {{10, 300}})));
        assertFalse(PinchZoom.active());

        // The next tap is Facebook's.
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertEquals(MotionEvent.ACTION_UP, touch(event(MotionEvent.ACTION_UP, new float[][] {{250, 300}})));
    }

    @Test
    public void aThirdFingerCarriesOnFromTheScaleReached() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}}));
        assertEquals(2f, zoom.copy.getScaleX(), 0.01f);
        float cornerX = drawnX(zoom.copy, 0f);
        float cornerY = drawnY(zoom.copy, 0f);
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 2),
                new float[][] {{200, 300}, {400, 300}, {300, 500}})));
        assertEquals("a third finger landing changed the scale", 2f, zoom.copy.getScaleX(), 0.01f);
        assertEquals("a third finger landing moved the copy", cornerX, drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("a third finger landing moved the copy", cornerY, drawnY(zoom.copy, 0f), 0.01f);
        // The three spread about the same middle: the part of the photo under it stays under it.
        float middleY = (300f + 300f + 500f) / 3f;
        float heldX = underX(zoom.copy, 300f);
        float heldY = underY(zoom.copy, middleY);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{150, 300}, {450, 300}, {300, 500}}));
        assertTrue("spreading three fingers didn't zoom", zoom.copy.getScaleX() > 2.1f);
        assertEquals("the photo slid out from under the fingers", 300f, drawnX(zoom.copy, heldX), 0.01f);
        assertEquals("the photo slid out from under the fingers", middleY, drawnY(zoom.copy, heldY), 0.01f);
        // The third finger lifts and two stay: still zoomed, still following from the scale reached.
        float reached = zoom.copy.getScaleX();
        touch(event(pointer(MotionEvent.ACTION_POINTER_UP, 2), new float[][] {{150, 300}, {450, 300}, {300, 500}}));
        assertTrue(zoom.shown());
        assertFalse(zoom.settling);
        assertEquals("the third finger lifting changed the scale", reached, zoom.copy.getScaleX(), 0.01f);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{75, 300}, {525, 300}}));
        assertEquals(reached * 1.5f, zoom.copy.getScaleX(), 0.01f);
        assertEquals("the photo slid out from under the fingers", 300f, drawnX(zoom.copy, underX(zoom.copy, 300f)), 0.01f);
    }

    /** Off the window's corner, the copy starts over the photo and springs back there, not to the corner. */
    @Test
    public void theCopySpringsBackToWhereThePhotoSits() {
        FrameLayout.LayoutParams moved = (FrameLayout.LayoutParams) photo.getLayoutParams();
        moved.leftMargin = 120;
        moved.topMargin = 40;
        photo.setLayoutParams(moved);
        layOut(activity);
        int[] at = new int[2];
        photo.getLocationInWindow(at);
        assertEquals(120, at[0]);
        float x = at[0] + 200f;
        float y = at[1] + 200f;
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{x, y}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{x, y}, {x + 100, y}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertEquals("the copy isn't over the photo", at[0], drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("the copy isn't over the photo", at[1], drawnY(zoom.copy, 0f), 0.01f);
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{x - 100, y + 30}, {x + 200, y + 30}}));
        assertEquals(3f, zoom.copy.getScaleX(), 0.01f);
        touch(event(MotionEvent.ACTION_UP, new float[][] {{x - 100, y + 30}}));
        assertTrue(zoom.settling);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertFalse("the layer stayed after the spring back", zoom.shown());
        assertEquals("the copy sprang back somewhere else", at[0], drawnX(zoom.copy, 0f), 0.01f);
        assertEquals("the copy sprang back somewhere else", at[1], drawnY(zoom.copy, 0f), 0.01f);
        assertEquals(1f, zoom.copy.getScaleX(), 0.01f);
        assertNull("the copy was held after the layer went", zoom.copy.getDrawable());
    }

    /** A window closing mid zoom takes the zoom with it, and nothing keeps the copy. */
    @Test
    public void aWindowClosingMidZoomLetsGoOfIt() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertTrue(zoom.shown());
        assertTrue("the window isn't open", decor().isAttachedToWindow());
        activity.getWindowManager().removeViewImmediate(decor());
        assertNull("the zoom outlived its window", PinchZoom.zoom());
        assertFalse(PinchZoom.active());
        assertFalse(zoom.shown());
        assertNull(zoom.copy.getDrawable());
    }

    @Test
    public void aNewTouchTakesASpringingLayerAwayAtOnce() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}}));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}}));
        touch(event(MotionEvent.ACTION_UP, new float[][] {{200, 300}}));
        assertTrue("the spring back didn't start", zoom.settling);
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertFalse("the layer could take the new touch", zoom.shown());
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void withNoCopyNothingShowsButTheGestureStaysCancelled() {
        noCopy = true;
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_CANCEL,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        assertFalse(zoom.shown());
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 300}, {400, 300}})));
        assertEquals(MotionEvent.ACTION_CANCEL, touch(event(MotionEvent.ACTION_UP, new float[][] {{200, 300}})));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void oneFingerIsLeftAlone() {
        assertEquals(MotionEvent.ACTION_DOWN, touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}})));
        assertEquals(MotionEvent.ACTION_MOVE, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{260, 330}})));
        assertEquals(MotionEvent.ACTION_UP, touch(event(MotionEvent.ACTION_UP, new float[][] {{260, 330}})));
        assertNull(PinchZoom.zoom());
        assertTrue(copies.isEmpty());
    }

    @Test
    public void aPinchOverSomethingThatIsntMediaIsLeftAlone() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 800}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 800}, {350, 800}})));
        assertEquals(MotionEvent.ACTION_MOVE, touch(event(MotionEvent.ACTION_MOVE, new float[][] {{200, 800}, {400, 800}})));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void anIconSizedPictureIsLeftAlone() {
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{10, 1210}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{10, 1210}, {30, 1210}})));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void thePhotoUnderTheFingersIsTheDeepestMediaThere() {
        assertSame(photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        assertNull(PinchZoom.mediaUnder(activity, decor(), 300, 800));
        assertNull("an icon counted", PinchZoom.mediaUnder(activity, decor(), 20, 1220));
        photo.setVisibility(View.INVISIBLE);
        assertNull("a hidden photo counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /** A clear view over the photo, like the story viewer's layer for taps, is looked past to the photo. */
    @Test
    public void aViewOverThePhotoThatDrawsNoMediaIsLookedPast() {
        FrameLayout content = (FrameLayout) photo.getParent();
        FrameLayout veil = new FrameLayout(activity);
        ImageView badge = new ImageView(activity);
        badge.setImageDrawable(new ColorDrawable(Color.WHITE));
        veil.addView(badge, new FrameLayout.LayoutParams(40, 40));
        content.addView(veil, new FrameLayout.LayoutParams(600, 600));
        layOut(activity);
        assertSame("the photo under a clear view was missed", photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
        // A picture of its own on top is what shows, so it's what zooms.
        ImageView cover = new ImageView(activity);
        cover.setImageDrawable(new ColorDrawable(Color.BLACK));
        veil.addView(cover, new FrameLayout.LayoutParams(600, 600));
        layOut(activity);
        assertSame(cover, PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /**
     * A screen Facebook opens over a profile in the same window, a post say, can leave the profile
     * shown under it. A pinch there is the screen's, never a profile photo's it covers.
     */
    @Test
    public void aScreenOverAProfileHidesItsPhotos() {
        ViewGroup frame = (ViewGroup) ((View) photo.getParent()).getParent();
        FrameLayout post = new FrameLayout(activity);
        post.addView(new View(activity), new FrameLayout.LayoutParams(600, 200));
        frame.addView(post, new FrameLayout.LayoutParams(1080, 1920));
        layOut(activity);
        assertNull("a photo under another screen counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());
    }

    /**
     * The news feed shares its activity with a profile and zooms its own photos, so a photo that
     * isn't inside a root ProfileFragment handed over is left to Facebook.
     */
    @Test
    public void aPhotoOutsideAProfileIsLeftToFacebook() {
        PinchZoom.resetForTests();
        assertNull("a photo outside a profile counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());
        PinchZoom.profile(plain);
        assertNull("a profile beside the photo counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
        PinchZoom.profile((View) photo.getParent());
        assertSame(photo, PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    @Test
    public void inTheStoryViewerAStoryZoomsWithNoProfile() {
        PinchZoom.resetForTests();
        Activity viewer = Robolectric.buildActivity(
                com.facebook.stories.viewer.activity.StoryViewerActivity.class).setup().get();
        FrameLayout card = new FrameLayout(viewer);
        ImageView story = new ImageView(viewer);
        story.setImageDrawable(new ColorDrawable(Color.GREEN));
        card.addView(story, new FrameLayout.LayoutParams(1080, 1500));
        viewer.setContentView(card);
        layOut(viewer);
        assertSame(story, PinchZoom.mediaUnder(viewer, viewer.getWindow().getDecorView(), 540, 700));
        assertNull("an activity that isn't the story viewer counted", PinchZoom.mediaUnder(activity, decor(), 300, 300));
    }

    /**
     * A profile's photo grid on 584 is Litho hosts with no child view, each drawing its picture as
     * a mounted drawable. A host that draws no text is a photo; one drawing text, or holding a view
     * of its own, isn't.
     */
    @Test
    public void aLithoHostDrawingNoTextIsAPhoto() {
        LithoHost cell = new LithoHost(activity);
        assertTrue("a grid cell isn't a photo", PinchZoom.isMedia(cell));
        cell.drawn.add("Edit profile");
        assertFalse("a host drawing text counted", PinchZoom.isMedia(cell));
        cell.drawn.clear();
        cell.addView(new View(activity));
        assertFalse("a host holding a view counted", PinchZoom.isMedia(cell));
        assertFalse("a plain empty frame counted", PinchZoom.isMedia(new FrameLayout(activity)));

        LithoHost grid = new LithoHost(activity);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(355, 352);
        at.topMargin = 1300;
        ((ViewGroup) photo.getParent()).addView(grid, at);
        layOut(activity);
        int[] where = new int[2];
        grid.getLocationInWindow(where);
        assertSame(grid, PinchZoom.mediaUnder(activity, decor(), where[0] + 170, where[1] + 170));
    }

    @Test
    public void switchOffOrPausedLeavesThePinchToFacebook() {
        Settings.PINCH_TO_ZOOM.save(false);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());

        Settings.PINCH_TO_ZOOM.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        touch(event(MotionEvent.ACTION_DOWN, new float[][] {{250, 300}}));
        assertEquals(MotionEvent.ACTION_POINTER_DOWN,
                touch(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), new float[][] {{250, 300}, {350, 300}})));
        assertNull(PinchZoom.zoom());
    }
}
