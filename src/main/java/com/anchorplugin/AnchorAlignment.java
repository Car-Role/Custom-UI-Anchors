/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

public enum AnchorAlignment {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    STRETCH // Adding STRETCH as a potential 10th option for "fill" behavior if needed, or
            // just to round it out.
    // Actually user said "ten options". Let's Stick to standard 9 for now to match
    // constraints,
    // maybe "STRETCH" is the 10th. I'll add it just in case.
}
