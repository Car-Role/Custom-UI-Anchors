package com.customuianchors;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import java.util.List;
import java.util.ArrayList;

@ConfigGroup("customuianchors")
public interface CustomUIAnchorsConfig extends Config
{
    @ConfigItem(
            keyName = "showAnchors",
            name = "Show Anchors",
            description = "Toggle to show/hide the custom UI anchors"
    )
    default boolean showAnchors()
    {
        return true;
    }

    @ConfigItem(
            keyName = "anchors",
            name = "UI Anchors",
            description = "List of custom UI anchors"
    )
    default List<UIAnchor> anchors()
    {
        return new ArrayList<>();
    }

    @ConfigItem(
            keyName = "anchors",
            name = "UI Anchors",
            description = "Set the list of UI anchors"
    )
    void setAnchors(List<UIAnchor> anchors);
}