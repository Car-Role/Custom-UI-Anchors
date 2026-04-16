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
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.KeyCode;
import net.runelite.client.callback.ClientThread;
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

    @Inject
    private ClientThread clientThread;

    private AnchorCustomizerPanel panel;
    private NavigationButton navButton;

    @Inject
    private Gson gson;

    private final List<AnchorRegion> anchorRegions = new ArrayList<>();
    private Dimension lastViewport = null;

    // Debounced persistence + self-triggered config-reload suppression
    private static final long SAVE_DEBOUNCE_MS = 500L;
    private boolean regionsDirty = false;
    private boolean assignmentsDirty = false;
    private long lastFlushTime = 0L;
    private String lastWrittenRegionJson = null;
    private String lastWrittenAssignmentsJson = null;

    // Periodic re-acquire throttle for overlays that load after startup
    private long lastReacquireTime = 0L;
    private static final long REACQUIRE_INTERVAL_MS = 1000L;

    // Fused overlay walk (cleanup + auto-track) throttle. Runs when the mouse or any
    // tracked overlay moves, or at least once per interval as a safety net so new
    // overlays that register mid-session are still picked up.
    private long lastFusedWalkTime = 0L;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private static final long FUSED_WALK_MIN_INTERVAL_MS = 500L;

    // Flag set by input listeners asking the next client tick to run an immediate snap.
    private volatile boolean needsSnap = false;

    public boolean isOverlaysVisible() {
        boolean isPanelOpen = panel != null && panel.isShowing();
        boolean isAltHeld = client.isKeyPressed(KeyCode.KC_ALT);
        return isPanelOpen || isAltHeld;
    }

    public boolean isAnchorBeingDragged() {
        return inputListener != null && inputListener.isDragging();
    }

    /**
     * Ask the next {@code onClientTick} to run a snap pass. Safe to call from any
     * thread; the actual snap runs on the client thread where {@code anchorRegions}
     * is iterated.
     */
    public void requestSnap() {
        needsSnap = true;
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
        mouseManager.registerMouseListener(inputListener);
        loadRegions();
        loadOverlayAssignments();
        
        // Try to re-acquire overlay references for persisted assignments
        scanAndReacquireOverlays();
        lastReacquireTime = System.currentTimeMillis();

        // Update panel
        final List<AnchorRegion> snapshot = new ArrayList<>(anchorRegions);
        SwingUtilities.invokeLater(() -> panel.updateList(snapshot));
    }

    @Override
    protected void shutDown() throws Exception {
        // Flush any pending persistence before tearing down
        flushPendingSaves(true);
        overlayManager.remove(customizerOverlay);
        mouseManager.unregisterMouseListener(inputListener);
        clientToolbar.removeNavigation(navButton);
        anchorRegions.clear();
        trackedOverlays.clear();
        overlayAssignments.clear();
        lastSeenLocations.clear();
        lastExternalMoveTime.clear();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("anchorcustomizer")) {
            return;
        }

        if (event.getKey().equals("regionJson")) {
            String incoming = event.getNewValue();
            // Suppress self-triggered reloads when the value matches what we just wrote
            if (incoming != null && incoming.equals(lastWrittenRegionJson)) {
                return;
            }
            clientThread.invoke(() -> {
                loadRegions();
                final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
                SwingUtilities.invokeLater(() -> panel.updateList(snap));
            });
        } else if (event.getKey().equals("overlayAssignments")) {
            String incoming = event.getNewValue();
            if (incoming != null && incoming.equals(lastWrittenAssignmentsJson)) {
                return;
            }
            clientThread.invoke(this::loadOverlayAssignments);
        }
    }

    public void createNewAnchor() {
        // Route mutation onto the client thread to match where anchorRegions is iterated
        clientThread.invoke(this::createNewAnchorOnClientThread);
    }

    private void createNewAnchorOnClientThread() {
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
        markRegionsDirty();
        selectAnchor(region);
        final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
        SwingUtilities.invokeLater(() -> panel.updateList(snap));
    }

    public void deleteRegion(AnchorRegion region) {
        clientThread.invoke(() -> {
            anchorRegions.remove(region);
            markRegionsDirty();
            selectAnchor(null);
            final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
            SwingUtilities.invokeLater(() -> panel.updateList(snap));
        });
    }

    public void selectAnchor(AnchorRegion region) {
        SwingUtilities.invokeLater(() -> panel.setSelectedRegion(region));
    }

    /** Lightweight panel-properties refresh (no JList mutation). */
    public void refreshSelectedAnchorProperties(AnchorRegion region) {
        SwingUtilities.invokeLater(() -> panel.refreshSelectedRegionProperties(region));
    }

    /**
     * Apply a mutation to a region on the client thread (the same thread that iterates
     * {@code anchorRegions} during the snap loop). Guarantees panel edits can't race with
     * the render pipeline mid-update. Persists immediately after.
     */
    public void updateRegion(AnchorRegion region, java.util.function.Consumer<AnchorRegion> updater) {
        if (region == null || updater == null) return;
        clientThread.invoke(() -> {
            updater.accept(region);
            saveRegions();
        });
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
                for (AnchorRegion r : loaded) {
                    if (r == null) continue;
                    // Normalize null enums (may occur in old persisted data)
                    if (r.getConstraint() == null) r.setConstraint(AnchorConstraint.TOP_LEFT);
                    if (r.getAlignment() == null) r.setAlignment(AnchorAlignment.CENTER);
                    if (r.getStacking() == null) r.setStacking(AnchorStacking.VERTICAL);
                    anchorRegions.add(r);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load regions", e);
        }
    }

    /**
     * Persist regions immediately. Regions change infrequently (user actions: create,
     * delete, rename, panel edits, window resize) so we never debounce them — this
     * guarantees durability even if RuneLite is killed abruptly.
     */
    public void saveRegions() {
        String json = gson.toJson(anchorRegions);
        lastWrittenRegionJson = json;
        config.setRegionJson(json);
        regionsDirty = false;
    }

    private void markRegionsDirty() {
        // Kept for compatibility: treat as an immediate save. Regions are small and
        // change infrequently; no reason to delay persistence.
        saveRegions();
    }

    private void markAssignmentsDirty() {
        assignmentsDirty = true;
    }

    /**
     * Flush pending assignment writes. Assignments can churn during capture-phase
     * drags (up to ~50 Hz), so they are debounced on the tick loop. Force-flush on
     * shutDown to guarantee persistence.
     */
    private void flushPendingSaves(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastFlushTime) < SAVE_DEBOUNCE_MS) {
            return;
        }
        if (regionsDirty) {
            // Defensive: should normally be false because saveRegions writes immediately,
            // but flush anything still marked dirty just in case.
            saveRegions();
        }
        if (assignmentsDirty) {
            String json = gson.toJson(overlayAssignments);
            lastWrittenAssignmentsJson = json;
            config.setOverlayAssignmentsJson(json);
            assignmentsDirty = false;
        }
        lastFlushTime = now;
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
        flushPendingSaves(false);
    }

    private long lastResizeTime = 0;

    private boolean updateRegionPositions(Dimension oldDim, Dimension newDim) {
        if (oldDim.equals(newDim))
            return false;

        lastResizeTime = System.currentTimeMillis();

        // Snapshot to avoid concurrent modification if panel edits fire during iteration
        for (AnchorRegion region : new ArrayList<>(anchorRegions)) {
            updateRegionConstraint(region, oldDim, newDim);
        }
        markRegionsDirty(); // Persist new positions (debounced)
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

    // Map<OverlayKey, RegionId> to track which region owns an overlay (persisted)
    private final Map<String, Integer> overlayAssignments = new ConcurrentHashMap<>();
    // Map<OverlayKey, Overlay> to store direct references to captured overlays (runtime only)
    private final Map<String, Overlay> trackedOverlays = new ConcurrentHashMap<>();

    /**
     * Stable identity for an overlay. Prefers {@link Overlay#getName()} (which is
     * overridden to something unique by RuneLite plugins that register multiple
     * instances of the same class — e.g. screen markers, timers). Falls back to the
     * class FQN when the name is blank. Both code paths use only public Overlay API —
     * no reflection.
     */
    private static String overlayKey(Overlay overlay) {
        if (overlay == null) return null;
        String name = overlay.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return overlay.getClass().getName();
    }

    // Drag-detection: track the preferredLocation we observed at the end of the previous
    // tick. If it differs from the current preferredLocation, something moved the overlay
    // between ticks (RuneLite drag renderer following the cursor). Used to avoid fighting
    // the drag. `lastSeenLocations` is updated every tick AFTER apply-positions so that
    // both our own setPreferredLocation and external moves are captured uniformly.
    private final Map<String, Point> lastSeenLocations = new ConcurrentHashMap<>();
    private final Map<String, Long> lastExternalMoveTime = new ConcurrentHashMap<>();
    private static final long DRAG_DETECT_MS = 125L;

    private void snapAndStackOverlays(boolean isResizingWindowIgnored) {
        if (anchorRegions.isEmpty())
            return;

        // Snapshot regions once per call (defensive against EDT/panel edits)
        final List<AnchorRegion> regionsSnapshot = new ArrayList<>(anchorRegions);

        // Periodically re-acquire overlays that may have registered after startup
        long nowMs = System.currentTimeMillis();
        if (!overlayAssignments.isEmpty()
                && overlayAssignments.size() > trackedOverlays.size()
                && (nowMs - lastReacquireTime) >= REACQUIRE_INTERVAL_MS) {
            scanAndReacquireOverlays();
            lastReacquireTime = nowMs;
        }

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

        // --- Drag detection pass (runs before fused walk so we know who is dragging) ---
        // Compare current preferredLocation to the one observed at the end of the previous
        // tick. If they differ, the overlay moved between ticks (cursor-driven drag). We
        // keep the "dragging" flag for a short window after the last change so that a
        // momentary pause (cursor not moving but button still held) doesn't flip to snap.
        final Set<String> draggingIds = Collections.newSetFromMap(new HashMap<>());
        boolean anyOverlayMoved = false;
        for (Map.Entry<String, Overlay> entry : trackedOverlays.entrySet()) {
            String id = entry.getKey();
            Overlay ov = entry.getValue();
            Point cur = ov.getPreferredLocation();
            if (cur == null) continue;
            Point last = lastSeenLocations.get(id);
            if (last != null && !cur.equals(last)) {
                lastExternalMoveTime.put(id, nowMs);
                anyOverlayMoved = true;
            }
            Long t = lastExternalMoveTime.get(id);
            if (t != null && (nowMs - t) < DRAG_DETECT_MS) {
                draggingIds.add(id);
            }
        }

        // Short-circuit: if neither the mouse nor any tracked overlay has moved since
        // the last tick, skip the expensive fused walk. We still need to fall through
        // to the apply-positions step — but the auto-track + staleness-check passes can
        // wait for something to actually change.
        net.runelite.api.Point mcp = client.getMouseCanvasPosition();
        int mouseX = mcp != null ? mcp.getX() : Integer.MIN_VALUE;
        int mouseY = mcp != null ? mcp.getY() : Integer.MIN_VALUE;
        boolean mouseMoved = (mouseX != lastMouseX || mouseY != lastMouseY);
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        boolean runFusedWalk = mouseMoved || anyOverlayMoved
                || (nowMs - lastFusedWalkTime) >= FUSED_WALK_MIN_INTERVAL_MS;

        if (runFusedWalk) {
            runFusedOverlayWalk(regionsSnapshot, lockAssignments);
            lastFusedWalkTime = nowMs;
        }

        Map<Integer, List<Overlay>> buckets = new HashMap<>();
        for (AnchorRegion r : regionsSnapshot) {
            buckets.put(r.getId(), new ArrayList<>());
        }

        // Process tracked overlays
        for (Map.Entry<String, Overlay> entry : trackedOverlays.entrySet()) {
            String overlayId = entry.getKey();
            Overlay overlay = entry.getValue();

            if (overlay.getPreferredLocation() == null || !overlay.isMovable())
                continue;

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
            boolean isDraggingThis = draggingIds.contains(overlayId);

            if (!lockAssignments) {
                // "Capture" phase - check if overlay moved into a different region or out of all regions
                boolean foundInRegion = false;
                for (AnchorRegion region : regionsSnapshot) {
                    if (region.getBounds().contains(center)) {
                        if (assignedRegionId == null || !assignedRegionId.equals(region.getId())) {
                            overlayAssignments.put(overlayId, region.getId());
                            markAssignmentsDirty();
                        }
                        buckets.get(region.getId()).add(overlay);
                        foundInRegion = true;
                        break;
                    }
                }
                // If moved out of all regions, unassign — but NEVER while the overlay is
                // being dragged. Drag motion can briefly push the center outside a region
                // between cursor samples; unassigning there would orphan the overlay on
                // release. Keep its current assignment so it re-enters its bucket cleanly.
                if (!foundInRegion && !isDraggingThis) {
                    if (overlayAssignments.containsKey(overlayId)) {
                        overlayAssignments.remove(overlayId);
                        markAssignmentsDirty();
                    }
                } else if (!foundInRegion && isDraggingThis && assignedRegionId != null
                        && buckets.containsKey(assignedRegionId)) {
                    // Dragging outside all regions but still holding: keep it in its
                    // current bucket visually so other overlays don't reshuffle twice.
                    buckets.get(assignedRegionId).add(overlay);
                }
            } else {
                // "Maintenance" phase (or dragging in-region) - keep current assignment,
                // just add to its bucket so it participates in sort/layout without bouncing regions.
                if (assignedRegionId != null && buckets.containsKey(assignedRegionId)) {
                    buckets.get(assignedRegionId).add(overlay);
                }
            }
        }

        // Process buckets (Positioning logic)
        for (AnchorRegion region : regionsSnapshot) {
            List<Overlay> overlays = buckets.get(region.getId());
            if (overlays.isEmpty())
                continue;

            AnchorStacking stacking = region.getStacking();
            if (stacking == null)
                stacking = AnchorStacking.VERTICAL;

            // Sort along the stacking axis so that reordering (e.g. dragging one overlay
            // past another) reflects the user's intent. Horizontal stacks sort by X,
            // vertical and fill layouts sort by Y.
            final AnchorStacking sortStacking = stacking;
            overlays.sort((a, b) -> {
                Rectangle ba = a.getBounds();
                Rectangle bb = b.getBounds();
                if (sortStacking == AnchorStacking.HORIZONTAL) {
                    return Integer.compare(ba.x, bb.x);
                }
                return Integer.compare(ba.y, bb.y);
            });

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

                String overlayId = overlayKey(overlay);
                if (overlayId == null) continue;
                // If this overlay is being dragged by the user, don't fight the drag renderer.
                // Let its cursor-driven position stand; other overlays will reflow around it.
                if (draggingIds.contains(overlayId)) {
                    continue;
                }

                Point targetPoint = new Point(targetX, targetY);
                Point currentLoc = overlay.getPreferredLocation();
                if (currentLoc == null || currentLoc.x != targetX || currentLoc.y != targetY) {
                    overlay.setPreferredLocation(targetPoint);
                }
            }
        }

        // End-of-tick: snapshot every tracked overlay's current preferredLocation so that
        // the next tick's drag-detection pass can tell whether it changed between ticks.
        // We include dragged overlays too — that way when the user releases (cursor stops
        // moving), the next tick sees cur == lastSeen, drag detection falls off after
        // DRAG_DETECT_MS, and the overlay snaps into its anchor slot.
        for (Map.Entry<String, Overlay> entry : trackedOverlays.entrySet()) {
            Point loc = entry.getValue().getPreferredLocation();
            if (loc != null) {
                lastSeenLocations.put(entry.getKey(), new Point(loc));
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
        markAssignmentsDirty();
    }

    /**
     * Called when an overlay is dragged. Checks if it should be captured by an anchor region.
     * This is the entry point for tracking overlays without reflection.
     */
    public void onOverlayDragged(Overlay overlay) {
        if (overlay == null || !overlay.isMovable() || overlay == customizerOverlay)
            return;

        String overlayId = overlayKey(overlay);
        if (overlayId == null) return;

        // Always track the overlay so we can manage it
        if (!trackedOverlays.containsKey(overlayId)) {
            trackedOverlays.put(overlayId, overlay);
        }

        // Check if it's inside any anchor region
        Rectangle overlayBounds = overlay.getBounds();
        if (overlayBounds.isEmpty()) {
            Point loc = overlay.getPreferredLocation();
            if (loc == null) return;
            Dimension size = overlay.getPreferredSize();
            if (size == null) size = new Dimension(100, 20);
            overlayBounds = new Rectangle(loc.x, loc.y, size.width, size.height);
        }

        Point center = new Point((int) overlayBounds.getCenterX(), (int) overlayBounds.getCenterY());

        boolean foundInRegion = false;
        for (AnchorRegion region : new ArrayList<>(anchorRegions)) {
            if (region.getBounds().contains(center)) {
                Integer currentAssignment = overlayAssignments.get(overlayId);
                if (currentAssignment == null || !currentAssignment.equals(region.getId())) {
                    overlayAssignments.put(overlayId, region.getId());
                    markAssignmentsDirty();
                    log.debug("Captured overlay {} into region {}", overlayId, region.getName());
                }
                foundInRegion = true;
                break;
            }
        }

        if (!foundInRegion && overlayAssignments.containsKey(overlayId)) {
            overlayAssignments.remove(overlayId);
            markAssignmentsDirty();
            log.debug("Released overlay {} from all regions", overlayId);
        }
    }

    /**
     * Scan all overlays using anyMatch and re-acquire references for previously assigned overlays.
     * This is called on startup to restore overlay tracking from persisted assignments.
     */
    private void scanAndReacquireOverlays() {
        if (overlayAssignments.isEmpty()) {
            return;
        }

        // Use anyMatch to scan through overlays and capture references
        // The predicate has a side-effect of storing references, but always returns false
        // so we scan ALL overlays
        overlayManager.anyMatch(overlay -> {
            if (overlay == null || !overlay.isMovable() || overlay == customizerOverlay) {
                return false;
            }

            String overlayId = overlayKey(overlay);
            if (overlayId == null) return false;

            // If this overlay was previously assigned, start tracking it again
            if (overlayAssignments.containsKey(overlayId) && !trackedOverlays.containsKey(overlayId)) {
                trackedOverlays.put(overlayId, overlay);
                log.debug("Re-acquired overlay {} for region {}", overlayId, overlayAssignments.get(overlayId));
            }

            return false; // Always return false to continue scanning all overlays
        });

        log.debug("Re-acquired {} overlay references out of {} assignments", 
                  trackedOverlays.size(), overlayAssignments.size());
    }

    /**
     * Single pass over the OverlayManager that does BOTH:
     * 1. Collects an identity set of present overlays so stale entries in
     *    {@code trackedOverlays} can be removed.
     * 2. Auto-tracks any movable overlay whose center lies inside an anchor region
     *    (when assignment changes are allowed).
     * Replaces three separate tick-rate overlay walks with one.
     */
    private void runFusedOverlayWalk(List<AnchorRegion> regionsSnapshot, boolean lockAssignments) {
        final Set<Overlay> present = Collections.newSetFromMap(new IdentityHashMap<>());
        overlayManager.anyMatch(ov -> {
            if (ov == null) return false;
            present.add(ov);

            if (lockAssignments) return false; // skip auto-track when not capturing
            if (ov == customizerOverlay || !ov.isMovable()) return false;
            Point loc = ov.getPreferredLocation();
            if (loc == null) return false;
            String id = overlayKey(ov);
            if (id == null || trackedOverlays.containsKey(id)) return false;

            Rectangle b = ov.getBounds();
            if (b.isEmpty()) {
                Dimension size = ov.getPreferredSize();
                if (size == null) size = new Dimension(100, 20);
                b = new Rectangle(loc.x, loc.y, size.width, size.height);
            }
            Point c = new Point((int) b.getCenterX(), (int) b.getCenterY());
            for (AnchorRegion r : regionsSnapshot) {
                if (r.getBounds().contains(c)) {
                    trackedOverlays.put(id, ov);
                    log.debug("Auto-tracked overlay {} (inside region {})", id, r.getName());
                    break;
                }
            }
            return false; // scan all
        });

        // Remove any tracked references whose overlays no longer exist in the manager.
        if (!trackedOverlays.isEmpty()) {
            Iterator<Map.Entry<String, Overlay>> it = trackedOverlays.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Overlay> entry = it.next();
                if (!present.contains(entry.getValue())) {
                    it.remove();
                    log.debug("Removed stale overlay reference: {}", entry.getKey());
                }
            }
        }
    }

    @Provides
    AnchorCustomizerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AnchorCustomizerConfig.class);
    }
}
