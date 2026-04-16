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
    /**
     * @deprecated The Stretch toggle was removed from the UI because it was unreliable.
     * This value is retained only so older saved configs (persisted via Gson) continue
     * to deserialize; the panel coerces it to {@link #CENTER} on load.
     */
    @Deprecated
    STRETCH
}
