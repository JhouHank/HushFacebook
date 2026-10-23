/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The Background color row (issue #214): the table of resources route two wrote over, read as
 * AmoledResources loads, the colours its loader gives them, and the page colour route three reads.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledResourcesTest {
    private static final int NAVY = 0xFF0D1117;
    private static final int PAGE_ID = 0x7f060020;

    @After
    public void forget() {
        AmoledResources.useTable(null);
        AmoledTheme.useBackground(0xFF000000);
    }

    @Test
    public void theTableIsReadSortedAndAnUnreadableEntryIsLeftOut() {
        AmoledResources.Table table = AmoledResources.parse(
                "7f060010=p;7f060002=ff242526/p;zz=p;7f060005=p/x;7f060003=p/p;7f060004=p/p/p;7f060010=ff000000");
        assertArrayEquals(new int[] {0x7f060002, 0x7f060003, 0x7f060010}, table.ids);
        assertArrayEquals(new boolean[] {false, true, true}, table.defaultWritten);
        assertArrayEquals(new boolean[] {true, true, false}, table.hasNight);
        assertEquals(0xFF242526, table.defaults[0]);
        assertEquals(0, AmoledResources.parse(null).ids.length);
        assertEquals(0, AmoledResources.parse("").ids.length);
    }

    @Test
    public void theLoaderGivesTheRowsColourWhereRouteTwoWroteAndThePagesResourceToo() {
        AmoledResources.Table table = AmoledResources.parse("7f060002=ff242526/p;7f060010=p");
        int[][] given = AmoledResources.colours(table, NAVY, PAGE_ID);
        assertArrayEquals(new int[] {0x7f060002, 0x7f060010, PAGE_ID}, given[0]);
        assertArrayEquals("Facebook's default stays where route two left it", new int[] {0xFF242526, NAVY, NAVY}, given[1]);
        assertArrayEquals(new int[] {NAVY, 0, 0}, given[2]);
        assertArrayEquals(new int[] {1, 0, 0}, given[3]);

        assertEquals("a build without the page's resource", 2, AmoledResources.colours(table, NAVY, 0)[0].length);
        assertEquals("one the table lists already", 2, AmoledResources.colours(table, NAVY, 0x7f060010)[0].length);
    }

    @Test
    public void wroteAnswersForTheTablesIdsOnly() {
        AmoledResources.useTable("7f060002=ff242526/p;7f060010=p");
        assertTrue(AmoledResources.wrote(0x7f060010));
        assertFalse(AmoledResources.wrote(0x7f060011));
    }

    enum Token { WASH }

    /**
     * Patched black, the row navy: Facebook's own black stays black on route one, as routes two and
     * three leave it, while a grey and a patched colour that isn't black take the row's.
     */
    @Test
    public void facebooksBlackStaysBlackUnderTheRowsColour() {
        DarkMode.answer(true);
        AmoledTheme.useBackground(0xFF000000);
        AmoledTheme.use(NAVY, AmoledBackground.lightestStep(NAVY));
        assertEquals(0xFF000000, AmoledTheme.apply(0xFF000000, Token.WASH));
        assertEquals(NAVY, AmoledTheme.apply(0xFF101011, Token.WASH));

        int sand = 0xFF5B513F;
        AmoledTheme.useBackground(sand, AmoledBackground.lightestStep(sand));
        AmoledTheme.use(NAVY, AmoledBackground.lightestStep(NAVY));
        assertEquals("a resource still holding the patched colour", NAVY, AmoledTheme.apply(sand, Token.WASH));
        assertEquals(0xFF000000, AmoledTheme.apply(0xFF000000, Token.WASH));
    }

    /** Route three reads PAGE and PAGE_WIDE, and a long holds the colour as const-wide/32 sign-extends it. */
    @Test
    public void theRowsColourGoesWhereRouteThreeReadsIt() {
        AmoledTheme.useBackground(0xFF000000);
        AmoledTheme.use(NAVY, AmoledBackground.lightestStep(NAVY));
        assertEquals(NAVY, AmoledTheme.PAGE);
        assertEquals((long) NAVY, AmoledTheme.PAGE_WIDE);
        assertEquals(NAVY, AmoledTheme.background());
        assertEquals("the patch's colour stays what the row stands in for", 0xFF000000, AmoledTheme.written());
    }
}
