/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("anchorcustomizer")
public interface AnchorCustomizerConfig extends Config {

    // RuneLite's config panel can't host buttons, so this section points to the side panel's
    // "Having a problem?" buttons (Copy diagnostics / Report issue on GitHub / Copy logs folder path).
    @ConfigSection(name = "Report an issue", description = "To report a problem, open the Custom UI Anchors side panel and use the buttons under \"Having a problem?\" at the bottom: Copy diagnostics, Report issue on GitHub, and Copy logs folder path. If the sidebar button is hidden, turn on Show sidebar button first.", position = 10)
    String reportSection = "reportSection";

    @ConfigItem(keyName = "showSidebarButton", name = "Show sidebar button", description = "Show the Custom UI Anchors button in the RuneLite sidebar. Turn this off to hide it once your anchors are set up. The side panel also has the Copy diagnostics and Report issue buttons for bug reports.", position = 1)
    default boolean showSidebarButton() {
        return true;
    }

    @Range(min = 0, max = 20)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "stackSpacing", name = "Stack spacing", description = "Gap inserted between multiple overlays stacked inside the same anchor region. Set to 0 to pack them flush together. Each anchor can override this with its Spacing setting in the side panel.", position = 3)
    default int stackSpacing() {
        return 2;
    }

    @ConfigItem(keyName = "drawAboveInterfaces", name = "Render over interfaces", description = "Draw anchored overlays on top of game interfaces such as the bank (RuneLite's default for moved overlays). Turn off to let interfaces cover them instead.", position = 4)
    default boolean drawAboveInterfaces() {
        return true;
    }

    @ConfigItem(keyName = "anchorVisibility", name = "Show anchors", description = "When anchor boxes are shown on the canvas: only while the drag hotkey is held, only while an overlay is being dragged, or never outside the side panel. Anchors are always shown (and editable) while this side panel is open.", position = 5)
    default AnchorVisibility anchorVisibility() {
        return AnchorVisibility.HOTKEY_HELD;
    }

    @ConfigItem(keyName = "showPinIndicator", name = "Show pin line", description = "While you drag an anchor box, or point at one with the drag hotkey held, draw a thin line from the box to the corner, edge or center of the game window it is pinned to. The box keeps its distance to that point when the window changes size. The plugin picks the pin when you drop the box, unless you set it yourself in the side panel.", position = 6)
    default boolean showPinIndicator() {
        return true;
    }

    @ConfigItem(keyName = "debugLogging", name = "Debug logging", description = "To report a problem, use Copy diagnostics or Report issue on GitHub in the side panel (under \"Having a problem?\"). Turn this on only if asked: it writes extra details to client.log while you reproduce the problem (find it with Copy logs folder path in the side panel).", position = 11, section = reportSection)
    default boolean debugLogging() {
        return false;
    }

    // All layouts (anchors, overlay assignments and order per layout) plus which layout
    // each computer selected. This is the only layout data the plugin writes.
    @ConfigItem(keyName = "layouts", name = "Layouts", description = "Internal storage for the named layouts", hidden = true)
    default String layoutsJson() {
        return "";
    }

    @ConfigItem(keyName = "layouts", name = "Layouts", description = "Internal storage for the named layouts", hidden = true)
    void setLayoutsJson(String json);

    // ---- Data from before named layouts existed. Read once to build the layouts, then
    // never written again (no setters), so it stays in config as the backup. ----

    @ConfigItem(keyName = "regionJson", name = "Region Data", description = "Old internal storage for region data (read-only backup)", hidden = true)
    default String regionJson() {
        return "[]";
    }

    @ConfigItem(keyName = "regionProfiles", name = "Region Profiles", description = "Old internal storage for per-monitor layout profiles (read-only backup)", hidden = true)
    default String regionProfilesJson() {
        return "{}";
    }

    @ConfigItem(keyName = "overlayAssignments", name = "Overlay Assignments", description = "Old internal storage for overlay assignments (read-only backup)", hidden = true)
    default String overlayAssignmentsJson() {
        return "{}";
    }

    @ConfigItem(keyName = "overlayOrder", name = "Overlay Order", description = "Old internal storage for overlay ordering within regions (read-only backup)", hidden = true)
    default String overlayOrderJson() {
        return "{}";
    }

    @ConfigItem(keyName = "selectedRegionId", name = "Selected Region Id", description = "Internal storage for the last selected anchor region in the panel", hidden = true)
    default int selectedRegionId() {
        return -1;
    }

    @ConfigItem(keyName = "selectedRegionId", name = "Selected Region Id", description = "Internal storage for the last selected anchor region in the panel", hidden = true)
    void setSelectedRegionId(int id);
}
