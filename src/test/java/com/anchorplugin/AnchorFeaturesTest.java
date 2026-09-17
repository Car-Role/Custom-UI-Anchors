/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.awt.event.MouseEvent;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the overlay-size persistence fix (issue #25), the fill-from mirroring math
 * (issue #24), the chat-pin shift (issue #17) and the clipboard export/import round
 * trip (issue #23). The plugin instance is exercised directly with seam handlers
 * stubbed out — no RuneLite client required.
 */
public class AnchorFeaturesTest {

    /** Overlay whose rendered bounds track preferredLocation; also simulates a RuneLite reset. */
    private static final class FakeOverlay extends Overlay {
        private final Dimension size;

        FakeOverlay(Dimension size) {
            this.size = size;
        }

        @Override
        public Dimension render(Graphics2D graphics) {
            return null;
        }

        @Override
        public Rectangle getBounds() {
            Point p = getPreferredLocation();
            if (p == null) {
                return new Rectangle();
            }
            return new Rectangle(p.x, p.y, size.width, size.height);
        }
    }

    private static AnchorCustomizerPlugin pluginWithFakes(
            AtomicInteger resets, AtomicInteger saves,
            AtomicReference<Dimension> sizeAtSave, AtomicReference<Point> locAtSave,
            AtomicReference<OverlayPosition> posAtSave) {
        AnchorCustomizerPlugin p = new AnchorCustomizerPlugin();
        p.config = new MemConfig();
        // Simulate OverlayManager.resetOverlay: nulls preferredSize/Location/Position.
        p.resetOverlayHandler = o -> {
            resets.incrementAndGet();
            o.setPreferredSize(null);
            o.setPreferredLocation(null);
            o.setPreferredPosition(null);
        };
        p.saveOverlayHandler = o -> {
            saves.incrementAndGet();
            sizeAtSave.set(o.getPreferredSize());
            locAtSave.set(o.getPreferredLocation());
            posAtSave.set(o.getPreferredPosition());
        };
        return p;
    }

    @Test
    public void resetReSavesSizeAndLocation() {
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<Dimension> sizeAtSave = new AtomicReference<>();
        AtomicReference<Point> locAtSave = new AtomicReference<>();
        AtomicReference<OverlayPosition> posAtSave = new AtomicReference<>();
        AnchorCustomizerPlugin p = pluginWithFakes(resets, saves, sizeAtSave, locAtSave, posAtSave);

        FakeOverlay o = new FakeOverlay(new Dimension(70, 40));
        o.setPreferredSize(new Dimension(70, 40));

        p.applyAnchorPosition(o, "infobox", new Point(300, 200));

        assertEquals(1, resets.get());
        // The save must run AFTER the location write and carry the restored size —
        // otherwise resetOverlay's internal save leaves preferredSize unset in config
        // and the size is lost next session (issue #25).
        assertEquals(new Dimension(70, 40), sizeAtSave.get());
        assertEquals(new Point(300, 200), locAtSave.get());
        assertEquals(new Dimension(70, 40), o.getPreferredSize());
        assertEquals(new Point(300, 200), o.getPreferredLocation());
        // Reset + location + layer policy covered by exactly one save.
        assertEquals(1, saves.get());
    }

    @Test
    public void drawBelowInterfacesSavesOnceWithOwnPosition() {
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<Dimension> sizeAtSave = new AtomicReference<>();
        AtomicReference<Point> locAtSave = new AtomicReference<>();
        AtomicReference<OverlayPosition> posAtSave = new AtomicReference<>();
        AnchorCustomizerPlugin p = pluginWithFakes(resets, saves, sizeAtSave, locAtSave, posAtSave);
        ((MemConfig) p.config).drawAbove = false;

        FakeOverlay o = new FakeOverlay(new Dimension(70, 40));
        p.applyAnchorPosition(o, "infobox", new Point(300, 200));

        // The reset nulls preferredPosition; the layer policy must restore the overlay's
        // own position and a SINGLE save must persist it — a save before the policy
        // (or two saves) would strand preferredPosition=null in config (issue #19/#25).
        assertEquals(1, resets.get());
        assertEquals(1, saves.get());
        assertEquals(o.getPosition(), posAtSave.get());
        assertEquals(o.getPosition(), o.getPreferredPosition());
    }

    @Test
    public void alreadyNormalizedOverlayIsNotResetAgain() {
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<Dimension> sizeAtSave = new AtomicReference<>();
        AtomicReference<Point> locAtSave = new AtomicReference<>();
        AtomicReference<OverlayPosition> posAtSave = new AtomicReference<>();
        AnchorCustomizerPlugin p = pluginWithFakes(resets, saves, sizeAtSave, locAtSave, posAtSave);

        FakeOverlay o = new FakeOverlay(new Dimension(70, 40));
        o.setPreferredSize(new Dimension(70, 40));

        p.applyAnchorPosition(o, "infobox", new Point(300, 200));
        p.applyAnchorPosition(o, "infobox", new Point(300, 200));

        assertEquals("already-normalized overlay must not be reset a second time", 1, resets.get());
    }

    @Test
    public void mirrorFlipsLeadingEdgeOffset() {
        assertEquals(70, AnchorCustomizerPlugin.mirror(0, 100, 30));
        assertEquals(0, AnchorCustomizerPlugin.mirror(70, 100, 30));
        assertEquals(50, AnchorCustomizerPlugin.mirror(20, 100, 30));
    }

    @Test
    public void fillDirectionLabelsFollowAxis() {
        assertEquals("Left to right", AnchorFillDirection.FORWARD.label(AnchorStacking.FILL_HORIZONTAL));
        assertEquals("Right to left", AnchorFillDirection.REVERSE.label(AnchorStacking.FILL_HORIZONTAL));
        assertEquals("Top to bottom", AnchorFillDirection.FORWARD.label(AnchorStacking.FILL_VERTICAL));
        assertEquals("Bottom to top", AnchorFillDirection.REVERSE.label(AnchorStacking.FILL_VERTICAL));
        // Wrap direction reads along the perpendicular axis.
        assertEquals("Top to bottom", AnchorFillDirection.FORWARD.wrapLabel(AnchorStacking.FILL_HORIZONTAL));
        assertEquals("Bottom to top", AnchorFillDirection.REVERSE.wrapLabel(AnchorStacking.FILL_HORIZONTAL));
        assertEquals("Left to right", AnchorFillDirection.FORWARD.wrapLabel(AnchorStacking.FILL_VERTICAL));
        assertEquals("Right to left", AnchorFillDirection.REVERSE.wrapLabel(AnchorStacking.FILL_VERTICAL));
        assertTrue(AnchorFillDirection.appliesTo(AnchorStacking.FILL_HORIZONTAL));
        assertTrue(AnchorFillDirection.appliesTo(AnchorStacking.FILL_VERTICAL));
        assertTrue(!AnchorFillDirection.appliesTo(AnchorStacking.VERTICAL));
        assertTrue(!AnchorFillDirection.appliesTo(AnchorStacking.HORIZONTAL));
    }

    @Test
    public void regionJsonLegacyAndReverseRoundTrip() {
        Gson gson = new Gson();
        java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.List<AnchorRegion>>() {
        }.getType();

        // Legacy data: no fillDirection, plus a stale "fillOrigin" key from the
        // abandoned corner-based design — must deserialize cleanly; the field
        // initializer (and normalizeRegions' null-check) leaves it FORWARD.
        java.util.List<AnchorRegion> legacy = gson.fromJson(
                "[{\"id\":1,\"name\":\"Box 1\",\"x\":10,\"y\":20,\"width\":100,\"height\":100,"
                        + "\"constraint\":\"TOP_LEFT\",\"alignment\":\"CENTER\",\"stacking\":\"FILL_HORIZONTAL\","
                        + "\"locked\":false,\"fillOrigin\":\"TOP_RIGHT\"}]", t);
        assertEquals(1, legacy.size());
        assertEquals(AnchorFillDirection.FORWARD, legacy.get(0).getFillDirection());
        assertEquals(AnchorFillDirection.FORWARD, legacy.get(0).getWrapDirection());

        // REVERSE round-trips through serialization.
        AnchorRegion r = new AnchorRegion(2, "Box 2", 0, 0, 100, 100,
                AnchorConstraint.TOP_LEFT, AnchorAlignment.CENTER, AnchorStacking.FILL_VERTICAL,
                false, 0, 0, 765, 503, false, AnchorFillDirection.REVERSE, AnchorFillDirection.REVERSE, false, 0);
        java.util.List<AnchorRegion> back = gson.fromJson(
                gson.toJson(java.util.Collections.singletonList(r)), t);
        assertEquals(AnchorFillDirection.REVERSE, back.get(0).getFillDirection());
        assertEquals(AnchorFillDirection.REVERSE, back.get(0).getWrapDirection());
    }

    @Test
    public void mirrorPlacesRowsFlushWithFarEdge() {
        // 100-tall block, two 30-tall rows at y=0 and y=34 (4px padding): reverse-wrap
        // mirrors each row's position — row 0 to y=70, row 1 to y=36 — so mirrored
        // row 1's bottom edge (66) sits exactly 4px above mirrored row 0's top (70).
        assertEquals(70, AnchorCustomizerPlugin.mirror(0, 100, 30));
        assertEquals(36, AnchorCustomizerPlugin.mirror(34, 100, 30));
    }

    @Test
    public void chatPinShiftTracksChatTop() {
        assertEquals(-20, AnchorCustomizerPlugin.chatPinnedYShift(500, 480));
        assertEquals(0, AnchorCustomizerPlugin.chatPinnedYShift(400, 400));
        assertEquals(15, AnchorCustomizerPlugin.chatPinnedYShift(400, 415));
    }

    /** Minimal in-memory config; default methods cover everything we don't override. */
    private static final class MemConfig implements AnchorCustomizerConfig {
        boolean drawAbove = true;
        String regions = "[]";
        String profiles = "{}";
        String assignments = "{}";
        String order = "{}";

        @Override public String regionJson() { return regions; }
        @Override public void setRegionJson(String json) { regions = json; }
        @Override public String regionProfilesJson() { return profiles; }
        @Override public void setRegionProfilesJson(String json) { profiles = json; }
        @Override public String overlayAssignmentsJson() { return assignments; }
        @Override public void setOverlayAssignmentsJson(String json) { assignments = json; }
        @Override public String overlayOrderJson() { return order; }
        @Override public void setOverlayOrderJson(String json) { order = json; }
        @Override public void setSelectedRegionId(int id) { }
        @Override public boolean drawAboveInterfaces() { return drawAbove; }
    }

    @Test
    public void exportImportRoundTripPreservesBlobs() {
        Gson gson = new Gson();
        MemConfig cfg = new MemConfig();
        cfg.regions = gson.toJson(java.util.Collections.singletonList(
                new AnchorRegion(1, "Box 1", 10, 20, 100, 100,
                        AnchorConstraint.TOP_LEFT, AnchorAlignment.CENTER,
                        AnchorStacking.VERTICAL, false, 10, 20, 765, 503,
                        false, AnchorFillDirection.FORWARD, AnchorFillDirection.FORWARD, false, 0)));
        cfg.profiles = "{\"fixed\":[]}";
        cfg.assignments = "{\"someOverlay\":1}";
        cfg.order = "{\"someOverlay\":0}";

        String expectedRegions = canonical(gson, cfg.regions);
        String exported = AnchorCustomizerPlugin.buildExportJson(gson, cfg);

        // Simulate the user having changed everything since the export.
        cfg.regions = "[]";
        cfg.profiles = "{}";
        cfg.assignments = "{}";
        cfg.order = "{}";

        AnchorCustomizerPlugin p = new AnchorCustomizerPlugin();
        p.gson = gson;
        p.config = cfg;
        p.overlayScanHandler = pred -> { /* no overlays in test */ };

        assertNull(p.checkLayoutImport(exported));
        p.applyLayoutImportOnClientThread(gson.fromJson(exported, JsonElement.class).getAsJsonObject());

        // All four blobs are restored (canonical Gson form — the inputs above were
        // already gson-serialized, so round-tripping is byte-identical).
        assertEquals(expectedRegions, cfg.regions);
        assertEquals("{\"fixed\":[]}", cfg.profiles);
        assertEquals("{\"someOverlay\":1}", cfg.assignments);
        assertEquals("{\"someOverlay\":0}", cfg.order);
    }

    private static String canonical(Gson gson, String json) {
        return gson.toJson(gson.fromJson(json, JsonElement.class));
    }

    // ---- Drag-race fix (origin normalization vs renderer drag) ---------------------

    private static MouseEvent leftPress() {
        return new MouseEvent(new java.awt.Label(), MouseEvent.MOUSE_PRESSED, 0,
                MouseEvent.BUTTON1_DOWN_MASK, 0, 0, 1, false, MouseEvent.BUTTON1);
    }

    private static MouseEvent leftRelease() {
        return new MouseEvent(new java.awt.Label(), MouseEvent.MOUSE_RELEASED, 0,
                0, 0, 0, 1, false, MouseEvent.BUTTON1);
    }

    private static MouseEvent rightPress() {
        return new MouseEvent(new java.awt.Label(), MouseEvent.MOUSE_PRESSED, 0,
                MouseEvent.BUTTON3_DOWN_MASK, 0, 0, 1, false, MouseEvent.BUTTON3);
    }

    @Test
    public void buttonObserverTracksLeftButtonWithoutConsuming() {
        MouseButtonObserver obs = new MouseButtonObserver();

        MouseEvent press = leftPress();
        assertTrue(obs.mousePressed(press) == press);
        assertTrue(!press.isConsumed());
        assertTrue(obs.isLeftButtonDown());

        MouseEvent release = leftRelease();
        assertTrue(obs.mouseReleased(release) == release);
        assertTrue(!release.isConsumed());
        assertTrue(!obs.isLeftButtonDown());
        assertTrue(obs.getLastLeftReleaseMs() > 0);

        // Right button never touches the left-button state.
        MouseEvent rpress = rightPress();
        assertTrue(obs.mousePressed(rpress) == rpress);
        assertTrue(!obs.isLeftButtonDown());
    }

    @Test
    public void manualDragStartsFromAbsoluteOriginAndKeepsSize() {
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<Dimension> sizeAtSave = new AtomicReference<>();
        AtomicReference<Point> locAtSave = new AtomicReference<>();
        AtomicReference<OverlayPosition> posAtSave = new AtomicReference<>();
        AnchorCustomizerPlugin p = pluginWithFakes(resets, saves, sizeAtSave, locAtSave, posAtSave);

        // Unanchored overlay left on a RIGHT/CENTER origin: preferredLocation is relative (-483, 9).
        FakeOverlay o = new FakeOverlay(new Dimension(46, 23));
        o.setPreferredSize(new Dimension(46, 23));
        o.setPreferredLocation(new Point(-483, 9));

        p.prepareForManualDrag(o, new Point(600, 290));

        // Origin reset (so later absolute writes are absolute), size kept, and the absolute
        // start position persisted together with the size.
        assertEquals(1, resets.get());
        assertEquals(new Point(600, 290), o.getPreferredLocation());
        assertEquals(new Dimension(46, 23), o.getPreferredSize());
        assertEquals(new Dimension(46, 23), sizeAtSave.get());
        assertEquals(new Point(600, 290), locAtSave.get());
    }

    @Test
    public void separationPushesNeighbourClearAwayFromHeld() {
        Rectangle held = new Rectangle(40, 0, 30, 30);
        // Neighbour to the right overlapping by 10 -> pushed right to held.right + gap.
        Point p = AnchorCustomizerPlugin.separation(new Rectangle(60, 0, 30, 30), held, 2);
        assertEquals(new Point(12, 0), p);
        // Neighbour to the left overlapping by 5 -> pushed left.
        p = AnchorCustomizerPlugin.separation(new Rectangle(15, 0, 30, 30), held, 2);
        assertEquals(new Point(-7, 0), p);
        // Neighbour just below with a small vertical overlap -> pushed down (shorter move).
        p = AnchorCustomizerPlugin.separation(new Rectangle(40, 25, 30, 30), held, 2);
        assertEquals(new Point(0, 7), p);
        // Result never intersects the held rectangle.
        Rectangle n = new Rectangle(60, 0, 30, 30);
        n.translate(AnchorCustomizerPlugin.separation(n, held, 2).x, 0);
        assertTrue(!n.intersects(held));
    }

    @Test
    public void buttonObserverTracksOverlayGrabOnlyWhilePressHeld() {
        MouseButtonObserver obs = new MouseButtonObserver();

        // Press that doesn't land on UI -> not grabbed.
        obs.mousePressed(leftPress());
        assertTrue(!obs.isOverlayGrabbed());
        obs.mouseReleased(leftRelease());

        // Press on UI -> grabbed until the release, regardless of movement.
        FakeOverlay grabbed = new FakeOverlay(new Dimension(10, 10));
        obs.grabTest = e -> grabbed;
        obs.mousePressed(leftPress());
        assertTrue(obs.isOverlayGrabbed());
        assertTrue(obs.getGrabbedOverlay() == grabbed);
        obs.mouseReleased(leftRelease());
        assertTrue(!obs.isOverlayGrabbed());
        assertNull(obs.getGrabbedOverlay());
    }

    @Test
    public void whileDraggingVisibilityFollowsHeldUiOnly() {
        AnchorCustomizerPlugin p = new AnchorCustomizerPlugin();
        assertTrue(!p.isOverlayHeld());
        p.buttonObserver.overlayGrabbed = true;
        assertTrue(p.isOverlayHeld());
        p.buttonObserver.overlayGrabbed = false;
        // A plain left press (not on UI) is not "holding UI".
        p.buttonObserver.leftButtonDown = true;
        assertTrue(!p.isOverlayHeld());
    }

    @Test
    public void precedesUsesReadingOrderForWrappedFillRows() {
        // Row 0: item at (100,0) 50x20. Point on row 1 at a SMALLER x must still come after it.
        Rectangle row0 = new Rectangle(100, 0, 50, 20);
        assertTrue(AnchorCustomizerPlugin.precedes(row0, 10, 30, AnchorStacking.FILL_HORIZONTAL, false, false));
        // Same row, left of it -> it does not precede.
        assertTrue(!AnchorCustomizerPlugin.precedes(row0, 10, 10, AnchorStacking.FILL_HORIZONTAL, false, false));
        // Same row, right of it -> precedes; reversed fill flips that.
        assertTrue(AnchorCustomizerPlugin.precedes(row0, 200, 10, AnchorStacking.FILL_HORIZONTAL, false, false));
        assertTrue(!AnchorCustomizerPlugin.precedes(row0, 200, 10, AnchorStacking.FILL_HORIZONTAL, true, false));
        // Reversed wrap: rows grow upward, so a point on a lower row comes BEFORE.
        assertTrue(!AnchorCustomizerPlugin.precedes(row0, 10, 30, AnchorStacking.FILL_HORIZONTAL, false, true));

        // Fill-vertical mirrors on the other axis.
        Rectangle col0 = new Rectangle(0, 100, 20, 50);
        assertTrue(AnchorCustomizerPlugin.precedes(col0, 30, 10, AnchorStacking.FILL_VERTICAL, false, false));
        assertTrue(!AnchorCustomizerPlugin.precedes(col0, 10, 10, AnchorStacking.FILL_VERTICAL, false, false));

        // Plain stacks compare along the main axis only.
        Rectangle v = new Rectangle(0, 0, 20, 20);
        assertTrue(AnchorCustomizerPlugin.precedes(v, 0, 50, AnchorStacking.VERTICAL, false, false));
        assertTrue(!AnchorCustomizerPlugin.precedes(v, 50, 5, AnchorStacking.VERTICAL, false, false));
        assertTrue(AnchorCustomizerPlugin.precedes(v, 50, 5, AnchorStacking.HORIZONTAL, false, false));
    }

    @Test
    public void shouldDeferApplyOnlyForUnnormalizedDuringExternalDrag() {
        AtomicInteger resets = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<Dimension> sizeAtSave = new AtomicReference<>();
        AtomicReference<Point> locAtSave = new AtomicReference<>();
        AtomicReference<OverlayPosition> posAtSave = new AtomicReference<>();
        AnchorCustomizerPlugin p = pluginWithFakes(resets, saves, sizeAtSave, locAtSave, posAtSave);

        // No mouse activity -> never defer.
        assertTrue(!p.shouldDeferApply("ov"));

        // Left button down (possible renderer-side drag) + un-normalized -> defer.
        p.buttonObserver.leftButtonDown = true;
        assertTrue(p.shouldDeferApply("ov"));

        // Normalized overlays are safe to write even mid external drag.
        FakeOverlay o = new FakeOverlay(new Dimension(70, 40));
        p.applyAnchorPosition(o, "ov", new Point(10, 10));
        assertTrue(!p.shouldDeferApply("ov"));

        // Button released long ago -> no drag possible -> no defer.
        p.buttonObserver.leftButtonDown = false;
        p.buttonObserver.lastLeftReleaseMs = System.currentTimeMillis() - 1000;
        assertTrue(!p.shouldDeferApply("other"));
    }

    @Test
    public void importValidationRejectsBadInput() {
        AnchorCustomizerPlugin p = new AnchorCustomizerPlugin();
        p.gson = new Gson();
        assertTrue(p.checkLayoutImport(null) != null);
        assertTrue(p.checkLayoutImport("") != null);
        assertTrue(p.checkLayoutImport("not json") != null);
        assertTrue(p.checkLayoutImport("[1,2]") != null);
        assertTrue(p.checkLayoutImport("{\"format\":\"other\",\"version\":1}") != null);
        assertTrue(p.checkLayoutImport("{\"format\":\"custom-ui-anchors\",\"version\":2}") != null);
        assertNull(p.checkLayoutImport("{\"format\":\"custom-ui-anchors\",\"version\":1}"));
    }
}
