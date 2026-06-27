/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.client.ui.overlay.Overlay;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests the absolute positioning helpers used by Custom UI Anchors under RuneLite 1.12.31
 * ("configurable overlay origins").
 *
 * The plugin forces every managed overlay back to a LEFT/TOP origin (via
 * {@code OverlayManager.resetOverlay}, in {@code normalizeOrigin}), so its preferredLocation is a
 * plain absolute point and getBounds() reflects it. {@link AnchorCustomizerPlugin#setAbsoluteLocation}
 * must therefore be a simple, idempotent absolute write — NOT a getBounds()-delta loop. (The delta
 * loop accumulated against the renderer's edge clamp and drove overlays off their anchors; these
 * tests guard against reintroducing it.)
 */
public class OverlayOriginPositioningTest {

    /** A LEFT/TOP overlay whose rendered bounds equal its preferredLocation; counts location writes. */
    private static final class FakeOverlay extends Overlay {
        private final Dimension size;
        int setLocationCalls = 0;

        FakeOverlay(Dimension size) {
            this.size = size;
        }

        @Override
        public Dimension render(Graphics2D graphics) {
            return null;
        }

        @Override
        public void setPreferredLocation(Point preferredLocation) {
            setLocationCalls++;
            super.setPreferredLocation(preferredLocation);
        }

        @Override
        public Rectangle getBounds() {
            Point p = getPreferredLocation();
            if (p == null) {
                return new Rectangle(); // empty: not yet rendered
            }
            return new Rectangle(p.x, p.y, size.width, size.height);
        }
    }

    @Test
    public void writesAbsolutePoint() {
        FakeOverlay o = new FakeOverlay(new Dimension(120, 20));
        o.setPreferredLocation(new Point(10, 10));

        AnchorCustomizerPlugin.setAbsoluteLocation(o, new Point(640, 360));

        assertEquals(new Point(640, 360), o.getPreferredLocation());
    }

    @Test
    public void seedsWhenLocationNull() {
        FakeOverlay o = new FakeOverlay(new Dimension(100, 16));

        AnchorCustomizerPlugin.setAbsoluteLocation(o, new Point(200, 50));

        assertEquals(new Point(200, 50), o.getPreferredLocation());
    }

    @Test
    public void doesNotRewriteWhenAlreadyOnTarget() {
        FakeOverlay o = new FakeOverlay(new Dimension(100, 16));
        o.setPreferredLocation(new Point(300, 120));
        int callsAfterSetup = o.setLocationCalls;

        AnchorCustomizerPlugin.setAbsoluteLocation(o, new Point(300, 120));

        assertEquals("must not rewrite preferredLocation when already on target",
            callsAfterSetup, o.setLocationCalls);
        assertEquals(new Point(300, 120), o.getPreferredLocation());
    }

    @Test
    public void absoluteTopLeftUsesRenderedBounds() {
        FakeOverlay o = new FakeOverlay(new Dimension(100, 16));
        o.setPreferredLocation(new Point(700, 12));

        assertEquals(new Point(700, 12), AnchorCustomizerPlugin.absoluteTopLeftOf(o));
    }

    @Test
    public void absoluteTopLeftFallsBackToPreferredBeforeRender() {
        FakeOverlay o = new FakeOverlay(new Dimension(0, 0)); // empty bounds → not rendered yet
        o.setPreferredLocation(new Point(42, 7));

        assertEquals(new Point(42, 7), AnchorCustomizerPlugin.absoluteTopLeftOf(o));
    }

    @Test
    public void absoluteTopLeftNullWhenNoPosition() {
        FakeOverlay o = new FakeOverlay(new Dimension(0, 0));

        assertNull(AnchorCustomizerPlugin.absoluteTopLeftOf(o));
    }
}
