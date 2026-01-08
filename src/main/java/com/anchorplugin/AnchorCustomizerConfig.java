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
}
