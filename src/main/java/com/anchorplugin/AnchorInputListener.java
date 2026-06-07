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
import net.runelite.api.Client;
import net.runelite.client.input.MouseListener;

public class AnchorInputListener implements MouseListener {
    private static final int RESIZE_HANDLE_SIZE = 10;

    private final Client client;
    private final AnchorCustomizerPlugin plugin;

    @Getter
    private boolean isDragging = false;

    @Getter
    private AnchorRegion draggedAnchor = null;

    private Point dragStartPoint = null;
    private Rectangle originalBounds = null;

    // Resize state
    private boolean isResizing = false;
    private int resizeDirection = 0; // 0=None, see below for flags

    // Resize Direction Flags
    private static final int NORTH = 1;
    private static final int SOUTH = 2;
    private static final int EAST = 4;
    private static final int WEST = 8;

    @Inject
    public AnchorInputListener(Client client, AnchorCustomizerPlugin plugin) {
        this.client = client;
        this.plugin = plugin;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent e) {
        if (!plugin.isOverlaysVisible())
            return e;
        if (!plugin.isDragKeyHeld() || SwingUtilities.isRightMouseButton(e)) {
            return e;
        }

        Point mousePos = e.getPoint();
        AnchorRegion region = pickAnchorByClickCount(mousePos, e.getClickCount());
        if (region == null) {
            return e;
        }

        // Overlay passthrough: if the user Alt+pressed directly on top of a movable
        // RuneLite overlay (e.g. a detached InfoBoxOverlay rendered inside one of our
        // anchor regions), yield to RuneLite's OverlayRenderer so it can start its
        // own drag. Without this, we silently consume the event and the overlay
        // appears uninteractable. We still let the click reach mouseClicked for
        // selection, but only when the user is clicking empty space inside a region
        // do we proceed with anchor drag/resize.
        if (plugin.isMovableOverlayAt(mousePos)) {
            return e;
        }

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

    @Override
    public MouseEvent mouseDragged(MouseEvent e) {
        if (!plugin.isOverlaysVisible())
            return e;
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
        if (!plugin.isOverlaysVisible() && !isDragging)
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
        if (!plugin.isDragKeyHeld()) {
            resetCursorToDefault();
        }
        return e;
    }

    /**
     * Reset the canvas cursor to the default arrow. Safe to call from the AWT event thread
     * (the same thread the other cursor mutations in this class run on). No-op if the canvas
     * is briefly unavailable around client startup/shutdown.
     */
    public void resetCursorToDefault() {
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas != null) {
            canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }
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
        if (!plugin.isOverlaysVisible())
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
            // Overlay passthrough (mirrors mousePressed): if the click landed on a
            // movable overlay, do not consume — let RuneLite handle Alt+click on it.
            if (plugin.isDragKeyHeld() && !plugin.isMovableOverlayAt(mousePos)) {
                e.consume();
            }
        } else if (plugin.isDragKeyHeld()) {
            // Only deselect if Alt is held (explicit edit intention); clicking into
            // empty space without Alt should not drop the panel selection.
            plugin.selectAnchor(null);
        }

        return e;
    }

    /**
     * Collect every anchor region whose bounds (or resize handle zone) contain {@code p},
     * ordered from topmost to bottommost. "Topmost" matches {@link AnchorCustomizerOverlay}'s
     * drawing order: the last element in {@code plugin.getAnchorRegions()} is drawn last
     * and therefore appears on top, so we iterate the list in reverse.
     */
    private List<AnchorRegion> pickAnchorsAt(Point p) {
        List<AnchorRegion> regions = plugin.getAnchorRegions();
        List<AnchorRegion> hits = new ArrayList<>();
        for (int i = regions.size() - 1; i >= 0; i--) {
            AnchorRegion r = regions.get(i);
            Rectangle b = r.getBounds();
            if (b.contains(p) || isNearResizeHandle(b, p)) {
                hits.add(r);
            }
        }
        return hits;
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
        if (!plugin.isOverlaysVisible()) {
            return e;
        }
        // Canvas can briefly be null around client startup/shutdown; skip cursor updates
        // rather than NPE.
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return e;
        }
        if (!plugin.isDragKeyHeld()) {
            // Ensure cursor is reset if we released Alt while hovering
            canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            return e;
        }

        // Update cursor based on hover
        for (AnchorRegion region : plugin.getAnchorRegions()) {
            Rectangle bounds = region.getBounds();
            if (bounds.contains(e.getPoint()) || isNearResizeHandle(bounds, e.getPoint())) {
                int dir = getResizeDirection(bounds, e.getPoint());
                canvas.setCursor(getCursorForDirection(dir));
                return e;
            }
        }

        // Reset cursor if not colliding with any region
        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        return e;
    }

    // Helper methods

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
