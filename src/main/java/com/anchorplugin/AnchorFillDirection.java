/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

/**
 * Direction along one axis of an anchor box's fill layout (GitHub issue #24). The same
 * axis-neutral enum stores both the fill direction (order of items within a row/column)
 * and the wrap direction (which way new rows/columns are added once the first is full).
 * Only the wrapping fill modes use either; plain Vertical/Horizontal ignores both.
 */
public enum AnchorFillDirection {
    FORWARD,
    REVERSE;

    /** Axis-specific label: horizontal stacking reads left/right, vertical reads top/bottom. */
    public String label(AnchorStacking stacking) {
        boolean horizontal = stacking == AnchorStacking.HORIZONTAL || stacking == AnchorStacking.FILL_HORIZONTAL;
        if (this == FORWARD) return horizontal ? "Left to right" : "Top to bottom";
        return horizontal ? "Right to left" : "Bottom to top";
    }

    /** Label for the perpendicular (wrap) axis: FILL_HORIZONTAL wraps top/bottom, FILL_VERTICAL left/right. */
    public String wrapLabel(AnchorStacking stacking) {
        return label(stacking == AnchorStacking.FILL_HORIZONTAL || stacking == AnchorStacking.HORIZONTAL
                ? AnchorStacking.FILL_VERTICAL : AnchorStacking.FILL_HORIZONTAL);
    }

    /** Only the wrapping fill modes have a meaningful fill direction. */
    public static boolean appliesTo(AnchorStacking s) {
        return s == AnchorStacking.FILL_HORIZONTAL || s == AnchorStacking.FILL_VERTICAL;
    }
}
