/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One layout: a complete, self-contained set of anchors plus the tables that say which
 * overlay sits in which anchor and in what order. Layouts share nothing with each other,
 * so an anchor id only has a meaning inside its own layout. See {@link LayoutStore} for
 * which layouts exist.
 */
@Data
@NoArgsConstructor
public class AnchorLayout {
    private String id;
    private String name;

    // The old per-monitor profile key this layout's content came from (e.g.
    // "3440x1440"), or null. Only used for the one-time monitor pick after migration.
    private String legacyKey;

    // True for layouts built from older data until first shown: anchors still on the
    // old default top-left pin are then pinned to their nearest edge, once, without
    // moving them.
    private boolean repinPending = false;

    // Next anchor id to hand out. Ids are never reused inside a layout, so a stale
    // reference can never point at a different anchor.
    private int nextRegionId = 1;

    // True until the layout is first shown. A layout that has never been shown takes a
    // one-time copy of the layout it replaces on screen (fixed mode and resizable mode
    // each start from the other's anchors instead of from nothing).
    private boolean unseeded = false;

    private List<AnchorRegion> regions = new ArrayList<>();
    // overlay key -> anchor id
    private Map<String, Integer> assignments = new LinkedHashMap<>();
    // overlay key -> order index inside its anchor
    private Map<String, Integer> order = new LinkedHashMap<>();

    public boolean isFixed() {
        return LayoutStore.FIXED_ID.equals(id);
    }
}
