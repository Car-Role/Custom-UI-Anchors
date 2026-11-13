/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import com.google.gson.Gson;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The plugin side of "one layout": what is live, how positions follow the canvas, the
 * automatic pin, and the update from per-monitor profiles (GitHub issue #28). The
 * plugin is exercised directly through its test seams — no RuneLite client.
 */
public class LayoutBehaviourTest {
    private static final Gson GSON = new Gson();

    /** In-memory config holding the keys older versions wrote plus the new one. */
    private static final class MemConfig implements AnchorCustomizerConfig {
        String layouts = "";
        String regions = "[]";
        String profiles = "{}";
        String assignments = "{}";
        String order = "{}";
        int layoutWrites = 0;

        @Override public String layoutsJson() { return layouts; }
        @Override public void setLayoutsJson(String json) { layouts = json; layoutWrites++; }
        @Override public String regionJson() { return regions; }
        @Override public String regionProfilesJson() { return profiles; }
        @Override public String overlayAssignmentsJson() { return assignments; }
        @Override public String overlayOrderJson() { return order; }
        @Override public void setSelectedRegionId(int id) { }
    }

    /** A plugin whose canvas size, client mode and monitor answer are set by the test. */
    private static final class Rig {
        final AnchorCustomizerPlugin p = new AnchorCustomizerPlugin();
        final MemConfig cfg = new MemConfig();
        Dimension canvas = new Dimension(1920, 1080);
        boolean resizable = true;
        String monitor = null;
        final List<String> notices = new ArrayList<>();

        Rig() {
            p.gson = GSON;
            p.config = cfg;
            p.overlayScanHandler = pred -> { /* no overlays in test */ };
            p.chatTopSupplier = () -> null;
            p.canvasDimSupplier = () -> canvas;
            p.resizedSupplier = () -> resizable;
            p.legacyDisplayKeySupplier = () -> monitor;
            p.noticeHandler = notices::add;
        }

        /** What a logged-in client tick does when the live layout must be (re)shown. */
        void show() {
            if (p.store.legacyPickPending) {
                p.resolveLegacyPick();
            }
            p.activateLayout(resizable ? p.store.main : p.store.ensureFixed(), canvas);
        }

        void resizeTo(int w, int h) {
            canvas = new Dimension(w, h);
            for (AnchorRegion r : p.anchorRegions) {
                p.recomputePosition(r, canvas);
            }
        }

        AnchorRegion anchor(String name) {
            for (AnchorRegion r : p.anchorRegions) {
                if (name.equals(r.getName())) {
                    return r;
                }
            }
            return null;
        }
    }

    private static AnchorRegion region(int id, String name, int x, int y, AnchorConstraint pin, int w, int h) {
        return LayoutStoreTest.region(id, name, x, y, pin, w, h);
    }

    @Test
    public void issue28StaleProfileNoLongerComesBack() {
        Rig rig = new Rig();
        List<AnchorRegion> wide = new ArrayList<>();
        String[] names = { "TopMid", "TopMid(Lower)", "BottomMid", "Center", "BankLeft", "HotBar", "MiniMap", "Inventory" };
        for (int i = 0; i < names.length; i++) {
            wide.add(region(i + 1, names[i], 100 + 300 * i, 600, AnchorConstraint.TOP_LEFT, 3440, 1440));
        }
        List<AnchorRegion> stale = Arrays.asList(
                region(1, "TopMid", 900, 6, AnchorConstraint.TOP_LEFT, 1920, 1080),
                region(2, "TopMid(Lower)", 900, 110, AnchorConstraint.TOP_LEFT, 1920, 1080),
                region(3, "BankRight", 900, 400, AnchorConstraint.TOP_LEFT, 1920, 1080));
        rig.cfg.profiles = "{\"3440x1440\":" + GSON.toJson(wide) + ",\"1920x1080\":" + GSON.toJson(stale) + "}";
        // Last closed in the bugged state: the stale profile was the live one.
        rig.cfg.regions = GSON.toJson(stale);
        rig.cfg.assignments = "{\"motherlode\":3,\"inventoryOverlay\":8}";
        String profilesBefore = rig.cfg.profiles;

        rig.p.loadLayouts();
        rig.canvas = new Dimension(3440, 1440);
        rig.monitor = "3440x1440";
        rig.show();

        // The update lands on the profile of the monitor in use: all 8 anchors, and the
        // overlay table points at this layout's anchors (id 3 is BottomMid, not BankRight).
        assertEquals(8, rig.p.anchorRegions.size());
        assertEquals("BottomMid", rig.p.anchorRegions.get(2).getName());
        assertEquals(Integer.valueOf(8), rig.p.store.main.getAssignments().get("inventoryOverlay"));
        assertFalse(rig.p.store.legacyPickPending);

        // From here on the monitor is never asked again. Whatever AWT would now report
        // (the wrong monitor, as in the bug report), showing the layout changes nothing.
        rig.monitor = "1920x1080";
        rig.show();
        rig.show();
        assertEquals(8, rig.p.anchorRegions.size());
        assertEquals("BottomMid", rig.p.anchorRegions.get(2).getName());

        // The old data is untouched as a backup, and the stale profile is still restorable.
        assertEquals(profilesBefore, rig.cfg.profiles);
        assertNotNull(rig.p.store.old("old-1920x1080"));
        assertTrue(rig.cfg.layouts.contains("BottomMid"));
    }

    @Test
    public void oldTopLeftAnchorsArePinnedToTheirNearestEdgeWithoutMoving() {
        Rig rig = new Rig();
        List<AnchorRegion> regions = Arrays.asList(
                region(1, "inventory", 1700, 900, AnchorConstraint.TOP_LEFT, 1920, 1080),
                region(2, "minimap", 1700, 20, AnchorConstraint.TOP_LEFT, 1920, 1080),
                region(3, "chatInfo", 20, 900, AnchorConstraint.TOP_LEFT, 1920, 1080),
                region(4, "status", 30, 30, AnchorConstraint.TOP_LEFT, 1920, 1080),
                // The user chose this pin: it must not be changed.
                region(5, "byHand", 1700, 500, AnchorConstraint.TOP_CENTER, 1920, 1080));
        rig.cfg.regions = GSON.toJson(regions);
        rig.cfg.profiles = "{\"1920x1080\":" + GSON.toJson(regions) + "}";
        rig.p.loadLayouts();
        rig.show();

        // Nothing moved at the current window size...
        assertEquals(new Rectangle(1700, 900, 100, 100), rig.anchor("inventory").getBounds());
        assertEquals(new Rectangle(1700, 20, 100, 100), rig.anchor("minimap").getBounds());
        // ...but each old default pin became the nearest edge.
        assertEquals(AnchorConstraint.BOTTOM_RIGHT, rig.anchor("inventory").getConstraint());
        assertEquals(AnchorConstraint.TOP_RIGHT, rig.anchor("minimap").getConstraint());
        assertEquals(AnchorConstraint.BOTTOM_LEFT, rig.anchor("chatInfo").getConstraint());
        assertEquals(AnchorConstraint.TOP_LEFT, rig.anchor("status").getConstraint());
        assertEquals(AnchorConstraint.TOP_CENTER, rig.anchor("byHand").getConstraint());
        assertTrue(rig.anchor("byHand").isManualPin());
        assertFalse(rig.p.store.main.isRepinPending());

        // Move the window to a monitor of another size: every box keeps its distance to
        // its own edge — the case that needed a second profile before.
        rig.resizeTo(2560, 1440);
        assertEquals(new Rectangle(2340, 1260, 100, 100), rig.anchor("inventory").getBounds());
        assertEquals(new Rectangle(2340, 20, 100, 100), rig.anchor("minimap").getBounds());
        assertEquals(new Rectangle(20, 1260, 100, 100), rig.anchor("chatInfo").getBounds());
        assertEquals(new Rectangle(30, 30, 100, 100), rig.anchor("status").getBounds());
        rig.resizeTo(1280, 720);
        assertEquals(new Rectangle(1060, 540, 100, 100), rig.anchor("inventory").getBounds());
        // And back: exactly where it started. Nothing drifts.
        rig.resizeTo(1920, 1080);
        assertEquals(new Rectangle(1700, 900, 100, 100), rig.anchor("inventory").getBounds());

        // The upgrade runs once: a top-left pin seen later is left alone.
        rig.anchor("inventory").setConstraint(AnchorConstraint.TOP_LEFT);
        rig.show();
        assertEquals(AnchorConstraint.TOP_LEFT, rig.anchor("inventory").getConstraint());
    }

    @Test
    public void aBoxInsideTheWindowStaysInsideOnASmallerWindowWithoutBeingSaved() {
        // Inside the window at the origin size: held on the canvas at smaller sizes.
        assertEquals(1180, AnchorCustomizerPlugin.keepOnCanvas(1800, 100, 1800, 1920, 1280));
        assertEquals(1800, AnchorCustomizerPlugin.keepOnCanvas(1800, 100, 1800, 1920, 1920));
        assertEquals(0, AnchorCustomizerPlugin.keepOnCanvas(-40, 100, 300, 1920, 1280));
        // Wider than the canvas: starts at its edge.
        assertEquals(0, AnchorCustomizerPlugin.keepOnCanvas(50, 900, 50, 1920, 800));
        // Deliberately placed partly outside the window: left exactly where the pin puts it.
        assertEquals(-30, AnchorCustomizerPlugin.keepOnCanvas(-30, 100, -30, 1920, 1280));
        assertEquals(1850, AnchorCustomizerPlugin.keepOnCanvas(1850, 100, 1850, 1920, 1280));

        // A box the user pinned top-left by hand, far from that corner.
        Rig rig = new Rig();
        AnchorRegion far = region(1, "far", 1800, 950, AnchorConstraint.TOP_LEFT, 1920, 1080);
        far.setManualPin(true);
        LayoutStore saved = new LayoutStore();
        saved.normalize();
        saved.main.getRegions().add(far);
        saved.main.setUnseeded(false);
        rig.cfg.layouts = saved.toJson(GSON);
        rig.p.loadLayouts();
        rig.show();

        rig.resizeTo(1280, 720);
        AnchorRegion live = rig.anchor("far");
        assertEquals(new Rectangle(1180, 620, 100, 100), live.getBounds());
        // The origin still holds the user's position, so the box goes back when the window grows.
        assertEquals(1800, live.getOriginX());
        assertEquals(950, live.getOriginY());
        rig.resizeTo(1920, 1080);
        assertEquals(new Rectangle(1800, 950, 100, 100), live.getBounds());

        // A panel edit that does not move the box (a rename) must not bake the held position in.
        rig.resizeTo(1280, 720);
        rig.p.applyRegionUpdate(live, r -> r.setName("renamed"));
        assertEquals(1800, live.getOriginX());
        rig.resizeTo(1920, 1080);
        assertEquals(new Rectangle(1800, 950, 100, 100), live.getBounds());
    }

    @Test
    public void droppingABoxPinsItToTheNearestEdgeUnlessTheUserPickedThePin() {
        Rig rig = new Rig();
        rig.p.loadLayouts();
        rig.show();

        // A new box starts in the middle of the window, pinned to the centre.
        rig.p.createNewAnchorOnClientThread();
        AnchorRegion box = rig.p.anchorRegions.get(0);
        assertEquals(AnchorConstraint.CENTER, box.getConstraint());
        assertFalse(box.isManualPin());

        // Drag it to the bottom-right and drop: the plugin picks that corner.
        box.setX(1780);
        box.setY(950);
        assertEquals(AnchorConstraint.BOTTOM_RIGHT, rig.p.pinIfDroppedNow(box));
        rig.p.commitAnchorEdit(box);
        assertEquals(AnchorConstraint.BOTTOM_RIGHT, box.getConstraint());
        rig.resizeTo(1280, 720);
        assertEquals(new Rectangle(1140, 590, 100, 100), box.getBounds());
        rig.resizeTo(1920, 1080);

        // Moving it with the panel's X/Y fields re-pins it the same way.
        rig.p.applyRegionUpdate(box, r -> { r.setX(20); r.setY(20); });
        assertEquals(AnchorConstraint.TOP_LEFT, box.getConstraint());

        // The user clicks a constraint arrow: from now on the pin is theirs.
        rig.p.applyRegionUpdate(box, r -> { r.setConstraint(AnchorConstraint.TOP_CENTER); r.setManualPin(true); });
        box.setX(1780);
        box.setY(950);
        assertEquals(AnchorConstraint.TOP_CENTER, rig.p.pinIfDroppedNow(box));
        rig.p.commitAnchorEdit(box);
        assertEquals(AnchorConstraint.TOP_CENTER, box.getConstraint());
    }

    @Test
    public void anchorIdsAreNeverUsedAgain() {
        Rig rig = new Rig();
        rig.p.loadLayouts();
        rig.show();
        rig.p.createNewAnchorOnClientThread();
        rig.p.createNewAnchorOnClientThread();
        assertEquals(2, rig.p.anchorRegions.get(1).getId());

        // Delete anchor 2, then add another: it must not get id 2 back (an overlay or a
        // stale reference that pointed at the deleted anchor would silently move to it).
        rig.p.anchorRegions.remove(1);
        rig.p.createNewAnchorOnClientThread();
        assertEquals(3, rig.p.anchorRegions.get(1).getId());

        // Also across a restart.
        rig.p.anchorRegions.remove(1);
        rig.p.flushPendingSaves(true);
        Rig again = new Rig();
        again.cfg.layouts = rig.cfg.layouts;
        again.p.loadLayouts();
        again.show();
        again.p.createNewAnchorOnClientThread();
        assertEquals(4, again.p.anchorRegions.get(1).getId());
    }

    @Test
    public void fixedModeHasItsOwnLayoutAndStartsAsACopy() {
        Rig rig = new Rig();
        List<AnchorRegion> regions = Arrays.asList(
                region(1, "inventory", 1700, 900, AnchorConstraint.BOTTOM_RIGHT, 1920, 1080));
        rig.cfg.regions = GSON.toJson(regions);
        rig.cfg.profiles = "{\"1920x1080\":" + GSON.toJson(regions) + "}";
        rig.cfg.assignments = "{\"inv\":1}";
        rig.p.loadLayouts();
        rig.show();

        // Switch the game to fixed mode: its layout starts as a copy, held on the small canvas.
        rig.resizable = false;
        rig.canvas = new Dimension(765, 503);
        rig.show();
        assertTrue(rig.p.activeLayout.isFixed());
        AnchorRegion fixedBox = rig.anchor("inventory");
        assertTrue(new Rectangle(0, 0, 765, 503).contains(fixedBox.getBounds()));
        assertEquals(Integer.valueOf(1), rig.p.store.fixed.getAssignments().get("inv"));

        // An edit in fixed mode stays in fixed mode.
        rig.p.applyRegionUpdate(fixedBox, r -> r.setName("fixed inventory"));
        rig.resizable = true;
        rig.canvas = new Dimension(1920, 1080);
        rig.show();
        assertFalse(rig.p.activeLayout.isFixed());
        assertEquals("inventory", rig.p.anchorRegions.get(0).getName());
        assertEquals(new Rectangle(1700, 900, 100, 100), rig.p.anchorRegions.get(0).getBounds());

        rig.resizable = false;
        rig.canvas = new Dimension(765, 503);
        rig.show();
        assertEquals("fixed inventory", rig.p.anchorRegions.get(0).getName());
    }

    @Test
    public void restoreOldLayoutReplacesTheLayoutAndCanBeUndone() {
        Rig rig = new Rig();
        List<AnchorRegion> a = Arrays.asList(region(1, "a1", 10, 10, AnchorConstraint.TOP_LEFT, 1920, 1080),
                region(2, "a2", 300, 10, AnchorConstraint.TOP_LEFT, 1920, 1080));
        List<AnchorRegion> b = Arrays.asList(region(1, "b1", 50, 50, AnchorConstraint.TOP_LEFT, 2560, 1440));
        rig.cfg.regions = GSON.toJson(a);
        rig.cfg.profiles = "{\"1920x1080\":" + GSON.toJson(a) + ",\"2560x1440\":" + GSON.toJson(b) + "}";
        rig.p.loadLayouts();
        rig.monitor = "1920x1080";
        rig.show();
        assertEquals(2, rig.p.anchorRegions.size());
        // The user is told once that there is now one layout.
        rig.p.showPendingNotice();
        rig.p.showPendingNotice();
        assertEquals(1, rig.notices.size());

        rig.p.restoreOldLayoutOnClientThread("old-2560x1440");
        rig.show();
        assertEquals(1, rig.p.anchorRegions.size());
        assertEquals("b1", rig.p.anchorRegions.get(0).getName());

        rig.p.restoreOldLayoutOnClientThread(LayoutStore.BEFORE_RESTORE_ID);
        rig.show();
        assertEquals(2, rig.p.anchorRegions.size());
        assertEquals("a1", rig.p.anchorRegions.get(0).getName());
    }

    @Test
    public void savesAreWrittenOnlyWhenSomethingChanged() {
        Rig rig = new Rig();
        rig.p.loadLayouts();
        rig.show();
        rig.p.createNewAnchorOnClientThread();
        rig.p.flushPendingSaves(true);
        int writes = rig.cfg.layoutWrites;

        // Nothing changed: no write, however often the flush runs.
        rig.p.flushPendingSaves(true);
        rig.p.saveRegions();
        rig.p.flushPendingSaves(true);
        assertEquals(writes, rig.cfg.layoutWrites);

        // The old keys are never written: there are no setters for them at all.
        assertEquals("[]", rig.cfg.regions);
        assertEquals("{}", rig.cfg.profiles);
    }
}
