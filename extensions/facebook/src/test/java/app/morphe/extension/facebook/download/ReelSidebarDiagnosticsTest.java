/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.litho.BaseMountingView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.misc.AppLockForTests;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/** The missing-sidebar read is opt-in, delayed, cancelled by either known sidebar and content-free. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelSidebarDiagnosticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TREE = String.join("\n",
            "litho.Column{user-id-8273 V.E..... .. 0,0-1080,2000 text=\"Pat Doe\"}",
            "  litho.Video{2 V.E..... .. 0,0-1080,2000 text=\"https://private.example/reel/token\"}",
            "  litho.SideButtons{3 V.E..... .. 900,600-1080,1600 text=\"Private caption\"}",
            "    litho.Like{4 V.E..... .. 900,600-1080,800 content-description=\"Secret account\"}",
            "      litho.Text{5 V.E..... .. 900,700-1080,800 text=\"1.2K\"}",
            "    litho.Comments{6 V.E..... .. 900,800-1080,1000}",
            "      litho.Text{7 V.E..... .. 900,900-1080,1000 text=\"١٢\"}");

    @Before
    public void start() {
        AppLockForTests.disarm();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        PauseForTests.resume();
        Settings.DOWNLOAD_REELS.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        BaseSettings.DEBUG.save(true);
    }

    @After
    public void finish() {
        AppLockForTests.disarm();
        PauseForTests.resume();
        Settings.DOWNLOAD_REELS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static void waitForRead() {
        shadowOf(Looper.getMainLooper()).idleFor(ReelSidebarDiagnostics.DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static String counts() {
        return String.join("\n", HookStatus.report());
    }

    private static void screen() {
        FbMainTabActivity screen = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        BaseMountingView mount = new BaseMountingView(screen);
        mount.description = TREE;
        screen.setContentView(mount);
    }

    @Test
    public void aReelWithoutEitherSidebarExportsOnlyCountComponentPathsAfterDrawing() {
        screen();
        ReelSidebarDiagnostics.reelStarted();
        assertTrue(counts(), counts().contains(ReelSidebarDiagnostics.SCHEDULED + " 1"));
        assertFalse(LogBufferManager.buildExportText().contains(ReelSidebarDiagnostics.LABEL));
        waitForRead();
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(ReelSidebarDiagnostics.LABEL));
        assertTrue(report, report.contains("litho.Column > litho.SideButtons > litho.Like > litho.Text [count]"));
        assertTrue(counts(), counts().contains(ReelSidebarDiagnostics.SAMPLED + " 1"));
        for (String secret : Arrays.asList("Pat Doe", "8273", "private.example", "/reel/token", "Private caption",
                "Secret account", "1.2K", "١٢", "content-description", "text=", "0,0-1080")) {
            assertFalse(secret + " was exported: " + report, report.contains(secret));
        }
    }

    @Test
    public void eitherSidebarBeforeTheStartOrDuringTheDelaySuppressesTheRead() {
        screen();
        for (boolean udd : new boolean[]{true, false}) {
            HookStatus.clear();
            if (udd) ReelDownload.showsButton(); else ReelDownload.otherSidebarBuilt();
            ReelSidebarDiagnostics.reelStarted();
            waitForRead();
            assertFalse(counts(), counts().contains(ReelSidebarDiagnostics.SCHEDULED));

            HookStatus.clear();
            ReelSidebarDiagnostics.reelStarted();
            if (udd) ReelDownload.showsButton(); else ReelDownload.otherSidebarBuilt();
            waitForRead();
            assertFalse(counts(), counts().contains(ReelSidebarDiagnostics.SAMPLED));
            assertFalse(LogBufferManager.buildExportText().contains(ReelSidebarDiagnostics.LABEL));
        }
    }

    @Test
    public void debugOffDownloadOffPauseAndUnreadySettingsNeverPlanARead() {
        screen();
        BaseSettings.DEBUG.save(false);
        ReelSidebarDiagnostics.reelStarted();
        BaseSettings.DEBUG.save(true);
        Settings.DOWNLOAD_REELS.save(false);
        ReelSidebarDiagnostics.reelStarted();
        Settings.DOWNLOAD_REELS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReelSidebarDiagnostics.reelStarted();
        PauseForTests.resume();
        SettingsContextRule.beforeThePauseIsDecided(ReelSidebarDiagnostics::reelStarted);
        waitForRead();
        assertFalse(counts(), counts().contains(ReelSidebarDiagnostics.SCHEDULED));
        assertFalse(LogBufferManager.buildExportText().contains(ReelSidebarDiagnostics.LABEL));
    }

    @Test
    public void eachGuardIsCheckedAgainWhenTheDelayEnds() {
        screen();
        for (int guard = 0; guard < 4; guard++) {
            HookStatus.clear();
            BaseSettings.DEBUG.save(true);
            Settings.DOWNLOAD_REELS.save(true);
            PauseForTests.resume();
            ReelSidebarDiagnostics.reelStarted();
            if (guard == 0) BaseSettings.DEBUG.save(false);
            if (guard == 1) Settings.DOWNLOAD_REELS.save(false);
            if (guard == 2) PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
            if (guard == 3) SettingsContextRule.beforeThePauseIsDecided(ReelSidebarDiagnosticsTest::waitForRead);
            else waitForRead();
            assertFalse(counts(), counts().contains(ReelSidebarDiagnostics.SAMPLED));
            assertFalse(LogBufferManager.buildExportText().contains(ReelSidebarDiagnostics.LABEL));
        }
    }

    @Test
    public void unclassifiedPlayersNeverPlanAReadAndADiagnosticClearCancelsAnOldPlan() {
        ReelSidebarDiagnostics.started(null);
        ReelSidebarDiagnostics.started(new Object());
        assertFalse(counts(), counts().contains(ReelSidebarDiagnostics.SCHEDULED));
        ReelSidebarDiagnostics.reelStarted();
        HookStatus.clear();
        waitForRead();
        assertFalse(counts(), counts().contains(ReelSidebarDiagnostics.SAMPLED));
        assertFalse(LogBufferManager.buildExportText().contains(ReelSidebarDiagnostics.LABEL));
    }

    @Test
    public void repeatedStartsAreDeduplicatedAndAtMostThreeReadsArePlannedPerDiagnosticGeneration() {
        screen();
        for (int read = 0; read < ReelSidebarDiagnostics.READS_MAX + 2; read++) {
            for (int duplicate = 0; duplicate < 20; duplicate++) ReelSidebarDiagnostics.reelStarted();
            waitForRead();
            shadowOf(Looper.getMainLooper()).idleFor(ReelSidebarDiagnostics.GAP_MS, TimeUnit.MILLISECONDS);
        }
        assertTrue(counts(), counts().contains(ReelSidebarDiagnostics.SCHEDULED + " " + ReelSidebarDiagnostics.READS_MAX));
        assertTrue(counts(), counts().contains(ReelSidebarDiagnostics.SAMPLED + " " + ReelSidebarDiagnostics.READS_MAX));
    }

    @Test
    public void walksIgnoreHiddenTreesAndNeverReadAndroidViewContent() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        BaseMountingView hidden = new BaseMountingView(root.getContext());
        hidden.description = TREE;
        hidden.setVisibility(View.GONE);
        root.addView(hidden);
        TextView secret = new TextView(root.getContext()) {
            @Override public CharSequence getContentDescription() { throw new AssertionError("read view description"); }
        };
        secret.setText("Secret ordinary view text");
        root.addView(secret);
        ReelSidebarDiagnostics.Reading reading = ReelSidebarDiagnostics.read(Collections.singletonList(root), Long.MAX_VALUE);
        assertEquals(0, reading.trees);
        assertTrue(reading.paths.isEmpty());
    }

    @Test
    public void expiredBudgetsAndBroadOrDeepViewTreesStopWithoutUnboundedWalks() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        for (int i = 0; i < ReelSidebarDiagnostics.VIEWS_MAX + 20; i++) root.addView(new View(root.getContext()));
        ReelSidebarDiagnostics.Reading expired = ReelSidebarDiagnostics.read(Collections.singletonList(root), SystemClock.elapsedRealtime());
        assertEquals(0, expired.views);
        assertTrue(expired.limited);
        ReelSidebarDiagnostics.Reading broad = ReelSidebarDiagnostics.read(Collections.singletonList(root), Long.MAX_VALUE);
        assertEquals(ReelSidebarDiagnostics.VIEWS_MAX, broad.views);
        assertTrue(broad.limited);
        FrameLayout deep = new FrameLayout(root.getContext());
        FrameLayout parent = deep;
        for (int i = 0; i < ReelSidebarDiagnostics.DEPTH_MAX + 20; i++) {
            FrameLayout child = new FrameLayout(root.getContext());
            parent.addView(child);
            parent = child;
        }
        ReelSidebarDiagnostics.Reading depth = ReelSidebarDiagnostics.read(Collections.singletonList(deep), Long.MAX_VALUE);
        assertEquals(ReelSidebarDiagnostics.DEPTH_MAX, depth.views);
        assertTrue(depth.limited);
    }

    @Test
    public void descriptionPrefixesLinesPathsWindowsAndTreesHaveIndependentCaps() {
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < ReelSidebarDiagnostics.PATHS_MAX + 20; i++) {
            description.append("litho.Button").append(i).append("{id text=\"12\"}\n");
        }
        ReelSidebarDiagnostics.Reading paths = new ReelSidebarDiagnostics.Reading();
        ReelSidebarDiagnostics.paths(description.toString(), paths, Long.MAX_VALUE);
        assertEquals(ReelSidebarDiagnostics.PATHS_MAX, paths.paths.size());
        assertTrue(paths.limited);
        for (String prefix : Arrays.asList("x\n".repeat(ReelSidebarDiagnostics.LINES_MAX),
                "x".repeat(ReelSidebarDiagnostics.DESCRIPTION_CHARS_MAX) + "\n")) {
            ReelSidebarDiagnostics.Reading truncated = new ReelSidebarDiagnostics.Reading();
            ReelSidebarDiagnostics.paths(prefix + TREE, truncated, Long.MAX_VALUE);
            assertTrue(truncated.paths.isEmpty());
            assertTrue(truncated.limited);
        }
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        for (int i = 0; i < ReelSidebarDiagnostics.TREES_MAX + 2; i++) root.addView(new BaseMountingView(root.getContext()));
        ReelSidebarDiagnostics.Reading trees = ReelSidebarDiagnostics.read(Collections.nCopies(ReelSidebarDiagnostics.WINDOWS_MAX + 1, root), Long.MAX_VALUE);
        assertEquals(ReelSidebarDiagnostics.TREES_MAX, trees.trees);
        assertTrue(trees.limited);
    }
}
