/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted.
 */
package com.anchorplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
// Gson parameterized-type helpers — used only for JSON (de)serialization of our
// own config strings. No reflection into RuneLite internals.
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.Player;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.externalplugins.PluginHubManifest;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

@Slf4j
@PluginDescriptor(name = "Custom UI Anchors", description = "Visualize and customize overlay anchor positions. Alt+drag to move anchor points.", tags = {
        "overlay", "anchor", "position", "customization", "ui" })
public class AnchorCustomizerPlugin extends Plugin {

    @Inject
    private Client client;

    // Package-private (not private) so unit tests can substitute in-memory stubs.
    @Inject
    @Getter
    AnchorCustomizerConfig config;

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

    @Inject
    private PluginManager pluginManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    private RuneLiteConfig runeLiteConfig;

    // Recent events for the diagnostics report. Always on and bounded; see DiagnosticEvents.
    final DiagnosticEvents events = new DiagnosticEvents(250);

    private AnchorCustomizerPanel panel;
    private NavigationButton navButton;
    private volatile boolean navButtonAdded = false;

    // Package-private for unit-test substitution.
    @Inject
    Gson gson;

    // Package-private for unit-test poking (same pattern as the seams below).
    MouseButtonObserver buttonObserver = new MouseButtonObserver();

    // Test seams: unit tests substitute fakes; in production these delegate to the
    // injected OverlayManager (null in tests, so the delegates must be replaced before
    // the corresponding code paths run).
    Consumer<Overlay> resetOverlayHandler = o -> overlayManager.resetOverlay(o);
    Consumer<Overlay> saveOverlayHandler = o -> overlayManager.saveOverlay(o);
    Consumer<Predicate<Overlay>> overlayScanHandler = p -> overlayManager.anyMatch(p);

    // Chatbox-top supplier for the pin-to-chat feature; tests inject a fixed value.
    Supplier<Integer> chatTopSupplier = this::getChatTopY;

    // Package-private for unit tests.
    final List<AnchorRegion> anchorRegions = new ArrayList<>();
    private Dimension lastViewport = null;

    // ---- Layouts --------------------------------------------------------------------
    //
    // anchorRegions, overlayAssignments and overlayOrder are the LIVE copy of the active
    // layout; saves fold them back into it (see LayoutStore for what is stored).
    //
    // Resizable mode has ONE layout, used at every window size and on every monitor:
    // each anchor's position is derived from its pin (constraint) and the canvas size
    // (recomputePosition). Fixed mode has its own layout. The live layout therefore
    // changes for one reason only — the client goes between fixed and resizable mode,
    // which the user does on purpose and client.isResized() reports reliably. The
    // monitor is never consulted: older versions swapped a hidden per-monitor layout on
    // what AWT reported, and a wrong or momentary report swapped in a stale layout
    // (GitHub issue #28).
    LayoutStore store = new LayoutStore();
    AnchorLayout activeLayout = null;
    // False until a logged-in tick has shown a layout for the real client mode and
    // canvas size; the startup materialization only feeds the panel and login screen.
    private boolean liveResolved = false;
    // Ticks spent waiting for AWT screen info for the one-time monitor pick.
    private int legacyPickAttempts = 0;
    private static final int LEGACY_PICK_MAX_ATTEMPTS = 100;
    private String lastWrittenLayoutsJson = null;

    // Test seams (the client is null in unit tests).
    Supplier<Boolean> resizedSupplier = () -> client.isResized();
    Supplier<Dimension> canvasDimSupplier = () -> client.getCanvas() == null ? null : client.getRealDimensions();
    Supplier<String> legacyDisplayKeySupplier = this::legacyDisplayKey;
    Consumer<String> noticeHandler = msg -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);

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
    private boolean layoutsDirty = false;
    private long lastFlushTime = 0L;

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

    /**
     * Whether the anchor boxes should be drawn (and pickable) right now. Anchors are
     * always shown while the side panel is open; otherwise the {@code anchorVisibility}
     * config decides (GitHub issue #18): while the drag hotkey is held, only while an
     * overlay/anchor drag is in progress, or never outside the panel.
     */
    public boolean isOverlaysVisible() {
        if (panel != null && panel.isShowing()) {
            return true;
        }
        AnchorVisibility mode = config != null && config.anchorVisibility() != null
                ? config.anchorVisibility() : AnchorVisibility.HOTKEY_HELD;
        switch (mode) {
            case PANEL_OPEN:
                return false;
            case WHILE_DRAGGING:
                return hotkeyDown() && isOverlayHeld();
            case HOTKEY_HELD:
            default:
                return hotkeyDown();
        }
    }

    // Tracked flag OR live key state: the tracked flag misses a press made while keyboard
    // focus was elsewhere (e.g. right after clicking the side panel), which hid the anchors
    // while Alt was plainly held.
    private boolean hotkeyDown() {
        return isDragKeyHeld() || isDragHotkeyActive();
    }

    /**
     * Whether a capturable overlay is physically held by the mouse right now: either our own
     * pointer-on-UI drag, or a left-press (with the hotkey) that landed on movable UI and hasn't
     * been released yet (RuneLite's OverlayRenderer drag). Movement timing is deliberately not
     * used — pausing mid-drag must not hide the anchors, and overlays moved by other plugins
     * must not show them.
     */
    public boolean isOverlayHeld() {
        return (inputListener != null && inputListener.getDraggedOverlay() != null)
                || buttonObserver.isOverlayGrabbed();
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
        lastWrittenLayoutsJson = null;
        activeLayout = null;
        liveResolved = false;
        legacyPickAttempts = 0;
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
        orderDirty = false;
        layoutsDirty = false;
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
            } catch (Exception e) {
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
        // Index 0: RuneLite's OverlayRenderer consumes overlay-drag presses, and consumed
        // events never reach later listeners — only an observer registered before it can
        // reliably track the left button state (used to detect renderer-side drags).
        // Same condition under which RuneLite's renderer (or our fallback) picks the UI up:
        // hotkey held and the pointer on a movable overlay we could anchor.
        buttonObserver.grabTest = e -> {
            boolean hotkey = isDragHotkeyActive(e);
            Point p = toCanvasPoint(e);
            Overlay grabbed = hotkey ? getCapturableOverlayAt(p) : null;
            if (grabbed != null) {
                Rectangle gb = grabbed.getBounds();
                grabOffset = new Point(p.x - gb.x, p.y - gb.y);
            }
            if (config.debugLogging()) {
                log.info("[anchor-debug] grabTest at=({},{}) hotkey={} overlay={}",
                        p.x, p.y, hotkey, grabbed == null ? "none" : grabbed.getName());
            }
            return grabbed;
        };
        mouseManager.registerMouseListener(0, buttonObserver);
        mouseManager.registerMouseListener(inputListener);
        anchorKeyListener.reset();
        // When the edit hotkey is released (or focus is lost), cancel any in-progress drag
        // and reset the cursor. cancelDrag() is a no-op when nothing is being dragged, so
        // this also covers the "stuck cursor while only hovering" case.
        anchorKeyListener.setOnReleased(() -> {
            inputListener.cancelDrag();
            inputListener.resetCursorToDefault();
            inputListener.restoreCursorAfterHotkey();
        });
        anchorKeyListener.setOnPressed(inputListener::rememberCursorForHotkey);
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
        // Load the layouts (older data is migrated once) and show this computer's
        // selected layout, so the panel and login screen have anchors. The first
        // logged-in tick then shows the layout for the real client mode and canvas size.
        loadLayouts();

        // Try to re-acquire overlay references for persisted assignments
        scanAndReacquireOverlays();
        lastReacquireTime = System.currentTimeMillis();
        events.add("Plugin started: " + anchorRegions.size() + " anchors, " + overlayAssignments.size()
                + " assigned overlays, layouts: " + layoutSummary());

        // Update the panel (layout choices, anchor list) and restore the last-selected
        // region (Fix SEL).
        refreshPanel();
    }

    @Override
    protected void shutDown() throws Exception {
        // Flush any pending persistence before tearing down
        flushPendingSaves(true);
        events.add("Plugin stopped");
        overlayManager.remove(customizerOverlay);
        mouseManager.unregisterMouseListener(buttonObserver);
        mouseManager.unregisterMouseListener(inputListener);
        keyManager.unregisterKeyListener(anchorKeyListener);
        if (navButtonAdded) {
            clientToolbar.removeNavigation(navButton);
            navButtonAdded = false;
        }
        anchorRegions.clear();
        activeLayout = null;
        liveResolved = false;
        trackedOverlays.clear();
        overlayAssignments.clear();
        overlayOrder.clear();
        lastSeenLocations.clear();
        lastExternalMoveTime.clear();
        lastUserMoveTime.clear();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("anchorcustomizer")) {
            return;
        }
        if (!INTERNAL_CONFIG_KEYS.contains(event.getKey())) {
            events.add("Setting changed: " + event.getKey() + " = " + event.getNewValue());
        }

        if (event.getKey().equals("layouts")) {
            String incoming = event.getNewValue();
            // Suppress self-triggered reloads when the value matches what we just wrote
            if (incoming != null && incoming.equals(lastWrittenLayoutsJson)) {
                return;
            }
            // Written by something else (e.g. another computer through config sync).
            clientThread.invoke(this::reloadLayoutsFromConfig);
        } else if (event.getKey().equals("showSidebarButton")) {
            SwingUtilities.invokeLater(this::syncSidebarButton);
        } else if (event.getKey().equals("drawAboveInterfaces")) {
            // Re-apply the layer policy to every tracked overlay; applyLayerPolicy only
            // touches overlays whose preferredPosition actually differs from the target.
            clientThread.invoke(() -> {
                for (Overlay o : trackedOverlays.values()) {
                    applyLayerPolicy(o);
                }
            });
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

    void createNewAnchorOnClientThread() {
        // Ids are never reused inside a layout, so a new anchor can never inherit
        // something (an overlay assignment, a stale reference) that pointed at a
        // deleted one.
        int nextId = activeLayout != null ? activeLayout.getNextRegionId() : 1;
        for (AnchorRegion r : anchorRegions) {
            nextId = Math.max(nextId, r.getId() + 1);
        }
        if (activeLayout != null) {
            activeLayout.setNextRegionId(nextId + 1);
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
                0, 0, 0, 0,
                false,
                AnchorFillDirection.FORWARD,
                AnchorFillDirection.FORWARD,
                false,
                0,
                null,
                false);
        // Pin to the nearest corner / edge / centre (the centre, for a new box).
        applyAutoPin(region);
        rebaselineOrigin(region);

        anchorRegions.add(region);
        markRegionsDirty();
        events.add("Created anchor " + nextId + " at " + x + "," + y);
        selectAnchor(region);
        final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.updateList(snap);
        });
    }

    /**
     * Move a region to a new index in the layer list (panel drag-and-drop reorder).
     * Index 0 is the TOP of the layer stack: it is drawn last by the customizer
     * overlay and picked first by the input listener. The list order is persisted via
     * {@link #saveRegions()} (it's simply the JSON array order), so layering survives
     * restarts and is stored per layout.
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
            events.add("Deleted anchor " + deletedId);
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
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.setSelectedRegion(region);
        });
    }

    /** Lightweight panel-properties refresh (no JList mutation). */
    public void refreshSelectedAnchorProperties(AnchorRegion region) {
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.refreshSelectedRegionProperties(region);
        });
    }

    /**
     * Apply a mutation to a region on the client thread (the same thread that iterates
     * {@code anchorRegions} during the snap loop). Guarantees panel edits can't race with
     * the render pipeline mid-update. Persists immediately after.
     */
    public void updateRegion(AnchorRegion region, java.util.function.Consumer<AnchorRegion> updater) {
        if (region == null || updater == null) return;
        clientThread.invoke(() -> applyRegionUpdate(region, updater));
    }

    void applyRegionUpdate(AnchorRegion region, java.util.function.Consumer<AnchorRegion> updater) {
        Rectangle before = region.getBounds();
        AnchorConstraint pinBefore = region.getConstraint();
        boolean chatBefore = region.isPinToChat();

        updater.accept(region);

        boolean moved = !before.equals(region.getBounds());
        // Automatic pin: moving the box (X/Y/W/H) re-pins it, exactly like a drop on
        // the canvas. An anchor whose pin the user set by hand is never re-pinned.
        if (!region.isManualPin() && moved) {
            applyAutoPin(region);
        }
        boolean pinChanged = pinBefore != region.getConstraint() || chatBefore != region.isPinToChat();
        // A geometry or pin commit is a user-confirmed position: rebaseline the origin
        // so later window resizes derive from it. Other edits (name, stacking, spacing,
        // ...) leave the origin alone — the live position can differ from it while the
        // box is held on a small canvas (keepOnCanvas), and must not be baked in.
        if (moved || pinChanged || region.getOriginW() <= 0) {
            rebaselineOrigin(region);
        }
        saveRegions();
        if (pinBefore != region.getConstraint()) {
            refreshSelectedAnchorProperties(region);
        }
    }

    /** Pin a box to the nearest corner / edge / centre of the canvas. No-op without a canvas size. */
    private void applyAutoPin(AnchorRegion region) {
        Rectangle viewport = getViewportBounds();
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            return;
        }
        region.setConstraint(AnchorConstraint.nearest(region.getBounds(), viewport.width, viewport.height));
    }

    /**
     * The pin an anchor gets if it is dropped where it is right now: the nearest
     * corner / edge / centre for an automatic anchor, its own constraint when the user
     * set the pin by hand. Drawn by the pin indicator during a drag.
     */
    public AnchorConstraint pinIfDroppedNow(AnchorRegion region) {
        AnchorConstraint current = region.getConstraint() != null ? region.getConstraint() : AnchorConstraint.TOP_LEFT;
        if (region.isManualPin()) {
            return current;
        }
        Rectangle viewport = getViewportBounds();
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            return current;
        }
        return AnchorConstraint.nearest(region.getBounds(), viewport.width, viewport.height);
    }

    /**
     * Commit an anchor the user just dropped (drag or edge-resize end): pick its pin
     * (unless the user set one by hand), rebaseline its origin and save. Safe from any
     * thread — marshals onto the client thread where {@code anchorRegions} is mutated.
     */
    public void commitAnchorEditFromAnyThread(AnchorRegion region) {
        clientThread.invoke(() -> commitAnchorEdit(region));
    }

    void commitAnchorEdit(AnchorRegion region) {
        if (region != null) {
            AnchorConstraint pinBefore = region.getConstraint();
            if (!region.isManualPin()) {
                applyAutoPin(region);
            }
            rebaselineOrigin(region);
            if (pinBefore != region.getConstraint()) {
                refreshSelectedAnchorProperties(region);
            }
        }
        saveRegions();
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
        // Seed the chatbox-top baseline alongside the canvas origin so pinToChat regions
        // measure chat open/close deltas from the user's confirmed position.
        Integer ct = chatTopSupplier.get();
        if (ct != null) {
            region.setOriginChatTop(ct);
        }
    }

    /**
     * The chatbox's top edge in absolute canvas coordinates, or null when the chat
     * container widget isn't available yet. Mirrors RuneLite's own CHATBOX_TOP origin
     * computation (OverlayManager.convertOriginToAbsolute): the chat container bounds
     * for the active top-level interface, pushed down by the chat area's height while
     * the chatbox is hidden. Client-thread only.
     */
    private Integer getChatTopY() {
        Widget chatContainer = client.isResized()
                ? (client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_PRE_EOC
                        ? client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER)
                        : client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER))
                : client.getWidget(InterfaceID.Toplevel.CHAT_CONTAINER);
        if (chatContainer == null) {
            return null;
        }
        Rectangle bounds = chatContainer.getBounds();
        if (bounds == null || bounds.isEmpty()) {
            return null;
        }
        int chatTop = bounds.y;
        Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
        if (chatArea != null && chatArea.isSelfHidden()) {
            Rectangle areaBounds = chatArea.getBounds();
            if (areaBounds != null) {
                chatTop += areaBounds.height;
            }
        }
        return chatTop;
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
    void recomputePosition(AnchorRegion region, Dimension currentDim) {
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

        region.setX(keepOnCanvas(region.getOriginX() + xShift, region.getWidth(),
                region.getOriginX(), originW, currentDim.width));

        // Pin-to-chat (GitHub issue #17): vertical position tracks the chatbox top
        // instead of the window constraint. X still follows the constraint above.
        if (region.isPinToChat()) {
            Integer ct = chatTopSupplier.get();
            if (ct == null) {
                // Chat widget unavailable this tick — keep the current y.
                return;
            }
            if (region.getOriginChatTop() == 0) {
                // Lazy seed, like the legacy origin fields: anchor at the current chat
                // top so the first pinned tick is a no-op.
                region.setOriginChatTop(ct);
                region.setY(region.getOriginY());
                return;
            }
            region.setY(region.getOriginY() + chatPinnedYShift(region.getOriginChatTop(), ct));
            return;
        }

        region.setY(keepOnCanvas(region.getOriginY() + yShift, region.getHeight(),
                region.getOriginY(), originH, currentDim.height));
    }

    /**
     * Display-only correction for one axis: a box the user placed fully inside the
     * window stays fully inside it on a smaller window (one layout is used at every
     * window size, and a box pinned to the wrong edge would otherwise leave the
     * canvas where it can't be seen or grabbed). A box the user placed partly outside
     * the window is left exactly where the pin puts it. The result is never written
     * back to the origin, so the box returns to its place when the window grows.
     */
    static int keepOnCanvas(int pos, int size, int originPos, int originCanvas, int canvas) {
        boolean insideAtOrigin = originPos >= 0 && originPos + size <= originCanvas;
        if (!insideAtOrigin) {
            return pos;
        }
        return Math.max(0, Math.min(pos, canvas - size));
    }

    /** Gap between items stacked in this box: its own spacing (GitHub issue #30), else the global Stack spacing. */
    int spacingFor(AnchorRegion region) {
        Integer own = region.getSpacing();
        return own != null ? Math.max(0, own) : config.stackSpacing();
    }

    /** Vertical shift for a chat-pinned region: how far the chatbox top has moved since the origin snapshot. */
    static int chatPinnedYShift(int originChatTop, int currentChatTop) {
        return currentChatTop - originChatTop;
    }

    // ---- Layout core -----------------------------------------------------------------

    /**
     * Load the saved layouts, or build them once from the data older versions saved.
     * The old keys (regionJson, regionProfiles, overlayAssignments, overlayOrder) are
     * never written again, so they stay in config as the pre-migration backup.
     */
    void loadLayouts() {
        LayoutStore loaded = LayoutStore.fromJson(gson, config.layoutsJson());
        boolean migrated = false;
        if (loaded == null) {
            loaded = LayoutStore.migrate(gson, config.regionJson(), config.regionProfilesJson(),
                    config.overlayAssignmentsJson(), config.overlayOrderJson());
            migrated = true;
        }
        store = loaded;
        legacyPickAttempts = 0;
        materialize(store.main);
        liveResolved = false;
        layoutsDirty = migrated;
        if (migrated) {
            events.add("Layouts created from older data: " + layoutSummary());
        }
    }

    /** Layouts changed outside this client (another computer through config sync): reload. */
    private void reloadLayoutsFromConfig() {
        String json = config.layoutsJson();
        if (json != null && json.equals(lastWrittenLayoutsJson)) {
            return;
        }
        LayoutStore loaded = LayoutStore.fromJson(gson, json);
        if (loaded == null) {
            return;
        }
        if (inputListener != null) {
            inputListener.cancelDrag();
        }
        boolean fixedWasLive = activeLayout != null && activeLayout.isFixed();
        store = loaded;
        // The incoming data wins over anything not yet flushed here.
        regionsDirty = false;
        assignmentsDirty = false;
        orderDirty = false;
        layoutsDirty = false;
        materialize(fixedWasLive && store.fixed != null ? store.fixed : store.main);
        // The next logged-in tick derives positions for this canvas again.
        liveResolved = false;
        resetOverlayRuntimeState();
        requestSnap();
        events.add("Layouts reloaded (changed outside this client): " + layoutSummary());
        refreshPanel();
    }

    /** Replace the live anchors and overlay tables with a deep copy of {@code layout}'s. */
    private void materialize(AnchorLayout layout) {
        anchorRegions.clear();
        overlayAssignments.clear();
        overlayOrder.clear();
        activeLayout = layout;
        if (layout == null) {
            return;
        }
        anchorRegions.addAll(LayoutStore.normalizeRegions(LayoutStore.deepCopy(layout.getRegions())));
        overlayAssignments.putAll(layout.getAssignments());
        overlayOrder.putAll(layout.getOrder());
    }

    /** Write the live anchors and overlay tables back into the active layout object. */
    private void foldLiveIntoActive() {
        if (activeLayout == null) {
            return;
        }
        activeLayout.setRegions(LayoutStore.deepCopy(anchorRegions));
        // Sorted, so the saved JSON only changes when the content does.
        activeLayout.setAssignments(new java.util.TreeMap<>(overlayAssignments));
        activeLayout.setOrder(new java.util.TreeMap<>(overlayOrder));
    }

    /** Overlay tracking derived from the previous layout's assignments; rebuilt by a rescan. */
    private void resetOverlayRuntimeState() {
        trackedOverlays.clear();
        originNormalized.clear();
        overlayTargets.clear();
        lastExternalMoveTime.clear();
        lastUserMoveTime.clear();
        scanAndReacquireOverlays();
    }

    /** The layout that should be live right now. Decided by the client mode alone. */
    private AnchorLayout desiredLayout() {
        return resizedSupplier.get() ? store.main : store.ensureFixed();
    }

    /**
     * Make {@code target} the live layout and derive every anchor position for the
     * current canvas. Runs on the client thread.
     *
     * A layout shown for the first time while another one is on screen starts as a copy
     * of that one (fixed mode and resizable mode each start from the other's anchors),
     * held on the canvas and rebaselined; after that the two are independent. Otherwise
     * the stored origins are left untouched — a position is only rebaselined by a user
     * edit, never by showing a layout.
     */
    void activateLayout(AnchorLayout target, Dimension currentDim) {
        // Never carry an in-progress drag across a layout swap — the dragged object
        // would be orphaned from the new live list.
        if (inputListener != null) {
            inputListener.cancelDrag();
        }
        AnchorLayout from = activeLayout;
        boolean switching = from != target;
        foldLiveIntoActive();

        boolean copied = false;
        if (target.isUnseeded()) {
            if (switching && from != null && target.getRegions().isEmpty() && !from.getRegions().isEmpty()) {
                LayoutStore.copyContent(from, target);
                copied = true;
            }
            target.setUnseeded(false);
        }

        if (switching) {
            materialize(target);
            resetOverlayRuntimeState();
        }
        liveResolved = true;

        if (currentDim != null && currentDim.width > 0 && currentDim.height > 0) {
            for (AnchorRegion r : anchorRegions) {
                recomputePosition(r, currentDim);
                if (copied) {
                    clampToCanvas(r, currentDim);
                    rebaselineOrigin(r);
                }
            }
            if (target.isRepinPending()) {
                upgradePins(target, currentDim);
            }
        }

        log.debug("Layout {} -> {} (copied={})", layoutName(from), layoutName(target), copied);
        events.add((switching ? "Layout " + layoutName(from) + " -> " + layoutName(target)
                : "Layout shown: " + layoutName(target))
                + (copied ? " (new, copied from previous)" : "") + ", canvas " + dim(currentDim));
        saveRegions();
        layoutsDirty = true;
        // Layout switches are rare and important — persist now, not on the debounce.
        flushPendingSaves(true);
        requestSnap();
        refreshPanel();
    }

    /**
     * One-time upgrade of a layout built from older data. Older versions pinned every
     * new anchor to the top-left corner unless the user changed it, so such an anchor
     * keeps its distance to the top-left corner when the canvas size changes — wrong for
     * a box sitting near another edge. Each anchor still on that default is pinned to
     * its nearest corner / edge / centre here and rebaselined where it is now, so
     * nothing moves at the current window size and it adapts correctly from now on.
     * Pins the user set by hand (anything but top-left) are not touched.
     */
    private void upgradePins(AnchorLayout layout, Dimension currentDim) {
        int upgraded = 0;
        for (AnchorRegion r : anchorRegions) {
            if (r.isManualPin() || r.getConstraint() != AnchorConstraint.TOP_LEFT) {
                continue;
            }
            AnchorConstraint pin = AnchorConstraint.nearest(r.getBounds(), currentDim.width, currentDim.height);
            if (pin != AnchorConstraint.TOP_LEFT) {
                r.setConstraint(pin);
                rebaselineOrigin(r);
                upgraded++;
            }
        }
        layout.setRepinPending(false);
        if (upgraded > 0) {
            events.add("Pinned " + upgraded + " older anchors to their nearest edge (they were on the old top-left default)");
        }
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
     * One-time, after the migration from several per-monitor profiles: make the profile
     * of the monitor in use the layout, so nothing changes on screen on update day. This
     * is the only place the monitor is still read; the answer is used once.
     */
    void resolveLegacyPick() {
        String key = legacyDisplayKeySupplier.get();
        if (key == null && ++legacyPickAttempts < LEGACY_PICK_MAX_ATTEMPTS) {
            return; // no screen info yet; try again next tick
        }
        boolean mainWasLive = activeLayout == store.main;
        boolean changed = store.pickLegacy(key);
        layoutsDirty = true;
        if (changed && mainWasLive) {
            // Show the picked content; the live anchors were the provisional pick.
            materialize(store.main);
            resetOverlayRuntimeState();
            liveResolved = false;
        }
        events.add(key == null ? "Layout after update: no screen info, kept the last used old profile"
                : "Layout after update: monitor " + key
                        + (changed ? ", switched to its old profile" : ", its old profile was already used (or it has none)"));
    }

    /** The old monitor-profile key ("<W>x<H>" of the monitor's display mode), or null. */
    private String legacyDisplayKey() {
        java.awt.Canvas canvas = client.getCanvas();
        if (canvas == null) return null;
        java.awt.GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
        if (gc == null) return null;
        java.awt.DisplayMode dm = gc.getDevice().getDisplayMode();
        if (dm == null || dm.getWidth() <= 0 || dm.getHeight() <= 0) return null;
        return dm.getWidth() + "x" + dm.getHeight();
    }

    /** Tell a user who had several monitor profiles, once, that there is now one layout. */
    void showPendingNotice() {
        if (!store.noticePending) {
            return;
        }
        store.noticePending = false;
        layoutsDirty = true;
        try {
            noticeHandler.accept("Custom UI Anchors now uses one layout on every monitor. Your other old "
                    + "monitor layouts are in the side panel: Export / Import > Restore old layout.");
        } catch (RuntimeException e) {
            log.debug("Could not show the layout notice", e);
        }
    }

    /**
     * Replace the resizable-mode layout with one of the old layouts (an old monitor
     * profile, or the layout replaced by the previous restore). The layout being
     * replaced is kept, so one restore can always be undone from the same menu.
     */
    public void restoreOldLayout(String oldId) {
        clientThread.invoke(() -> restoreOldLayoutOnClientThread(oldId));
    }

    void restoreOldLayoutOnClientThread(String oldId) {
        if (inputListener != null) {
            inputListener.cancelDrag();
        }
        foldLiveIntoActive();
        AnchorLayout source = store.old(oldId);
        String name = layoutName(source);
        if (!store.restore(oldId)) {
            return;
        }
        store.legacyPickPending = false;
        layoutsDirty = true;
        if (activeLayout == store.main) {
            materialize(store.main);
            resetOverlayRuntimeState();
            // The next logged-in tick derives positions for this canvas.
            liveResolved = false;
            requestSnap();
        }
        events.add("Restored old layout " + name + ": " + store.main.getRegions().size() + " anchors");
        flushPendingSaves(true);
        refreshPanel();
    }

    private static String layoutName(AnchorLayout l) {
        return l == null ? "none" : l.getName();
    }

    /** "Layout=7, Fixed mode=3, old: 1920x1080=4" — every stored layout with its anchor count. */
    private String layoutSummary() {
        List<String> parts = new ArrayList<>();
        if (store.main != null) {
            parts.add(layoutCount(store.main));
        }
        if (store.fixed != null) {
            parts.add(layoutCount(store.fixed));
        }
        for (AnchorLayout l : store.oldLayouts) {
            parts.add("old: " + layoutCount(l));
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private String layoutCount(AnchorLayout l) {
        return l.getName() + "=" + (l == activeLayout ? anchorRegions.size() : l.getRegions().size());
    }

    /** Push the anchor list, the anchor selection and the restorable old layouts to the side panel. */
    private void refreshPanel() {
        final List<AnchorRegion> snap = new ArrayList<>(anchorRegions);
        final int selectedId = config.selectedRegionId();
        final List<String> oldIds = new ArrayList<>();
        final List<String> oldLabels = new ArrayList<>();
        for (AnchorLayout l : store.oldLayouts) {
            int n = l.getRegions().size();
            oldIds.add(l.getId());
            oldLabels.add(l.getName() + " (" + n + (n == 1 ? " anchor)" : " anchors)"));
        }
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            panel.updateOldLayouts(oldIds, oldLabels);
            panel.updateList(snap);
            // Also clears a selection the shown layout does not contain.
            AnchorRegion sel = null;
            for (AnchorRegion r : snap) {
                if (r.getId() == selectedId) {
                    sel = r;
                    break;
                }
            }
            panel.setSelectedRegion(sel);
        });
    }

    // ---- Saving -----------------------------------------------------------------------

    /**
     * Record that the live anchors changed. {@link #flushPendingSaves} folds the live
     * state into the active layout and writes it to config; it runs every client tick
     * (debounced) and is forced on shutdown and on layout switches, so this is cheap
     * enough to call on every tick of a window resize.
     */
    public void saveRegions() {
        regionsDirty = true;
    }

    /**
     * Write the layouts to config when something changed. Debounced: anchor positions
     * change every tick during a window resize and overlay assignments can churn during
     * capture-phase drags (up to ~50 Hz). Force-flush on shutDown and layout switches.
     */
    void flushPendingSaves(boolean force) {
        if (!regionsDirty && !assignmentsDirty && !orderDirty && !layoutsDirty) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && (now - lastFlushTime) < SAVE_DEBOUNCE_MS) {
            return;
        }
        foldLiveIntoActive();
        String json = store.toJson(gson);
        regionsDirty = false;
        assignmentsDirty = false;
        orderDirty = false;
        layoutsDirty = false;
        lastFlushTime = now;
        if (!json.equals(lastWrittenLayoutsJson)) {
            lastWrittenLayoutsJson = json;
            config.setLayoutsJson(json);
        }
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
     * Returns an immutable snapshot of the current anchor regions. The underlying list
     * is mutated only on the client thread; callers on other threads (AWT mouse handlers,
     * overlay-manager callbacks) iterate this snapshot safely without risk of
     * {@link java.util.ConcurrentModificationException}.
     */
    public List<AnchorRegion> getAnchorRegions() {
        return Collections.unmodifiableList(new ArrayList<>(anchorRegions));
    }

    public Rectangle getViewportBounds() {
        Dimension dim = canvasDimSupplier.get();
        if (dim == null)
            return null;

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
                if (elapsedMs >= 20) {
                    events.add("slow", "Slow tick: " + elapsedMs + " ms");
                }
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
                } else if (!lastViewport.equals(currentDim)) {
                    events.add("canvas", "Canvas resized to " + dim(currentDim)
                            + (inStartupGrace ? " (ignored: starting up)"
                            : !loggedIn ? " (ignored: not logged in)" : ""));
                }

                if (!inStartupGrace && loggedIn) {
                    // Show the layout for the current client mode: on the first
                    // logged-in tick, and on a fixed/resizable toggle. activateLayout
                    // derives every position for this canvas and persists, so the rest
                    // of this tick is skipped.
                    if (store.legacyPickPending) {
                        resolveLegacyPick();
                    }
                    if (!store.legacyPickPending) {
                        AnchorLayout desired = desiredLayout();
                        if (desired != null && (desired != activeLayout || !liveResolved)) {
                            activateLayout(desired, currentDim);
                            showPendingNotice();
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
                    } else {
                        // Chat-pinned regions track the chatbox top, which moves without
                        // any canvas resize (chat opening/closing) — recompute them every
                        // tick so they follow it. Not persisted: the shift is transient.
                        for (AnchorRegion region : new ArrayList<>(anchorRegions)) {
                            if (region == dragged) continue;
                            if (region.isPinToChat()) {
                                recomputePosition(region, currentDim);
                            }
                        }
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
        makeRoomForHeldGroup();
    }

    // Where inside the grabbed overlay the press landed (press point minus its top-left), so the
    // held overlay's live rectangle can be derived from the mouse position every frame.
    private volatile Point grabOffset = null;

    /**
     * Keep infobox groups inside an anchor from merging by accident.
     *
     * RuneLite's OverlayRenderer merges a dropped infobox group into any other drag-targetable
     * group whose bounds it merely INTERSECTS (checked each frame during render), and plugins
     * can't change drag-targetability. Items inside an anchor sit edge to edge, so dropping a
     * group between two others to reorder them almost always touched a neighbour and merged.
     * While a group is held over an anchor, neighbouring groups are pushed just clear of it
     * each frame (before render), unless the held group's center is ON a neighbour — that's a
     * deliberate merge and is left alone. The next tick's layout puts everything back.
     */
    void makeRoomForHeldGroup() {
        Overlay held = buttonObserver.getGrabbedOverlay();
        Point off = grabOffset;
        if (held == null || off == null || !held.isDragTargetable() || overlayTargets.isEmpty()) {
            return;
        }
        Rectangle hb = held.getBounds();
        Point mouse = mouseCanvasSupplier.get();
        if (hb == null || hb.isEmpty() || mouse == null) {
            return;
        }
        Rectangle h = new Rectangle(mouse.x - off.x, mouse.y - off.y, hb.width, hb.height);
        Point hc = new Point((int) h.getCenterX(), (int) h.getCenterY());
        AnchorRegion overAnchor = null;
        for (AnchorRegion r : anchorRegions) {
            if (!r.isDisabled() && r.getBounds().contains(hc)) {
                overAnchor = r;
                break;
            }
        }
        if (overAnchor == null) {
            return;
        }
        final int gap = Math.max(1, spacingFor(overAnchor));
        for (Map.Entry<String, Point> entry : overlayTargets.entrySet()) {
            Overlay n = trackedOverlays.get(entry.getKey());
            if (n == null || n == held || !n.isDragTargetable() || n.getClass() != held.getClass()
                    || !originNormalized.contains(entry.getKey())) {
                continue;
            }
            Rectangle nb = n.getBounds();
            if (nb == null || nb.isEmpty()) {
                continue;
            }
            Rectangle nr = new Rectangle(entry.getValue().x, entry.getValue().y, nb.width, nb.height);
            if (!nr.intersects(h) || nr.contains(hc)) {
                continue;
            }
            Point push = separation(nr, h, gap);
            Point pushed = new Point(nr.x + push.x, nr.y + push.y);
            setAbsoluteLocation(n, pushed);
            // Our own write: don't let the next tick's move detection mistake it for a drag.
            lastSeenLocations.put(entry.getKey(), pushed);
        }
    }

    /**
     * Smallest push that moves {@code n} clear of {@code h} (plus {@code gap}), away from
     * {@code h}'s center, along whichever axis needs the shorter move.
     */
    static Point separation(Rectangle n, Rectangle h, int gap) {
        int dx = n.getCenterX() >= h.getCenterX()
                ? (h.x + h.width + gap) - n.x
                : (h.x - gap) - (n.x + n.width);
        int dy = n.getCenterY() >= h.getCenterY()
                ? (h.y + h.height + gap) - n.y
                : (h.y - gap) - (n.y + n.height);
        return Math.abs(dx) <= Math.abs(dy) ? new Point(dx, 0) : new Point(0, dy);
    }

    // Mouse position in game-canvas coordinates; tests inject a fixed value.
    Supplier<Point> mouseCanvasSupplier = () -> {
        net.runelite.api.Point m = client.getMouseCanvasPosition();
        return m == null ? null : new Point(m.getX(), m.getY());
    };

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
            if (overlay == null || !overlay.isMovable() || overlay == buttonObserver.getGrabbedOverlay()) {
                continue;
            }

            // preferredPosition is owned/normalized to null by the tick path
            // (snapAndStackOverlays); here we only re-assert the location every frame.
            // entry.getValue() is the absolute canvas target; setAbsoluteLocation converts it to
            // the overlay's (RuneLite 1.12.31+) origin-relative preferredLocation and no-ops when
            // the overlay is already rendering on target.
            setAbsoluteLocation(overlay, entry.getValue());
            // Our own write (also undoes a make-room push); not a user move.
            lastSeenLocations.put(overlayId, new Point(entry.getValue()));
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
    private boolean normalizeOrigin(Overlay overlay, String overlayId) {
        if (overlayId == null || originNormalized.contains(overlayId)) {
            return false;
        }
        Dimension savedSize = overlay.getPreferredSize();
        resetOverlayHandler.accept(overlay);
        if (savedSize != null) {
            overlay.setPreferredSize(savedSize);
        }
        originNormalized.add(overlayId);
        return true;
    }

    /**
     * Normalize + position + persist in one step (the tick loop's per-overlay apply).
     * When a reset happened, {@code resetOverlay}'s internal save persisted
     * preferredSize=null — so the restored size AND the new location are re-saved here,
     * AFTER the location write, fixing infobox sizes being lost across sessions
     * (GitHub issue #25).
     */
    void applyAnchorPosition(Overlay overlay, String overlayId, Point target) {
        boolean didReset = normalizeOrigin(overlay, overlayId);
        setAbsoluteLocation(overlay, target);
        // Layer policy is applied AFTER the reset+location write (issue #19 + #25
        // ordering): reset() nulls preferredPosition, so running the policy first would
        // need a second save (and a one-tick layer flicker). One saveOverlay covers the
        // restored size, the new location, and the desired position together.
        OverlayPosition desired = config.drawAboveInterfaces() ? null : overlay.getPosition();
        boolean positionChanged = overlay.getPreferredPosition() != desired;
        if (positionChanged) {
            overlay.setPreferredPosition(desired);
        }
        if (didReset || positionChanged) {
            saveOverlayHandler.accept(overlay);
        }
    }

    /**
     * Put an overlay onto an absolute LEFT/TOP origin at {@code absTopLeft} before our own
     * pointer-on-UI drag starts writing absolute locations into it. AWT thread;
     * resetOverlay is synchronized on the OverlayManager. The tick re-normalizes on drop
     * anyway (the drag's moves re-arm it), which is then a no-op reset at the same spot.
     */
    void prepareForManualDrag(Overlay overlay, Point absTopLeft) {
        Dimension savedSize = overlay.getPreferredSize();
        resetOverlayHandler.accept(overlay);
        if (savedSize != null) {
            overlay.setPreferredSize(savedSize);
        }
        overlay.setPreferredLocation(new Point(absTopLeft));
        saveOverlayHandler.accept(overlay);
    }

    /**
     * Enforce the global "draw above interfaces" layer policy (GitHub issue #19). With
     * the option on, preferredPosition stays null so RuneLite promotes the overlay to
     * ABOVE_WIDGETS (same as a free-dragged overlay); with it off, the overlay's own
     * default position is restored so interfaces cover it again. Idempotent — only
     * writes (and saves) when the current value differs.
     */
    private void applyLayerPolicy(Overlay overlay) {
        OverlayPosition desired = config.drawAboveInterfaces() ? null : overlay.getPosition();
        if (overlay.getPreferredPosition() != desired) {
            overlay.setPreferredPosition(desired);
            saveOverlayHandler.accept(overlay);
        }
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
    // Subset of lastExternalMoveTime: moves that happened while the user's left button
    // was down (or within the release-settle window) — i.e. a real user drag. Only these
    // may ever change overlay assignments; moves caused by anything else (another plugin
    // repositioning its overlay, or our own origin-normalization racing the renderer)
    // must never re-home or release an overlay.
    private final Map<String, Long> lastUserMoveTime = new ConcurrentHashMap<>();
    // Whether the anchors were visible at some point while the current drag was held; a drop
    // may only be captured by an anchor the user could see. Client-thread only.
    private boolean anchorsSeenDuringDrag = false;
    private static final long DRAG_DETECT_MS = 125L;

    private static final long RELEASE_SETTLE_MS = 50L;

    /** Left button is down, or was released within RELEASE_SETTLE_MS (the AWT release chain may still be running). */
    boolean userMouseDragPossible() {
        return buttonObserver.isLeftButtonDown()
                || (System.currentTimeMillis() - buttonObserver.getLastLeftReleaseMs()) < RELEASE_SETTLE_MS;
    }

    /** A RuneLite-renderer overlay drag may be in progress: the mouse is down and it isn't one of OUR drags. */
    boolean externalDragPossible() {
        return userMouseDragPossible() && !isAnchorBeingDragged()
                && (inputListener == null || inputListener.getDraggedOverlay() == null);
    }

    /** Defer reset+positioning of an overlay while a renderer-side drag could still be writing its origin. */
    boolean shouldDeferApply(String overlayId) {
        return externalDragPossible() && !originNormalized.contains(overlayId);
    }
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
        // While UI is held by the mouse, keep the held overlay "moving" even if the cursor is
        // still — otherwise a pause longer than the grace window would drop it out of the hot
        // set and the eventual release would never be evaluated (or a >125ms pause over empty
        // space would be mistaken for the drop and release it mid-drag).
        final boolean overlayHeld = isOverlayHeld();
        if (overlayHeld) {
            for (Map.Entry<String, Long> e : lastUserMoveTime.entrySet()) {
                if ((nowMs - e.getValue()) < ASSIGNMENT_GRACE_MS) {
                    e.setValue(nowMs);
                    lastExternalMoveTime.put(e.getKey(), nowMs);
                }
            }
        }

        // Overlays in the user's hand, or just dropped and awaiting their one drop decision.
        final Set<String> userHotIds = new HashSet<>();
        for (Map.Entry<String, Long> e : lastUserMoveTime.entrySet()) {
            if ((nowMs - e.getValue()) < ASSIGNMENT_GRACE_MS) userHotIds.add(e.getKey());
        }

        // Only anchors the user could actually see during this drag may receive the drop.
        // Latched while held so releasing the mouse (which hides anchors in "While dragging"
        // mode) or letting go of the hotkey first doesn't veto a drop onto a visible anchor.
        if (userHotIds.isEmpty()) {
            anchorsSeenDuringDrag = false;
        } else if (isOverlaysVisible()) {
            anchorsSeenDuringDrag = true;
        }

        final Set<String> draggingIds = new HashSet<>();
        final Set<String> hotIds = new HashSet<>();
        for (Map.Entry<String, Long> e : lastExternalMoveTime.entrySet()) {
            long age = nowMs - e.getValue();
            if (age < ASSIGNMENT_GRACE_MS) hotIds.add(e.getKey());
            if (age < DRAG_DETECT_MS) draggingIds.add(e.getKey());
        }

        Map<Integer, List<Overlay>> buckets = new HashMap<>();
        for (AnchorRegion r : regionsSnapshot) {
            // Disabled regions hold no bucket: their assigned overlays float free (the
            // maintenance pass below skips them), while assignments/order are retained
            // so re-enabling the region restores the previous layout.
            if (!r.isDisabled()) {
                buckets.put(r.getId(), new ArrayList<>());
            }
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
            if (overlay == null || overlay == customizerOverlay || !isCapturable(overlay)) continue;
            if (overlay.getPreferredLocation() == null) continue;

            // Assignments only ever change from the user's own drag; moves from anything
            // else (another plugin repositioning its overlay, or our own origin
            // normalization racing the renderer's origin writes) must never re-home or
            // release an overlay. Non-user-moved overlays keep their current bucket.
            Long userMovedAt = lastUserMoveTime.get(overlayId);
            if (userMovedAt == null || (nowMs - userMovedAt) >= ASSIGNMENT_GRACE_MS) {
                Integer assigned = overlayAssignments.get(overlayId);
                if (assigned != null && buckets.containsKey(assigned)) {
                    buckets.get(assigned).add(overlay);
                    alreadyBucketed.add(overlayId);
                }
                continue;
            }

            Rectangle overlayBounds = overlay.getBounds();
            if (overlayBounds.isEmpty()) {
                Point loc = overlay.getPreferredLocation();
                Dimension size = overlay.getPreferredSize();
                if (size == null) size = new Dimension(100, 20);
                overlayBounds = new Rectangle(loc.x, loc.y, size.width, size.height);
            }
            Point center = new Point((int) overlayBounds.getCenterX(), (int) overlayBounds.getCenterY());

            Integer assignedRegionId = overlayAssignments.get(overlayId);
            // Still in the user's hand (or moved too recently for its bounds to have caught up).
            // Once it isn't, this tick is the drop: decide capture/release exactly ONCE, from the
            // drop position, then settle it. Re-testing on later ticks would test the position
            // our own layout gave it — which can legitimately sit outside the box (e.g. centered
            // rows wider than the box) and wrongly released the overlay right after capture.
            boolean isDraggingThis = overlayHeld || draggingIds.contains(overlayId);
            if (!isDraggingThis) {
                lastUserMoveTime.remove(overlayId);
            }

            boolean foundInRegion = false;
            for (AnchorRegion region : regionsSnapshot) {
                if (region.isDisabled() || !anchorsSeenDuringDrag) continue;
                if (region.getBounds().contains(center)) {
                    if (assignedRegionId == null || !assignedRegionId.equals(region.getId())) {
                        overlayAssignments.put(overlayId, region.getId());
                        markAssignmentsDirty();
                        events.add("Captured " + overlayId + " into anchor " + region.getId()
                                + (assignedRegionId == null ? "" : " (was anchor " + assignedRegionId + ")"));
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
                    // Commit the release — unless the overlay belongs to a disabled box: a
                    // disabled box keeps its assignments so re-enabling restores the layout,
                    // and moving its (free-floating) UI meanwhile must not unlink it.
                    AnchorRegion assignedRegion = regionById(regionsSnapshot, assignedRegionId);
                    if (assignedRegion != null && assignedRegion.isDisabled()) {
                        // keep assignment
                    } else if (overlayAssignments.containsKey(overlayId)) {
                        // RuneLite's drag nulled preferredPosition (promoting the overlay above
                        // interfaces); honour the "Render over interfaces" setting for UI dragged
                        // out of a box too, not only while it's anchored.
                        applyLayerPolicy(overlay);
                        overlayAssignments.remove(overlayId);
                        originNormalized.remove(overlayId);
                        markAssignmentsDirty();
                        log.debug("Released overlay {} from all regions (dropped in empty space)", overlayId);
                        events.add("Released " + overlayId + " from anchor " + assignedRegionId + " (dropped outside)");
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
            if (region.isDisabled()) continue;
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

            // Inter-overlay gap: the box's own spacing, else the global Stack spacing
            // (default 2px); 0 packs overlays flush.
            final int PADDING = spacingFor(region);

            // Establish item order (GitHub issue #5). While the user is actively arranging
            // overlays here, order follows their live positions and is persisted; otherwise
            // we sort by the persisted order so a window resize can't scramble it.
            orderOverlays(region, overlays, stacking, userHotIds);

            AnchorAlignment align = region.getAlignment();
            if (align == null)
                align = AnchorAlignment.CENTER;
            final int hAlign = hAlignCode(align);
            final int vAlign = vAlignCode(align);

            // Fill + wrap directions (GitHub issue #24): REVERSE fill mirrors the order
            // of items within each row (Fill-Horizontal) or column (Fill-Vertical);
            // REVERSE wrap mirrors where successive rows/columns are added (rows grow
            // bottom-to-top / columns right-to-left). Per-item cross alignment is NOT
            // flipped — wrap direction mirrors the row/column position only. Both are
            // ignored for plain VERTICAL/HORIZONTAL even if REVERSE is stored.
            final boolean reverseFill = AnchorFillDirection.appliesTo(stacking)
                    && region.getFillDirection() == AnchorFillDirection.REVERSE;
            final boolean reverseWrap = AnchorFillDirection.appliesTo(stacking)
                    && region.getWrapDirection() == AnchorFillDirection.REVERSE;

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
                    case FILL_HORIZONTAL: {
                        int rowH = groupCross.getOrDefault(groupIdx[i], hs[i]);
                        int rowY = reverseWrap ? mirror(mainY[i], totalLayoutHeight, rowH) : mainY[i];
                        relX = reverseFill ? mirror(mainX[i], totalLayoutWidth, ws[i]) : mainX[i];
                        relY = rowY + crossAlignOffset(rowH, hs[i], vAlign);
                        break;
                    }
                    case FILL_VERTICAL: {
                        int colW = groupCross.getOrDefault(groupIdx[i], ws[i]);
                        int colX = reverseWrap ? mirror(mainX[i], totalLayoutWidth, colW) : mainX[i];
                        relY = reverseFill ? mirror(mainY[i], totalLayoutHeight, hs[i]) : mainY[i];
                        relX = colX + crossAlignOffset(colW, ws[i], hAlign);
                        break;
                    }
                }

                int targetX = startX + relX;
                int targetY = startY + relY;

                String overlayId = overlayKey(overlay);
                if (overlayId == null) continue;
                // If this overlay is being dragged by the user, don't fight the drag renderer.
                // Let its cursor-driven position stand; other overlays will reflow around it.
                if (draggingIds.contains(overlayId)) {
                    continue;
                }

                // An un-normalized overlay may be the one RuneLite's renderer is dragging right now (AWT
                // thread). resetOverlay + our absolute write racing its origin-relative writes leaves
                // origin=CENTER/... with an absolute location -> rendered far away -> mis-captured by
                // another region. Defer until the mouse is up; normalized overlays are safe to write.
                if (shouldDeferApply(overlayId)) continue;

                // Neutralize any RIGHT/CENTRE/BOTTOM origin RuneLite assigned on the last drag, once,
                // so preferredLocation is absolute again; write the absolute target; then apply the
                // "draw above interfaces" layer policy (GitHub issue #19) — NULL preferred position
                // (not DYNAMIC) lets rebuildOverlayLayers() promote UNDER_WIDGETS overlays to
                // ABOVE_WIDGETS like any free-dragged overlay. A single saveOverlay at the end covers
                // reset + location + position together (GitHub issue #25); subsequent ticks no-op.
                applyAnchorPosition(overlay, overlayId, new Point(targetX, targetY));

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
                log.info("[anchor-follow] overlay={} region={} tracked={} movable={} dragging={} target={} prefLoc={} bounds={} layer={} prefPos={}",
                        id, a.getValue(), trackedOverlays.containsKey(id),
                        ov != null && ov.isMovable(), draggingIds.contains(id),
                        target, loc, b, ov == null ? null : ov.getLayer(),
                        ov == null ? null : ov.getPreferredPosition());
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
     * Establish the display order of overlays within a region (GitHub issue #5). Resting
     * overlays always keep their persisted order, so a window resize (or our own layout
     * writes) can never reshuffle the arrangement. An overlay the user is dragging here (or
     * just dropped) is inserted among them at the slot under its current position, and the
     * resulting order is persisted.
     *
     * Earlier this re-sorted the WHOLE bucket by live x (or y) whenever anything was hot,
     * which scrambled wrapped Fill layouts (a second-row item at a smaller x sorted before
     * first-row items) and fed our own freshly-written positions back into the order.
     */
    private void orderOverlays(AnchorRegion region, List<Overlay> overlays, AnchorStacking stacking, Set<String> hotIds) {
        if (overlays.size() < 2) {
            return;
        }
        final boolean horizontal = stacking == AnchorStacking.HORIZONTAL
                || stacking == AnchorStacking.FILL_HORIZONTAL;

        // When the region's fill direction reverses the main axis (issue #24), "first"
        // in display order is the far end (rightmost for Fill-Horizontal, bottommost
        // for Fill-Vertical). Only the FILL_* modes have a fill direction.
        final boolean mirroredMain = AnchorFillDirection.appliesTo(stacking)
                && region.getFillDirection() == AnchorFillDirection.REVERSE;
        final boolean mirroredWrap = AnchorFillDirection.appliesTo(stacking)
                && region.getWrapDirection() == AnchorFillDirection.REVERSE;

        List<Overlay> moving = new ArrayList<>();
        List<Overlay> resting = new ArrayList<>();
        for (Overlay o : overlays) {
            String id = overlayKey(o);
            if (id != null && hotIds.contains(id)) {
                moving.add(o);
            } else {
                resting.add(o);
            }
        }

        // Stable: seed any missing order (legacy data) once from current positions, then
        // sort by the persisted order.
        seedMissingOrder(resting, horizontal, mirroredMain);
        resting.sort((a, b) -> compareResting(orderOf(a), orderOf(b), a.getBounds(), b.getBounds(),
                horizontal, mirroredMain));

        if (moving.isEmpty()) {
            overlays.clear();
            overlays.addAll(resting);
            // Two resting overlays sharing an order index (left over from different bucket
            // contents) were tie-broken by live position every tick, which made them swap
            // places each frame under Reverse fill (GitHub issue #21). Persist the resolved
            // order once so the tie, and the position dependence, go away.
            if (hasDuplicateOrder(resting)) {
                for (int i = 0; i < resting.size(); i++) {
                    String id = overlayKey(resting.get(i));
                    if (id == null) continue;
                    overlayOrder.put(id, i);
                    orderDirty = true;
                }
            }
            return;
        }

        // Insertion slot for each moving overlay: just after the last resting overlay that
        // precedes its center in layout (reading) order. Inserted highest-slot first so the
        // lower slots stay valid.
        final Map<Overlay, Integer> slots = new IdentityHashMap<>();
        for (Overlay m : moving) {
            Rectangle mb = m.getBounds();
            int slot = resting.size();
            if (mb != null && !mb.isEmpty()) {
                slot = 0;
                for (int i = 0; i < resting.size(); i++) {
                    if (precedes(resting.get(i).getBounds(), (int) mb.getCenterX(), (int) mb.getCenterY(),
                            stacking, mirroredMain, mirroredWrap)) {
                        slot = i + 1;
                    }
                }
            }
            slots.put(m, slot);
        }
        moving.sort((a, b) -> Integer.compare(slots.get(b), slots.get(a)));
        List<Overlay> result = new ArrayList<>(resting);
        for (Overlay m : moving) {
            result.add(slots.get(m), m);
        }

        overlays.clear();
        overlays.addAll(result);
        for (int i = 0; i < overlays.size(); i++) {
            String id = overlayKey(overlays.get(i));
            if (id == null) continue;
            Integer prev = overlayOrder.get(id);
            if (prev == null || prev != i) {
                overlayOrder.put(id, i);
                orderDirty = true;
            }
        }
    }

    private static AnchorRegion regionById(List<AnchorRegion> regions, Integer id) {
        if (id == null) return null;
        for (AnchorRegion r : regions) {
            if (r.getId() == id) return r;
        }
        return null;
    }

    /**
     * Whether an already-placed item (bounds {@code r}) comes before the point (cx, cy) in the
     * region's layout order. Fill modes compare row/column first (so wrapped layouts order
     * correctly), then position within the row/column; REVERSE fill/wrap flip those.
     */
    static boolean precedes(Rectangle r, int cx, int cy, AnchorStacking stacking,
                            boolean reverseFill, boolean reverseWrap) {
        switch (stacking) {
            case HORIZONTAL:
                return r.getCenterX() < cx;
            case FILL_HORIZONTAL:
                if (cy >= r.y + r.height) return !reverseWrap;   // point is on a lower row
                if (cy < r.y) return reverseWrap;                 // point is on a higher row
                return reverseFill ? r.getCenterX() > cx : r.getCenterX() < cx;
            case FILL_VERTICAL:
                if (cx >= r.x + r.width) return !reverseWrap;     // point is in a column to the right
                if (cx < r.x) return reverseWrap;                 // point is in a column to the left
                return reverseFill ? r.getCenterY() > cy : r.getCenterY() < cy;
            case VERTICAL:
            default:
                return r.getCenterY() < cy;
        }
    }

    /**
     * Resting-overlay order: persisted index first; ties fall back to position in the
     * region's reading order, which is right-to-left (or bottom-to-top) under Reverse fill.
     * Breaking ties left-to-right regardless of fill direction made a Reverse layout put the
     * winner at the far end, flipping the comparison every tick (GitHub issue #21).
     */
    static int compareResting(int orderA, int orderB, Rectangle boundsA, Rectangle boundsB,
                              boolean horizontal, boolean mirroredMain) {
        if (orderA != orderB) {
            return Integer.compare(orderA, orderB);
        }
        int byPos = horizontal ? Integer.compare(boundsA.x, boundsB.x) : Integer.compare(boundsA.y, boundsB.y);
        return mirroredMain ? -byPos : byPos;
    }

    private boolean hasDuplicateOrder(List<Overlay> overlays) {
        Set<Integer> seen = new HashSet<>();
        for (Overlay o : overlays) {
            if (overlayKey(o) != null && !seen.add(orderOf(o))) return true;
        }
        return false;
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
    private void seedMissingOrder(List<Overlay> overlays, boolean horizontal, boolean mirroredMain) {
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
        sorted.sort((a, b) -> mirroredMain
                ? compareByPosition(b, a, horizontal)
                : compareByPosition(a, b, horizontal));
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

    /**
     * Mirror a leading-edge offset within a {@code total}-long layout block for the
     * fill/wrap direction feature (GitHub issue #24): e.g. a 100-wide block, item at
     * rel 0 with size 30 lands at 70 (its right edge flush with the block's right edge).
     */
    static int mirror(int rel, int total, int size) {
        return total - rel - size;
    }

    /**
     * Whether this overlay may be captured into / managed by an anchor region
     * (GitHub issue #22). Excludes nulls, non-movable overlays, and RuneLite's Screen
     * Marker overlay (matched by FQN so we need no compile-time dependency on the
     * screenmarkers plugin, which may be disabled): screen markers are user-drawn
     * rectangles that must never be stacked or assigned, though they remain
     * alt-draggable via the pointer-on-UI rule in {@link #getMovableOverlayAt}.
     */
    private static boolean isCapturable(Overlay o) {
        if (o == null || !o.isMovable()) {
            return false;
        }
        String cls = o.getClass().getName();
        return !SCREEN_MARKER_OVERLAY_FQN.equals(cls) && !SNAP_CORNER_OVERLAY_FQN.equals(cls);
    }

    private static final String SCREEN_MARKER_OVERLAY_FQN =
            "net.runelite.client.plugins.screenmarkers.ScreenMarkerOverlay";
    // RuneLite's own snap-corner anchors (added as movable overlays while the drag hotkey is
    // held, 1.12.39+). They are anchors themselves and must never be captured into a box.
    private static final String SNAP_CORNER_OVERLAY_FQN =
            "net.runelite.client.ui.overlay.SnapCorner$CornerOverlay";

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
        return overlayAt(p, false);
    }

    /**
     * The observer runs at index 0 of the mouse chain, BEFORE Stretched Mode's
     * TranslateMouseListener, so its events carry raw stretched-canvas coordinates. Scale
     * them to game-canvas coordinates exactly like RuneLite does (a no-op when not stretched).
     */
    private Point toCanvasPoint(java.awt.event.MouseEvent e) {
        if (client == null || !client.isStretchedEnabled()) {
            return e.getPoint();
        }
        Dimension stretched = client.getStretchedDimensions();
        Dimension real = client.getRealDimensions();
        if (stretched == null || real == null || stretched.width <= 0 || stretched.height <= 0) {
            return e.getPoint();
        }
        return new Point(
                (int) (e.getX() / (stretched.width / real.getWidth())),
                (int) (e.getY() / (stretched.height / real.getHeight())));
    }

    /** Like {@link #getMovableOverlayAt} but ignores overlays we never anchor (snap corners, screen markers). */
    public Overlay getCapturableOverlayAt(Point p) {
        return overlayAt(p, true);
    }

    // Picks the SMALLEST matching overlay under p — the same tie-break RuneLite's
    // OverlayRenderer uses for its hovered overlay — rather than the first in list order,
    // which could be a large overlay (e.g. a snap corner) behind the UI actually grabbed.
    private Overlay overlayAt(Point p, boolean capturableOnly) {
        if (p == null) return null;
        final Overlay[] found = {null};
        overlayManager.anyMatch(ov -> {
            if (ov == null || ov == customizerOverlay) return false;
            if (!ov.isMovable()) return false;
            if (capturableOnly && !isCapturable(ov)) return false;
            Rectangle b = ov.getBounds();
            if (b != null && !b.isEmpty() && b.contains(p)) {
                Overlay best = found[0];
                if (best == null || (long) b.width * b.height
                        <= (long) best.getBounds().width * best.getBounds().height) {
                    found[0] = ov;
                }
            }
            return false;
        });
        return found[0];
    }

    /**
     * Called when an overlay is dropped onto the customizer overlay (RuneLite drag-target hook).
     * Only starts tracking the overlay — see the note below on where capture is decided.
     */
    public void onOverlayDragged(Overlay overlay) {
        if (overlay == null || overlay == customizerOverlay || !isCapturable(overlay))
            return;

        String overlayId = overlayKey(overlay);
        if (overlayId == null) return;

        // Always track the overlay so we can manage it
        if (!trackedOverlays.containsKey(overlayId)) {
            trackedOverlays.put(overlayId, overlay);
        }

        // Capture/release is decided solely by the tick's drop logic (snapAndStackOverlays),
        // which knows whether the anchors were visible during the drag and evaluates the drop
        // exactly once. Deciding here too (on the AWT thread, from a possibly origin-relative
        // preferredLocation) could assign UI to hidden anchors or release it spuriously.
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
        overlayScanHandler.accept(overlay -> {
            if (overlay == null || overlay == customizerOverlay || !isCapturable(overlay)) {
                return false;
            }

            String overlayId = overlayKey(overlay);
            if (overlayId == null) return false;

            // If this overlay was previously assigned, start tracking it again
            Integer assignedRegionId = overlayAssignments.get(overlayId);
            if (assignedRegionId != null && !trackedOverlays.containsKey(overlayId)) {
                trackedOverlays.put(overlayId, overlay);
                log.debug("Re-acquired overlay {} for region {}", overlayId, assignedRegionId);
                events.add("Found " + overlayId + " for anchor " + assignedRegionId);

                // Fix L1: seed preferredLocation if missing or out-of-region
                AnchorRegion region = regionsById.get(assignedRegionId);
                if (region != null) {
                    Point loc = absoluteTopLeftOf(overlay);
                    Rectangle regionBounds = region.getBounds();
                    boolean needsSeed = loc == null || !regionBounds.contains(loc);
                    if (needsSeed) {
                        setAbsoluteLocation(overlay, new Point(region.getX(), region.getY()));
                        // Layer policy (issue #19), applied in-memory only — the next snap
                        // tick's applyLayerPolicy persists any change via saveOverlay.
                        overlay.setPreferredPosition(config.drawAboveInterfaces() ? null : overlay.getPosition());
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
        final boolean userDrag = userMouseDragPossible();
        final Overlay ourDragged = inputListener != null ? inputListener.getDraggedOverlay() : null;
        // The overlay the user's press actually grabbed. When known, only IT counts as user-moved
        // while the button is down — other overlays moving meanwhile (e.g. neighbours we push
        // aside in makeRoomForHeldGroup) must never be re-homed or released by that drag.
        final Overlay pressGrabbed = buttonObserver.getLastGrabbedOverlay();

        overlayScanHandler.accept(ov -> {
            if (ov == null) return false;
            present.add(ov);
            if (ov == customizerOverlay || !isCapturable(ov)) return false;

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
                    // Only a move made while the user's left button was held (or our own
                    // overlay drag is the source) counts as a user drag eligible to
                    // re-home the overlay in the capture pass.
                    if (ourDragged == ov || (userDrag && (pressGrabbed == null || pressGrabbed == ov))) {
                        lastUserMoveTime.put(id, now);
                    }
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
                    events.add("Lost " + entry.getKey() + " (its plugin removed the overlay)");
                }
            }
        }
    }

    // ---- Clipboard export/import (GitHub issue #23) ----------------------------------
    //
    // The export is a small JSON envelope around the stored layouts (LayoutStore.toExport).
    // An import replaces them all; exports from the per-monitor profile era (the four raw
    // config blobs) go through the same migration as an in-place upgrade.

    /**
     * Serialize the layouts to the export JSON envelope. Marshals onto the client thread
     * so the live anchors are folded in first and the export can't miss an unsaved edit;
     * blocks the caller (the panel's EDT) briefly until it completes. Returns null on
     * failure.
     */
    public String exportLayoutJson() {
        final CompletableFuture<String> result = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try {
                flushPendingSaves(true);
                result.complete(buildExportJson(gson));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Layout export failed", e);
            return null;
        }
    }

    String buildExportJson(Gson g) {
        foldLiveIntoActive();
        return g.toJson(store.toExport(g));
    }

    /**
     * Validate clipboard text as a layout export. Returns null when importable, else a
     * user-facing error message for the panel to show.
     */
    public String checkLayoutImport(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "The clipboard does not contain a layout.";
        }
        JsonElement el;
        try {
            el = gson.fromJson(json, JsonElement.class);
        } catch (Exception e) {
            return "The clipboard contents are not valid JSON.";
        }
        if (el == null || !el.isJsonObject()) {
            return "The clipboard contents are not a Custom UI Anchors layout.";
        }
        JsonObject root = el.getAsJsonObject();
        JsonElement format = root.get("format");
        if (format == null || !format.isJsonPrimitive() || !LayoutStore.EXPORT_FORMAT.equals(format.getAsString())) {
            return "The clipboard contents are not a Custom UI Anchors layout.";
        }
        JsonElement version = root.get("version");
        int v;
        try {
            v = (version != null && version.isJsonPrimitive()) ? version.getAsInt() : -1;
        } catch (RuntimeException e) {
            v = -1;
        }
        if (v < 0 || v > LayoutStore.EXPORT_VERSION) {
            return "This layout was exported by a newer version of the plugin and cannot be imported.";
        }
        return null;
    }

    /**
     * Apply a validated layout export, replacing ALL anchors and overlay assignments in
     * the current RuneLite profile. Marshals the mutation onto the client thread (where
     * anchorRegions is iterated); the panel is refreshed on the EDT.
     */
    public void applyLayoutImport(String json) {
        final JsonObject root = gson.fromJson(json, JsonElement.class).getAsJsonObject();
        clientThread.invoke(() -> applyLayoutImportOnClientThread(root));
    }

    void applyLayoutImportOnClientThread(JsonObject root) {
        if (inputListener != null) {
            inputListener.cancelDrag();
        }
        boolean fixedWasLive = activeLayout != null && activeLayout.isFixed();
        store = LayoutStore.fromExport(gson, root);
        legacyPickAttempts = 0;

        // Drop all runtime state derived from the previous layouts and show the imported
        // one; the next logged-in tick derives positions for this canvas (and, for an
        // old export holding several monitor profiles, picks this monitor's).
        activeLayout = null;
        materialize(fixedWasLive && store.fixed != null ? store.fixed : store.main);
        liveResolved = false;
        resetOverlayRuntimeState();
        layoutsDirty = true;
        flushPendingSaves(true);
        requestSnap();
        events.add("Imported a layout (" + layoutSummary() + "): " + anchorRegions.size() + " anchors, "
                + overlayAssignments.size() + " assigned overlays");
        refreshPanel();
    }

    // ---- Diagnostics: "Copy diagnostics" / "Report issue on GitHub" -------------------
    //
    // One report with what past GitHub issues kept asking for: versions, monitors and
    // scaling, the layouts, settings, other plugins, every anchor, every anchored
    // overlay (live state and what RuneLite saved for it), the saved layout, and the
    // recent events. Read-only; nothing leaves the machine unless the user pastes it.

    private static final Set<String> INTERNAL_CONFIG_KEYS = new HashSet<>(java.util.Arrays.asList(
            "layouts", "regionJson", "regionProfiles", "overlayAssignments", "overlayOrder", "selectedRegionId"));

    // Enabled plugins whose names match these have interfered with anchors in past issues
    // (renderers, cursor plugins, chat and infobox plugins, stretched mode).
    private static final String[] NOTABLE_PLUGIN_HINTS = {
            "117", "gpu", "cursor", "chat widget", "resizable chat", "stretched", "interface style", "infobox",
            "vital", "time tracking reminder"};

    // RuneLite's per-overlay keys in the "runelite" config group (OverlayManager).
    private static final String[] OVERLAY_CONFIG_SUFFIXES = {
            "_preferredLocation", "_preferredSize", "_preferredPosition", "_origin", "_originX", "_originY"};

    /**
     * Build the diagnostics report on the client thread (where the anchor and overlay
     * state lives), then hand it to {@code onDone} on the EDT — null on failure. Never
     * blocks the caller, so the side panel stays responsive.
     */
    public void buildDiagnosticsReport(Consumer<String> onDone) {
        clientThread.invoke(() -> {
            String report = null;
            try {
                flushPendingSaves(true);
                report = collectDiagnostics();
            } catch (Exception e) {
                log.warn("Building diagnostics failed", e);
            }
            final String result = report;
            SwingUtilities.invokeLater(() -> onDone.accept(result));
        });
    }

    public void openIssueForm(String report) {
        LinkBrowser.browse(DiagnosticsReport.issueUrl(report));
    }

    private String collectDiagnostics() {
        java.awt.Canvas canvas = client.getCanvas();
        GraphicsDevice windowDevice = canvas != null && canvas.getGraphicsConfiguration() != null
                ? canvas.getGraphicsConfiguration().getDevice() : null;
        Dimension real = client.getRealDimensions();

        List<String> enabledPlugins = new ArrayList<>(new TreeSet<>(enabledPluginNames()));
        List<String> notable = new ArrayList<>();
        for (String name : enabledPlugins) {
            String lower = name.toLowerCase();
            for (String hint : NOTABLE_PLUGIN_HINTS) {
                if (lower.contains(hint)) {
                    notable.add(name);
                    break;
                }
            }
        }

        int missing = 0;
        for (String key : overlayAssignments.keySet()) {
            if (!trackedOverlays.containsKey(key) && !movableOverlayByKey.containsKey(key)) {
                missing++;
            }
        }
        int disabledAnchors = 0;
        for (AnchorRegion r : anchorRegions) {
            if (r.isDisabled()) disabledAnchors++;
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Plugin version", pluginVersion());
        String launcher = RuneLiteProperties.getLauncherVersion();
        summary.put("RuneLite", RuneLiteProperties.getVersion() + " (" + shortCommit(RuneLiteProperties.getCommit()) + ")"
                + (launcher != null ? ", launcher " + launcher : ""));
        summary.put("Java", System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
        summary.put("OS", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        summary.put("Client", (client.isResized() ? "Resizable" : "Fixed") + ", canvas " + dim(real)
                + (client.isStretchedEnabled() ? ", stretched to " + dim(client.getStretchedDimensions()) : "")
                + ", " + client.getGameState());
        summary.put("Window", windowDescription(canvas, windowDevice));
        summary.put("Layout", layoutName(activeLayout)
                + (liveResolved ? "" : " (positions not derived yet: that happens after login)")
                + ", all layouts: " + layoutSummary());
        summary.put("Drag hotkey", runeLiteConfig != null ? String.valueOf(runeLiteConfig.dragHotkey()) : "?");
        summary.put("Settings", "Show anchors " + config.anchorVisibility()
                + ", render over interfaces " + config.drawAboveInterfaces()
                + ", stack spacing " + config.stackSpacing()
                + ", sidebar button " + config.showSidebarButton()
                + ", debug logging " + config.debugLogging()
                + ", side panel open " + (panel != null && panel.isShowing()));
        summary.put("Anchors", anchorRegions.size() + (disabledAnchors > 0 ? " (" + disabledAnchors + " disabled)" : ""));
        summary.put("Anchored overlays", overlayAssignments.size() + " assigned, " + trackedOverlays.size()
                + " tracked, " + missing + " not found");
        summary.put("Plugins to note", notable.isEmpty() ? "none" : String.join(", ", notable));

        // No HTML escaping, so overlay names like "a=b" stay readable.
        Gson g = gson.newBuilder().disableHtmlEscaping().create();
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("Displays", jsonLines(g, displaysJson(windowDevice)));
        sections.put("Anchors", jsonLines(g, anchorsJson(real)));
        sections.put("Anchored overlays", jsonLines(g, overlaysJson()));
        JsonArray plugins = new JsonArray();
        enabledPlugins.forEach(plugins::add);
        sections.put("Enabled plugins (" + enabledPlugins.size() + ")", g.toJson(plugins));
        sections.put("Saved layouts (importable with Import layout)", buildExportJson(g));

        Map<String, String> redactions = new LinkedHashMap<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            redactions.put(home, "~");
            redactions.put(home.replace('\\', '/'), "~");
            redactions.put(home.replace("\\", "\\\\"), "~");
        }
        Player local = client.getLocalPlayer();
        if (local != null && local.getName() != null) {
            redactions.put(local.getName(), "<player>");
        }

        return DiagnosticsReport.render(summary, sections, events.snapshot(), redactions);
    }

    private List<String> enabledPluginNames() {
        List<String> names = new ArrayList<>();
        if (pluginManager == null) {
            return names;
        }
        for (Plugin p : pluginManager.getPlugins()) {
            if (pluginManager.isPluginEnabled(p)) {
                names.add(p.getName());
            }
        }
        return names;
    }

    /** The Plugin Hub version (the pinned commit), or a note when running a local build. */
    private static String pluginVersion() {
        try {
            PluginHubManifest.DisplayData data = ExternalPluginManager.getDisplayData(AnchorCustomizerPlugin.class);
            if (data == null) {
                return "local build (not from the Plugin Hub)";
            }
            return shortCommit(data.getVersion()) + (data.getLastUpdatedAt() > 0
                    ? ", updated " + java.time.Instant.ofEpochMilli(data.getLastUpdatedAt()).toString().substring(0, 10)
                    : "");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String shortCommit(String commit) {
        return commit != null && commit.length() > 10 && commit.matches("[0-9a-f]+") ? commit.substring(0, 10) : commit;
    }

    private static String windowDescription(java.awt.Canvas canvas, GraphicsDevice device) {
        Window window = canvas != null ? SwingUtilities.getWindowAncestor(canvas) : null;
        if (window == null) {
            return "?";
        }
        Rectangle b = window.getBounds();
        boolean maximized = window instanceof Frame
                && (((Frame) window).getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        return rect(b) + (maximized ? ", maximized" : "") + (device != null ? ", on " + device.getIDstring() : "");
    }

    private static JsonArray displaysJson(GraphicsDevice windowDevice) {
        JsonArray out = new JsonArray();
        if (GraphicsEnvironment.isHeadless()) {
            return out;
        }
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", d.getIDstring());
            java.awt.DisplayMode mode = d.getDisplayMode();
            o.addProperty("mode", mode.getWidth() + "x" + mode.getHeight() + "@" + mode.getRefreshRate() + "Hz");
            Rectangle b = d.getDefaultConfiguration().getBounds();
            o.addProperty("bounds", rect(b));
            AffineTransform t = d.getDefaultConfiguration().getDefaultTransform();
            o.addProperty("scale", t.getScaleX() + "x" + t.getScaleY());
            o.addProperty("portrait", b.height > b.width);
            o.addProperty("hasWindow", d == windowDevice);
            out.add(o);
        }
        return out;
    }

    private JsonArray anchorsJson(Dimension canvas) {
        JsonArray out = new JsonArray();
        Rectangle canvasRect = canvas == null ? null : new Rectangle(0, 0, canvas.width, canvas.height);
        for (AnchorRegion r : anchorRegions) {
            JsonObject o = gson.toJsonTree(r).getAsJsonObject();
            if (canvasRect != null) {
                Rectangle b = r.getBounds();
                o.addProperty("onCanvas", canvasRect.contains(b) ? "fully" : canvasRect.intersects(b) ? "partly" : "off");
            }
            out.add(o);
        }
        return out;
    }

    private JsonArray overlaysJson() {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(overlayAssignments.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(a.getValue(), b.getValue());
            return c != 0 ? c : Integer.compare(overlayOrder.getOrDefault(a.getKey(), Integer.MAX_VALUE),
                    overlayOrder.getOrDefault(b.getKey(), Integer.MAX_VALUE));
        });
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Integer> a : entries) {
            String key = a.getKey();
            Overlay ov = trackedOverlays.get(key);
            if (ov == null) ov = movableOverlayByKey.get(key);
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            o.addProperty("anchor", a.getValue());
            o.addProperty("order", overlayOrder.get(key));
            o.addProperty("found", ov != null);
            o.addProperty("tracked", trackedOverlays.containsKey(key));
            if (ov != null) {
                Plugin owner = ov.getPlugin();
                o.addProperty("plugin", owner != null ? owner.getName() : null);
                o.addProperty("class", ov.getClass().getName());
                o.addProperty("movable", ov.isMovable());
                o.addProperty("layer", String.valueOf(ov.getLayer()));
                o.addProperty("position", String.valueOf(ov.getPosition()));
                o.addProperty("preferredPosition", String.valueOf(ov.getPreferredPosition()));
                o.addProperty("preferredLocation", point(ov.getPreferredLocation()));
                o.addProperty("preferredSize", ov.getPreferredSize() == null ? null : dim(ov.getPreferredSize()));
                o.addProperty("bounds", rect(ov.getBounds()));
            }
            o.addProperty("target", point(overlayTargets.get(key)));
            if (configManager != null) {
                JsonObject saved = new JsonObject();
                for (String suffix : OVERLAY_CONFIG_SUFFIXES) {
                    String v = configManager.getConfiguration("runelite", key + suffix);
                    if (v != null) {
                        saved.addProperty(suffix.substring(1), v);
                    }
                }
                o.add("savedByRuneLite", saved);
            }
            out.add(o);
        }
        return out;
    }

    /** One compact JSON object per line: readable in an issue, still valid JSON. */
    static String jsonLines(Gson g, JsonArray array) {
        if (array.size() == 0) {
            return "[]";
        }
        StringBuilder b = new StringBuilder("[\n");
        for (int i = 0; i < array.size(); i++) {
            b.append("  ").append(g.toJson(array.get(i))).append(i < array.size() - 1 ? ",\n" : "\n");
        }
        return b.append(']').toString();
    }

    private static String dim(Dimension d) {
        return d == null ? null : d.width + "x" + d.height;
    }

    private static String point(Point p) {
        return p == null ? null : p.x + "," + p.y;
    }

    private static String rect(Rectangle r) {
        return r == null ? null : r.x + "," + r.y + " " + r.width + "x" + r.height;
    }

    @Provides
    AnchorCustomizerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AnchorCustomizerConfig.class);
    }
}
