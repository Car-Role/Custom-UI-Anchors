/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("anchorcustomizer")
public interface AnchorCustomizerConfig extends Config {

    @ConfigItem(keyName = "showSidebarButton", name = "Show sidebar button", description = "Show the Custom UI Anchors button in the RuneLite sidebar. Turn this off to hide it once your anchors are set up.", position = 1)
    default boolean showSidebarButton() {
        return true;
    }

    @Range(min = 0, max = 20)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "stackSpacing", name = "Stack spacing", description = "Gap inserted between multiple overlays stacked inside the same anchor region. Set to 0 to pack them flush together.", position = 3)
    default int stackSpacing() {
        return 2;
    }

    @ConfigItem(keyName = "debugLogging", name = "Debug logging", description = "Log diagnostic details to the RuneLite client logs when you Alt+click an anchor. Only enable this if you're reproducing a drag/resize problem (e.g. with 117 HD) and sharing logs.", position = 2)
    default boolean debugLogging() {
        return false;
    }

    @ConfigItem(keyName = "regionJson", name = "Region Data", description = "Internal storage for region data", hidden = true)
    default String regionJson() {
        return "[]";
    }

    @ConfigItem(keyName = "regionJson", name = "Region Data", description = "Internal storage for region data", hidden = true)
    void setRegionJson(String json);

    @ConfigItem(keyName = "regionProfiles", name = "Region Profiles", description = "Internal storage for per-resolution region layout profiles", hidden = true)
    default String regionProfilesJson() {
        return "{}";
    }

    @ConfigItem(keyName = "regionProfiles", name = "Region Profiles", description = "Internal storage for per-resolution region layout profiles", hidden = true)
    void setRegionProfilesJson(String json);

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
