/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Black cards, issue #191: with the switch on, a card, a comment or a popover takes the page's
 * colour instead of the near black that keeps its edge, and a secondary button takes an input's
 * fill so it still shows on the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledFlatCardsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Facebook's colour token enums: only the constant names matter. */
    enum Token { CARD_BACKGROUND, COMMENT_BACKGROUND, POPOVER_BACKGROUND, PRIMARY_UI, BACKGROUND_PRIMARY_UI,
        SECONDARY_BUTTON_BACKGROUND, SECONDARY_BUTTON_BACKGROUND_OPAQUE, SECONDARY_BUTTON_BACKGROUND_FLOATING,
        PRIMARY_TEXT, DIVIDER }

    private static final int BLACK = 0xFF000000;

    /** An input's fill on black, which a button takes with Black cards on. */
    private static final int INPUT_FILL = 0xFF262627;

    @Before
    public void darkMode() {
        DarkMode.answer(true);
    }

    @After
    public void restore() {
        Settings.AMOLED_FLAT_CARDS.resetToDefault();
        DarkMode.answer(true);
        AmoledTheme.useBackground(BLACK);
    }

    /** The Menu's profile card and the Create story strip are #333334 cards, the older ones #3A3B3C. */
    @Test
    public void aCardTakesThePagesBlack() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("a card", BLACK, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("a comment", BLACK, AmoledTheme.apply(0xFF333334, Token.COMMENT_BACKGROUND));
        assertEquals("the older palette's card", BLACK, AmoledTheme.apply(0xFF3A3B3C, Token.CARD_BACKGROUND));
        assertEquals("a popover", BLACK, AmoledTheme.apply(0xFF3B3C3E, Token.POPOVER_BACKGROUND));
        assertEquals("the black band, as before", BLACK, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals("a server's card", BLACK, AmoledTheme.parseColor("#333334"));
    }

    /** See more and Log out sit on the Menu's cards: with the cards gone, they keep a shape. */
    @Test
    public void aButtonOrAnInputKeepsAFill() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("an input", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI));
        assertEquals("FDS's input", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.BACKGROUND_PRIMARY_UI));
        assertEquals("a secondary button", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("an opaque one", INPUT_FILL, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals("a floating one", INPUT_FILL, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_FLOATING));
    }

    /** On a lighter Background colour the cards take that colour, and a button sits the same step above it. */
    @Test
    public void aLighterBackgroundColourIsWhatACardTakes() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        AmoledTheme.useBackground(0xFF101010);
        assertEquals(0xFF101010, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals(0xFF363637, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals(0xFF101010, AmoledTheme.parseColor("#333334"));
    }

    /** The mutation controls: off, the theme's near black and Facebook's buttons, as before the switch. */
    @Test
    public void offTheCardsStayNearBlack() {
        assertEquals("a card", 0xFF121213, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("a server's card", 0xFF121213, AmoledTheme.parseColor("#333334"));
        assertEquals("a secondary button", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("an opaque one", 0xFF46484B, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals("an input", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI));
    }

    /** More controls, with the switch on: light mode, text, a divider, a lighter button and a hue. */
    @Test
    public void onlyDarkSurfacesChange() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("text", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.PRIMARY_TEXT));
        assertEquals("a divider", 0xFF3A3B3C, AmoledTheme.apply(0xFF3A3B3C, Token.DIVIDER));
        assertEquals("a button above the band", 0xFF5A5B5C,
                AmoledTheme.apply(0xFF5A5B5C, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("a button with a hue", 0xFF263951, AmoledTheme.apply(0xFF263951, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("a card with a hue", 0xFF2E3A44, AmoledTheme.apply(0xFF2E3A44, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", 0xFF333334, AmoledTheme.apply(0xFF333334, "CARD_BACKGROUND"));

        DarkMode.answer(false);
        assertEquals("light mode, the Video tab's dark card", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("light mode, a button", 0xFF46484B,
                AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals("light mode, a server's card", 0xFF333334, AmoledTheme.parseColor("#333334"));
    }

    /** A colour asked for before the settings are ready gets the theme's own answer. */
    @Test
    public void beforeTheSettingsAreReadyTheCardsStayNearBlack() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        int[] seen = new int[2];
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            seen[0] = AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND);
            seen[1] = AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE);
        });
        assertEquals(0xFF121213, seen[0]);
        assertEquals(0xFF46484B, seen[1]);
    }
}
