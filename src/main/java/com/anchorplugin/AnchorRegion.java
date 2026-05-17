/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AnchorRegion {
    private int id;
    private String name;
    // Positional fields are written from the AWT mouse thread (AnchorInputListener
    // during drag/resize) and read from the client thread (AnchorCustomizerPlugin's
    // snap loop). Marking them volatile prevents torn reads of a half-updated
    // rectangle for a single tick. Int writes are already atomic per JLS; volatile
    // adds the visibility guarantee across threads without needing a lock.
    private volatile int x;
    private volatile int y;
    private volatile int width;
    private volatile int height;
    private AnchorConstraint constraint;
    private AnchorAlignment alignment = AnchorAlignment.CENTER; // Default to Center
    private AnchorStacking stacking = AnchorStacking.VERTICAL; // Default to Vertical

    // Origin snapshot used by the constraint-derivation tick loop. Captured at the
    // most recent user edit (drag end, edge-resize end, panel field commit, region
    // create). Each tick the live (x, y) is recomputed deterministically from
    // (origin, currentCanvasDim, constraint), making the position immune to phantom
    // dimension samples and accumulated delta drift.
    //
    // Persisted alongside x/y/width/height. On legacy data (where these are zero),
    // the tick loop seeds them from the loaded (x, y) plus the first valid canvas
    // dim it observes, so the first tick after upgrade is a no-op for the user.
    //
    // Volatile for the same reason as x/y — read on the client thread, written
    // from the AWT thread (input listener) and the client thread (panel commits
    // marshalled via clientThread.invoke).
    private volatile int originX;
    private volatile int originY;
    private volatile int originW;
    private volatile int originH;

    // Helper to get bounds as AWT Rectangle. Snapshots the four volatile fields in a
    // single call so callers get a self-consistent rectangle (still possible for x/y
    // vs width/height to be read across a drag update, but the worst case is a
    // one-frame visual glitch, never a crash).
    public java.awt.Rectangle getBounds() {
        return new java.awt.Rectangle(x, y, width, height);
    }
}
