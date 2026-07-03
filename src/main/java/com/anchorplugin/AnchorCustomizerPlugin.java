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
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(name = "Custom UI Anchors", description = "Visualize and customize overlay anchor positions. Alt+drag to move anchor points.", tags = {
        "overlay", "anchor", "position", "customization", "ui" })
public class AnchorCustomizerPlugin extends Plugin {

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
    private KeyManager keyManager;

    @Inject
    private AnchorKeyListener anchorKeyListener;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ClientThread clientThread;

    private AnchorCustomizerPanel panel;
    private NavigationButton navButton;
    private volatile boolean navButtonAdded = false;

    @Inject
    private Gson gson;

    private final List<AnchorRegion> anchorRegions = new ArrayList<>();
    private Dimension lastViewport = null;

    // ---- Resolution profile system -------------------------------------------------
    //
    // Region geometry is stored per layout profile so machines with different monitors
    // (synced through one RuneLite config) stop overwriting each other's layouts.
    //
    // Profile keys:
    //   "fixed"      — fixed client mode. The game canvas is always 765x503 in fixed
    //                  mode (even under Stretched Mode, since getRealDimensions() is
    //                  pre-stretch), so this single profile is pixel-perfect on every
    //                  machine with no scaling.
    //   "<W>x<H>"    — resizable mode, keyed by the monitor's display-mode resolution
    //                  (NOT the canvas size — window resizes within one monitor stay in
    //                  one profile and are handled by the origin-derivation tick loop).
    //
    // Every monitor resolution is a fully INDEPENDENT profile storing absolute pixel
    // geometry — edits on one monitor never propagate to another. (An earlier design
    // shared one scaled canonical across integer-related resolutions, e.g. 1080p/4K at
    // x2; that assumed the canvas scales with the monitor, which is false when a
    // fixed-size window is dragged across monitors, and its shared write-back clobbered
    // sibling layouts. See GitHub issue on multi-resolution setups.) A brand-new
    // profile gets a one-time seed from the outgoing live layout, scaled by the
    // OBSERVED canvas dimension change across the swap — an exact copy when the window
    // wasn't resized — and is on its own track thereafter.
    private final Map<String, List<AnchorRegion>> profileLayouts = new HashMap<>();
    private String activeProfileKey = null;
    private String lastWrittenProfilesJson = null;

    private static final String FIXED_PROFILE_KEY = "fixed";

    // Throttle for the profile re-check in onClientTick (the display-mode query goes
    // through the AWT toolkit; no need to poll it at tick rate when nothing changed).
    private long lastProfileCheckTime = 0L;
    private static final long PROFILE_CHECK_INTERVAL_MS = 1000L;

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
    //      LARGER of the old and new canvas dimensions (catches anything the other two
    //      miss, e.g. an unexpected mid-session fullscreen/monitor swap we'd rather not
    //      auto-shift for). The threshold is symmetric on purpose: an earlier version
    //      used only the new dimension, which silently dropped the inverse half of any
    //      large step (most visible on cross-monitor drags with mismatched DPI — the
    //      grow-into-bigger-monitor delta passed but the shrink-back delta was clamped
    //      out, stranding right/bottom-anchored regions hundreds of pixels off-screen).
    private static final long VIEWPORT_GRACE_MS = 1500L;
    private long startupTimeMs = 0L;

    // Debounced persistence + self-triggered config-reload suppression
    private static final long SAVE_DEBOUNCE_MS = 500L;
    private boolean regionsDirty = false;
    private boolean assignmentsDirty = false;
    private boolean orderDirty = false;
    private boolean profilesDirty = false;
    private long lastFlushTime = 0L;
    private String lastWrittenRegionJson = null;
    private String lastWrittenAssignmentsJson = null;
    private String lastWrittenOrderJson = null;

    // Periodic re-acquire throttle for overlays that load after startup
    private long lastReacquireTime = 0L;
    private static final long REACQUIRE_INTERVAL_MS = 1000L;

    // Throttle for the opt-in "why isn't this overlay following its anchor" diagnostic dump.
    private long lastFollowLogTime = 0L;
    private static final long FOLLOW_LOG_INTERVAL_MS = 1000L;

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
        return isPanelOpen || isDragKeyHeld();
    }

    /**
     * Whether RuneLite's configured drag hotkey is currently held. Replaces the old
     * hardcoded Alt check so the plugin honours the user's "Drag hotkey" setting
     * (GitHub issue #4). Falls back to Alt when the hotkey is unset.
     */
    public boolean isDragKeyHeld() {
        return anchorKeyListener != null && anchorKeyListener.isHeld();
    }

    /**
     * Event-aware variant of {@link #isDragKeyHeld()} for the mouse listeners: resolves the
     * drag hotkey from the client's live key state and the AWT event's own modifiers rather
     * than the tracked flag, so anchor drag/resize keeps working even when the tracked flag
     * desyncs (e.g. focus blips around the 117 HD canvas, or a non-modifier hotkey whose
     * KEY_PRESSED is consumed before our listener sees it). See {@link AnchorKeyListener#isHotkeyDownLive}.
     */
    public boolean isDragHotkeyActive(java.awt.event.MouseEvent e) {
        return anchorKeyListener != null && anchorKeyListener.isHotkeyDownLive(e);
    }

    /**
     * No-event variant of {@link #isDragHotkeyActive(java.awt.event.MouseEvent)} for the overlay
     * render pass (which has no MouseEvent). Lets the anchor highlight follow the user's configured
     * drag hotkey instead of a hardcoded Alt, so it lights up for whatever key actually edits.
     */
    public boolean isDragHotkeyActive() {
        return anchorKeyListener != null && anchorKeyListener.isHotkeyDownLive();
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
        lastWrittenProfilesJson = null;
        activeProfileKey = null;
        profileLayouts.clear();
        lastProfileCheckTime = 0L;
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
        profilesDirty = false;
        needsSnap = false;
        navButtonAdded = false;

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
                // Do NOT re-interrupt: startUp() runs on a RuneLite-owned thread
                // (shared executor / client thread). Re-asserting the interrupt
                // flag can cause unrelated blocking calls scheduled on this
                // thread to throw spuriously. The RuntimeException below
                // already signals startup failure unambiguously, and the
                // original InterruptedException is preserved as the cause.
                throw new RuntimeException("Failed to initialise Custom UI Anchors panel on EDT", e);
            }
        }

        if (config.showSidebarButton()) {
            clientToolbar.addNavigation(navButton);
            navButtonAdded = true;
        }

        overlayManager.add(customizerOverlay);
        mouseManager.registerMouseListener(inputListener);
        anchorKeyListener.reset();
        // When the edit hotkey is released (or focus is lost), cancel any in-progress drag
        // and reset the cursor. cancelDrag() is a no-op when nothing is being dragged, so
        // this also covers the "stuck cursor while only hovering" case.
        anchorKeyListener.setOnReleased(() -> {
            inputListener.cancelDrag();
            inputListener.resetCursorToDefault();
        });
        keyManager.registerKeyListener(anchorKeyListener);
        // One-time heal for in-place upgrades: older builds set an explicit cursor directly
        // on the game canvas, which overrode the Custom Cursor plugin's cursor (set on the
        // parent container) whenever the mouse was over the canvas, and was never cleared —
        // stranding users on the Windows default until a full client restart. Clearing the
        // canvas cursor restores inheritance from the container; all cursor changes now go
        // through ClientUI (AnchorInputListener), which never touches the canvas again.
        java.awt.Canvas startupCanvas = client.getCanvas();
        if (startupCanvas != null) {
            startupCanvas.setCursor(null);
        }
        // loadRegions materializes the legacy "last live layout" snapshot so the panel
        // and login-screen overlays behave exactly as before. The first logged-in tick
        // resolves the actual layout profile (fixed mode / monitor resolution) and swaps
        // in its stored geometry via switchProfile.
        loadRegions();
        loadProfiles();
        loadOverlayAssignments();
        loadOverlayOrder();

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
        keyManager.unregisterKeyListener(anchorKeyListener);
        if (navButtonAdded) {
            clientToolbar.removeNavigation(navButton);
            navButtonAdded = false;
        }
        anchorRegions.clear();
        profileLayouts.clear();
        activeProfileKey = null;
        trackedOverlays.clear();
        overlayAssignments.clear();
        overlayOrder.clear();
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
                // Once the profile system owns the layout, remote regionJson writes are
                // just another machine's back-compat "last live layout" snapshot — its
                // canvas/monitor context, not ours. Reloading it here is exactly the
                // cross-machine clobbering the profiles exist to prevent, so ignore it.
                // Pure-legacy sessions (no profiles yet) keep the old reload behavior.
                if (activeProfileKey != null || !profileLayouts.isEmpty()) {
                    return;
                }
                loadRegions();
                final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
                SwingUtilities.invokeLater(() -> panel.updateList(snap));
            });
        } else if (event.getKey().equals("regionProfiles")) {
            String incoming = event.getNewValue();
            if (incoming != null && incoming.equals(lastWrittenProfilesJson)) {
                return;
            }
            clientThread.invoke(() -> {
                // Remote profile update (another machine edited some profile). Reload the
                // map; if our active profile's stored layout changed, re-materialize it.
                loadProfiles();
                if (activeProfileKey == null) {
                    return;
                }
                List<AnchorRegion> stored = profileLayouts.get(activeProfileKey);
                if (stored == null) {
                    return;
                }
                List<AnchorRegion> live = deepCopy(stored);
                live = normalizeRegions(live);
                anchorRegions.clear();
                anchorRegions.addAll(live);
                Rectangle vp = getViewportBounds();
                if (vp != null && vp.width > 0 && vp.height > 0) {
                    for (AnchorRegion r : anchorRegions) {
                        recomputePosition(r, vp.getSize());
                    }
                }
                requestSnap();
                final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
                SwingUtilities.invokeLater(() -> panel.updateList(snap));
            });
        } else if (event.getKey().equals("overlayAssignments")) {
            String incoming = event.getNewValue();
            if (incoming != null && incoming.equals(lastWrittenAssignmentsJson)) {
                return;
            }
            clientThread.invoke(this::loadOverlayAssignments);
        } else if (event.getKey().equals("overlayOrder")) {
            String incoming = event.getNewValue();
            if (incoming != null && incoming.equals(lastWrittenOrderJson)) {
                return;
            }
            clientThread.invoke(this::loadOverlayOrder);
        } else if (event.getKey().equals("showSidebarButton")) {
            SwingUtilities.invokeLater(this::syncSidebarButton);
        }
    }

    /**
     * Add or remove the sidebar navigation button to match the {@code showSidebarButton}
     * config (GitHub issue #3). Idempotent via {@code navButtonAdded} so repeated config
     * events can't double-add or double-remove. Runs on the EDT (Swing contract).
     */
    private void syncSidebarButton() {
        if (navButton == null) {
            return;
        }
        boolean shouldShow = config.showSidebarButton();
        if (shouldShow && !navButtonAdded) {
            clientToolbar.addNavigation(navButton);
            navButtonAdded = true;
        } else if (!shouldShow && navButtonAdded) {
            clientToolbar.removeNavigation(navButton);
            navButtonAdded = false;
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

        // Origins seeded to 0 here; rebaselineOrigin below captures the real values
        // from current canvas dim immediately after the region is added.
        AnchorRegion region = new AnchorRegion(
                nextId,
                "Box " + nextId,
                x,
                y,
                100,
                100,
                AnchorConstraint.TOP_LEFT,
                AnchorAlignment.CENTER,
                AnchorStacking.VERTICAL,
                false,
                0, 0, 0, 0);
        rebaselineOrigin(region);

        anchorRegions.add(region);
        markRegionsDirty();
        selectAnchor(region);
        final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
        SwingUtilities.invokeLater(() -> panel.updateList(snap));
    }

    /**
     * Move a region to a new index in the layer list (panel drag-and-drop reorder).
     * Index 0 is the TOP of the layer stack: it is drawn last by the customizer
     * overlay and picked first by the input listener. The list order is persisted via
     * {@link #saveRegions()} (it's simply the JSON array order), so layering survives
     * restarts and is stored per resolution profile.
     */
    public void moveRegionToIndex(AnchorRegion region, int targetIndex) {
        if (region == null) return;
        clientThread.invoke(() -> {
            int from = anchorRegions.indexOf(region);
            if (from < 0) return;
            int to = Math.max(0, Math.min(targetIndex, anchorRegions.size() - 1));
            if (from == to) return;
            anchorRegions.remove(from);
            anchorRegions.add(to, region);
            saveRegions();
            final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
            SwingUtilities.invokeLater(() -> panel.updateList(snap));
        });
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
            // Any panel commit (X/Y/W/H spinner, constraint, alignment, stacking, name)
            // counts as a user-confirmed geometry. Rebaseline the origin so subsequent
            // window resizes are derived from this state, not the pre-edit state.
            rebaselineOrigin(region);
            saveRegions();
        });
    }

    /**
     * Snapshot the region's current geometry as the new origin baseline. The tick-loop
     * recompute derives live (x, y) every tick from (origin, currentCanvasDim, constraint),
     * so this baseline is the only thing that anchors the region in canvas space.
     *
     * Called from every site where the user has confirmed a geometry: drag end, edge-resize
     * end, panel field commits, region creation, and lazy migration of legacy regions on
     * their first valid tick.
     *
     * If the current canvas dim is unavailable (e.g. very early startup), origins are
     * left as-is — the next tick that observes a valid dim will retry. The recompute
     * path treats originW <= 0 as "not seeded yet" and skips the region until then.
     */
    public void rebaselineOrigin(AnchorRegion region) {
        if (region == null) return;
        Rectangle viewport = getViewportBounds();
        if (viewport == null) return;
        int w = viewport.width;
        int h = viewport.height;
        if (w <= 0 || h <= 0) return;
        region.setOriginX(region.getX());
        region.setOriginY(region.getY());
        region.setOriginW(w);
        region.setOriginH(h);
    }

    /**
     * AWT-thread-safe entry point for the input listener. Marshals onto the client
     * thread to match where {@code anchorRegions} is iterated.
     */
    public void rebaselineOriginFromAnyThread(AnchorRegion region) {
        if (region == null) return;
        clientThread.invoke(() -> rebaselineOrigin(region));
    }

    /**
     * Recompute a region's live (x, y) from its origin snapshot and the current canvas
     * dim, applying the constraint's directional multiplier:
     *
     *   liveX = originX + (currentW - originW) * hMul
     *   liveY = originY + (currentH - originH) * vMul
     *
     * where hMul / vMul are 0 (left/top), 0.5 (center), or 1 (right/bottom). This
     * makes position a pure function of (origin, currentDim, constraint) — drift-free,
     * immune to phantom dimension samples, and deterministic regardless of how many
     * resize events the OS / GPU plugin / DPI subsystem fires.
     *
     * No-op if the region has not been seeded with valid origins (e.g. legacy data
     * loaded before this field existed). The tick loop seeds those on first valid
     * dim observation.
     */
    private void recomputePosition(AnchorRegion region, Dimension currentDim) {
        if (region == null || currentDim == null) return;
        int originW = region.getOriginW();
        int originH = region.getOriginH();
        if (originW <= 0 || originH <= 0) return;

        AnchorConstraint constraint = region.getConstraint();
        if (constraint == null) constraint = AnchorConstraint.TOP_LEFT;

        int deltaW = currentDim.width - originW;
        int deltaH = currentDim.height - originH;

        int xShift;
        switch (constraint) {
            case TOP_RIGHT:
            case CENTER_RIGHT:
            case BOTTOM_RIGHT:
                xShift = deltaW;
                break;
            case TOP_CENTER:
            case CENTER:
            case BOTTOM_CENTER:
                xShift = deltaW / 2;
                break;
            case TOP_LEFT:
            case CENTER_LEFT:
            case BOTTOM_LEFT:
            default:
                xShift = 0;
                break;
        }

        int yShift;
        switch (constraint) {
            case BOTTOM_LEFT:
            case BOTTOM_CENTER:
            case BOTTOM_RIGHT:
                yShift = deltaH;
                break;
            case CENTER_LEFT:
            case CENTER:
            case CENTER_RIGHT:
                yShift = deltaH / 2;
                break;
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
            default:
                yShift = 0;
                break;
        }

        region.setX(region.getOriginX() + xShift);
        region.setY(region.getOriginY() + yShift);
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
                anchorRegions.addAll(normalizeRegions(loaded));
            }
        } catch (Exception e) {
            log.error("Failed to load regions", e);
        }
    }

    /**
     * Validate and repair a deserialized region list: null entries dropped, null enums
     * defaulted, degenerate sizes clamped, duplicate/non-positive IDs reassigned, and
     * missing names defaulted. Shared by the legacy {@link #loadRegions()} path and the
     * profile-materialization path so both apply identical hygiene.
     */
    private List<AnchorRegion> normalizeRegions(List<AnchorRegion> loaded) {
        List<AnchorRegion> result = new ArrayList<>();
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

            result.add(r);
        }
        return result;
    }

    /** Parse the per-resolution profile map from config. Tolerates missing/corrupt JSON. */
    private void loadProfiles() {
        profileLayouts.clear();
        String json = config.regionProfilesJson();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            Type mapType = new TypeToken<Map<String, List<AnchorRegion>>>() {
            }.getType();
            Map<String, List<AnchorRegion>> loaded = gson.fromJson(json, mapType);
            if (loaded != null) {
                for (Map.Entry<String, List<AnchorRegion>> e : loaded.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        profileLayouts.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load region profiles", e);
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
        // Legacy "last live layout" snapshot — kept for older plugin versions and as the
        // startup materialization source before the first logged-in tick resolves a
        // profile. Note onConfigChanged deliberately no longer reloads from remote
        // regionJson writes when profiles exist (see there).
        String json = gson.toJson(snapshot);
        lastWrittenRegionJson = json;
        config.setRegionJson(json);

        // Authoritative per-profile storage: fold the live layout back into the active
        // profile's entry (absolute pixels, deep-copied). The actual config write is
        // DEBOUNCED via flushPendingSaves — saveRegions runs every tick during a window
        // resize, and serializing the whole profile map + firing a second ConfigChanged
        // through the event bus at 50 Hz was measurably costing frame time. The in-memory
        // map is always current; only the persistence is deferred (and force-flushed on
        // shutDown / profile switches).
        if (activeProfileKey != null) {
            profileLayouts.put(activeProfileKey, deepCopy(snapshot));
        }
        profilesDirty = true;
        regionsDirty = false;
    }

    // ---- Resolution profile core ----------------------------------------------------

    /**
     * Deep-copy a region list so the stored layouts never alias live region objects
     * mutated by the input listener or panel (and vice versa on materialization).
     */
    private List<AnchorRegion> deepCopy(List<AnchorRegion> regions) {
        List<AnchorRegion> out = new ArrayList<>(regions.size());
        for (AnchorRegion r : regions) {
            out.add(r.scaledCopy(1.0));
        }
        return out;
    }

    /**
     * Resolve the current client state to a layout profile key.
     *
     * Fixed client mode always maps to the universal {@code "fixed"} profile (canvas is
     * 765x503 everywhere). Resizable mode is keyed by the monitor's display-mode
     * resolution — every distinct resolution is its own fully independent profile.
     * (Deliberately NO integer-factor "family" matching: inferring a scale factor from
     * monitor resolution ratios corrupted layouts on multi-resolution setups where the
     * window keeps its pixel size across monitors, and the shared write-back meant
     * editing on one monitor clobbered its sibling's layout.)
     *
     * Returns null when the client state can't be resolved yet (no canvas, no graphics
     * configuration, degenerate display mode) — callers simply retry next tick.
     */
    private String computeProfileMatch() {
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) return null;
        if (!client.isResized()) {
            return FIXED_PROFILE_KEY;
        }
        java.awt.GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
        if (gc == null) return null;
        java.awt.DisplayMode dm = gc.getDevice().getDisplayMode();
        int mw = dm.getWidth();
        int mh = dm.getHeight();
        if (mw <= 0 || mh <= 0) return null;
        return mw + "x" + mh;
    }

    /** Clamp a region fully on-canvas (and shrink it if larger than the canvas). */
    private void clampToCanvas(AnchorRegion r, Dimension dim) {
        if (dim == null || dim.width <= 0 || dim.height <= 0) return;
        if (r.getWidth() > dim.width) r.setWidth(Math.max(10, dim.width));
        if (r.getHeight() > dim.height) r.setHeight(Math.max(10, dim.height));
        r.setX(Math.max(0, Math.min(r.getX(), dim.width - r.getWidth())));
        r.setY(Math.max(0, Math.min(r.getY(), dim.height - r.getHeight())));
    }

    /**
     * Swap the live layout to a different profile. Runs on the client thread (called
     * from onClientTick).
     *
     * Steps: fold the outgoing live layout back into its profile entry; materialize the
     * incoming profile's stored layout as-is (or seed a brand-new profile from the
     * outgoing layout, scaled once by the OBSERVED canvas dimension change across the
     * swap — an exact pixel-perfect copy when the window wasn't resized, e.g. a plain
     * drag to another monitor); derive to the actual canvas; clamp only when seeding
     * (never fight a layout the user saved deliberately); rebaseline origins; persist;
     * refresh the panel.
     *
     * After the one-time seed a profile is fully independent — edits never propagate
     * across monitors.
     */
    private void switchProfile(String key, Dimension currentDim) {
        // Never carry an in-progress drag across a layout swap — the dragged object
        // would be orphaned from the new live list.
        inputListener.cancelDrag();

        if (activeProfileKey != null) {
            profileLayouts.put(activeProfileKey, deepCopy(anchorRegions));
        }

        List<AnchorRegion> stored = profileLayouts.get(key);
        boolean seeded = (stored == null);
        List<AnchorRegion> live;
        if (!seeded) {
            live = normalizeRegions(deepCopy(stored));
        } else {
            // One-time seed: scale by the actual canvas change across this swap
            // (lastViewport still holds the pre-swap dim here; the tick loop updates
            // it after we return). Per-axis, since window chrome means maximized
            // canvases are not exact monitor-ratio multiples. Ratios near 1 collapse
            // to an exact copy so an un-resized cross-monitor drag stays pixel-perfect.
            // Only meaningful for a real swap from a live profile — the startup
            // resolution (activeProfileKey == null) always copies as-is.
            double fx = 1.0;
            double fy = 1.0;
            if (activeProfileKey != null && lastViewport != null
                    && lastViewport.width > 0 && lastViewport.height > 0) {
                fx = currentDim.width / (double) lastViewport.width;
                fy = currentDim.height / (double) lastViewport.height;
                if (Math.abs(fx - 1.0) < 0.02) fx = 1.0;
                if (Math.abs(fy - 1.0) < 0.02) fy = 1.0;
            }
            live = new ArrayList<>(anchorRegions.size());
            for (AnchorRegion r : anchorRegions) {
                live.add(r.scaledCopy(fx, fy));
            }
        }

        String fromKey = activeProfileKey;
        anchorRegions.clear();
        anchorRegions.addAll(live);
        activeProfileKey = key;

        for (AnchorRegion r : anchorRegions) {
            recomputePosition(r, currentDim);
            if (seeded) {
                clampToCanvas(r, currentDim);
            }
            rebaselineOrigin(r);
        }

        log.debug("Switched layout profile {} -> {} (seeded={})", fromKey, key, seeded);
        saveRegions();
        // Profile swaps are rare and important — persist immediately rather than waiting
        // for the debounced flush.
        flushPendingSaves(true);
        requestSnap();

        final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
        final int selectedId = config.selectedRegionId();
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            panel.updateList(snap);
            for (AnchorRegion r : snap) {
                if (r.getId() == selectedId) {
                    panel.setSelectedRegion(r);
                    break;
                }
            }
        });
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
        if (orderDirty) {
            String json = gson.toJson(overlayOrder);
            lastWrittenOrderJson = json;
            config.setOverlayOrderJson(json);
            orderDirty = false;
        }
        if (profilesDirty) {
            String json = gson.toJson(profileLayouts);
            lastWrittenProfilesJson = json;
            config.setRegionProfilesJson(json);
            profilesDirty = false;
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
        // Diagnostic instrumentation: client ticks run at ~50 Hz, so anything over a
        // few ms here is eating frame budget. Logged at debug so it shows up in
        // client.log (debug is enabled there) without spamming normal users' consoles.
        final long tickStartNanos = System.nanoTime();
        try {
            tickInternal();
        } finally {
            long elapsedMs = (System.nanoTime() - tickStartNanos) / 1_000_000L;
            if (elapsedMs >= 5) {
                log.debug("Slow anchor tick: {} ms (regions={}, tracked={}, assignments={})",
                        elapsedMs, anchorRegions.size(), trackedOverlays.size(), overlayAssignments.size());
            }
        }
    }

    private void tickInternal() {
        boolean isResizingWindow = false;

        // Handle Window Resize Constraints — derivation model.
        //
        // Old design accumulated per-tick deltas onto a stored absolute (x, y),
        // which baked any noisy `getRealDimensions()` sample (DPI swap on cross-monitor
        // drag, GPU FBO/AA reset transients) into permanent state. Anything the
        // plausibility clamp didn't filter became drift or sudden displacement —
        // see `progress.txt` history if curious.
        //
        // New design: each region carries an origin snapshot (originX/Y/W/H) captured
        // at the most recent user edit. Each tick we recompute live (x, y) as a pure
        // function of (origin, currentDim, constraint). Phantom dim samples produce
        // a one-tick visual blip that auto-corrects on the next sample; nothing
        // accumulates, nothing is persisted from intermediate state.
        //
        // Startup-grace and LOGGED_IN guards remain — not because the math needs them
        // (it doesn't drift), but because we don't want to seed origins from a
        // degenerate canvas dim during early client init.
        Rectangle currentViewport = getViewportBounds();
        if (currentViewport != null) {
            Dimension currentDim = currentViewport.getSize();
            if (currentDim.width > 0 && currentDim.height > 0) {
                boolean inStartupGrace =
                        (System.currentTimeMillis() - startupTimeMs) < VIEWPORT_GRACE_MS;
                boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;

                if (lastViewport == null) {
                    lastViewport = currentDim;
                }

                if (!inStartupGrace && loggedIn) {
                    // Resolve the layout profile (fixed mode / monitor resolution) and swap
                    // layouts when it changes — first logged-in tick, fixed/resizable
                    // toggles, and cross-monitor moves all land here. switchProfile
                    // derives, clamps (seed only), rebaselines and persists, so the
                    // rest of this tick can be skipped for this pass.
                    //
                    // Throttled: the display-mode query touches the AWT toolkit, so only
                    // re-check when unresolved, when the canvas dim moved (mode toggles
                    // and cross-DPI monitor drags always change it), or once per
                    // interval as a safety net (e.g. same-dim monitor swaps).
                    long now = System.currentTimeMillis();
                    boolean checkProfile = activeProfileKey == null
                            || !lastViewport.equals(currentDim)
                            || (now - lastProfileCheckTime) >= PROFILE_CHECK_INTERVAL_MS;
                    if (checkProfile) {
                        lastProfileCheckTime = now;
                        String matchKey = computeProfileMatch();
                        if (matchKey != null && !matchKey.equals(activeProfileKey)) {
                            switchProfile(matchKey, currentDim);
                            lastViewport = currentDim;
                            snapAndStackOverlays(true);
                            flushPendingSaves(false);
                            return;
                        }
                    }

                    // Lazy-seed origins for any region missing them (legacy data, or
                    // regions created when canvas dim was unavailable). One-shot per
                    // region — once seeded, the recompute path takes over.
                    boolean seededAny = false;
                    for (AnchorRegion region : new ArrayList<>(anchorRegions)) {
                        if (region.getOriginW() <= 0 || region.getOriginH() <= 0) {
                            rebaselineOrigin(region);
                            seededAny = true;
                        }
                    }
                    if (seededAny) {
                        saveRegions();
                    }

                    // Recompute every region from its origin + currentDim. Skip the
                    // region the user is actively dragging or edge-resizing — the input
                    // listener owns its (x, y) until release, at which point it
                    // rebaselines. Without this skip the recompute would fight the drag.
                    AnchorRegion dragged = inputListener.getDraggedAnchor();
                    if (!lastViewport.equals(currentDim)) {
                        for (AnchorRegion region : new ArrayList<>(anchorRegions)) {
                            if (region == dragged) continue;
                            recomputePosition(region, currentDim);
                        }
                        isResizingWindow = true;
                        lastResizeTime = System.currentTimeMillis();
                        markRegionsDirty();
                    }
                }
                lastViewport = currentDim;
            }
        }

        snapAndStackOverlays(isResizingWindow);
        flushPendingSaves(false);
    }

    /**
     * Re-assert anchored overlay positions every rendered frame. {@link BeforeRender} fires
     * on the client thread immediately before the overlay renderer draws, so this overrides
     * any transient position reset caused by a RuneLite relayout (e.g. toggling the
     * inventory/spellbook/prayer tab) within the same frame — eliminating the 1-frame flash
     * of the default snap-corner that {@link #onClientTick}'s 50 Hz cadence could not catch.
     *
     * Cheap by construction: it only re-applies positions already computed during the tick
     * (no layout math, no OverlayManager scan), and {@code setPreferredLocation} is a no-op
     * when the position already matches — which it does on every frame except the relayout
     * one.
     */
    @Subscribe
    public void onBeforeRender(BeforeRender event) {
        reassertOverlayPositions();
    }

    private void reassertOverlayPositions() {
        if (overlayTargets.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (Map.Entry<String, Point> entry : overlayTargets.entrySet()) {
            String overlayId = entry.getKey();

            // Don't fight an overlay the user is actively dragging (same guard the tick's
            // positioning pass uses): let its cursor-driven position stand.
            Long movedAt = lastExternalMoveTime.get(overlayId);
            if (movedAt != null && (now - movedAt) < DRAG_DETECT_MS) {
                continue;
            }

            Overlay overlay = trackedOverlays.get(overlayId);
            if (overlay == null) {
                overlay = movableOverlayByKey.get(overlayId);
            }
            if (overlay == null || !overlay.isMovable()) {
                continue;
            }

            // preferredPosition is owned/normalized to null by the tick path
            // (snapAndStackOverlays); here we only re-assert the location every frame.
            // entry.getValue() is the absolute canvas target; setAbsoluteLocation converts it to
            // the overlay's (RuneLite 1.12.31+) origin-relative preferredLocation and no-ops when
            // the overlay is already rendering on target.
            setAbsoluteLocation(overlay, entry.getValue());
        }
    }

    /**
     * The overlay's true top-left in ABSOLUTE canvas coordinates.
     *
     * RuneLite 1.12.31 ("configurable overlay origins") made {@link Overlay#getPreferredLocation()}
     * origin-relative — it can be measured from the right/bottom/centre edge of the canvas, or
     * relative to a widget — so it is no longer a usable absolute point. {@link Overlay#getBounds()}
     * is the rectangle the renderer actually drew (resolved via OverlayManager.computeAbsolutePosition),
     * i.e. already origin-resolved and absolute, so we treat it as the source of truth. Falls back to
     * the raw preferredLocation only before the overlay has rendered (empty bounds), where nothing
     * better is available.
     */
    static Point absoluteTopLeftOf(Overlay overlay) {
        Rectangle b = overlay.getBounds();
        if (b != null && !b.isEmpty()) {
            return new Point(b.x, b.y);
        }
        Point pref = overlay.getPreferredLocation();
        return pref != null ? new Point(pref) : null;
    }

    /**
     * Set an overlay's preferred location to an absolute canvas point (idempotent).
     *
     * RuneLite 1.12.31 ("configurable overlay origins") made {@link Overlay#getPreferredLocation()}
     * origin-relative, but {@link #normalizeOrigin} forces every overlay we manage back to a LEFT/TOP
     * origin (via {@link net.runelite.client.ui.overlay.OverlayManager#resetOverlay}), so for our
     * overlays preferredLocation is plain absolute again. Writing the absolute value directly is
     * idempotent and clamp-safe: when the target sits near a screen edge RuneLite clamps the RENDERED
     * position, but the stored value stays put (it never accumulates), so the overlay rests against
     * the clamp instead of drifting. (An earlier delta-based "closed loop" accumulated against the
     * clamped getBounds() and made overlays jiggle off their anchors — do not reintroduce it.)
     */
    static void setAbsoluteLocation(Overlay overlay, Point absoluteTopLeft) {
        Point cur = overlay.getPreferredLocation();
        if (cur == null || cur.x != absoluteTopLeft.x || cur.y != absoluteTopLeft.y) {
            overlay.setPreferredLocation(new Point(absoluteTopLeft));
        }
    }

    /**
     * Force a managed overlay back to an absolute (LEFT/TOP) origin so {@link #setAbsoluteLocation}
     * behaves, exactly once per capture/settle.
     *
     * RuneLite 1.12.31 auto-assigns RIGHT/CENTRE/BOTTOM origins when the user free-drags an overlay
     * (OverlayRenderer.mouseDragged), which makes preferredLocation origin-relative and sends our
     * absolute writes off-screen. The package-private origin setters are unreachable, but the public
     * {@link net.runelite.client.ui.overlay.OverlayManager#resetOverlay} resets origin to
     * AUTO/LEFT/TOP. Origins only change during a user drag, so a single reset after the overlay
     * settles sticks; {@link #runFusedOverlayWalk} re-arms this (removes the id from
     * {@link #originNormalized}) when it sees the overlay move again. resetOverlay also nulls
     * preferredLocation/Size/Position — the caller re-sets the location immediately, and we preserve
     * any user-set size here. Client-thread only (resetOverlay persists + rebuilds overlay layers).
     */
    private void normalizeOrigin(Overlay overlay, String overlayId) {
        if (overlayId == null || originNormalized.contains(overlayId)) {
            return;
        }
        Dimension savedSize = overlay.getPreferredSize();
        overlayManager.resetOverlay(overlay);
        if (savedSize != null) {
            overlay.setPreferredSize(savedSize);
        }
        originNormalized.add(overlayId);
    }

    private long lastResizeTime = 0;

    // Map<OverlayKey, RegionId> to track which region owns an overlay (persisted)
    private final Map<String, Integer> overlayAssignments = new ConcurrentHashMap<>();
    // Persisted display order of overlays within their assigned region (GitHub issue #5):
    // overlayKey -> order index. Sorted by this instead of live position so window
    // resizes can't scramble the arrangement.
    private final Map<String, Integer> overlayOrder = new ConcurrentHashMap<>();
    // Map<OverlayKey, Overlay> to store direct references to captured overlays (runtime only)
    private final Map<String, Overlay> trackedOverlays = new ConcurrentHashMap<>();

    // Overlays whose origin we've normalized to LEFT/TOP (RuneLite 1.12.31 configurable origins).
    // Reset once per capture; re-armed (id removed) when the user drags the overlay again, so a fresh
    // RIGHT/CENTRE/BOTTOM origin from that drag gets neutralized on the next settle. See normalizeOrigin.
    private final Set<String> originNormalized = ConcurrentHashMap.newKeySet();

    // Last anchored target position computed for each assigned overlay during the tick's
    // positioning pass. Re-applied every rendered frame in onBeforeRender so a RuneLite
    // relayout (e.g. toggling the inventory tab) can't flash the overlay at its default
    // snap-corner for the frame(s) before the next client tick (GitHub issue: 1-frame
    // flicker). Cleared + repopulated each tick, so released overlays drop out automatically.
    private final Map<String, Point> overlayTargets = new ConcurrentHashMap<>();

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
        // Rebuild the per-frame reassert cache from scratch each tick. Clearing before the
        // empty-regions early return means that if every region was just deleted, the
        // onBeforeRender pass stops re-positioning overlays immediately.
        overlayTargets.clear();
        if (anchorRegions.isEmpty())
            return;

        // Inter-overlay gap when stacking multiple overlays in one region. User-configurable
        // (default 2px, the long-standing hardcoded value); set to 0 to pack overlays flush.
        final int PADDING = config.stackSpacing();

        // Snapshot regions once per call (defensive against EDT/panel edits)
        final List<AnchorRegion> regionsSnapshot = new ArrayList<>(anchorRegions);

        // Periodically re-acquire overlays that registered after startup.
        //
        // The old gate (assignments.size() > tracked.size()) never converges when an
        // assignment key is permanently unresolvable — e.g. plugins like
        // TimeTrackingReminder whose overlay NAME embeds a per-session timestamp
        // (..._1780500862051), so the persisted key never matches a live overlay again.
        // The result was the full overlayManager.anyMatch scan firing every second for
        // the entire session, accomplishing nothing (observed in client.log).
        //
        // New gate: only rescan when some unresolved assignment's key is ACTUALLY PRESENT
        // in the overlay manager right now (movableOverlayByKey, rebuilt every tick by the
        // fused walk). A stale timestamp key is never present → never triggers a scan; a
        // legitimately late-registering overlay shows up in the map and is re-acquired
        // within a tick. The present-key check short-circuits behind the interval gate so
        // it only runs ~1 Hz.
        long nowMs = System.currentTimeMillis();
        if ((nowMs - lastReacquireTime) >= REACQUIRE_INTERVAL_MS && hasReacquirableAssignment()) {
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
                        originNormalized.remove(overlayId);
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

            // Ghost-space fix: an overlay that is registered but currently rendering
            // nothing (plugin condition hides it, infobox group empty, owning plugin
            // half-disabled) has empty live bounds — the renderer zeroes them on
            // non-rendering frames. Previously these fell through to the phantom
            // 100x24 fallback size below and the stack reserved invisible space for
            // them indefinitely. Drop them from the layout instead; their assignment
            // AND their persisted overlayOrder index are deliberately retained, so
            // the moment they render again they reclaim their original slot and
            // neighbors reflow around them. Mid-drag overlays are exempt (their
            // bounds can be briefly empty before first render during capture).
            overlays.removeIf(o -> {
                if (!o.getBounds().isEmpty()) return false;
                String id = overlayKey(o);
                return id == null || !draggingIds.contains(id);
            });

            if (overlays.isEmpty())
                continue;

            AnchorStacking stacking = region.getStacking();
            if (stacking == null)
                stacking = AnchorStacking.VERTICAL;

            // Establish item order (GitHub issue #5). While the user is actively arranging
            // overlays here, order follows their live positions and is persisted; otherwise
            // we sort by the persisted order so a window resize can't scramble it.
            orderOverlays(region, overlays, stacking, hotIds);

            AnchorAlignment align = region.getAlignment();
            if (align == null)
                align = AnchorAlignment.CENTER;
            final int hAlign = hAlignCode(align);
            final int vAlign = vAlignCode(align);

            // 1. First pass: lay items out along the stacking (main) axis and record each
            // item's size and, for fill modes, which row/column it landed in. Cross-axis
            // placement is deferred to pass 3 once group extents are known.
            int n = overlays.size();
            int[] ws = new int[n];
            int[] hs = new int[n];
            int[] mainX = new int[n];
            int[] mainY = new int[n];
            int[] groupIdx = new int[n];

            int totalLayoutWidth = 0;
            int totalLayoutHeight = 0;
            int currentX = 0;
            int currentY = 0;
            int rowMaxH = 0; // For horizontal fill flow
            int colMaxW = 0; // For vertical fill flow
            int group = 0;

            for (int i = 0; i < n; i++) {
                Overlay overlay = overlays.get(i);
                int w = overlay.getBounds().width;
                if (w <= 0)
                    w = overlay.getPreferredSize() != null ? overlay.getPreferredSize().width : 100;
                int h = overlay.getBounds().height;
                if (h <= 0)
                    h = overlay.getPreferredSize() != null ? overlay.getPreferredSize().height : 24;
                ws[i] = w;
                hs[i] = h;

                switch (stacking) {
                    case VERTICAL:
                        mainX[i] = 0;
                        mainY[i] = currentY;
                        currentY += h + PADDING;
                        totalLayoutWidth = Math.max(totalLayoutWidth, w);
                        totalLayoutHeight = currentY - PADDING;
                        break;

                    case HORIZONTAL:
                        mainX[i] = currentX;
                        mainY[i] = 0;
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
                            group++;
                        }
                        mainX[i] = currentX;
                        mainY[i] = currentY;
                        currentX += w + PADDING;
                        rowMaxH = Math.max(rowMaxH, h);
                        groupIdx[i] = group;
                        totalLayoutWidth = Math.max(totalLayoutWidth, mainX[i] + w);
                        totalLayoutHeight = Math.max(totalLayoutHeight, mainY[i] + h);
                        break;

                    case FILL_VERTICAL:
                        if (currentY + h > region.getHeight() && currentY > 0) {
                            // Wrap to next column
                            currentY = 0;
                            currentX += colMaxW + PADDING;
                            colMaxW = 0;
                            group++;
                        }
                        mainX[i] = currentX;
                        mainY[i] = currentY;
                        currentY += h + PADDING;
                        colMaxW = Math.max(colMaxW, w);
                        groupIdx[i] = group;
                        totalLayoutWidth = Math.max(totalLayoutWidth, mainX[i] + w);
                        totalLayoutHeight = Math.max(totalLayoutHeight, mainY[i] + h);
                        break;
                }
            }

            // 2. Cross-axis extent of each fill row/column (tallest item per row for
            // FILL_HORIZONTAL, widest per column for FILL_VERTICAL). Lets mixed-size items
            // align within their row/column instead of pinning to its leading edge, which
            // is what made the vital bars sit a few pixels high (GitHub issue #5).
            Map<Integer, Integer> groupCross = new HashMap<>();
            if (stacking == AnchorStacking.FILL_HORIZONTAL) {
                for (int i = 0; i < n; i++) {
                    groupCross.merge(groupIdx[i], hs[i], Math::max);
                }
            } else if (stacking == AnchorStacking.FILL_VERTICAL) {
                for (int i = 0; i < n; i++) {
                    groupCross.merge(groupIdx[i], ws[i], Math::max);
                }
            }

            // Align the whole layout block within the region.
            int startX = region.getX();
            int startY = region.getY();
            if (hAlign == 2) {
                startX = region.getX() + region.getWidth() - totalLayoutWidth;
            } else if (hAlign == 1) {
                startX = region.getX() + (region.getWidth() - totalLayoutWidth) / 2;
            }
            if (vAlign == 2) {
                startY = region.getY() + region.getHeight() - totalLayoutHeight;
            } else if (vAlign == 1) {
                startY = region.getY() + (region.getHeight() - totalLayoutHeight) / 2;
            }

            // 3. Resolve each item's cross-axis position from the region alignment, then
            // apply. Items now honour left/right (vertical stack) or top/bottom (horizontal
            // stack) alignment relative to one another instead of always centering
            // (GitHub issue #5).
            for (int i = 0; i < n; i++) {
                Overlay overlay = overlays.get(i);
                int relX = mainX[i];
                int relY = mainY[i];

                switch (stacking) {
                    case VERTICAL:
                        relX = crossAlignOffset(totalLayoutWidth, ws[i], hAlign);
                        break;
                    case HORIZONTAL:
                        relY = crossAlignOffset(totalLayoutHeight, hs[i], vAlign);
                        break;
                    case FILL_HORIZONTAL:
                        relY = mainY[i] + crossAlignOffset(groupCross.getOrDefault(groupIdx[i], hs[i]), hs[i], vAlign);
                        break;
                    case FILL_VERTICAL:
                        relX = mainX[i] + crossAlignOffset(groupCross.getOrDefault(groupIdx[i], ws[i]), ws[i], hAlign);
                        break;
                }

                int targetX = startX + relX;
                int targetY = startY + relY;

                // Use a NULL preferred position (not DYNAMIC). RuneLite's
                // OverlayManager.rebuildOverlayLayers() promotes an UNDER_WIDGETS overlay to
                // ABOVE_WIDGETS only when preferredPosition == null && preferredLocation != null
                // — the same promotion it applies to any overlay the user free-drags onto the
                // screen, "so it can draw over interfaces". Forcing DYNAMIC here defeated that
                // promotion, which is exactly why attached infoboxes rendered behind game UI.
                // Positioning is unaffected: the renderer draws at preferredLocation whenever it
                // is non-null, regardless of preferredPosition. saveOverlay() triggers the
                // one-time layer rebuild so the promotion lands immediately; once the position is
                // null it stays null and this no-ops on subsequent ticks.
                if (overlay.getPreferredPosition() != null) {
                    overlay.setPreferredPosition(null);
                    overlayManager.saveOverlay(overlay);
                }

                String overlayId = overlayKey(overlay);
                if (overlayId == null) continue;
                // If this overlay is being dragged by the user, don't fight the drag renderer.
                // Let its cursor-driven position stand; other overlays will reflow around it.
                if (draggingIds.contains(overlayId)) {
                    continue;
                }

                // Neutralize any RIGHT/CENTRE/BOTTOM origin RuneLite assigned on the last drag, once,
                // so preferredLocation is absolute again. resetOverlay clears the location; the write
                // below restores it the same iteration.
                normalizeOrigin(overlay, overlayId);

                // targetX/targetY are absolute canvas coords; with a normalized LEFT/TOP origin this
                // is a plain absolute, idempotent write.
                setAbsoluteLocation(overlay, new Point(targetX, targetY));

                // Remember this target so onBeforeRender can re-assert it every frame,
                // closing the 1-frame gap where a RuneLite relayout would otherwise show
                // the overlay at its default position until the next client tick.
                overlayTargets.put(overlayId, new Point(targetX, targetY));
            }
        }

        // Opt-in diagnostic: for every assigned overlay, dump why it is (or isn't) following
        // its anchor — tracked?, in a bucket this tick?, where the snap targeted it vs where it
        // actually rendered, and whether the live render bounds drifted from our target (the
        // signature of an owning plugin, e.g. Resizable Chat, fighting our positioning).
        if (config != null && config.debugLogging()
                && (nowMs - lastFollowLogTime) >= FOLLOW_LOG_INTERVAL_MS
                && !overlayAssignments.isEmpty()) {
            lastFollowLogTime = nowMs;
            for (Map.Entry<String, Integer> a : overlayAssignments.entrySet()) {
                String id = a.getKey();
                Overlay ov = trackedOverlays.get(id);
                if (ov == null) ov = movableOverlayByKey.get(id);
                Point target = overlayTargets.get(id);
                Point loc = ov == null ? null : ov.getPreferredLocation();
                Rectangle b = ov == null ? null : ov.getBounds();
                log.info("[anchor-follow] overlay={} region={} tracked={} movable={} dragging={} target={} prefLoc={} bounds={}",
                        id, a.getValue(), trackedOverlays.containsKey(id),
                        ov != null && ov.isMovable(), draggingIds.contains(id),
                        target, loc, b);
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

    /**
     * Establish the display order of overlays within a region (GitHub issue #5). When the
     * user is actively arranging overlays here (one is "hot" - moved within the assignment
     * grace window) order is derived from live positions along the stacking axis and
     * persisted. Otherwise we sort by the persisted order, so a window resize (which moves
     * anchors, not overlay preferred locations, and therefore leaves nothing "hot") can no
     * longer reshuffle the arrangement.
     */
    private void orderOverlays(AnchorRegion region, List<Overlay> overlays, AnchorStacking stacking, Set<String> hotIds) {
        if (overlays.size() < 2) {
            return;
        }
        final boolean horizontal = stacking == AnchorStacking.HORIZONTAL
                || stacking == AnchorStacking.FILL_HORIZONTAL;

        boolean anyHot = false;
        for (Overlay o : overlays) {
            String id = overlayKey(o);
            if (id != null && hotIds.contains(id)) {
                anyHot = true;
                break;
            }
        }

        if (anyHot) {
            overlays.sort((a, b) -> compareByPosition(a, b, horizontal));
            for (int i = 0; i < overlays.size(); i++) {
                String id = overlayKey(overlays.get(i));
                if (id == null) continue;
                Integer prev = overlayOrder.get(id);
                if (prev == null || prev != i) {
                    overlayOrder.put(id, i);
                    orderDirty = true;
                }
            }
            return;
        }

        // Stable: seed any missing order (legacy data) once from current positions, then
        // sort by the persisted order.
        seedMissingOrder(overlays, horizontal);
        overlays.sort((a, b) -> {
            int oa = orderOf(a);
            int ob = orderOf(b);
            if (oa != ob) {
                return Integer.compare(oa, ob);
            }
            return compareByPosition(a, b, horizontal);
        });
    }

    private int compareByPosition(Overlay a, Overlay b, boolean horizontal) {
        Rectangle ba = a.getBounds();
        Rectangle bb = b.getBounds();
        return horizontal ? Integer.compare(ba.x, bb.x) : Integer.compare(ba.y, bb.y);
    }

    private int orderOf(Overlay o) {
        String id = overlayKey(o);
        if (id == null) return Integer.MAX_VALUE;
        Integer v = overlayOrder.get(id);
        return v == null ? Integer.MAX_VALUE : v;
    }

    /**
     * If any overlay in the bucket lacks a persisted order, assign the whole bucket an
     * order from current live positions. One-shot migration for configs saved before the
     * ordering feature existed.
     */
    private void seedMissingOrder(List<Overlay> overlays, boolean horizontal) {
        boolean missing = false;
        for (Overlay o : overlays) {
            String id = overlayKey(o);
            if (id != null && !overlayOrder.containsKey(id)) {
                missing = true;
                break;
            }
        }
        if (!missing) return;
        List<Overlay> sorted = new ArrayList<>(overlays);
        sorted.sort((a, b) -> compareByPosition(a, b, horizontal));
        for (int i = 0; i < sorted.size(); i++) {
            String id = overlayKey(sorted.get(i));
            if (id != null) {
                overlayOrder.put(id, i);
                orderDirty = true;
            }
        }
    }

    /** Horizontal alignment code for a region alignment: 0=left, 1=center, 2=right. */
    private static int hAlignCode(AnchorAlignment a) {
        switch (a) {
            case TOP_LEFT:
            case CENTER_LEFT:
            case BOTTOM_LEFT:
                return 0;
            case TOP_RIGHT:
            case CENTER_RIGHT:
            case BOTTOM_RIGHT:
                return 2;
            default:
                return 1;
        }
    }

    /** Vertical alignment code for a region alignment: 0=top, 1=center, 2=bottom. */
    private static int vAlignCode(AnchorAlignment a) {
        switch (a) {
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                return 0;
            case BOTTOM_LEFT:
            case BOTTOM_CENTER:
            case BOTTOM_RIGHT:
                return 2;
            default:
                return 1;
        }
    }

    /**
     * Offset of an item of {@code size} within an {@code extent}-long track for the given
     * cross-axis alignment code (0=start, 1=center, 2=end).
     */
    private static int crossAlignOffset(int extent, int size, int code) {
        if (code == 0) return 0;
        if (code == 2) return extent - size;
        return (extent - size) / 2;
    }

    private void loadOverlayOrder() {
        String json = config.overlayOrderJson();
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            Type type = new TypeToken<Map<String, Integer>>() {
            }.getType();
            Map<String, Integer> loaded = gson.fromJson(json, type);
            if (loaded != null) {
                overlayOrder.clear();
                overlayOrder.putAll(loaded);
            }
        } catch (Exception e) {
            log.error("Failed to load overlay order", e);
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
     * Returns true if there is any movable RuneLite overlay (other than our own
     * customizer overlay) whose drawn bounds contain {@code p}.
     *
     * Used by {@link AnchorInputListener} to yield Alt+press / Alt+click events to
     * RuneLite's {@code OverlayRenderer} when the user is clicking directly on an
     * overlay that happens to be rendered inside an anchor region. Without this
     * passthrough, anchor input would swallow the event and the overlay would
     * become un-Alt-draggable (the "uninteractable infobox" bug).
     *
     * Thread-safety: invoked from the AWT event-dispatch thread. {@link OverlayManager#anyMatch}
     * iterates a copy-on-write list, so concurrent ticks on the client thread are
     * safe. Each overlay's {@code getBounds()} returns the last-rendered rectangle,
     * which is the same rectangle RuneLite itself uses for overlay hit-testing.
     */
    public boolean isMovableOverlayAt(Point p) {
        return getMovableOverlayAt(p) != null;
    }

    /**
     * The movable RuneLite overlay currently rendered under {@code p}, or null. Returned to
     * {@link AnchorInputListener} so it can drag that overlay directly when the user Alt-presses
     * on a UI element inside a region (the "pointer on the UI -> the UI moves" rule), rather than
     * relying on RuneLite's OverlayRenderer to grab it — which is unreliable under 117 HD.
     *
     * Thread-safety: invoked from the AWT event thread. {@link OverlayManager#anyMatch} iterates
     * a copy-on-write list, so concurrent client-thread ticks are safe. {@code getBounds()} is
     * the last-rendered rectangle — the same one RuneLite uses for overlay hit-testing.
     */
    public Overlay getMovableOverlayAt(Point p) {
        if (p == null) return null;
        final Overlay[] found = {null};
        overlayManager.anyMatch(ov -> {
            if (ov == null || ov == customizerOverlay) return false;
            if (!ov.isMovable()) return false;
            Rectangle b = ov.getBounds();
            if (b != null && !b.isEmpty() && b.contains(p)) {
                found[0] = ov;
                return true;
            }
            return false;
        });
        return found[0];
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
    /**
     * True when at least one persisted assignment refers to an overlay that is currently
     * present in the overlay manager (per the most recent {@link #runFusedOverlayWalk()})
     * but not yet tracked — i.e. a rescan would actually re-acquire something. Gates the
     * periodic {@link #scanAndReacquireOverlays()} so it can't loop fruitlessly every
     * second for assignment keys that can never resolve (timestamp-suffixed overlay names).
     */
    private boolean hasReacquirableAssignment() {
        if (overlayAssignments.isEmpty()) {
            return false;
        }
        for (String key : overlayAssignments.keySet()) {
            if (!trackedOverlays.containsKey(key) && movableOverlayByKey.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

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
                    Point loc = absoluteTopLeftOf(overlay);
                    Rectangle regionBounds = region.getBounds();
                    boolean needsSeed = loc == null || !regionBounds.contains(loc);
                    if (needsSeed) {
                        setAbsoluteLocation(overlay, new Point(region.getX(), region.getY()));
                        // Null position (not DYNAMIC) so the UNDER_WIDGETS->ABOVE_WIDGETS
                        // promotion applies; the next snap tick's saveOverlay rebuilds layers.
                        if (overlay.getPreferredPosition() != null) {
                            overlay.setPreferredPosition(null);
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
                    // RuneLite may have re-derived this overlay's origin during the drag; re-arm
                    // normalization so the next settle resets it back to LEFT/TOP.
                    originNormalized.remove(id);
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
                    originNormalized.remove(entry.getKey());
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
