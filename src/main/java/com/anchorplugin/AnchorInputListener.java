/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.overlay.Overlay;

@Slf4j
public class AnchorInputListener implements MouseListener {
    private static final int RESIZE_HANDLE_SIZE = 10;

    private final ClientUI clientUI;
    private final AnchorCustomizerPlugin plugin;

    @Getter
    private boolean isDragging = false;

    @Getter
    private AnchorRegion draggedAnchor = null;

    private Point dragStartPoint = null;
    private Rectangle originalBounds = null;

    // Overlay drag state. When the user Alt-presses directly on a movable RuneLite overlay
    // inside a region, we move that overlay ourselves (rather than the anchor) so the rule
    // "pointer on the UI -> the UI moves" holds even when RuneLite's OverlayRenderer fails to
    // grab it (observed with 117 HD). Updating the overlay's preferredLocation is exactly what
    // OverlayRenderer's drag does, so the plugin's existing external-move detection backs the
    // snap off and runs capture/release on drop automatically.
    private Overlay draggedOverlay = null;
    private int overlayGrabDx = 0;
    private int overlayGrabDy = 0;

    // Resize state
    private boolean isResizing = false;
    private int resizeDirection = 0; // 0=None, see below for flags

    // Resize Direction Flags
    private static final int NORTH = 1;
    private static final int SOUTH = 2;
    private static final int EAST = 4;
    private static final int WEST = 8;

    @Inject
    public AnchorInputListener(ClientUI clientUI, AnchorCustomizerPlugin plugin) {
        this.clientUI = clientUI;
        this.plugin = plugin;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent e) {
        // Resolve the edit hotkey from live key state + event modifiers, not the tracked
        // flag, so a desynced flag (focus blips around the 117 HD canvas, a consumed
        // non-modifier hotkey press, a missed release) can't silently block drags.
        final boolean hotkeyActive = plugin.isDragHotkeyActive(e);

        AnchorCustomizerConfig cfg = plugin.getConfig();
        if (cfg != null && cfg.debugLogging() && SwingUtilities.isLeftMouseButton(e)
                && (hotkeyActive || e.isAltDown() || plugin.isDragKeyHeld())) {
            Point dp = e.getPoint();
            Overlay dbgOverlay = plugin.getMovableOverlayAt(dp);
            log.info("[anchor-debug] mousePressed hotkeyLive={} trackedHeld={} overlaysVisible={} altDown={} at=({},{}) topRegion={} overlay={}",
                    hotkeyActive, plugin.isDragKeyHeld(), plugin.isOverlaysVisible(), e.isAltDown(),
                    dp.x, dp.y, regionLabel(pickTopAnchorAt(dp)), dbgOverlay == null ? "none" : dbgOverlay.getName());
        }

        if (!hotkeyActive || SwingUtilities.isRightMouseButton(e)) {
            return e;
        }

        Point mousePos = e.getPoint();
        AnchorRegion region = pickAnchorByClickCount(mousePos, e.getClickCount());
        if (region == null) {
            return e;
        }

        // Pointer-on-UI rule: if a movable RuneLite overlay sits under the cursor, drag THAT
        // overlay, never the anchor. We only get here when OverlayRenderer did not already
        // grab the press (it runs first in the MouseManager chain and consumes when it does),
        // so handling it ourselves is the fallback that keeps overlay dragging working even
        // when OverlayRenderer can't grab it (observed with 117 HD). We consume either way so
        // the press can never fall through to the game and rotate the camera.
        Overlay overlay = plugin.getMovableOverlayAt(mousePos);
        if (overlay != null) {
            beginOverlayDrag(overlay, mousePos);
            plugin.selectAnchor(region);
            e.consume();
            return e;
        }

        // Empty region area: drag/resize the anchor itself.
        Rectangle bounds = region.getBounds();
        isDragging = true;
        draggedAnchor = region;
        dragStartPoint = mousePos;
        originalBounds = new Rectangle(bounds);

        resizeDirection = getResizeDirection(bounds, mousePos);
        isResizing = resizeDirection != 0;

        // Reflect the picked anchor in the panel so double-click-drag visibly picks the
        // one beneath, not just silently drag it.
        plugin.selectAnchor(region);

        e.consume();
        return e;
    }

    /**
     * Start dragging a movable overlay ourselves. We record the grab offset from the overlay's
     * current rendered top-left so the overlay tracks the cursor 1:1, and detach it from any
     * snap corner so the renderer honours its preferredLocation while we move it. The plugin's
     * external-move detection then backs the snap off and commits capture/release on drop.
     */
    private void beginOverlayDrag(Overlay overlay, Point mousePos) {
        draggedOverlay = overlay;
        Rectangle b = overlay.getBounds();
        Point loc = (b != null && !b.isEmpty()) ? new Point(b.x, b.y) : overlay.getPreferredLocation();
        if (loc == null) {
            loc = new Point(mousePos);
        }
        overlayGrabDx = mousePos.x - loc.x;
        overlayGrabDy = mousePos.y - loc.y;
        if (overlay.getPreferredPosition() != null) {
            overlay.setPreferredPosition(null);
        }
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent e) {
        // Overlay drag (pointer-on-UI): move the overlay's preferred location to follow the
        // cursor. The plugin's external-move detection sees this, stops snapping the overlay,
        // and commits capture/release once the drag settles — same path as an OverlayRenderer
        // drag, just initiated by us so it works regardless of OverlayRenderer's hover state.
        if (draggedOverlay != null) {
            Point p = e.getPoint();
            // The overlay was normalized to a LEFT/TOP origin on capture, so preferredLocation is
            // absolute; write the cursor-relative target directly. On drop the snap pass re-normalizes
            // (if RuneLite changed the origin during this drag) and reasserts the anchored position.
            draggedOverlay.setPreferredLocation(new Point(p.x - overlayGrabDx, p.y - overlayGrabDy));
            plugin.requestSnap();
            e.consume();
            return e;
        }

        // isDragging is only set by mousePressed after the hotkey gate, so it is the
        // authoritative signal here. Don't re-check the (possibly-desynced) hotkey/panel
        // state mid-drag, or a transient flag flip could abort a legitimate drag.
        if (!isDragging || draggedAnchor == null) {
            return e;
        }

        Point currentPos = e.getPoint();
        int dx = currentPos.x - dragStartPoint.x;
        int dy = currentPos.y - dragStartPoint.y;

        if (isResizing) {
            handleResize(dx, dy);
        } else {
            handleMove(dx, dy);
        }

        // Ask the next client tick to snap overlays; avoids a synchronous cross-thread
        // snap pass on every mouse-move event.
        plugin.requestSnap();

        // Live update for panel properties (lightweight; no JList mutation)
        plugin.refreshSelectedAnchorProperties(draggedAnchor);

        e.consume();
        return e;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent e) {
        final boolean hotkeyActive = plugin.isDragHotkeyActive(e);

        // Finish an overlay drag. The overlay's final preferredLocation is already set; the
        // plugin's next snap pass treats it as "hot" and commits the capture (into whatever
        // region now contains it) or release (dropped in empty space). Just clear our state,
        // nudge a snap, and consume so the release can't reach the game.
        if (draggedOverlay != null) {
            draggedOverlay = null;
            plugin.requestSnap();
            e.consume();
            if (!hotkeyActive) {
                resetCursorToDefault();
            }
            return e;
        }

        if (!isDragging && !plugin.isOverlaysVisible() && !hotkeyActive)
            return e;
        if (isDragging) {
            // Capture the dragged region before clearing state so we can rebaseline
            // its origin to the new (x, y) the user just confirmed. The recompute path
            // in onClientTick was skipping this region while isDragging was true; the
            // rebaseline below makes the next tick's recompute a no-op (origin matches
            // current x/y), then constraint-driven shifts kick in normally on resize.
            AnchorRegion confirmed = draggedAnchor;
            isDragging = false;
            draggedAnchor = null;
            isResizing = false;
            resizeDirection = 0;
            if (confirmed != null) {
                plugin.rebaselineOriginFromAnyThread(confirmed);
            }
            // Fix R1: marshal the persistence write onto the client thread so it can't
            // race against create/delete/load of anchorRegions from other paths.
            plugin.saveRegionsFromAnyThread();
            e.consume();
        }
        // If the hotkey is no longer held (e.g. the user released Alt mid-drag and then
        // let go of the mouse without moving it), mouseMoved won't fire to clear the
        // move/resize cursor — reset it here so it can't get stuck.
        if (!hotkeyActive) {
            resetCursorToDefault();
        }
        return e;
    }

    /**
     * Restore the cursor to RuneLite's current baseline via {@link ClientUI}. When the Custom
     * Cursor plugin is active this is the user's custom cursor (ClientUI tracks it as the
     * "default"); otherwise it is the system arrow. Routing through ClientUI — exactly like
     * RuneLite's own OverlayRenderer — and never setting a cursor on the game canvas directly
     * is what stops us from stranding the custom cursor (GitHub: custom cursor disabled on Alt).
     */
    public void resetCursorToDefault() {
        clientUI.setCursor(clientUI.getDefaultCursor());
    }

    /**
     * Abort an in-progress drag/resize, reverting the anchor to the geometry it had when the
     * Alt+press started ({@link #originalBounds}). Invoked when the edit hotkey is released
     * or focus is lost mid-drag — the user's intent is "cancel", not "drop here", so we
     * deliberately do NOT rebaseline or persist the aborted position (unlike a normal
     * {@link #mouseReleased}). A snap is requested so any overlays reflow back to the
     * restored anchor. No-op when nothing is being dragged.
     */
    public void cancelDrag() {
        // Drop any in-progress overlay drag where it currently sits — the plugin's snap pass
        // captures/releases it. We intentionally don't revert overlay position on cancel.
        if (draggedOverlay != null) {
            draggedOverlay = null;
            plugin.requestSnap();
        }
        if (!isDragging || draggedAnchor == null) {
            return;
        }
        AnchorRegion cancelled = draggedAnchor;
        if (originalBounds != null) {
            cancelled.setX(originalBounds.x);
            cancelled.setY(originalBounds.y);
            cancelled.setWidth(originalBounds.width);
            cancelled.setHeight(originalBounds.height);
        }
        isDragging = false;
        draggedAnchor = null;
        isResizing = false;
        resizeDirection = 0;
        plugin.requestSnap();
        plugin.refreshSelectedAnchorProperties(cancelled);
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent e) {
        final boolean hotkeyActive = plugin.isDragHotkeyActive(e);
        if (!plugin.isOverlaysVisible() && !hotkeyActive)
            return e;

        // Click-through for overlapping anchors:
        //   click count 1 → topmost
        //   click count 2 → the one beneath
        //   click count 3 → beneath that, … wraps around after the bottom.
        // Consume events only when Alt is held (explicit edit mode); a non-Alt click
        // still selects in the panel but is allowed to pass through to the game.
        Point mousePos = e.getPoint();
        AnchorRegion picked = pickAnchorByClickCount(mousePos, e.getClickCount());
        if (picked != null) {
            plugin.selectAnchor(picked);
            // In edit mode, consume Alt-clicks inside a region (even on an overlay) so they
            // can't fall through to the game / rotate the camera. Dragging the overlay vs the
            // anchor is decided in mousePressed; the click itself only updates selection.
            if (hotkeyActive) {
                e.consume();
            }
        } else if (hotkeyActive) {
            // Only deselect if Alt is held (explicit edit intention); clicking into
            // empty space without Alt should not drop the panel selection.
            plugin.selectAnchor(null);
        }

        return e;
    }

    /**
     * Collect every anchor region whose bounds (or resize handle zone) contain {@code p},
     * ordered from topmost to bottommost. Layering follows the panel list order: index 0
     * is the TOP of the stack ({@link AnchorCustomizerOverlay} draws the list in reverse
     * so the first element renders last / on top), so we iterate the list forward.
     *
     * Locked regions are excluded entirely — they are click-through for anchor picking,
     * so hovering or clicking over a locked anchor falls through to whatever unlocked
     * anchor sits beneath (or to the game if there is none).
     */
    private List<AnchorRegion> pickAnchorsAt(Point p) {
        List<AnchorRegion> hits = new ArrayList<>();
        for (AnchorRegion r : plugin.getAnchorRegions()) {
            if (r.isLocked()) {
                continue;
            }
            Rectangle b = r.getBounds();
            if (b.contains(p) || isNearResizeHandle(b, p)) {
                hits.add(r);
            }
        }
        return hits;
    }

    /**
     * The anchor the user would interact with at {@code p}: the topmost unlocked region
     * under the point, or null. Used by {@link AnchorCustomizerOverlay} so hover visuals
     * (highlight + resize handles) light up only the region a click would actually hit.
     */
    public AnchorRegion pickTopAnchorAt(Point p) {
        return pickTopAnchorAt(p, plugin.getAnchorRegions());
    }

    /**
     * Snapshot-reusing variant for per-frame callers (the customizer overlay already
     * holds a region snapshot for drawing; re-snapshotting here every frame would just
     * double the allocations).
     */
    public AnchorRegion pickTopAnchorAt(Point p, List<AnchorRegion> regions) {
        for (AnchorRegion r : regions) {
            if (r.isLocked()) {
                continue;
            }
            Rectangle b = r.getBounds();
            if (b.contains(p) || isNearResizeHandle(b, p)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Pick the nth anchor at {@code p}, where n is derived from {@code clickCount}
     * (single-click = topmost, double-click = one beneath, etc.). Wraps around the
     * bottom so repeated clicks cycle through the overlap stack instead of getting
     * stuck.
     */
    private AnchorRegion pickAnchorByClickCount(Point p, int clickCount) {
        List<AnchorRegion> hits = pickAnchorsAt(p);
        if (hits.isEmpty()) return null;
        int safeCount = Math.max(1, clickCount);
        int idx = (safeCount - 1) % hits.size();
        return hits.get(idx);
    }

    @Override
    public MouseEvent mouseEntered(MouseEvent e) {
        return e;
    }

    @Override
    public MouseEvent mouseExited(MouseEvent e) {
        return e;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent e) {
        final boolean hotkeyActive = plugin.isDragHotkeyActive(e);
        if (!plugin.isOverlaysVisible() && !hotkeyActive) {
            return e;
        }
        if (!hotkeyActive) {
            // Not in edit mode: clear any stale move/resize cursor by restoring the ClientUI
            // baseline (the Custom Cursor plugin's cursor if one is set, else the arrow).
            clientUI.setCursor(clientUI.getDefaultCursor());
            return e;
        }

        // Over a movable overlay, leave the cursor to RuneLite's OverlayRenderer (which set it
        // just before/after us). It decides resize-vs-move from the cursor at press time, so if
        // we overwrote it with our anchor MOVE/resize cursor it would mis-pick resize and the
        // overlay wouldn't drag (the regression that broke dragging UI inside a region).
        if (plugin.isMovableOverlayAt(e.getPoint())) {
            return e;
        }

        // Update cursor based on hover. Use the pick path so the cursor reflects the
        // region a click would actually hit: topmost unlocked first, locked regions
        // click-through (no move/resize cursor over them). All cursor changes go through
        // ClientUI (never the game canvas) so we never strand the custom cursor.
        AnchorRegion hover = pickTopAnchorAt(e.getPoint());
        if (hover != null) {
            int dir = getResizeDirection(hover.getBounds(), e.getPoint());
            clientUI.setCursor(getCursorForDirection(dir));
            return e;
        }

        // Not over any region: restore the ClientUI baseline cursor.
        clientUI.setCursor(clientUI.getDefaultCursor());
        return e;
    }

    // Helper methods

    /** Null-safe label for an anchor region, used only by the opt-in debug logging. */
    private static String regionLabel(AnchorRegion r) {
        return r == null ? "none" : (r.getName() + "#" + r.getId());
    }

    private void handleMove(int dx, int dy) {
        draggedAnchor.setX(originalBounds.x + dx);
        draggedAnchor.setY(originalBounds.y + dy);
    }

    private void handleResize(int dx, int dy) {
        int x = originalBounds.x;
        int y = originalBounds.y;
        int w = originalBounds.width;
        int h = originalBounds.height;

        if ((resizeDirection & WEST) != 0) {
            x += dx;
            w -= dx;
        }
        if ((resizeDirection & EAST) != 0) {
            w += dx;
        }
        if ((resizeDirection & NORTH) != 0) {
            y += dy;
            h -= dy;
        }
        if ((resizeDirection & SOUTH) != 0) {
            h += dy;
        }

        // Minimum size check (10x10)
        if (w < 10) {
            if ((resizeDirection & WEST) != 0)
                x = originalBounds.x + originalBounds.width - 10;
            w = 10;
        }
        if (h < 10) {
            if ((resizeDirection & NORTH) != 0)
                y = originalBounds.y + originalBounds.height - 10;
            h = 10;
        }

        draggedAnchor.setX(x);
        draggedAnchor.setY(y);
        draggedAnchor.setWidth(w);
        draggedAnchor.setHeight(h);
    }

    private boolean isNearResizeHandle(Rectangle bounds, Point p) {
        // Simple bounding box expansion for hit testing handles
        Rectangle expanded = new Rectangle(
                bounds.x - RESIZE_HANDLE_SIZE / 2,
                bounds.y - RESIZE_HANDLE_SIZE / 2,
                bounds.width + RESIZE_HANDLE_SIZE,
                bounds.height + RESIZE_HANDLE_SIZE);
        return expanded.contains(p) && !new Rectangle(
                bounds.x + RESIZE_HANDLE_SIZE,
                bounds.y + RESIZE_HANDLE_SIZE,
                bounds.width - 2 * RESIZE_HANDLE_SIZE,
                bounds.height - 2 * RESIZE_HANDLE_SIZE).contains(p);
    }

    private int getResizeDirection(Rectangle bounds, Point p) {
        int dir = 0;
        int buffer = RESIZE_HANDLE_SIZE;

        // Check edges/corners logic
        boolean nearTop = p.y >= bounds.y - buffer && p.y <= bounds.y + buffer;
        boolean nearBottom = p.y >= bounds.y + bounds.height - buffer && p.y <= bounds.y + bounds.height + buffer;
        boolean nearLeft = p.x >= bounds.x - buffer && p.x <= bounds.x + buffer;
        boolean nearRight = p.x >= bounds.x + bounds.width - buffer && p.x <= bounds.x + bounds.width + buffer;

        if (nearTop)
            dir |= NORTH;
        if (nearBottom)
            dir |= SOUTH;
        if (nearLeft)
            dir |= WEST;
        if (nearRight)
            dir |= EAST;

        return dir;
    }

    private Cursor getCursorForDirection(int dir) {
        switch (dir) {
            case NORTH:
                return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
            case SOUTH:
                return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
            case EAST:
                return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
            case WEST:
                return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
            case NORTH | WEST:
                return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
            case NORTH | EAST:
                return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
            case SOUTH | WEST:
                return Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
            case SOUTH | EAST:
                return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
            default:
                return Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        }
    }
}
