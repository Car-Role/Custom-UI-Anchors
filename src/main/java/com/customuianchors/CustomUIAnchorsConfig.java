package com.customuianchors;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import java.util.List;
import java.util.ArrayList;
import com.google.gson.reflect.TypeToken;
import net.runelite.client.config.ConfigSection;

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

    // Add this method to help with JSON serialization/deserialization
    default TypeToken<List<UIAnchor>> getAnchorListType() {
        return new TypeToken<List<UIAnchor>>() {};
    }
}