/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/** The search screen's rows are named with Debug logging on, and only by type and module names (#80). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PreSearchSectionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for a tree: answers its type, whether it's valid, and its strings by key. */
    public static class Tree {
        private final String type;
        private final boolean valid;
        private final String modules;
        int stringReads;

        Tree(String type, boolean valid, String modules) {
            this.type = type;
            this.valid = valid;
            this.modules = modules;
        }

        public String getTypeName() {
            return type;
        }

        public boolean isValidGraphServicesJNIModel() {
            return valid;
        }

        public String getCachedNullableString(int key) {
            stringReads++;
            return key == "rendered_modules".hashCode() ? modules : "1000123456789 at another key";
        }
    }

    @Before
    public void prepare() {
        PreSearchSections.forget();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        PreSearchSections.forget();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static String log() {
        return LogBufferManager.buildExportText();
    }

    @Test
    public void namesTheNodeTheModuleAndItsModuleNames() {
        PreSearchSections.entry(new Tree("SearchNullStateUnit", true, "RECENT_SEARCHES, PYMK2"),
                new Tree("NTSearchModule", true, null));
        String log = log();
        assertTrue(log, log.contains(PreSearchSections.PREFIX
                + "node=SearchNullStateUnit module=NTSearchModule rendered_modules=RECENT_SEARCHES,PYMK2"));
    }

    @Test
    public void idsAndTextNeverGetOut() {
        PreSearchSections.entry(new Tree("Search Unit", true, "100012345678901,People you may know,"),
                new Tree("https://example.com/x", true, null));
        String log = log();
        assertTrue(log, log.contains("node=other module=other rendered_modules=other,other,other"));
        assertFalse(log, log.contains("100012345678901"));
        assertFalse(log, log.contains("People you may know"));
        assertFalse(log, log.contains("example.com"));
    }

    @Test
    public void anEmptyRowAndNoModuleNamesSaySo() {
        PreSearchSections.entry(new Tree("SearchNullStateUnit", true, null), null);
        String log = log();
        assertTrue(log, log.contains("node=SearchNullStateUnit module=none rendered_modules=none"));
    }

    @Test
    public void aReleasedNodeIsNeverAskedForItsStrings() {
        Tree released = new Tree("SearchNullStateUnit", false, "RECENT_SEARCHES");
        PreSearchSections.entry(released, null);
        assertEquals(0, released.stringReads);
        String log = log();
        assertTrue(log, log.contains("node=unreadable module=none rendered_modules=unread"));
    }

    @Test
    public void debugOffLogsNothingAndTouchesNothing() {
        BaseSettings.DEBUG.save(false);
        Tree node = new Tree("SearchNullStateUnit", true, "RECENT_SEARCHES");
        PreSearchSections.entry(node, new Tree("NTSearchModule", true, null));
        assertEquals(0, node.stringReads);
        assertFalse(log(), log().contains(PreSearchSections.PREFIX));
    }

    @Test
    public void longModuleListsAreCut() {
        StringBuilder modules = new StringBuilder("A");
        for (int i = 1; i < PreSearchSections.MAX_MODULES + 3; i++) modules.append(",A");
        PreSearchSections.entry(new Tree("SearchNullStateUnit", true, modules.toString()), null);
        String log = log();
        assertTrue(log, log.contains("rendered_modules=A,A,A,A,A,A,A,A,more"));
    }

    @Test
    public void eachLineOnceAndABudget() {
        Tree module = new Tree("NTSearchModule", true, null);
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < PreSearchSections.MAX_SHAPES + 5; i++) {
                PreSearchSections.entry(new Tree("Unit" + (char) ('a' + i % 26) + (char) ('a' + i / 26), true, null), module);
            }
        }
        String log = log();
        assertEquals(PreSearchSections.MAX_SHAPES + 1, log.split(PreSearchSections.PREFIX, -1).length - 1);
        assertEquals(1, log.split("more rows than the log keeps", -1).length - 1);
    }

    @Test
    public void aSpentBudgetStopsReadingEntries() {
        for (int i = 0; i <= PreSearchSections.MAX_SHAPES; i++) {
            PreSearchSections.entry(new Tree("Unit" + (char) ('a' + i % 26) + (char) ('a' + i / 26), true, null), null);
        }
        assertTrue(log(), log().contains("more rows than the log keeps"));
        Tree late = new Tree("SearchNullStateUnit", true, "RECENT_SEARCHES");
        PreSearchSections.entry(late, null);
        assertEquals(0, late.stringReads);
        assertFalse(log(), log().contains("RECENT_SEARCHES"));
    }

    @Test
    public void namesAreLettersUnderscoresAndAFewDigits() {
        assertEquals("PaginatedPeopleYouMayKnow", PreSearchSections.name("PaginatedPeopleYouMayKnow"));
        assertEquals("FB_Module_2026", PreSearchSections.name("FB_Module_2026"));
        assertEquals("other", PreSearchSections.name("Module_20261"));
        assertEquals("other", PreSearchSections.name("2026Module"));
        assertEquals("other", PreSearchSections.name(""));
        assertEquals("other", PreSearchSections.name("Caf" + (char) 0xe9));
        assertEquals("unreadable", PreSearchSections.name(null));
    }
}
