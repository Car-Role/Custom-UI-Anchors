/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted.
 */
package com.anchorplugin;

import com.google.gson.Gson;
// Gson parameterized-type helpers — used only for JSON (de)serialization of our
// own config strings. No reflection into RuneLite internals.
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
// java.lang.reflect.Type is the interface returned by TypeToken#getType(); still
// just Gson plumbing, no reflective member access.
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import net.runelite.api.GameState;
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

    // The RuneLite canvas frequently resizes once during the first few hundred ms of
    // plugin life, and again whenever the user transitions between login-screen (fixed
    // canvas) and gameplay (user's resizable window). Treating either transition as a
    // "user window resize" would shift every constraint-anchored region by hundreds of
    // pixels and then persist the corrupted positions via saveRegions().
    //
    // We defend in depth with three independent checks in onClientTick before applying
    // a viewport delta; if any one fails we silently reseed lastViewport instead:
    //   1. Startup grace window (catches the very first settle on fast machines).
    //   2. GameState gate: only track deltas while in LOGGED_IN (catches login-screen
    //      dwell on slow machines, where grace expires long before the user clicks Play).
    //   3. Plausibility clamp: skip any single delta whose magnitude exceeds half the
    //      current canvas dimension (catches anything the other two miss, e.g. an
    //      unexpected mid-session fullscreen/monitor swap we'd rather not auto-shift for).
    private static final long VIEWPORT_GRACE_MS = 1500L;
    private long startupTimeMs = 0L;

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
        // Reset cached state in case RuneLite is reusing this plugin instance across an
        // enable/disable cycle. Without this, a stale "last written" string could silently
        // suppress a legitimate ConfigChanged reload after re-enable.
        lastWrittenRegionJson = null;
        lastWrittenAssignmentsJson = null;
        lastViewport = null;
        startupTimeMs = System.currentTimeMillis();
        lastResizeTime = 0L;
        lastReacquireTime = 0L;
        lastFusedWalkTime = 0L;
        lastFlushTime = 0L;
        lastMouseX = Integer.MIN_VALUE;
        lastMouseY = Integer.MIN_VALUE;
        regionsDirty = false;
        assignmentsDirty = false;
        needsSnap = false;

        // Load icon (off-EDT work — just a resource read, safe on the client thread)
        BufferedImage icon = null;
        try {
            icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
        } catch (Exception e) {
            log.warn("Could not load icon", e);
        }
        final BufferedImage iconFinal = icon;

        // Construct the Swing panel + nav button on the EDT (Swing contract).
        // Use invokeAndWait when off-EDT so downstream loadRegions can rely on panel
        // being fully initialised before we update it. If startUp happens to be running
        // on the EDT already (e.g. triggered synchronously from the plugin-toggle
        // checkbox in the config panel), invokeAndWait would throw an Error — so we
        // fall back to running the initializer inline in that case.
        final Runnable panelInit = () -> {
            panel = new AnchorCustomizerPanel(this);
            navButton = NavigationButton.builder()
                    .tooltip("Custom UI Anchors")
                    .icon(iconFinal)
                    .priority(5)
                    .panel(panel)
                    .build();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            panelInit.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(panelInit);
            } catch (InvocationTargetException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Failed to initialise Custom UI Anchors panel on EDT", e);
            }
        }

        clientToolbar.addNavigation(navButton);

        overlayManager.add(customizerOverlay);
        mouseManager.registerMouseListener(inputListener);
        loadRegions();
        loadOverlayAssignments();

        // Try to re-acquire overlay references for persisted assignments
        scanAndReacquireOverlays();
        lastReacquireTime = System.currentTimeMillis();

        // Update panel and restore the last-selected region (Fix SEL).
        final List<AnchorRegion> snapshot = new ArrayList<>(anchorRegions);
        final int persistedSelectedId = config.selectedRegionId();
        AnchorRegion restored = null;
        if (persistedSelectedId >= 0) {
            for (AnchorRegion r : snapshot) {
                if (r.getId() == persistedSelectedId) {
                    restored = r;
                    break;
                }
            }
        }
        final AnchorRegion restoredFinal = restored;
        SwingUtilities.invokeLater(() -> {
            panel.updateList(snapshot);
            if (restoredFinal != null) {
                panel.setSelectedRegion(restoredFinal);
            }
        });
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
        // Clamp to >= 0 so we never spawn off-canvas if the viewport is still 0x0 early
        // in client startup.
        int x = (viewport != null) ? Math.max(0, viewport.width / 2 - 50) : 100;
        int y = (viewport != null) ? Math.max(0, viewport.height / 2 - 50) : 100;

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
        if (region == null) return;
        final int deletedId = region.getId();
        clientThread.invoke(() -> {
            anchorRegions.remove(region);
            // Fix A1: clean up every overlay assignment pointing at the deleted region
            // so a) the orphaned overlays stop counting as "assigned" and b) a future
            // region that reuses this id can't silently inherit them.
            boolean removedAny = overlayAssignments.values().removeIf(v -> v != null && v == deletedId);
            if (removedAny) {
                markAssignmentsDirty();
            }
            saveRegions();
            selectAnchor(null);
            final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
            SwingUtilities.invokeLater(() -> panel.updateList(snap));
        });
    }

    public void selectAnchor(AnchorRegion region) {
        // Fix SEL: persist which region the user had selected so we can restore it
        // on next startup. -1 represents "no selection".
        final int id = region == null ? -1 : region.getId();
        if (config.selectedRegionId() != id) {
            config.setSelectedRegionId(id);
        }
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
                Set<Integer> usedIds = new HashSet<>();
                int nextSyntheticId = 1;
                for (AnchorRegion r : loaded) {
                    if (r == null) continue;
                    // Normalize null enums (may occur in old persisted data)
                    if (r.getConstraint() == null) r.setConstraint(AnchorConstraint.TOP_LEFT);
                    if (r.getAlignment() == null) r.setAlignment(AnchorAlignment.CENTER);
                    if (r.getStacking() == null) r.setStacking(AnchorStacking.VERTICAL);

                    // Defensive validation against corrupt/old config data
                    if (r.getWidth() < 10) r.setWidth(10);
                    if (r.getHeight() < 10) r.setHeight(10);

                    // De-duplicate IDs: the first occurrence keeps its id; later duplicates
                    // (or non-positive ids) get reassigned to the next free positive int.
                    int id = r.getId();
                    if (id <= 0 || usedIds.contains(id)) {
                        while (usedIds.contains(nextSyntheticId)) nextSyntheticId++;
                        id = nextSyntheticId++;
                        r.setId(id);
                    }
                    usedIds.add(id);

                    // Default name if missing, so the list renderer never shows null/empty
                    String name = r.getName();
                    if (name == null || name.isEmpty()) {
                        r.setName("Box " + id);
                    }

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
     *
     * Fix R2: snapshot the list before serializing so callers on any thread (including
     * AWT via {@link #saveRegionsFromAnyThread}) can't trip a CME against client-thread
     * mutations.
     */
    public void saveRegions() {
        List<AnchorRegion> snapshot = new ArrayList<>(anchorRegions);
        String json = gson.toJson(snapshot);
        lastWrittenRegionJson = json;
        config.setRegionJson(json);
        regionsDirty = false;
    }

    /**
     * Fix R1: thread-safe entry point for AWT-side callers (e.g. the input listener's
     * {@code mouseReleased}). Marshals the write onto the client thread where the
     * region list is mutated, avoiding contention with create/delete/load.
     */
    public void saveRegionsFromAnyThread() {
        clientThread.invoke(this::saveRegions);
    }

    /**
     * Kept for backwards compatibility with older call sites; forwards to
     * {@link #saveRegions()}. Regions are small and change infrequently, so we always
     * persist synchronously rather than debouncing.
     */
    private void markRegionsDirty() {
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

    /**
     * Returns an immutable snapshot of the current anchor regions. The underlying list
     * is mutated only on the client thread; callers on other threads (AWT mouse handlers,
     * overlay-manager callbacks) iterate this snapshot safely without risk of
     * {@link java.util.ConcurrentModificationException}.
     */
    public List<AnchorRegion> getAnchorRegions() {
        return Collections.unmodifiableList(new ArrayList<>(anchorRegions));
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
            if (lastViewport == null) {
                // First observation ever; just seed.
                lastViewport = currentDim;
            } else if (!lastViewport.equals(currentDim)) {
                // Three defense layers against false-positive "resizes" that would corrupt
                // saved anchor positions. See field-level comment on VIEWPORT_GRACE_MS.
                boolean inStartupGrace =
                        (System.currentTimeMillis() - startupTimeMs) < VIEWPORT_GRACE_MS;
                boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
                int deltaW = Math.abs(currentDim.width - lastViewport.width);
                int deltaH = Math.abs(currentDim.height - lastViewport.height);
                // Guard against division by zero / degenerate dims during client init.
                int halfW = Math.max(1, currentDim.width / 2);
                int halfH = Math.max(1, currentDim.height / 2);
                boolean plausibleDelta = deltaW <= halfW && deltaH <= halfH;

                if (inStartupGrace || !loggedIn || !plausibleDelta) {
                    // Silently track the new dimension; do NOT shift or persist regions.
                    lastViewport = currentDim;
                } else {
                    isResizingWindow = updateRegionPositions(lastViewport, currentDim);
                    lastViewport = currentDim;
                }
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
     *
     * Assumption: the name a plugin sets for its overlay is stable across sessions.
     * This is true for every stock RuneLite overlay; third-party plugins that derive
     * their overlay name from a per-session random value would lose their assignment
     * on restart, but that is a bug in the other plugin, not in ours.
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
    /**
     * How long after the last external move of an overlay we still consider it "hot" for
     * assignment changes. Must exceed {@link #DRAG_DETECT_MS} so that the tick(s) after
     * drop can commit the final capture / release. Must be short enough that an overlay
     * the user dragged long ago can't be re-associated by unrelated activity.
     */
    private static final long ASSIGNMENT_GRACE_MS = 500L;

    /**
     * Populated by {@link #runFusedOverlayWalk()} each tick: every movable overlay
     * currently present in the {@link OverlayManager}, keyed by {@link #overlayKey(Overlay)}.
     * Used by the capture pass in {@link #snapAndStackOverlays(boolean)} so that a
     * brand-new (never-before-tracked) overlay can be captured on its first drag into
     * a region. Client-thread-only; no synchronization needed.
     */
    private final Map<String, Overlay> movableOverlayByKey = new HashMap<>();

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

        // Track mouse position so we can update bookkeeping even when nothing else changed.
        net.runelite.api.Point mcp = client.getMouseCanvasPosition();
        lastMouseX = mcp != null ? mcp.getX() : Integer.MIN_VALUE;
        lastMouseY = mcp != null ? mcp.getY() : Integer.MIN_VALUE;

        // Fix EXP2: run the fused walk every tick so drag detection covers EVERY movable
        // overlay — not just ones already in trackedOverlays. Without this, a brand-new
        // overlay the user drags into a region for the first time never registers as
        // "moving" and therefore never becomes capturable. The walk itself is cheap
        // (iterates ~dozens of overlays per tick).
        runFusedOverlayWalk();
        lastFusedWalkTime = nowMs;

        // Build the "dragging" and "hot" sets from lastExternalMoveTime. An overlay is
        // "dragging" if its preferredLocation changed within DRAG_DETECT_MS. It is "hot"
        // (eligible for assignment mutation) if it changed within ASSIGNMENT_GRACE_MS.
        // This gate replaces the old Alt-hover-based lockAssignments flag. The key
        // property: window resize / anchor drag / panel edit do NOT move overlay
        // preferredLocations (only anchor coordinates), so those states leave hotIds empty
        // and the capture pass below is a no-op — preventing overlay absorption.
        final Set<String> draggingIds = new HashSet<>();
        final Set<String> hotIds = new HashSet<>();
        for (Map.Entry<String, Long> e : lastExternalMoveTime.entrySet()) {
            long age = nowMs - e.getValue();
            if (age < ASSIGNMENT_GRACE_MS) hotIds.add(e.getKey());
            if (age < DRAG_DETECT_MS) draggingIds.add(e.getKey());
        }

        Map<Integer, List<Overlay>> buckets = new HashMap<>();
        for (AnchorRegion r : regionsSnapshot) {
            buckets.put(r.getId(), new ArrayList<>());
        }

        // --- CAPTURE PASS (hot overlays only) ---
        // This is the ONLY place in the tick loop that mutates overlayAssignments.
        // For every overlay the user is currently dragging, or just dropped within the
        // last ASSIGNMENT_GRACE_MS, test its center against every region:
        //   - inside a region  → track + assign + bucket
        //   - outside all regions AND not mid-drag → release (drop in empty space)
        //   - outside all regions AND mid-drag    → keep in current bucket for visual stability
        final Set<String> alreadyBucketed = new HashSet<>();
        for (String overlayId : hotIds) {
            Overlay overlay = trackedOverlays.get(overlayId);
            if (overlay == null) overlay = movableOverlayByKey.get(overlayId);
            if (overlay == null || overlay == customizerOverlay) continue;
            if (!overlay.isMovable() || overlay.getPreferredLocation() == null) continue;

            Rectangle overlayBounds = overlay.getBounds();
            if (overlayBounds.isEmpty()) {
                Point loc = overlay.getPreferredLocation();
                Dimension size = overlay.getPreferredSize();
                if (size == null) size = new Dimension(100, 20);
                overlayBounds = new Rectangle(loc.x, loc.y, size.width, size.height);
            }
            Point center = new Point((int) overlayBounds.getCenterX(), (int) overlayBounds.getCenterY());

            Integer assignedRegionId = overlayAssignments.get(overlayId);
            boolean isDraggingThis = draggingIds.contains(overlayId);

            boolean foundInRegion = false;
            for (AnchorRegion region : regionsSnapshot) {
                if (region.getBounds().contains(center)) {
                    if (assignedRegionId == null || !assignedRegionId.equals(region.getId())) {
                        overlayAssignments.put(overlayId, region.getId());
                        markAssignmentsDirty();
                    }
                    if (!trackedOverlays.containsKey(overlayId)) {
                        trackedOverlays.put(overlayId, overlay);
                    }
                    buckets.get(region.getId()).add(overlay);
                    alreadyBucketed.add(overlayId);
                    foundInRegion = true;
                    break;
                }
            }

            if (!foundInRegion) {
                if (isDraggingThis && assignedRegionId != null && buckets.containsKey(assignedRegionId)) {
                    // Mid-drag across empty space: keep in old bucket so others don't reshuffle.
                    buckets.get(assignedRegionId).add(overlay);
                    alreadyBucketed.add(overlayId);
                } else if (!isDraggingThis) {
                    // In grace window but not actively moving → this is the drop tick.
                    // Commit the release.
                    if (overlayAssignments.containsKey(overlayId)) {
                        overlayAssignments.remove(overlayId);
                        markAssignmentsDirty();
                        log.debug("Released overlay {} from all regions (dropped in empty space)", overlayId);
                    }
                }
            }
        }

        // --- MAINTENANCE PASS (all tracked overlays not already handled above) ---
        // No mutation — just bucket each overlay into its current assigned region so
        // stacking/alignment continues to work for anyone who isn't mid-drag. This is
        // what lets a stable assigned overlay keep its position as its anchor moves
        // during a window resize.
        for (Map.Entry<String, Overlay> entry : trackedOverlays.entrySet()) {
            String overlayId = entry.getKey();
            if (alreadyBucketed.contains(overlayId)) continue;
            Overlay overlay = entry.getValue();

            if (overlay.getPreferredLocation() == null || !overlay.isMovable())
                continue;

            Integer assignedRegionId = overlayAssignments.get(overlayId);
            if (assignedRegionId != null && buckets.containsKey(assignedRegionId)) {
                buckets.get(assignedRegionId).add(overlay);
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

        // End-of-tick: snapshot every MOVABLE overlay's current preferredLocation so
        // that the next tick's drag-detection pass can tell whether it changed between
        // ticks. Fix EXP2: we widen this from trackedOverlays to every movable overlay
        // present in the OverlayManager (via movableOverlayByKey, populated by the fused
        // walk). Without this, an untracked overlay the user starts dragging would never
        // get a baseline location stored, so its movement would never be detected and
        // it could never be captured on its first drag into a region.
        for (Map.Entry<String, Overlay> entry : movableOverlayByKey.entrySet()) {
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
     *
     * Fix L1: if a re-acquired overlay has no preferredLocation or sits entirely outside
     * its assigned region's bounds (e.g. because RuneLite lost the per-overlay position
     * config, or the region was resized since last session), seed its preferredLocation
     * to the region's top-left corner so the next snap pass can reposition it deterministically.
     * Without this seed, the snap loop's {@code if (preferredLocation == null) continue} guard
     * would leave the overlay stranded and its assignment would appear inert.
     */
    private void scanAndReacquireOverlays() {
        if (overlayAssignments.isEmpty()) {
            return;
        }

        // Build a quick region-id lookup for the seed step
        final Map<Integer, AnchorRegion> regionsById = new HashMap<>();
        for (AnchorRegion r : anchorRegions) {
            regionsById.put(r.getId(), r);
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
            Integer assignedRegionId = overlayAssignments.get(overlayId);
            if (assignedRegionId != null && !trackedOverlays.containsKey(overlayId)) {
                trackedOverlays.put(overlayId, overlay);
                log.debug("Re-acquired overlay {} for region {}", overlayId, assignedRegionId);

                // Fix L1: seed preferredLocation if missing or out-of-region
                AnchorRegion region = regionsById.get(assignedRegionId);
                if (region != null) {
                    Point loc = overlay.getPreferredLocation();
                    Rectangle regionBounds = region.getBounds();
                    boolean needsSeed = loc == null || !regionBounds.contains(loc);
                    if (needsSeed) {
                        overlay.setPreferredLocation(new Point(region.getX(), region.getY()));
                        if (overlay.getPreferredPosition() != OverlayPosition.DYNAMIC) {
                            overlay.setPreferredPosition(OverlayPosition.DYNAMIC);
                        }
                        log.debug("Seeded overlay {} preferredLocation to region {} origin", overlayId, assignedRegionId);
                    }
                }
            }

            return false; // Always return false to continue scanning all overlays
        });

        log.debug("Re-acquired {} overlay references out of {} assignments",
                  trackedOverlays.size(), overlayAssignments.size());
    }

    /**
     * Reconcile {@code trackedOverlays} with the live {@link OverlayManager}. Builds
     * an identity set of currently-present overlays and removes any stale references
     * we still hold for overlays that have been unregistered.
     *
     * Fix EXP: this method used to also auto-track any overlay whose center happened
     * to fall inside an anchor region, which caused "absorption" bugs on window
     * resize and large anchor edits. That behavior has been removed — the only way
     * to associate an overlay with a region is now the explicit drag-drop path in
     * {@link #onOverlayDragged(Overlay)}.
     */
    private void runFusedOverlayWalk() {
        final Set<Overlay> present = Collections.newSetFromMap(new IdentityHashMap<>());
        movableOverlayByKey.clear();
        final long now = System.currentTimeMillis();

        overlayManager.anyMatch(ov -> {
            if (ov == null) return false;
            present.add(ov);
            if (ov == customizerOverlay || !ov.isMovable()) return false;

            String id = overlayKey(ov);
            if (id == null) return false;
            movableOverlayByKey.put(id, ov);

            // External-move detection: compare current preferredLocation to the one we
            // recorded at the end of the previous tick. If they differ, the user (or
            // RuneLite's drag renderer) moved it — record the timestamp so this overlay
            // enters draggingIds / hotIds in the snap pass.
            Point cur = ov.getPreferredLocation();
            if (cur != null) {
                Point last = lastSeenLocations.get(id);
                if (last != null && !cur.equals(last)) {
                    lastExternalMoveTime.put(id, now);
                }
            }
            return false; // scan all
        });

        // Remove any tracked references whose overlays no longer exist in the manager.
        // The persisted assignment in overlayAssignments is intentionally retained so
        // that if the overlay re-registers later (e.g. its owning plugin toggles), we
        // can re-acquire it in scanAndReacquireOverlays.
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
