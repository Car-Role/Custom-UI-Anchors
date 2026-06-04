/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("anchorcustomizer")
public interface AnchorCustomizerConfig extends Config {

    @ConfigItem(keyName = "showSidebarButton", name = "Show sidebar button", description = "Show the Custom UI Anchors button in the RuneLite sidebar. Turn this off to hide it once your anchors are set up.", position = 1)
    default boolean showSidebarButton() {
        return true;
    }

    @ConfigItem(keyName = "regionJson", name = "Region Data", description = "Internal storage for region data", hidden = true)
    default String regionJson() {
        return "[]";
    }

    @ConfigItem(keyName = "regionJson", name = "Region Data", description = "Internal storage for region data", hidden = true)
    void setRegionJson(String json);

    @ConfigItem(keyName = "overlayAssignments", name = "Overlay Assignments", description = "Internal storage for overlay assignments", hidden = true)
    default String overlayAssignmentsJson() {
        return "{}";
    }

    @ConfigItem(keyName = "overlayAssignments", name = "Overlay Assignments", description = "Internal storage for overlay assignments", hidden = true)
    void setOverlayAssignmentsJson(String json);

    @ConfigItem(keyName = "overlayOrder", name = "Overlay Order", description = "Internal storage for overlay ordering within regions", hidden = true)
    default String overlayOrderJson() {
        return "{}";
    }

    @ConfigItem(keyName = "overlayOrder", name = "Overlay Order", description = "Internal storage for overlay ordering within regions", hidden = true)
    void setOverlayOrderJson(String json);

    @ConfigItem(keyName = "selectedRegionId", name = "Selected Region Id", description = "Internal storage for the last selected anchor region in the panel", hidden = true)
    default int selectedRegionId() {
        return -1;
    }

    @ConfigItem(keyName = "selectedRegionId", name = "Selected Region Id", description = "Internal storage for the last selected anchor region in the panel", hidden = true)
    void setSelectedRegionId(int id);
}
