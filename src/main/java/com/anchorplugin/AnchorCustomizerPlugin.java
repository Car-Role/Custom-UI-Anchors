/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted.
 */
package com.anchorplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.KeyCode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(name = "Custom UI Anchors", description = "Visualize and customize overlay anchor positions. Alt+drag to move anchor points.", tags = {
        "overlay", "anchor", "position", "customization", "ui" })
public class AnchorCustomizerPlugin extends Plugin {
    private static final int PADDING = 2;

    @Inject
    private Client client;

    @Inject
    @Getter
    private AnchorCustomizerConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private AnchorCustomizerOverlay customizerOverlay;

    @Inject
    @Getter
    private AnchorInputListener inputListener;

    @Inject
    private ClientToolbar clientToolbar;

    private AnchorCustomizerPanel panel;
    private NavigationButton navButton;

    @Inject
    private Gson gson;

    private final List<AnchorRegion> anchorRegions = new ArrayList<>();
    private Dimension lastViewport = null;

    public boolean isOverlaysVisible() {
        boolean isPanelOpen = panel != null && panel.isShowing();
        boolean isAltHeld = client.isKeyPressed(KeyCode.KC_ALT);
        return isPanelOpen || isAltHeld;
    }

    public boolean isAnchorBeingDragged() {
        return inputListener != null && inputListener.isDragging();
    }

    public void forceRepositionOverlays() {
        snapAndStackOverlays(false);
    }

    @Override
    protected void startUp() throws Exception {
        panel = new AnchorCustomizerPanel(this);

        // Load icon
        BufferedImage icon = null;
        try {
            // Load from root resources
            icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
        } catch (Exception e) {
            log.warn("Could not load icon", e);
        }

        navButton = NavigationButton.builder()
                .tooltip("Custom UI Anchors")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        overlayManager.add(customizerOverlay);
        // Note: AnchorInputListener constructor needs specific args, assuming updated
        // version
        // inputListener is already injected by Guice in most RuneLite setups if
        // correctly bound
        // but if manual creation is needed:
        // inputListener = new AnchorInputListener(client, this);
        mouseManager.registerMouseListener(inputListener);
        loadRegions();
        loadOverlayAssignments();

        // Update panel
        SwingUtilities.invokeLater(() -> panel.updateList(anchorRegions));
    }

    @Override
    protected void shutDown() throws Exception {
        overlayManager.remove(customizerOverlay);
        mouseManager.unregisterMouseListener(inputListener);
        clientToolbar.removeNavigation(navButton);
        anchorRegions.clear();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("anchorcustomizer")) {
            return;
        }

        if (event.getKey().equals("regionJson")) {
            loadRegions();
            SwingUtilities.invokeLater(() -> panel.updateList(anchorRegions));
        }
    }

    public void createNewAnchor() {
        int nextId = 1;
        List<Integer> ids = new ArrayList<>();
        for (AnchorRegion r : anchorRegions)
            ids.add(r.getId());
        Collections.sort(ids);

        // Find gap or next
        for (int id : ids) {
            if (id == nextId)
                nextId++;
            else
                break;
        }

        Rectangle viewport = getViewportBounds();
        int x = (viewport != null) ? viewport.width / 2 - 50 : 100;
        int y = (viewport != null) ? viewport.height / 2 - 50 : 100;

        AnchorRegion region = new AnchorRegion(
                nextId,
                "Box " + nextId,
                x,
                y,
                100,
                100,
                AnchorConstraint.TOP_LEFT,
                AnchorAlignment.CENTER,
                AnchorStacking.VERTICAL);

        anchorRegions.add(region);
        saveRegions();
        selectAnchor(region);
    }

    public void deleteRegion(AnchorRegion region) {
        anchorRegions.remove(region);
        saveRegions();
        selectAnchor(null);
    }

    public void selectAnchor(AnchorRegion region) {
        SwingUtilities.invokeLater(() -> panel.setSelectedRegion(region));
    }

    private void loadRegions() {
        anchorRegions.clear();
        String json = config.regionJson();
        if (json == null || json.isEmpty()) {
            return;
        }

        try {
            Type listType = new TypeToken<List<AnchorRegion>>() {
            }.getType();
            List<AnchorRegion> loaded = gson.fromJson(json, listType);
            if (loaded != null) {
                anchorRegions.addAll(loaded);
            }
        } catch (Exception e) {
            log.error("Failed to load regions", e);
        }
    }

    public void saveRegions() {
        String json = gson.toJson(anchorRegions);
        config.setRegionJson(json);
    }

    public List<AnchorRegion> getAnchorRegions() {
        return anchorRegions;
    }

    public Rectangle getViewportBounds() {
        if (client.getCanvas() == null)
            return null;
        Dimension dim = client.getRealDimensions();

        // Use logic similar to old getViewportBounds but simplified as we might treat
        // canvas as whole
        // For dynamic constraint logic, we usually care about the full canvas size
        // changes
        return new Rectangle(0, 0, dim.width, dim.height);
    }

    @Subscribe
    public void onClientTick(ClientTick event) {
        boolean isResizingWindow = false;

        // Handle Window Resize Constraints
        Rectangle currentViewport = getViewportBounds();
        if (currentViewport != null) {
            Dimension currentDim = currentViewport.getSize();
            // initialize lastViewport if null
            if (lastViewport == null) {
                lastViewport = currentDim;
            } else if (!lastViewport.equals(currentDim)) {
                isResizingWindow = updateRegionPositions(lastViewport, currentDim);
                lastViewport = currentDim;
            }
        }

        snapAndStackOverlays(isResizingWindow);
    }

    private long lastResizeTime = 0;

    private boolean updateRegionPositions(Dimension oldDim, Dimension newDim) {
        if (oldDim.equals(newDim))
            return false;

        lastResizeTime = System.currentTimeMillis();

        for (AnchorRegion region : anchorRegions) {
            updateRegionConstraint(region, oldDim, newDim);
        }
        saveRegions(); // Persist new positions
        return true;
    }

    private void updateRegionConstraint(AnchorRegion region, Dimension oldDim, Dimension newDim) {
        AnchorConstraint constraint = region.getConstraint();
        if (constraint == null)
            constraint = AnchorConstraint.TOP_LEFT;

        int deltaW = newDim.width - oldDim.width;
        int deltaH = newDim.height - oldDim.height;

        // Vertical Logic
        switch (constraint) {
            case BOTTOM_LEFT:
            case BOTTOM_CENTER:
            case BOTTOM_RIGHT:
                region.setY(region.getY() + deltaH);
                break;
            case CENTER_LEFT:
            case CENTER:
            case CENTER_RIGHT:
                region.setY(region.getY() + deltaH / 2);
                break;
            default:
                break;
        }

        // Horizontal Logic
        switch (constraint) {
            case TOP_RIGHT:
            case CENTER_RIGHT:
            case BOTTOM_RIGHT:
                region.setX(region.getX() + deltaW);
                break;
            case TOP_CENTER:
            case BOTTOM_CENTER:
            case CENTER:
                region.setX(region.getX() + deltaW / 2);
                break;
            default:
                break;
        }
    }

    // Map<OverlayClassName, RegionId> to track which region owns an overlay
    private final Map<String, Integer> overlayAssignments = new HashMap<>();

    private void snapAndStackOverlays(boolean isResizingWindowIgnored) {
        if (anchorRegions.isEmpty())
            return;

        boolean isAltDown = client.isKeyPressed(KeyCode.KC_ALT);
        // Lock assignments during:
        // 1. Window resize (within 500ms of last resize)
        // 2. When dragging an ANCHOR box (we don't want to lose associations)
        // Only allow assignment changes when Alt is held AND user is dragging an OVERLAY into/out of a region
        boolean isResizingWindow = (System.currentTimeMillis() - lastResizeTime) < 500;
        boolean isDraggingAnchor = isAnchorBeingDragged();

        // STRONG ASSOCIATION: Lock assignments unless Alt is held AND we're not dragging an anchor
        // This ensures overlays stay with their anchor when the anchor is moved
        boolean allowAssignmentChanges = isAltDown && !isResizingWindow && !isDraggingAnchor;
        boolean lockAssignments = !allowAssignmentChanges;

        Map<Integer, List<Overlay>> buckets = new HashMap<>();
        for (AnchorRegion r : anchorRegions) {
            buckets.put(r.getId(), new ArrayList<>());
        }

        List<Overlay> allOverlays = getOverlays();
        if (allOverlays == null)
            return;

        for (Overlay overlay : allOverlays) {
            if (overlay.getPreferredLocation() == null || !overlay.isMovable())
                continue;

            // Identify overlay by Class Name to ensure persistence across restarts
            String overlayId = overlay.getClass().getName();

            Rectangle overlayBounds = overlay.getBounds();
            if (overlayBounds.isEmpty()) {
                Point loc = overlay.getPreferredLocation();
                Dimension size = overlay.getPreferredSize();
                if (size == null)
                    size = new Dimension(100, 20);
                overlayBounds = new Rectangle(loc.x, loc.y, size.width, size.height);
            }

            Point center = new Point((int) overlayBounds.getCenterX(), (int) overlayBounds.getCenterY());

            Integer assignedRegionId = overlayAssignments.get(overlayId);

            if (!lockAssignments) {
                // "Capture" phase
                boolean foundInRegion = false;
                for (AnchorRegion region : anchorRegions) {
                    if (region.getBounds().contains(center)) {
                        if (assignedRegionId == null || !assignedRegionId.equals(region.getId())) {
                            overlayAssignments.put(overlayId, region.getId());
                            saveOverlayAssignments(); // Persist immediately on capture
                        }
                        buckets.get(region.getId()).add(overlay);
                        foundInRegion = true;
                        break;
                    }
                }
                // If moved out of all regions, unassign
                if (!foundInRegion) {
                    if (overlayAssignments.containsKey(overlayId)) {
                        overlayAssignments.remove(overlayId);
                        saveOverlayAssignments(); // Persist immediately on release
                    }
                }
            } else {
                // "Maintenance" phase
                if (assignedRegionId != null) {
                    if (buckets.containsKey(assignedRegionId)) {
                        buckets.get(assignedRegionId).add(overlay);
                    }
                }
            }
        }

        // Process buckets (Positioning logic)
        for (AnchorRegion region : anchorRegions) {
            List<Overlay> overlays = buckets.get(region.getId());
            if (overlays.isEmpty())
                continue;

            // Sort based on current Y to maintain stability
            overlays.sort(Comparator.comparingInt(o -> o.getBounds().y));

            AnchorStacking stacking = region.getStacking();
            if (stacking == null)
                stacking = AnchorStacking.VERTICAL;

            // 1. Calculate Layout Dimensions & Individual Positions relative to (0,0)
            List<Rectangle> relativeBounds = new ArrayList<>();
            int totalLayoutWidth = 0;
            int totalLayoutHeight = 0;

            int currentX = 0;
            int currentY = 0;
            int rowMaxH = 0; // For horizontal flow
            int colMaxW = 0; // For vertical flow

            for (Overlay overlay : overlays) {
                int w = overlay.getBounds().width;
                if (w <= 0)
                    w = overlay.getPreferredSize() != null ? overlay.getPreferredSize().width : 100;
                int h = overlay.getBounds().height;
                if (h <= 0)
                    h = overlay.getPreferredSize() != null ? overlay.getPreferredSize().height : 24;

                int xPos = 0, yPos = 0;

                switch (stacking) {
                    case VERTICAL:
                        // Vertical stack: Items stacked at x=0, y=currentY
                        xPos = 0;
                        yPos = currentY;
                        currentY += h + PADDING;
                        totalLayoutWidth = Math.max(totalLayoutWidth, w);
                        totalLayoutHeight = currentY - PADDING;
                        break;

                    case HORIZONTAL:
                        // Horizontal stack: Items stacked at x=currentX, y=0
                        xPos = currentX;
                        yPos = 0;
                        currentX += w + PADDING;
                        totalLayoutHeight = Math.max(totalLayoutHeight, h);
                        totalLayoutWidth = currentX - PADDING;
                        break;

                    case FILL_HORIZONTAL:
                        if (currentX + w > region.getWidth() && currentX > 0) {
                            // Wrap to next row
                            currentX = 0;
                            currentY += rowMaxH + PADDING;
                            rowMaxH = 0;
                        }
                        xPos = currentX;
                        yPos = currentY;
                        currentX += w + PADDING;
                        rowMaxH = Math.max(rowMaxH, h);
                        totalLayoutWidth = Math.max(totalLayoutWidth, xPos + w);
                        totalLayoutHeight = Math.max(totalLayoutHeight, yPos + h);
                        break;

                    case FILL_VERTICAL:
                        if (currentY + h > region.getHeight() && currentY > 0) {
                            // Wrap to next col
                            currentY = 0;
                            currentX += colMaxW + PADDING;
                            colMaxW = 0;
                        }
                        xPos = currentX;
                        yPos = currentY;
                        currentY += h + PADDING;
                        colMaxW = Math.max(colMaxW, w);
                        totalLayoutWidth = Math.max(totalLayoutWidth, xPos + w);
                        totalLayoutHeight = Math.max(totalLayoutHeight, yPos + h);
                        break;
                }
                relativeBounds.add(new Rectangle(xPos, yPos, w, h));
            }

            // 2. Align the Calculated Layout Block within the Region
            AnchorAlignment align = region.getAlignment();
            if (align == null)
                align = AnchorAlignment.CENTER;

            int startX = region.getX();
            int startY = region.getY();

            // Horizontal Alignment of the Block
            switch (align) {
                case TOP_RIGHT:
                case CENTER_RIGHT:
                case BOTTOM_RIGHT:
                    startX = region.getX() + region.getWidth() - totalLayoutWidth;
                    break;
                case TOP_CENTER:
                case BOTTOM_CENTER:
                case CENTER:
                case STRETCH:
                    startX = region.getX() + (region.getWidth() - totalLayoutWidth) / 2;
                    break;
                case TOP_LEFT:
                case CENTER_LEFT:
                case BOTTOM_LEFT:
                default:
                    // default startX = region.getX()
                    break;
            }

            // Vertical Alignment of the Block
            switch (align) {
                case BOTTOM_LEFT:
                case BOTTOM_CENTER:
                case BOTTOM_RIGHT:
                    startY = region.getY() + region.getHeight() - totalLayoutHeight;
                    break;
                case CENTER_LEFT:
                case CENTER:
                case CENTER_RIGHT:
                case STRETCH:
                    startY = region.getY() + (region.getHeight() - totalLayoutHeight) / 2;
                    break;
                case TOP_LEFT:
                case TOP_CENTER:
                case TOP_RIGHT:
                default:
                    // default startY = region.getY()
                    break;
            }

            // 3. Apply positions
            for (int i = 0; i < overlays.size(); i++) {
                Overlay overlay = overlays.get(i);
                Rectangle rel = relativeBounds.get(i);

                int targetX = startX + rel.x;
                int targetY = startY + rel.y;

                // Special case: Vertical Stack usually wants items centered horizontally
                // relative to EACH OTHER
                if (stacking == AnchorStacking.VERTICAL) {
                    targetX = startX + (totalLayoutWidth - rel.width) / 2;
                }
                // Special case: Horizontal Stack items centered vertically relative to EACH
                // OTHER
                if (stacking == AnchorStacking.HORIZONTAL) {
                    targetY = startY + (totalLayoutHeight - rel.height) / 2;
                }

                if (overlay.getPreferredPosition() != OverlayPosition.DYNAMIC) {
                    overlay.setPreferredPosition(OverlayPosition.DYNAMIC);
                }

                Point currentLoc = overlay.getPreferredLocation();
                if (currentLoc == null || currentLoc.x != targetX || currentLoc.y != targetY) {
                    overlay.setPreferredLocation(new Point(targetX, targetY));
                }
            }
        }
    }

    private void loadOverlayAssignments() {
        String json = config.overlayAssignmentsJson();
        if (json == null || json.isEmpty())
            return;
        try {
            Type type = new TypeToken<Map<String, Integer>>() {
            }.getType();
            Map<String, Integer> loaded = gson.fromJson(json, type);
            if (loaded != null) {
                overlayAssignments.clear();
                overlayAssignments.putAll(loaded);
            }
        } catch (Exception e) {
            log.warn("Failed to load overlay assignments", e);
        }
    }

    private void saveOverlayAssignments() {
        String json = gson.toJson(overlayAssignments);
        config.setOverlayAssignmentsJson(json);
    }

    private List<Overlay> getOverlays() {
        try {
            Field field = OverlayManager.class.getDeclaredField("overlays");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Overlay> overlays = (List<Overlay>) field.get(overlayManager);
            return overlays;
        } catch (Exception e) {
            return null;
        }
    }

    @Provides
    AnchorCustomizerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AnchorCustomizerConfig.class);
    }
}
