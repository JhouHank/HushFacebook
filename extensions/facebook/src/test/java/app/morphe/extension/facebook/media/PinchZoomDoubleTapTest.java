/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Double tap to zoom (#220): two quick taps on a profile photo that a tap does nothing to zoom it to
 * twice its size about the tap, and a second double tap puts it back. Taps are only watched, so every
 * tap reaches Facebook as it came, and a photo a tap opens, the story viewer, the switch off or
 * Hushfacebook paused all leave the double tap to Facebook.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PinchZoomDoubleTapTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private FrameLayout content;
    private ImageView photo;
    private long clock;

    @Before
    public void setUp() {
        HookStatus.clear();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.save(true);
        Settings.PINCH_TO_ZOOM_DOUBLE_TAP.save(true);
        activity = Robolectric.buildActivity(PinchZoomTest.BackCounter.class).setup().get();
        content = profileWithPhoto(activity);
        PinchZoom.snapshots = (screen, target, area, done) ->
                done.accept(Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888));
        clock = 1000;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        PinchZoom.resetForTests();
        Settings.PINCH_TO_ZOOM.resetToDefault();
        Settings.PINCH_TO_ZOOM_KEEP.resetToDefault();
        Settings.PINCH_TO_ZOOM_DOUBLE_TAP.resetToDefault();
        HookStatus.clear();
    }

    private FrameLayout profileWithPhoto(Activity screen) {
        FrameLayout root = new FrameLayout(screen);
        photo = new ImageView(screen);
        photo.setImageDrawable(new ColorDrawable(Color.RED));
        root.addView(photo, new FrameLayout.LayoutParams(600, 600));
        screen.setContentView(root);
        layOut(screen);
        // As ProfileFragment.onViewCreated hands its root over.
        PinchZoom.profile(root);
        return root;
    }

    private static void layOut(Activity screen) {
        View decor = screen.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }

    /** One event of a single finger at ([x], [y]), [at] milliseconds into the test's clock. */
    private MotionEvent finger(int action, long downAt, long at, float x, float y) {
        return MotionEvent.obtain(downAt, at, action, x, y, 0);
    }

    /** Hands a tap that lifts [heldFor] ms after it lands to [screen]'s hook; false when Facebook got both events as they were. */
    private boolean tap(Activity screen, long landsAt, long heldFor, float x, float y) {
        MotionEvent down = finger(MotionEvent.ACTION_DOWN, landsAt, landsAt, x, y);
        MotionEvent up = finger(MotionEvent.ACTION_UP, landsAt, landsAt + heldFor, x, y);
        boolean kept = PinchZoom.touch(screen, down) | PinchZoom.touch(screen, up);
        assertEquals(MotionEvent.ACTION_DOWN, down.getActionMasked());
        assertEquals(MotionEvent.ACTION_UP, up.getActionMasked());
        return kept;
    }

    private boolean tap(long landsAt, float x, float y) {
        return tap(activity, landsAt, 50, x, y);
    }

    @Test
    public void defaultsOff() {
        assertFalse(Settings.PINCH_TO_ZOOM_DOUBLE_TAP.defaultValue);
    }

    @Test
    public void aDoubleTapZoomsTwiceAboutTheTapAndFacebookGetsBothTaps() {
        assertFalse(tap(clock, 250, 300));
        assertNull("one tap zoomed", PinchZoom.zoom());
        assertFalse("Facebook was kept from the second tap", tap(clock + 150, 255, 305));
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull("a double tap on a free photo didn't zoom", zoom);
        assertEquals(PinchZoom.DOUBLE_TAP_SCALE, zoom.scale, 0f);
        assertEquals(PinchZoom.DOUBLE_TAP_SCALE, zoom.copy.getScaleX(), 0f);
        assertTrue("it springs back by itself", zoom.parked);
        assertTrue(zoom.shown());
        // About the second tap: the pivot is the point of the photo under the finger, in the photo's own coordinates.
        int[] origin = new int[2];
        photo.getLocationInWindow(origin);
        assertEquals(255f - origin[0], zoom.copy.getPivotX(), 1f);
        assertEquals(305f - origin[1], zoom.copy.getPivotY(), 1f);
        assertEquals(zoom.homeX, zoom.copy.getTranslationX(), 1f);
    }

    @Test
    public void aSecondDoubleTapPutsItBackAndFacebookNeverSeesItsTaps() {
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        // The first tap of the second pair does nothing, and none of it reaches Facebook.
        assertTrue("Facebook got a tap meant for the zoom", tap(clock + 1500, 400, 400));
        assertFalse("a lone tap let go of it", zoom.settling);
        assertTrue(tap(clock + 1650, 400, 400));
        assertTrue("the second double tap didn't put it back", zoom.settling);
        assertEquals(1f, zoom.scale, 0f);
    }

    @Test
    public void backPutsADoubleTapZoomBack() {
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        layOut(activity);
        zoom.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        zoom.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertTrue("Back didn't put it back", zoom.settling);
        assertEquals(0, ((PinchZoomTest.BackCounter) activity).backs);
    }

    @Test
    public void aDragSteersTheZoomAndIsNotATap() {
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        PinchZoom.Zoom zoom = PinchZoom.zoom();
        assertNotNull(zoom);
        long at = clock + 2000;
        assertTrue(PinchZoom.touch(activity, finger(MotionEvent.ACTION_DOWN, at, at, 300, 300)));
        float before = zoom.copy.getTranslationX();
        assertTrue(PinchZoom.touch(activity, finger(MotionEvent.ACTION_MOVE, at, at + 30, 200, 300)));
        assertTrue(PinchZoom.touch(activity, finger(MotionEvent.ACTION_UP, at, at + 60, 200, 300)));
        assertEquals(before - 100f, zoom.copy.getTranslationX(), 1f);
        assertFalse(zoom.settling);
    }

    @Test
    public void aSingleTapIsNeverHeldBackOrChanged() {
        assertFalse(tap(clock, 250, 300));
        assertNull(PinchZoom.zoom());
        // Late: the first tap has gone stale, so this is a first tap again.
        assertFalse(tap(clock + 5000, 250, 300));
        assertNull("two taps seconds apart zoomed", PinchZoom.zoom());
    }

    @Test
    public void aSlowSecondTapOrAFarOneIsNoDoubleTap() {
        tap(clock, 100, 100);
        tap(clock + 150, 560, 560);
        assertNull("taps far apart zoomed", PinchZoom.zoom());
        tap(clock + 1000, 250, 300);
        tap(clock + 1400, 250, 300);
        assertNull("taps 400 ms apart zoomed", PinchZoom.zoom());
    }

    @Test
    public void aHeldFingerOrADragIsNoTap() {
        assertFalse(tap(activity, clock, 900, 250, 300));
        assertFalse(tap(activity, clock + 1000, 50, 250, 300));
        assertNull("a long press counted as a tap", PinchZoom.zoom());
        long at = clock + 2000;
        PinchZoom.touch(activity, finger(MotionEvent.ACTION_DOWN, at, at, 250, 300));
        PinchZoom.touch(activity, finger(MotionEvent.ACTION_MOVE, at, at + 20, 450, 300));
        PinchZoom.touch(activity, finger(MotionEvent.ACTION_UP, at, at + 40, 450, 300));
        tap(at + 150, 450, 300);
        assertNull("a swipe then a tap zoomed", PinchZoom.zoom());
    }

    @Test
    public void switchOffLeavesTheDoubleTapToFacebook() {
        Settings.PINCH_TO_ZOOM_DOUBLE_TAP.save(false);
        assertFalse(tap(clock, 250, 300));
        assertFalse(tap(clock + 150, 250, 300));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void pinchToZoomOffLeavesTheDoubleTapToFacebook() {
        Settings.PINCH_TO_ZOOM.save(false);
        assertFalse(tap(clock, 250, 300));
        assertFalse(tap(clock + 150, 250, 300));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void pausedLeavesTheDoubleTapToFacebook() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(tap(clock, 250, 300));
        assertFalse(tap(clock + 150, 250, 300));
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void aPhotoThatTakesTapsIsLeftAlone() {
        photo.setOnClickListener(view -> { });
        assertFalse(tap(clock, 250, 300));
        assertFalse(tap(clock + 150, 250, 300));
        assertNull("a double tap zoomed a photo whose first tap opens it", PinchZoom.zoom());
    }

    @Test
    public void aPhotoUnderAClickableParentIsLeftAlone() {
        content.setClickable(true);
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void somethingThatIsNotMediaIsNotZoomed() {
        PinchZoom.places = (screen, root, x, y) -> null;
        tap(clock, 250, 300);
        tap(clock + 150, 250, 300);
        assertNull(PinchZoom.zoom());
    }

    @Test
    public void theStoryViewerIsLeftOutSinceATapMovesTheStoryOn() {
        Activity story = Robolectric.buildActivity(PinchZoomTest.HookedViewer.class).setup().get();
        profileWithPhoto(story);
        // Even where the place and the tap are both free, a story keeps its taps.
        PinchZoom.places = (screen, decor, x, y) -> photo;
        assertFalse(tap(story, clock, 50, 250, 300));
        assertFalse(tap(story, clock + 150, 50, 250, 300));
        assertNull("a story zoomed on a double tap", PinchZoom.zoom());
    }

    @Test
    public void aTwoFingerPinchStillZoomsWithTheSwitchOn() {
        PinchZoom.touch(activity, finger(MotionEvent.ACTION_DOWN, clock, clock, 250, 300));
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 250 + 100 * i;
            coords[i].y = 300;
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        MotionEvent second = MotionEvent.obtain(clock, clock + 30,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0);
        assertFalse(PinchZoom.touch(activity, second));
        assertEquals("the second finger wasn't turned into a cancel", MotionEvent.ACTION_CANCEL, second.getActionMasked());
        assertNotNull(PinchZoom.zoom());
        assertEquals(1f, PinchZoom.zoom().scale, 0f);
    }
}
