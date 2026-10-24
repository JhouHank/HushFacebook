/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.PatchFamilyForTests;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Home header text (#230): saved words take the wordmark's place at its height and in its theme
 * color, come back when Facebook reloads its wordmark, and step the picked app icon aside. Blank,
 * Pause and a view that isn't an image keep Facebook's header.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeaderTextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        PatchFamilyForTests.reset();
        Settings.HEADER_TEXT.resetToDefault();
        Settings.UNLOCK_APP_ICONS.resetToDefault();
        Settings.USE_APP_ICON_IN_HEADER.resetToDefault();
        HookStatus.clear();
    }

    /** Facebook's header after its stock load: a wordmark bitmap 24 pixels high. */
    private static ImageView header(Drawable[] stock) {
        Context context = RuntimeEnvironment.getApplication();
        ImageView logo = new ImageView(context);
        stock[0] = new BitmapDrawable(context.getResources(), Bitmap.createBitmap(120, 24, Bitmap.Config.ARGB_8888));
        logo.setImageDrawable(stock[0]);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        return logo;
    }

    @Test
    public void savedWordsTakeTheWordmarksPlaceAtItsHeight() {
        assertEquals("the text starts blank", "", Settings.HEADER_TEXT.defaultValue);
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("Matt's feed");

        HeaderText.dress(logo);

        assertNotSame("the stock wordmark was replaced", stock[0], logo.getDrawable());
        assertTrue(logo.getDrawable() instanceof HeaderText.TextMark);
        assertEquals("the words", "Matt's feed", ((HeaderText.TextMark) logo.getDrawable()).text);
        assertEquals("the text keeps the wordmark's height", 24, logo.getDrawable().getIntrinsicHeight());
        assertTrue("the text is wider than tall", logo.getDrawable().getIntrinsicWidth() > 24);
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
    }

    @Test
    public void theTextTakesTheThemeColorFacebookGivesItsWordmark() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("H");
        HeaderText.dress(logo);
        Drawable mark = logo.getDrawable();
        Bitmap drawn = Bitmap.createBitmap(mark.getIntrinsicWidth(), mark.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        mark.setBounds(0, 0, drawn.getWidth(), drawn.getHeight());
        mark.draw(new Canvas(drawn));
        int center = drawn.getPixel(drawn.getWidth() / 2, drawn.getHeight() / 2);
        assertEquals("before the header's color, Facebook's blue", 0x0866FF, center & 0xFFFFFF);

        // The header colors its wordmark as an ImageView color filter, as Facebook's does in dark mode.
        logo.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        drawn.eraseColor(Color.TRANSPARENT);
        mark.draw(new Canvas(drawn));
        assertEquals("the header's color", Color.WHITE, drawn.getPixel(drawn.getWidth() / 2, drawn.getHeight() / 2));
    }

    @Test
    public void longTextShrinksToFitAndALongerOneIsCut() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW");
        HeaderText.dress(logo);
        HeaderText.TextMark mark = (HeaderText.TextMark) logo.getDrawable();
        assertEquals("cut at the most characters", HeaderText.MAX_LENGTH, mark.text.length());
        int widest = Math.round(logo.getResources().getDisplayMetrics().widthPixels * 0.55f);
        assertTrue("narrow enough for the header: " + mark.getIntrinsicWidth(), mark.getIntrinsicWidth() <= widest + 1);
        assertEquals("still the wordmark's height", 24, mark.getIntrinsicHeight());
    }

    @Test
    public void facebookLoadingItsWordmarkAgainGetsTheTextBack() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("Home");
        HeaderText.dress(logo);

        // A theme change runs Facebook's stock load again.
        logo.setImageDrawable(stock[0]);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        assertTrue("that draw is skipped for one with the text", logo.getViewTreeObserver().dispatchOnPreDraw());
        assertTrue("the text came back", logo.getDrawable() instanceof HeaderText.TextMark);
        assertEquals("the words", "Home", ((HeaderText.TextMark) logo.getDrawable()).text);
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
        assertEquals("the same height", 24, logo.getDrawable().getIntrinsicHeight());

        Drawable kept = logo.getDrawable();
        assertFalse("with the text in place, drawing goes on", logo.getViewTreeObserver().dispatchOnPreDraw());
        assertSame("and nothing changes", kept, logo.getDrawable());
    }

    @Test
    public void blankPausedOrAnotherViewKeepFacebooksHeader() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        HeaderText.dress(logo);
        assertSame("blank", stock[0], logo.getDrawable());

        Settings.HEADER_TEXT.save("   \n ");
        HeaderText.dress(logo);
        assertSame("only spaces", stock[0], logo.getDrawable());

        Settings.HEADER_TEXT.save("Home");
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        HeaderText.dress(logo);
        assertSame("paused", stock[0], logo.getDrawable());
        PauseForTests.resume();

        HeaderText.dress(new View(RuntimeEnvironment.getApplication()));
        HeaderText.dress(null);
        assertEquals(ImageView.ScaleType.MATRIX, logo.getScaleType());
    }

    @Test
    public void wordsKeepThePickedAppIconOutOnlyWithThePatchInTheBuild() {
        Settings.HEADER_TEXT.save("Home");
        PatchFamilyForTests.carry(PatchFamily.APP_ICONS);
        assertFalse("without the patch, saved words step nothing aside", HeaderText.replacesLogo());

        PatchFamilyForTests.carry(PatchFamily.APP_ICONS, PatchFamily.HEADER_TEXT);
        assertTrue(HeaderText.replacesLogo());
        Settings.UNLOCK_APP_ICONS.save(true);
        Settings.USE_APP_ICON_IN_HEADER.save(true);
        Drawable stock = new ColorDrawable(Color.BLUE);
        ImageView logo = new ImageView(RuntimeEnvironment.getApplication());
        logo.setImageDrawable(stock);
        AppIcons.dressLogo(logo);
        assertSame("the app icon steps aside for the words", stock, logo.getDrawable());

        Settings.HEADER_TEXT.save(" ");
        assertFalse("blank words leave the header to the app icon", HeaderText.replacesLogo());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Settings.HEADER_TEXT.save("Home");
        assertFalse("paused, the words are blank", HeaderText.replacesLogo());
    }

    @Test
    public void theTextIsOneTrimmedLineOfAtMostThirtyCharacters() {
        assertEquals("", HeaderText.clean(null));
        assertEquals("", HeaderText.clean(" \t\n "));
        assertEquals("Matt's feed", HeaderText.clean("  Matt's \n\t feed  "));
        assertEquals("no invisible characters", "ab", HeaderText.clean("a" + (char) 0x200B + (char) 0x202E + "b" + (char) 7));
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals("an emoji is one character", emoji + " hi", HeaderText.clean(emoji + "  hi"));
        StringBuilder long31 = new StringBuilder();
        for (int i = 0; i < 31; i++) long31.append(emoji);
        assertEquals(HeaderText.MAX_LENGTH, HeaderText.clean(long31.toString()).codePointCount(0,
                HeaderText.clean(long31.toString()).length()));
        assertEquals("the cut never ends on a space", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                HeaderText.clean("aaaaaaaaaaaaaaaaaaaaaaaaaaaaa bbbb"));
        assertTrue(HeaderText.isClean("Home"));
        assertTrue(HeaderText.isClean(""));
        assertFalse(HeaderText.isClean(" Home"));
        assertFalse(HeaderText.isClean(null));
    }
}
