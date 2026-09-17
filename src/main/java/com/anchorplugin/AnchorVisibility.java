/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

/**
 * When the anchor boxes are shown on the canvas (GitHub issue #18). Anchors are always
 * shown (and editable) while the side panel is open, regardless of this setting.
 */
public enum AnchorVisibility {
    HOTKEY_HELD("Hotkey held"),
    WHILE_DRAGGING("While dragging"),
    PANEL_OPEN("Panel open");

    private final String label;

    AnchorVisibility(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
