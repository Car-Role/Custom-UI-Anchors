/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The saved-data side of "one layout": migration from the per-monitor profiles (GitHub
 * issue #28), the one-time monitor pick, restore, save/load and export/import. Pure
 * data — no RuneLite client.
 */
public class LayoutStoreTest {
    private final Gson gson = new Gson();

    static AnchorRegion region(int id, String name, int x, int y, AnchorConstraint pin, int canvasW, int canvasH) {
        return new AnchorRegion(id, name, x, y, 100, 100, pin, AnchorAlignment.CENTER, AnchorStacking.VERTICAL,
                false, x, y, canvasW, canvasH, false, AnchorFillDirection.FORWARD, AnchorFillDirection.FORWARD,
                false, 0, null, false);
    }

    private String json(Object o) {
        return gson.toJson(o);
    }

    /** The data shape from issue #28: a current 8-anchor profile and a stale 3-anchor one. */
    private LayoutStore issue28Store(String lastLiveKey) {
        List<AnchorRegion> wide = new ArrayList<>();
        String[] names = { "TopMid", "TopMid(Lower)", "BottomMid", "Center", "BankLeft", "HotBar", "MiniMap", "Inventory" };
        for (int i = 0; i < names.length; i++) {
            wide.add(region(i + 1, names[i], 100 * i, 50, AnchorConstraint.TOP_LEFT, 3440, 1440));
        }
        List<AnchorRegion> stale = Arrays.asList(
                region(1, "TopMid", 900, 6, AnchorConstraint.TOP_CENTER, 1920, 1080),
                region(2, "TopMid(Lower)", 900, 110, AnchorConstraint.TOP_CENTER, 1920, 1080),
                // Same id as BottomMid above, but an anchor the user deleted months ago.
                region(3, "BankRight", 900, 400, AnchorConstraint.TOP_LEFT, 1920, 1080));
        String profiles = "{\"3440x1440\":" + json(wide) + ",\"1920x1080\":" + json(stale) + "}";
        String lastLive = json("3440x1440".equals(lastLiveKey) ? wide : stale);
        return LayoutStore.migrate(gson, lastLive, profiles,
                "{\"motherlode\":3,\"inventoryOverlay\":8}", "{\"motherlode\":0,\"inventoryOverlay\":1}");
    }

    @Test
    public void oneResizableProfileSimplyBecomesTheLayout() {
        List<AnchorRegion> regions = Arrays.asList(
                region(1, "inventory", 1600, 800, AnchorConstraint.BOTTOM_RIGHT, 1920, 1080),
                region(2, "orbs", 1500, 20, AnchorConstraint.TOP_LEFT, 1920, 1080));
        LayoutStore s = LayoutStore.migrate(gson, json(regions), "{\"1920x1080\":" + json(regions) + "}",
                "{\"a\":1,\"gone\":9}", "{\"a\":0,\"gone\":0}");

        assertEquals(2, s.main.getRegions().size());
        assertTrue(s.oldLayouts.isEmpty());
        assertFalse(s.legacyPickPending);
        assertFalse(s.noticePending);
        assertNull(s.fixed);
        // Only assignments whose anchor exists in this layout are carried over.
        assertEquals(Integer.valueOf(1), s.main.getAssignments().get("a"));
        assertFalse(s.main.getAssignments().containsKey("gone"));
        assertFalse(s.main.getOrder().containsKey("gone"));
        // A pin other than the old top-left default was the user's choice.
        assertTrue(s.main.getRegions().get(0).isManualPin());
        assertFalse(s.main.getRegions().get(1).isManualPin());
        assertTrue(s.main.isRepinPending());
        assertEquals(3, s.main.getNextRegionId());
    }

    @Test
    public void severalProfilesAreAllKeptAndTheMonitorPickHappensOnce() {
        // The client was last closed in the bugged state: the stale profile was live.
        LayoutStore s = issue28Store("1920x1080");

        assertEquals("1920x1080", s.main.getLegacyKey());
        assertEquals(3, s.main.getRegions().size());
        assertEquals(2, s.oldLayouts.size());
        assertTrue(s.legacyPickPending);
        assertTrue(s.noticePending);

        // First login is on the 3440x1440 monitor: its profile becomes the layout.
        assertTrue(s.pickLegacy("3440x1440"));
        assertFalse(s.legacyPickPending);
        assertEquals(8, s.main.getRegions().size());
        assertEquals("BottomMid", s.main.getRegions().get(2).getName());
        // Each layout has its own overlay table: id 3 means BottomMid here...
        assertEquals(Integer.valueOf(3), s.main.getAssignments().get("motherlode"));
        assertEquals(Integer.valueOf(8), s.main.getAssignments().get("inventoryOverlay"));
        // ...and the stale profile, still restorable, never had an anchor 8.
        AnchorLayout stale = s.old("old-1920x1080");
        assertNotNull(stale);
        assertEquals("BankRight", stale.getRegions().get(2).getName());
        assertFalse(stale.getAssignments().containsKey("inventoryOverlay"));
        // Both old profiles stay available.
        assertEquals(2, s.oldLayouts.size());
    }

    @Test
    public void monitorPickKeepsTheLayoutWhenItAlreadyMatchesOrIsUnknown() {
        LayoutStore s = issue28Store("3440x1440");
        assertEquals(8, s.main.getRegions().size());
        assertFalse(s.pickLegacy("3440x1440"));
        assertEquals(8, s.main.getRegions().size());

        LayoutStore t = issue28Store("3440x1440");
        assertFalse(t.pickLegacy("2560x1440")); // a monitor with no old profile
        assertFalse(t.legacyPickPending);
        assertEquals(8, t.main.getRegions().size());

        LayoutStore u = issue28Store("3440x1440");
        assertFalse(u.pickLegacy(null)); // no screen info: give up, keep the layout
        assertFalse(u.legacyPickPending);
    }

    @Test
    public void restoreReplacesTheLayoutAndCanBeUndone() {
        LayoutStore s = issue28Store("3440x1440");
        s.pickLegacy("3440x1440");

        assertTrue(s.restore("old-1920x1080"));
        assertEquals(3, s.main.getRegions().size());
        assertEquals("BankRight", s.main.getRegions().get(2).getName());
        AnchorLayout backup = s.old(LayoutStore.BEFORE_RESTORE_ID);
        assertNotNull(backup);
        assertEquals(8, backup.getRegions().size());
        assertEquals(Integer.valueOf(8), backup.getAssignments().get("inventoryOverlay"));

        // Undo: restore the backup. The 3-anchor layout becomes the new backup.
        assertTrue(s.restore(LayoutStore.BEFORE_RESTORE_ID));
        assertEquals(8, s.main.getRegions().size());
        assertEquals(3, s.old(LayoutStore.BEFORE_RESTORE_ID).getRegions().size());
        assertEquals(3, s.oldLayouts.size());

        assertFalse(s.restore("no-such-layout"));
        // Ids handed out later never collide with ids of any content the layout held.
        assertTrue(s.main.getNextRegionId() >= 9);
    }

    @Test
    public void fixedProfileBecomesTheFixedLayout() {
        List<AnchorRegion> fixed = Arrays.asList(region(1, "fixedBox", 600, 300, AnchorConstraint.TOP_LEFT, 765, 503));
        List<AnchorRegion> wide = Arrays.asList(region(1, "wideBox", 1500, 800, AnchorConstraint.TOP_LEFT, 1920, 1080));
        LayoutStore s = LayoutStore.migrate(gson, json(wide),
                "{\"fixed\":" + json(fixed) + ",\"1920x1080\":" + json(wide) + "}", "{}", "{}");
        assertEquals("wideBox", s.main.getRegions().get(0).getName());
        assertNotNull(s.fixed);
        assertEquals("fixedBox", s.fixed.getRegions().get(0).getName());
        assertTrue(s.fixed.isFixed());
        assertFalse(s.main.isFixed());

        // Only ever played fixed mode: resizable mode starts from the last live anchors,
        // as older versions seeded it...
        LayoutStore fixedOnly = LayoutStore.migrate(gson, json(fixed), "{\"fixed\":" + json(fixed) + "}", "{}", "{}");
        assertEquals("fixedBox", fixedOnly.main.getRegions().get(0).getName());
        assertFalse(fixedOnly.main.isUnseeded());
        assertEquals(1, fixedOnly.fixed.getRegions().size());
        // ...or, with no last live anchors saved, as a copy of the fixed layout when first shown.
        LayoutStore noLive = LayoutStore.migrate(gson, "[]", "{\"fixed\":" + json(fixed) + "}", "{}", "{}");
        assertTrue(noLive.main.getRegions().isEmpty());
        assertTrue(noLive.main.isUnseeded());
    }

    @Test
    public void dataFromBeforeProfilesAndEmptyDataBothGiveALayout() {
        List<AnchorRegion> regions = Arrays.asList(region(4, "old", 10, 10, AnchorConstraint.TOP_LEFT, 0, 0));
        LayoutStore legacy = LayoutStore.migrate(gson, json(regions), "{}", "{\"x\":4}", "{}");
        assertEquals(1, legacy.main.getRegions().size());
        assertFalse(legacy.main.isUnseeded());
        assertEquals(Integer.valueOf(4), legacy.main.getAssignments().get("x"));
        assertEquals(5, legacy.main.getNextRegionId());

        for (String empty : new String[] { null, "", "[]", "not json" }) {
            LayoutStore fresh = LayoutStore.migrate(gson, empty, empty, empty, empty);
            assertNotNull(fresh.main);
            assertTrue(fresh.main.getRegions().isEmpty());
            assertTrue(fresh.oldLayouts.isEmpty());
        }
    }

    @Test
    public void saveAndLoadRoundTrip() {
        LayoutStore s = issue28Store("3440x1440");
        s.ensureFixed();
        LayoutStore back = LayoutStore.fromJson(gson, s.toJson(gson));
        assertNotNull(back);
        assertEquals(s.toJson(gson), back.toJson(gson));
        assertTrue(back.legacyPickPending);

        assertNull(LayoutStore.fromJson(gson, null));
        assertNull(LayoutStore.fromJson(gson, ""));
        assertNull(LayoutStore.fromJson(gson, "not json"));
        assertNull(LayoutStore.fromJson(gson, "{}")); // nothing saved yet: caller migrates
    }

    @Test
    public void exportRoundTripAndOldExportsStillImport() {
        LayoutStore s = issue28Store("3440x1440");
        s.pickLegacy("3440x1440");
        s.ensureFixed().getRegions().add(region(1, "fixedBox", 10, 10, AnchorConstraint.TOP_LEFT, 765, 503));

        String exported = gson.toJson(s.toExport(gson));
        LayoutStore back = LayoutStore.fromExport(gson, gson.fromJson(exported, JsonElement.class).getAsJsonObject());
        assertEquals(8, back.main.getRegions().size());
        assertEquals(1, back.fixed.getRegions().size());
        assertEquals(2, back.oldLayouts.size());
        assertEquals(Integer.valueOf(8), back.main.getAssignments().get("inventoryOverlay"));

        // Version 1: the four raw blobs of the per-monitor profile era.
        String v1 = "{\"format\":\"custom-ui-anchors\",\"version\":1,"
                + "\"regionJson\":" + json(Arrays.asList(region(1, "a", 5, 5, AnchorConstraint.TOP_LEFT, 800, 600)))
                + ",\"regionProfiles\":{},\"overlayAssignments\":{\"o\":1},\"overlayOrder\":{\"o\":0}}";
        LayoutStore old = LayoutStore.fromExport(gson, gson.fromJson(v1, JsonElement.class).getAsJsonObject());
        assertEquals("a", old.main.getRegions().get(0).getName());
        assertEquals(Integer.valueOf(1), old.main.getAssignments().get("o"));
        assertFalse(old.noticePending);

        // A broken export still yields a usable (empty) layout rather than a crash.
        LayoutStore broken = LayoutStore.fromExport(gson,
                gson.fromJson("{\"layout\":{\"regions\":null}}", JsonElement.class).getAsJsonObject());
        assertTrue(broken.main.getRegions().isEmpty());
    }

    @Test
    public void normalizeRepairsAnchorLists() {
        AnchorRegion a = region(1, "a", 0, 0, AnchorConstraint.TOP_LEFT, 800, 600);
        AnchorRegion dup = region(1, "dup", 0, 0, AnchorConstraint.TOP_LEFT, 800, 600);
        AnchorRegion zero = region(0, null, 0, 0, null, 800, 600);
        zero.setWidth(2);
        AnchorRegion five = region(5, "five", 0, 0, AnchorConstraint.TOP_LEFT, 800, 600);
        List<AnchorRegion> out = LayoutStore.normalizeRegions(Arrays.asList(a, null, dup, zero, five));

        assertEquals(4, out.size());
        assertEquals(1, out.get(0).getId());
        // Reassigned ids never take an id another anchor in the list already has.
        assertEquals(2, out.get(1).getId());
        assertEquals(3, out.get(2).getId());
        assertEquals(5, out.get(3).getId());
        assertEquals("Box 3", out.get(2).getName());
        assertEquals(AnchorConstraint.TOP_LEFT, out.get(2).getConstraint());
        assertEquals(10, out.get(2).getWidth());
    }

    @Test
    public void nearestPinFollowsTheClosestEdgeCornerOrCentre() {
        int w = 1920, h = 1080;
        assertEquals(AnchorConstraint.TOP_LEFT, AnchorConstraint.nearest(new Rectangle(10, 10, 100, 100), w, h));
        assertEquals(AnchorConstraint.BOTTOM_RIGHT, AnchorConstraint.nearest(new Rectangle(1700, 900, 200, 170), w, h));
        assertEquals(AnchorConstraint.TOP_RIGHT, AnchorConstraint.nearest(new Rectangle(1700, 20, 200, 170), w, h));
        assertEquals(AnchorConstraint.BOTTOM_LEFT, AnchorConstraint.nearest(new Rectangle(5, 800, 500, 270), w, h));
        assertEquals(AnchorConstraint.CENTER, AnchorConstraint.nearest(new Rectangle(910, 490, 100, 100), w, h));
        assertEquals(AnchorConstraint.TOP_CENTER, AnchorConstraint.nearest(new Rectangle(900, 6, 100, 100), w, h));
        assertEquals(AnchorConstraint.BOTTOM_CENTER, AnchorConstraint.nearest(new Rectangle(900, 970, 100, 100), w, h));
        assertEquals(AnchorConstraint.CENTER_LEFT, AnchorConstraint.nearest(new Rectangle(0, 480, 100, 100), w, h));
        assertEquals(AnchorConstraint.CENTER_RIGHT, AnchorConstraint.nearest(new Rectangle(1820, 480, 100, 100), w, h));
        // A wide box whose centre is mid-screen but whose edge touches the right side
        // of a small canvas pins right, not centre.
        assertEquals(AnchorConstraint.BOTTOM_RIGHT, AnchorConstraint.nearest(new Rectangle(505, 240, 250, 260), 765, 503));
    }

    @Test
    public void pinPointsForTheIndicator() {
        Rectangle box = new Rectangle(100, 200, 50, 40);
        assertEquals(new Point(100, 200), AnchorConstraint.TOP_LEFT.boxPoint(box));
        assertEquals(new Point(150, 240), AnchorConstraint.BOTTOM_RIGHT.boxPoint(box));
        assertEquals(new Point(125, 220), AnchorConstraint.CENTER.boxPoint(box));
        assertEquals(new Point(125, 200), AnchorConstraint.TOP_CENTER.boxPoint(box));

        assertEquals(new Point(5, 5), AnchorConstraint.TOP_LEFT.canvasPoint(800, 600, 5));
        assertEquals(new Point(794, 594), AnchorConstraint.BOTTOM_RIGHT.canvasPoint(800, 600, 5));
        assertEquals(new Point(399, 299), AnchorConstraint.CENTER.canvasPoint(800, 600, 5));
        assertEquals(new Point(794, 299), AnchorConstraint.CENTER_RIGHT.canvasPoint(800, 600, 5));
    }
}
