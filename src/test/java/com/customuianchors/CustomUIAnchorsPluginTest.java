package com.customuianchors;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CustomUIAnchorsPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(CustomUIAnchorsPlugin.class);
        RuneLite.main(args);
    }
}