/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.event.MouseEvent;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import lombok.Getter;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.overlay.Overlay;

/**
 * Passive observer of the left mouse button. Registered at index 0 in the
 * {@link net.runelite.client.input.MouseManager} chain so it runs BEFORE RuneLite's
 * OverlayRenderer — the renderer consumes presses it grabs, and consumed events never
 * reach later listeners, so only an early observer reliably sees the button state.
 *
 * The plugin uses this to detect that a RuneLite-renderer overlay drag may be in
 * progress (left button down while neither an anchor nor a plugin-initiated overlay
 * drag is active), because the drag can reassign overlay origins on the AWT thread
 * mid-flight.
 */
public class MouseButtonObserver extends MouseAdapter {

    // Package-private (not private) so unit tests can poke the state directly.
    @Getter
    volatile boolean leftButtonDown = false;

    // Timestamp of the most recent left-button release, so a just-finished release whose
    // AWT event chain is still unwinding still counts as "a drag may be settling".
    @Getter
    volatile long lastLeftReleaseMs = 0L;

    // True while the left button is held after a press that grabbed a capturable overlay
    // (drag hotkey held + pointer on movable UI) — i.e. the UI is physically in the user's
    // hand. Drives the "While dragging" anchor visibility mode.
    @Getter
    volatile boolean overlayGrabbed = false;

    // The overlay that press grabbed (null when none).
    @Getter
    volatile Overlay grabbedOverlay = null;

    // The overlay grabbed by the most recent left press; NOT cleared on release, so moves that
    // land in the release-settle window can still be attributed to it.
    @Getter
    volatile Overlay lastGrabbedOverlay = null;

    // Resolves at press time which overlay (if any) the press grabs; set by the plugin.
    Function<MouseEvent, Overlay> grabTest = e -> null;

    @Override
    public MouseEvent mousePressed(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e)) {
            leftButtonDown = true;
            grabbedOverlay = grabTest.apply(e);
            overlayGrabbed = grabbedOverlay != null;
            lastGrabbedOverlay = grabbedOverlay;
        }
        return e;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e)) {
            leftButtonDown = false;
            overlayGrabbed = false;
            grabbedOverlay = null;
            lastLeftReleaseMs = System.currentTimeMillis();
        }
        return e;
    }
}
