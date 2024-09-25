package com.customuianchors;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import java.awt.image.BufferedImage;

@PluginDescriptor(
    name = "Custom UI Anchors",
    description = "Allows customization of UI element anchor points",
    tags = {"ui", "anchors", "custom"}
)
public class CustomUIAnchorsPlugin extends Plugin
{
    @Inject
    private CustomUIAnchorsConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private CustomUIAnchorsOverlay overlay;

    @Inject
    private ClientToolbar clientToolbar;

    private CustomUIAnchorsPanel panel;
    private NavigationButton navButton;

    @Provides
    CustomUIAnchorsConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CustomUIAnchorsConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        panel = injector.getInstance(CustomUIAnchorsPanel.class);
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Custom UI Anchors")
            .icon(icon)
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        clientToolbar.removeNavigation(navButton);
        panel = null;
    }
}