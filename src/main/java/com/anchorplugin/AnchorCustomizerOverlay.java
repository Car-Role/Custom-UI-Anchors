/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.api.KeyCode;

public class AnchorCustomizerOverlay extends Overlay {
    // Colors
    private static final Color ANCHOR_BORDER_COLOR = Color.CYAN;
    private static final Color ANCHOR_FILL_COLOR = new Color(0, 255, 255, 20);
    private static final Color ANCHOR_DRAGGING_COLOR = Color.GREEN;
    private static final Color ANCHOR_DRAGGING_FILL_COLOR = new Color(0, 255, 0, 40);
    private static final Color RESIZE_HANDLE_COLOR = Color.WHITE;

    @Inject
    private Client client;

    private final AnchorCustomizerPlugin plugin;

    @Inject
    public AnchorCustomizerOverlay(AnchorCustomizerPlugin plugin) {
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(100.0f); // High priority
        setDragTargetable(true); // Allow other overlays to be dragged onto this
    }

    @Override
    public boolean onDrag(Overlay other) {
        // Called when another overlay is dragged onto this overlay
        // This is how we capture overlays without reflection
        plugin.onOverlayDragged(other);
        return false; // Don't consume the event, let normal drag behavior continue
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!plugin.isOverlaysVisible()) {
            return null;
        }

        graphics.setStroke(new BasicStroke(2));

        // Get dragging state
        AnchorRegion draggingAnchor = null;
        AnchorInputListener inputListener = plugin.getInputListener();
        if (inputListener != null && inputListener.isDragging()) {
            draggingAnchor = inputListener.getDraggedAnchor();
        }

        net.runelite.api.Point mouseCanvasPos = client.getMouseCanvasPosition();
        java.awt.Point mouseAwt = new java.awt.Point(mouseCanvasPos.getX(), mouseCanvasPos.getY());

        boolean isAltDown = client.isKeyPressed(KeyCode.KC_ALT);

        List<AnchorRegion> regions = plugin.getAnchorRegions();

        // Hover visuals follow the pick path: only the region a click would actually hit
        // (topmost unlocked under the cursor) lights up — overlapped regions beneath it
        // and locked regions stay at their resting style. Reuses this frame's snapshot.
        AnchorRegion hoverTarget = inputListener != null ? inputListener.pickTopAnchorAt(mouseAwt, regions) : null;

        // Draw in REVERSE list order so the panel list reads top-down as the layer
        // stack: index 0 is drawn last and therefore renders on top of everything else.
        for (int i = regions.size() - 1; i >= 0; i--) {
            AnchorRegion region = regions.get(i);
            boolean isDraggingThis = draggingAnchor != null && draggingAnchor.getId() == region.getId();
            boolean isHovering = hoverTarget != null && hoverTarget.getId() == region.getId();

            drawAnchorRegion(graphics, region, isDraggingThis, isHovering, isAltDown);
        }

        return null;
    }

    private void drawAnchorRegion(Graphics2D graphics, AnchorRegion region, boolean isDragging, boolean isHovering,
            boolean isAltDown) {
        // Locked regions are click-through (never the hover/pick target, never dragged),
        // so they always render at the resting style plus a lock glyph under the label.
        boolean locked = region.isLocked();

        // Only show yellow highlight if dragging OR (hovering AND Alt is held)
        // If just hovering without Alt, show standard border (Cyan)
        boolean showHighlight = !locked && (isDragging || (isHovering && isAltDown));

        Color borderColor = showHighlight ? (isDragging ? ANCHOR_DRAGGING_COLOR : Color.YELLOW) : ANCHOR_BORDER_COLOR;
        Color fillColor = isDragging ? ANCHOR_DRAGGING_FILL_COLOR : ANCHOR_FILL_COLOR;

        Rectangle bounds = region.getBounds();

        // Fill
        graphics.setColor(fillColor);
        graphics.fill(bounds);

        // Border
        graphics.setColor(borderColor);
        graphics.draw(bounds);

        // Label
        String label = region.getName();
        int textWidth = graphics.getFontMetrics().stringWidth(label);
        int textX = bounds.x + (bounds.width - textWidth) / 2;
        int textY = bounds.y + bounds.height / 2 + 5;

        graphics.setColor(Color.WHITE);
        graphics.drawString(label, textX, textY);

        // Lock glyph centered below the label, in the same color as the anchor border.
        if (locked) {
            drawLockIcon(graphics, bounds.x + bounds.width / 2, textY + 5, borderColor);
        }

        // Draw resize handles if hovering or dragging
        // Draw resize handles if (hovering AND Alt is held) or dragging
        if (!locked && ((isHovering && isAltDown) || isDragging)) {
            drawResizeHandles(graphics, bounds);
        }
    }

    /**
     * Tiny padlock: an arc shackle over a filled body, centered horizontally on
     * {@code cx} with the shackle's top at {@code topY}.
     */
    private void drawLockIcon(Graphics2D graphics, int cx, int topY, Color color) {
        // bodyW is odd so fillRect(cx - bodyW/2, ..) spans symmetrically around cx —
        // at 8 wide the body sat 1px short on the right relative to the shackle.
        final int bodyW = 9;
        final int bodyH = 6;
        final int shackleW = 6;
        final int shackleH = 6;

        graphics.setColor(color);
        // Shackle (upper half-circle), overlapping the body slightly so they connect.
        graphics.drawArc(cx - shackleW / 2, topY, shackleW, shackleH, 0, 180);
        // Body
        graphics.fillRect(cx - bodyW / 2, topY + shackleH / 2 + 1, bodyW, bodyH);
    }

    private void drawResizeHandles(Graphics2D graphics, Rectangle bounds) {
        int size = 6;
        int half = size / 2;

        graphics.setColor(RESIZE_HANDLE_COLOR);

        // Corners
        graphics.fillRect(bounds.x - half, bounds.y - half, size, size); // TL
        graphics.fillRect(bounds.x + bounds.width - half, bounds.y - half, size, size); // TR
        graphics.fillRect(bounds.x - half, bounds.y + bounds.height - half, size, size); // BL
        graphics.fillRect(bounds.x + bounds.width - half, bounds.y + bounds.height - half, size, size); // BR

        // Sides
        graphics.fillRect(bounds.x + bounds.width / 2 - half, bounds.y - half, size, size); // Top
        graphics.fillRect(bounds.x + bounds.width / 2 - half, bounds.y + bounds.height - half, size, size); // Bottom
        graphics.fillRect(bounds.x - half, bounds.y + bounds.height / 2 - half, size, size); // Left
        graphics.fillRect(bounds.x + bounds.width - half, bounds.y + bounds.height / 2 - half, size, size); // Right
    }
}
