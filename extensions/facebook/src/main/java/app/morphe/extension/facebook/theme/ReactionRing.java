/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The ring between the overlapping reaction icons at the bottom right of a post (issue #147).
 *
 * <p>Facebook's FDSInlineReactions draws the icons as one drawable and separates each from the one
 * it overlaps in one of two ways, decided by the colour its style hands it. A colour with any
 * transparency makes it cut a ring out of the icon behind, so the page shows through; an opaque one
 * makes it paint a disc of that colour behind the next icon. The bling bar's COLOR and MEDIA styles
 * hand it ALWAYS_TRANSPARENT and get the cut. The SURFACE style hands it SURFACE_BACKGROUND, resolved
 * on the row's context, which in Facebook's dark styles is its #252728 surface and in the light ones
 * white. One reporter on a black AMOLED page saw white discs behind the icons: that resolve came back
 * light. A ring painted in any one colour can only match one page, while the cut matches every page,
 * AMOLED's black, a picked background colour and Material You's tint alike.
 *
 * <p>So in dark mode, with AMOLED or Material You in the build, an opaque ring colour comes back fully
 * transparent and the drawable cuts the ring instead. A colour that's already translucent, light
 * mode, and a build with neither theme come back as they were.
 */
public final class ReactionRing {

    private ReactionRing() {
    }

    /** The colour that makes the facepile cut its rings: any alpha under 255 does, this one is clear. */
    static final int CUT = 0x00000000;

    /** The ring colour the facepile was about to be made with. Gives the colour to make it with. */
    public static int ring(int color) {
        boolean amoled = SettingsStatus.amoledTheme();
        boolean materialYou = SettingsStatus.materialYouTheme();
        if (!amoled && !materialYou) return color;
        HookStatus.invoked(amoled ? FamilyNames.AMOLED_THEME : FamilyNames.MATERIAL_YOU_THEME);
        return ring(color, true, DarkMode.on());
    }

    /** {@link #ring(int)} with whether a theme is in the build and Facebook's dark answer given. */
    static int ring(int color, boolean themed, boolean dark) {
        if (!themed || !dark || color >>> 24 != 0xFF) return color;
        return CUT;
    }
}
