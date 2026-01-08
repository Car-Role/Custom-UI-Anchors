/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
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
        if (!client.isKeyPressed(KeyCode.KC_ALT) || SwingUtilities.isRightMouseButton(e)) {
            return e;
        }

        Point mousePos = e.getPoint();

        for (AnchorRegion region : plugin.getAnchorRegions()) {
            Rectangle bounds = region.getBounds();
            if (bounds.contains(mousePos) || isNearResizeHandle(bounds, mousePos)) {
                isDragging = true;
                draggedAnchor = region;
                dragStartPoint = mousePos;
                originalBounds = new Rectangle(bounds);

                resizeDirection = getResizeDirection(bounds, mousePos);
                isResizing = resizeDirection != 0;

                e.consume();
                return e;
            }
        }

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

        // Force immediate overlay repositioning so UI follows anchor without lag
        plugin.forceRepositionOverlays();

        // Live update for panel properties
        plugin.selectAnchor(draggedAnchor);

        e.consume();
        return e;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent e) {
        if (!plugin.isOverlaysVisible() && !isDragging)
            return e;
        if (isDragging) {
            isDragging = false;
            draggedAnchor = null;
            isResizing = false;
            resizeDirection = 0;
            plugin.saveRegions();
            e.consume();
        }
        return e;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent e) {
        if (!plugin.isOverlaysVisible())
            return e;

        // Allow selection if visible.
        // If Alt is NOT held, we just select but do NOT consume (allow click-through)
        // If Alt IS held, we consume (edit mode)

        Point mousePos = e.getPoint();
        boolean found = false;

        for (AnchorRegion region : plugin.getAnchorRegions()) {
            if (region.getBounds().contains(mousePos)) {
                plugin.selectAnchor(region);
                found = true;
                break;
            }
        }

        if (found) {
            if (client.isKeyPressed(KeyCode.KC_ALT)) {
                e.consume();
            }
        } else if (client.isKeyPressed(KeyCode.KC_ALT)) {
            // Only deselect if Alt is held (explicit edit intention), otherwise clicking
            // void shouldn't drop selection
            plugin.selectAnchor(null);
        }

        return e;
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
        if (!client.isKeyPressed(KeyCode.KC_ALT)) {
            // Ensure cursor is reset if we released Alt while hovering
            client.getCanvas().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            return e;
        }

        // Update cursor based on hover
        for (AnchorRegion region : plugin.getAnchorRegions()) {
            Rectangle bounds = region.getBounds();
            if (bounds.contains(e.getPoint()) || isNearResizeHandle(bounds, e.getPoint())) {
                int dir = getResizeDirection(bounds, e.getPoint());
                client.getCanvas().setCursor(getCursorForDirection(dir));
                return e;
            }
        }

        // Reset cursor if not colliding with any region
        client.getCanvas().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
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
