/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the plugin saves. Pure data and logic — no RuneLite client — so it is
 * unit-testable.
 *
 * There is ONE layout for resizable mode ({@link #main}). It serves every window size,
 * monitor and computer, because each anchor's position is derived from its pin
 * (constraint) and the canvas size. Fixed mode has its own layout ({@link #fixed}): its
 * 765x503 canvas cannot share a layout with a resizable window, the user switches mode
 * on purpose, and the client reports the mode reliably.
 *
 * Nothing else ever swaps the layout. Older versions kept one hidden layout per monitor
 * resolution and swapped on what AWT reported, which swapped in stale layouts (GitHub
 * issue #28). Those old per-monitor layouts are kept in {@link #oldLayouts} so the user
 * can bring one back by hand.
 */
final class LayoutStore {
    static final String MAIN_ID = "main";
    static final String FIXED_ID = "fixed";
    static final String BEFORE_RESTORE_ID = "before-restore";
    static final String BEFORE_RESTORE_NAME = "Layout before the last restore";
    static final int VERSION = 1;

    static final String EXPORT_FORMAT = "custom-ui-anchors";
    // 1 = the four raw blobs of the per-monitor profile era; 2 = this store.
    static final int EXPORT_VERSION = 2;

    int version = VERSION;

    // The resizable-mode layout. Never null after normalize().
    AnchorLayout main;
    // The fixed-mode layout; null until fixed mode is first used.
    AnchorLayout fixed;
    // Layouts the user can restore into main by hand: the old per-monitor profiles (only
    // kept when there were several) and the layout replaced by the last restore.
    List<AnchorLayout> oldLayouts = new ArrayList<>();

    // Several old monitor profiles were migrated: the first login reads the monitor one
    // last time and makes that monitor's profile the layout, so nothing changes on screen
    // on update day. Cleared after that single read.
    boolean legacyPickPending = false;
    // The user is told once that there is now one layout and where the others went.
    boolean noticePending = false;

    // ---- Lookup ---------------------------------------------------------------------

    AnchorLayout ensureFixed() {
        if (fixed == null) {
            fixed = newLayout(FIXED_ID, "Fixed mode");
            fixed.setUnseeded(true);
        }
        return fixed;
    }

    AnchorLayout old(String id) {
        if (id == null) {
            return null;
        }
        for (AnchorLayout l : oldLayouts) {
            if (id.equals(l.getId())) {
                return l;
            }
        }
        return null;
    }

    private static AnchorLayout newLayout(String id, String name) {
        AnchorLayout l = new AnchorLayout();
        l.setId(id);
        l.setName(name);
        return l;
    }

    // ---- Restore --------------------------------------------------------------------

    /**
     * Replace the layout with an old one. The layout being replaced is kept as
     * "Layout before the last restore", so one restore can always be undone.
     */
    boolean restore(String oldId) {
        AnchorLayout source = old(oldId);
        if (source == null) {
            return false;
        }
        AnchorLayout backup = newLayout(BEFORE_RESTORE_ID, BEFORE_RESTORE_NAME);
        copyContent(main, backup);
        AnchorLayout content = newLayout(null, null);
        copyContent(source, content); // source may be the backup slot we are about to replace
        oldLayouts.removeIf(l -> BEFORE_RESTORE_ID.equals(l.getId()));
        oldLayouts.add(backup);
        copyContent(content, main);
        main.setLegacyKey(source.getLegacyKey());
        return true;
    }

    /**
     * One-time pick after the migration: make the old profile of the monitor in use the
     * layout. Returns true when the layout's content changed. No backup is taken — the
     * provisional content it replaces is itself still in {@link #oldLayouts}.
     */
    boolean pickLegacy(String displayKey) {
        legacyPickPending = false;
        if (displayKey == null || displayKey.equals(main.getLegacyKey())) {
            return false;
        }
        for (AnchorLayout l : oldLayouts) {
            if (displayKey.equals(l.getLegacyKey())) {
                copyContent(l, main);
                main.setLegacyKey(displayKey);
                return true;
            }
        }
        return false;
    }

    /** Deep-copy anchors, overlay tables and the pending pin upgrade from one layout to another. */
    static void copyContent(AnchorLayout from, AnchorLayout to) {
        to.setRegions(deepCopy(from.getRegions()));
        to.setAssignments(new LinkedHashMap<>(from.getAssignments()));
        to.setOrder(new LinkedHashMap<>(from.getOrder()));
        to.setNextRegionId(Math.max(to.getNextRegionId(), from.getNextRegionId()));
        to.setRepinPending(from.isRepinPending());
    }

    static List<AnchorRegion> deepCopy(List<AnchorRegion> regions) {
        List<AnchorRegion> out = new ArrayList<>();
        if (regions != null) {
            for (AnchorRegion r : regions) {
                if (r != null) {
                    out.add(r.scaledCopy(1.0));
                }
            }
        }
        return out;
    }

    // ---- Repair ---------------------------------------------------------------------

    /**
     * Repair loaded data in place so the rest of the plugin can trust it: a main layout
     * always exists, no null lists, valid anchors, and an anchor-id counter above every
     * used id.
     */
    void normalize() {
        if (main == null) {
            main = newLayout(MAIN_ID, "Layout");
            main.setUnseeded(true);
        }
        main.setId(MAIN_ID);
        normalizeLayout(main);
        if (fixed != null) {
            fixed.setId(FIXED_ID);
            normalizeLayout(fixed);
        }
        if (oldLayouts == null) {
            oldLayouts = new ArrayList<>();
        }
        List<AnchorLayout> kept = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (AnchorLayout l : oldLayouts) {
            if (l == null || l.getId() == null || !ids.add(l.getId())) {
                continue;
            }
            normalizeLayout(l);
            if (l.getName() == null || l.getName().trim().isEmpty()) {
                l.setName(l.getLegacyKey() != null ? l.getLegacyKey() : "Old layout");
            }
            kept.add(l);
        }
        oldLayouts = kept;
        if (oldLayouts.isEmpty()) {
            legacyPickPending = false;
        }
    }

    private static void normalizeLayout(AnchorLayout l) {
        l.setRegions(normalizeRegions(l.getRegions()));
        if (l.getAssignments() == null) {
            l.setAssignments(new LinkedHashMap<>());
        }
        if (l.getOrder() == null) {
            l.setOrder(new LinkedHashMap<>());
        }
        l.getAssignments().values().removeIf(v -> v == null);
        l.getOrder().values().removeIf(v -> v == null);
        int maxId = 0;
        for (AnchorRegion r : l.getRegions()) {
            maxId = Math.max(maxId, r.getId());
        }
        l.setNextRegionId(Math.max(l.getNextRegionId(), maxId + 1));
    }

    /**
     * Validate and repair a deserialized anchor list: null entries dropped, null enums
     * defaulted, degenerate sizes clamped, duplicate/non-positive ids reassigned, and
     * missing names defaulted.
     */
    static List<AnchorRegion> normalizeRegions(List<AnchorRegion> loaded) {
        List<AnchorRegion> result = new ArrayList<>();
        if (loaded == null) {
            return result;
        }
        Set<Integer> usedIds = new HashSet<>();
        for (AnchorRegion r : loaded) {
            if (r != null && r.getId() > 0) {
                usedIds.add(r.getId());
            }
        }
        Set<Integer> seen = new HashSet<>();
        int nextSyntheticId = 1;
        for (AnchorRegion r : loaded) {
            if (r == null) continue;
            // Normalize null enums (may occur in old persisted data)
            if (r.getConstraint() == null) r.setConstraint(AnchorConstraint.TOP_LEFT);
            if (r.getAlignment() == null) r.setAlignment(AnchorAlignment.CENTER);
            if (r.getStacking() == null) r.setStacking(AnchorStacking.VERTICAL);
            if (r.getFillDirection() == null) r.setFillDirection(AnchorFillDirection.FORWARD);
            if (r.getWrapDirection() == null) r.setWrapDirection(AnchorFillDirection.FORWARD);

            // Defensive validation against corrupt/old config data
            if (r.getWidth() < 10) r.setWidth(10);
            if (r.getHeight() < 10) r.setHeight(10);

            // De-duplicate ids: the first occurrence keeps its id; later duplicates (or
            // non-positive ids) get the next positive int no anchor in the list uses.
            int id = r.getId();
            if (id <= 0 || seen.contains(id)) {
                while (usedIds.contains(nextSyntheticId)) nextSyntheticId++;
                id = nextSyntheticId++;
                r.setId(id);
                usedIds.add(id);
            }
            seen.add(id);

            // Default name if missing, so the list renderer never shows null/empty
            String name = r.getName();
            if (name == null || name.isEmpty()) {
                r.setName("Box " + id);
            }

            result.add(r);
        }
        return result;
    }

    // ---- Save / load ----------------------------------------------------------------

    String toJson(Gson gson) {
        return gson.toJson(this);
    }

    /** Parse the saved store. Returns null when there is nothing saved or it cannot be read. */
    static LayoutStore fromJson(Gson gson, String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            LayoutStore s = gson.fromJson(json, LayoutStore.class);
            if (s == null || s.main == null) {
                return null;
            }
            s.normalize();
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- Migration from per-monitor profiles ----------------------------------------

    /**
     * Build the store from the data older versions saved: the last live anchor list
     * ({@code regionJson}), the per-monitor profile map ({@code regionProfiles}), and the
     * overlay tables all profiles shared ({@code overlayAssignments}, {@code overlayOrder}).
     *
     * One resizable profile simply becomes the layout. With several, the layout starts
     * as the profile that was live last, every profile is kept in {@link #oldLayouts},
     * and {@link #legacyPickPending} asks the first login to switch to the profile of the
     * monitor in use. The "fixed" profile becomes the fixed-mode layout. The shared
     * overlay tables are copied into each layout, keeping only entries whose anchor id
     * exists there.
     */
    static LayoutStore migrate(Gson gson, String regionJson, String profilesJson,
            String assignmentsJson, String orderJson) {
        List<AnchorRegion> lastLive = parse(gson, regionJson, new TypeToken<List<AnchorRegion>>() { });
        Map<String, List<AnchorRegion>> profiles =
                parse(gson, profilesJson, new TypeToken<LinkedHashMap<String, List<AnchorRegion>>>() { });
        Map<String, Integer> assignments =
                parse(gson, assignmentsJson, new TypeToken<LinkedHashMap<String, Integer>>() { });
        Map<String, Integer> order =
                parse(gson, orderJson, new TypeToken<LinkedHashMap<String, Integer>>() { });
        if (lastLive == null) lastLive = new ArrayList<>();
        if (profiles == null) profiles = new LinkedHashMap<>();
        if (assignments == null) assignments = new LinkedHashMap<>();
        if (order == null) order = new LinkedHashMap<>();
        lastLive = normalizeRegions(lastLive);

        // Resizable profiles; an empty one is only kept when no profile has anchors.
        List<String> keys = new ArrayList<>();
        boolean anyNonEmpty = false;
        for (Map.Entry<String, List<AnchorRegion>> e : profiles.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || FIXED_ID.equals(e.getKey())) {
                continue;
            }
            keys.add(e.getKey());
            anyNonEmpty |= !e.getValue().isEmpty();
        }
        if (anyNonEmpty) {
            final Map<String, List<AnchorRegion>> p = profiles;
            keys.removeIf(k -> p.get(k).isEmpty());
        }

        LayoutStore s = new LayoutStore();
        List<AnchorRegion> fixedRegions = profiles.get(FIXED_ID);
        if (keys.isEmpty()) {
            // No resizable profile (data from before profiles existed, or a user who
            // only ever played fixed mode): the last live list is the layout, which is
            // what older versions seeded a first resizable profile from. With no anchors
            // at all, resizable mode starts as a copy of the fixed layout when first shown.
            s.main = migrated(MAIN_ID, "Layout", null, lastLive, assignments, order);
            s.main.setUnseeded(lastLive.isEmpty());
        } else {
            String provisional = keys.get(0);
            for (String key : keys) {
                if (sameAnchors(normalizeRegions(deepCopy(profiles.get(key))), lastLive)) {
                    provisional = key;
                    break;
                }
            }
            s.main = migrated(MAIN_ID, "Layout", provisional, profiles.get(provisional), assignments, order);
            if (keys.size() > 1) {
                for (String key : keys) {
                    s.oldLayouts.add(migrated("old-" + key, key, key, profiles.get(key), assignments, order));
                }
                s.legacyPickPending = true;
                s.noticePending = true;
            }
        }
        if (fixedRegions != null) {
            s.fixed = migrated(FIXED_ID, "Fixed mode", null, fixedRegions, assignments, order);
        }
        s.normalize();
        return s;
    }

    private static AnchorLayout migrated(String id, String name, String legacyKey, List<AnchorRegion> regions,
            Map<String, Integer> sharedAssignments, Map<String, Integer> sharedOrder) {
        AnchorLayout l = newLayout(id, name);
        l.setLegacyKey(legacyKey);
        l.setRegions(normalizeRegions(deepCopy(regions)));
        // Old anchors still on the default top-left pin get pinned to their nearest edge
        // the first time this layout is shown (see AnchorCustomizerPlugin.upgradePins).
        l.setRepinPending(true);
        Set<Integer> ids = new HashSet<>();
        for (AnchorRegion r : l.getRegions()) {
            ids.add(r.getId());
            // Older versions pinned every new anchor top-left and only the user changed
            // it, so any other pin was a deliberate choice: the plugin must not touch it.
            r.setManualPin(r.getConstraint() != AnchorConstraint.TOP_LEFT);
        }
        for (Map.Entry<String, Integer> e : sharedAssignments.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && ids.contains(e.getValue())) {
                l.getAssignments().put(e.getKey(), e.getValue());
                Integer o = sharedOrder.get(e.getKey());
                if (o != null) {
                    l.getOrder().put(e.getKey(), o);
                }
            }
        }
        return l;
    }

    private static boolean sameAnchors(List<AnchorRegion> a, List<AnchorRegion> b) {
        if (a.size() != b.size() || a.isEmpty()) {
            return false;
        }
        Set<String> names = new HashSet<>();
        for (AnchorRegion r : a) {
            names.add(r.getId() + ":" + r.getName());
        }
        for (AnchorRegion r : b) {
            if (!names.contains(r.getId() + ":" + r.getName())) {
                return false;
            }
        }
        return true;
    }

    private static <T> T parse(Gson gson, String json, TypeToken<T> type) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return gson.fromJson(json, type.getType());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- Clipboard export / import ---------------------------------------------------

    JsonObject toExport(Gson gson) {
        JsonObject root = new JsonObject();
        root.addProperty("format", EXPORT_FORMAT);
        root.addProperty("version", EXPORT_VERSION);
        root.add("layout", gson.toJsonTree(main));
        if (fixed != null) {
            root.add("fixedLayout", gson.toJsonTree(fixed));
        }
        if (!oldLayouts.isEmpty()) {
            root.add("oldLayouts", gson.toJsonTree(oldLayouts));
        }
        return root;
    }

    /** Rebuild a store from an export of either version. Never returns null. */
    static LayoutStore fromExport(Gson gson, JsonObject root) {
        JsonElement layoutEl = root.get("layout");
        if (layoutEl != null && layoutEl.isJsonObject()) {
            LayoutStore s = new LayoutStore();
            try {
                s.main = gson.fromJson(layoutEl, AnchorLayout.class);
                JsonElement fixedEl = root.get("fixedLayout");
                if (fixedEl != null && fixedEl.isJsonObject()) {
                    s.fixed = gson.fromJson(fixedEl, AnchorLayout.class);
                }
                JsonElement oldEl = root.get("oldLayouts");
                if (oldEl != null && oldEl.isJsonArray()) {
                    List<AnchorLayout> old = gson.fromJson(oldEl, new TypeToken<List<AnchorLayout>>() { }.getType());
                    if (old != null) {
                        s.oldLayouts = old;
                    }
                }
            } catch (RuntimeException e) {
                // keep whatever parsed; normalize() fills in the rest
            }
            s.normalize();
            return s;
        }
        // Version 1: the four raw blobs. Imported layouts were chosen by the user, so
        // there is no update-day notice — but the monitor pick still applies.
        LayoutStore s = migrate(gson, child(gson, root, "regionJson"), child(gson, root, "regionProfiles"),
                child(gson, root, "overlayAssignments"), child(gson, root, "overlayOrder"));
        s.noticePending = false;
        return s;
    }

    private static String child(Gson gson, JsonObject root, String key) {
        JsonElement el = root.get(key);
        return el == null || el.isJsonNull() ? null : gson.toJson(el);
    }
}
