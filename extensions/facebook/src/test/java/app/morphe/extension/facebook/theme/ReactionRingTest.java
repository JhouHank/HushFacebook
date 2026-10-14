/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The reaction facepile's ring (#147): in dark mode, with a theme in the build, an opaque ring colour
 * becomes one the facepile cuts the ring with, so the page shows through; a colour that's already
 * translucent, light mode and a build with neither theme stay as they were.
 */
public class ReactionRingTest {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DARK_SURFACE = 0xFF252728;
    private static final int BLACK = 0xFF000000;

    @Test
    public void anOpaqueRingInDarkModeIsCutSoThePageShowsThrough() {
        for (int colour : new int[]{WHITE, DARK_SURFACE, BLACK}) {
            int ring = ReactionRing.ring(colour, true, true);
            assertEquals(Integer.toHexString(colour), ReactionRing.CUT, ring);
            // Facebook's facepile cuts the ring for any colour under full alpha.
            assertTrue(Integer.toHexString(colour), ring >>> 24 < 0xFF);
        }
    }

    @Test
    public void aRingFacebookAlreadyCutsIsLeftAlone() {
        for (int colour : new int[]{0x00000000, 0x00FFFFFF, 0x66000000, 0xFEFFFFFF}) {
            assertEquals(Integer.toHexString(colour), colour, ReactionRing.ring(colour, true, true));
        }
    }

    @Test
    public void lightModeAndABuildWithoutAThemeKeepFacebooksRing() {
        for (int colour : new int[]{WHITE, DARK_SURFACE}) {
            assertEquals("light mode", colour, ReactionRing.ring(colour, true, false));
            assertEquals("neither theme", colour, ReactionRing.ring(colour, false, true));
        }
        // The hook itself, with neither theme's status filled in, hands the colour back.
        assertEquals(WHITE, ReactionRing.ring(WHITE));
    }
}
