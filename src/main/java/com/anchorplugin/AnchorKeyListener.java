/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.input.KeyListener;

/**
 * Tracks whether RuneLite's configured "Drag hotkey" is currently held, so the plugin
 * no longer hardcodes Alt (see GitHub issue #4). The hotkey is read live from
 * {@link RuneLiteConfig#dragHotkey()} on every key event, so changing it in RuneLite's
 * settings takes effect immediately without a plugin restart.
 *
 * {@link Keybind#matches(KeyEvent)} is documented to return true for the press of a
 * hotkey when given a KEY_PRESSED event and for its release when given a KEY_RELEASED
 * event, so it works for modifier-only binds (the default {@link Keybind#ALT}), plain
 * keys, and modifier+key combos alike.
 *
 * {@link #focusLost()} clears the flag so a missed key-release (e.g. Alt+Tab away while
 * the hotkey is held) can never strand the plugin in a permanent "drag mode" that
 * silently consumes input — a failure mode that previously looked like the anchors
 * becoming uninteractable.
 */
public class AnchorKeyListener implements KeyListener {
    private final RuneLiteConfig runeLiteConfig;

    // Written from the AWT event thread (key events / focusLost), read from the client
    // thread (isOverlaysVisible) and the AWT mouse thread (AnchorInputListener).
    private volatile boolean held = false;

    // Invoked on the AWT event thread whenever the hotkey is released or focus is lost.
    // The plugin wires this to reset the cursor and cancel any in-progress drag, so a
    // stationary mouse on Alt-release can't strand the move/resize cursor.
    private volatile Runnable onReleased;

    @Inject
    AnchorKeyListener(RuneLiteConfig runeLiteConfig) {
        this.runeLiteConfig = runeLiteConfig;
    }

    public void setOnReleased(Runnable onReleased) {
        this.onReleased = onReleased;
    }

    /**
     * The active drag hotkey, falling back to Alt when the user has it set to "Not set"
     * (confirmed desired behaviour) so the plugin always has a usable edit modifier.
     */
    private Keybind dragHotkey() {
        Keybind kb = runeLiteConfig.dragHotkey();
        if (kb == null || Keybind.NOT_SET.equals(kb)) {
            return Keybind.ALT;
        }
        return kb;
    }

    public boolean isHeld() {
        return held;
    }

    public void reset() {
        held = false;
    }

    @Override
    public boolean isEnabledOnLoginScreen() {
        // Keep tracking on the login screen so the held state is already correct the
        // moment the user logs in and starts editing.
        return true;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // no-op
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (dragHotkey().matches(e)) {
            held = true;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (dragHotkey().matches(e)) {
            held = false;
            fireReleased();
        }
    }

    @Override
    public void focusLost() {
        held = false;
        fireReleased();
    }

    private void fireReleased() {
        Runnable r = onReleased;
        if (r != null) {
            r.run();
        }
    }
}
