package com.customuianchors;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;

public class CustomUIAnchorsOverlay extends Overlay
{
    private final CustomUIAnchorsConfig config;

    @Inject
    private CustomUIAnchorsOverlay(CustomUIAnchorsConfig config)
    {
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showAnchors())
        {
            return null;
        }

        for (UIAnchor anchor : config.anchors())
        {
            renderAnchor(graphics, anchor);
        }

        return null;
    }

    private void renderAnchor(Graphics2D graphics, UIAnchor anchor)
    {
        graphics.setColor(new Color(255, 0, 0, 100));
        graphics.fillRect(anchor.getX(), anchor.getY(), anchor.getWidth(), anchor.getHeight());
        graphics.setColor(Color.RED);
        graphics.drawRect(anchor.getX(), anchor.getY(), anchor.getWidth(), anchor.getHeight());
        graphics.setColor(Color.WHITE);
        graphics.drawString(anchor.getName(), anchor.getX() + 5, anchor.getY() + 15);
    }
}