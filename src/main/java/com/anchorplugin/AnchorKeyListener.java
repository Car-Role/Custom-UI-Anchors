/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
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
    private final Client client;

    // Written from the AWT event thread (key events / focusLost), read from the client
    // thread (isOverlaysVisible) and the AWT mouse thread (AnchorInputListener).
    private volatile boolean held = false;

    // AWT virtual key code -> Jagex KeyCode, used by {@link #isHotkeyDownLive} to query
    // the client's live key state ({@link Client#isKeyPressed(int)}) for non-modifier
    // drag hotkeys. Modifier keys (Alt/Ctrl/Shift/Meta) are handled separately via the
    // Keybind modifier mask + the AWT event, so they are intentionally absent here. The
    // Jagex codes are scancode-like (not alphabetical), so an explicit table is required.
    private static final Map<Integer, Integer> AWT_TO_JAGEX = new HashMap<>();
    static {
        // Letters
        AWT_TO_JAGEX.put(KeyEvent.VK_A, KeyCode.KC_A);
        AWT_TO_JAGEX.put(KeyEvent.VK_B, KeyCode.KC_B);
        AWT_TO_JAGEX.put(KeyEvent.VK_C, KeyCode.KC_C);
        AWT_TO_JAGEX.put(KeyEvent.VK_D, KeyCode.KC_D);
        AWT_TO_JAGEX.put(KeyEvent.VK_E, KeyCode.KC_E);
        AWT_TO_JAGEX.put(KeyEvent.VK_F, KeyCode.KC_F);
        AWT_TO_JAGEX.put(KeyEvent.VK_G, KeyCode.KC_G);
        AWT_TO_JAGEX.put(KeyEvent.VK_H, KeyCode.KC_H);
        AWT_TO_JAGEX.put(KeyEvent.VK_I, KeyCode.KC_I);
        AWT_TO_JAGEX.put(KeyEvent.VK_J, KeyCode.KC_J);
        AWT_TO_JAGEX.put(KeyEvent.VK_K, KeyCode.KC_K);
        AWT_TO_JAGEX.put(KeyEvent.VK_L, KeyCode.KC_L);
        AWT_TO_JAGEX.put(KeyEvent.VK_M, KeyCode.KC_M);
        AWT_TO_JAGEX.put(KeyEvent.VK_N, KeyCode.KC_N);
        AWT_TO_JAGEX.put(KeyEvent.VK_O, KeyCode.KC_O);
        AWT_TO_JAGEX.put(KeyEvent.VK_P, KeyCode.KC_P);
        AWT_TO_JAGEX.put(KeyEvent.VK_Q, KeyCode.KC_Q);
        AWT_TO_JAGEX.put(KeyEvent.VK_R, KeyCode.KC_R);
        AWT_TO_JAGEX.put(KeyEvent.VK_S, KeyCode.KC_S);
        AWT_TO_JAGEX.put(KeyEvent.VK_T, KeyCode.KC_T);
        AWT_TO_JAGEX.put(KeyEvent.VK_U, KeyCode.KC_U);
        AWT_TO_JAGEX.put(KeyEvent.VK_V, KeyCode.KC_V);
        AWT_TO_JAGEX.put(KeyEvent.VK_W, KeyCode.KC_W);
        AWT_TO_JAGEX.put(KeyEvent.VK_X, KeyCode.KC_X);
        AWT_TO_JAGEX.put(KeyEvent.VK_Y, KeyCode.KC_Y);
        AWT_TO_JAGEX.put(KeyEvent.VK_Z, KeyCode.KC_Z);
        // Digits (top row)
        AWT_TO_JAGEX.put(KeyEvent.VK_0, KeyCode.KC_0);
        AWT_TO_JAGEX.put(KeyEvent.VK_1, KeyCode.KC_1);
        AWT_TO_JAGEX.put(KeyEvent.VK_2, KeyCode.KC_2);
        AWT_TO_JAGEX.put(KeyEvent.VK_3, KeyCode.KC_3);
        AWT_TO_JAGEX.put(KeyEvent.VK_4, KeyCode.KC_4);
        AWT_TO_JAGEX.put(KeyEvent.VK_5, KeyCode.KC_5);
        AWT_TO_JAGEX.put(KeyEvent.VK_6, KeyCode.KC_6);
        AWT_TO_JAGEX.put(KeyEvent.VK_7, KeyCode.KC_7);
        AWT_TO_JAGEX.put(KeyEvent.VK_8, KeyCode.KC_8);
        AWT_TO_JAGEX.put(KeyEvent.VK_9, KeyCode.KC_9);
        // Function keys
        AWT_TO_JAGEX.put(KeyEvent.VK_F1, KeyCode.KC_F1);
        AWT_TO_JAGEX.put(KeyEvent.VK_F2, KeyCode.KC_F2);
        AWT_TO_JAGEX.put(KeyEvent.VK_F3, KeyCode.KC_F3);
        AWT_TO_JAGEX.put(KeyEvent.VK_F4, KeyCode.KC_F4);
        AWT_TO_JAGEX.put(KeyEvent.VK_F5, KeyCode.KC_F5);
        AWT_TO_JAGEX.put(KeyEvent.VK_F6, KeyCode.KC_F6);
        AWT_TO_JAGEX.put(KeyEvent.VK_F7, KeyCode.KC_F7);
        AWT_TO_JAGEX.put(KeyEvent.VK_F8, KeyCode.KC_F8);
        AWT_TO_JAGEX.put(KeyEvent.VK_F9, KeyCode.KC_F9);
        AWT_TO_JAGEX.put(KeyEvent.VK_F10, KeyCode.KC_F10);
        AWT_TO_JAGEX.put(KeyEvent.VK_F11, KeyCode.KC_F11);
        AWT_TO_JAGEX.put(KeyEvent.VK_F12, KeyCode.KC_F12);
        // Common non-letter keys
        AWT_TO_JAGEX.put(KeyEvent.VK_SPACE, KeyCode.KC_SPACE);
        AWT_TO_JAGEX.put(KeyEvent.VK_ENTER, KeyCode.KC_ENTER);
        AWT_TO_JAGEX.put(KeyEvent.VK_TAB, KeyCode.KC_TAB);
        AWT_TO_JAGEX.put(KeyEvent.VK_ESCAPE, KeyCode.KC_ESCAPE);
        AWT_TO_JAGEX.put(KeyEvent.VK_BACK_QUOTE, KeyCode.KC_BACK_QUOTE);
        AWT_TO_JAGEX.put(KeyEvent.VK_MINUS, KeyCode.KC_MINUS);
        AWT_TO_JAGEX.put(KeyEvent.VK_EQUALS, KeyCode.KC_EQUALS);
        AWT_TO_JAGEX.put(KeyEvent.VK_OPEN_BRACKET, KeyCode.KC_OPEN_BRACKET);
        AWT_TO_JAGEX.put(KeyEvent.VK_CLOSE_BRACKET, KeyCode.KC_CLOSE_BRACKET);
        AWT_TO_JAGEX.put(KeyEvent.VK_SEMICOLON, KeyCode.KC_SEMICOLON);
        AWT_TO_JAGEX.put(KeyEvent.VK_QUOTE, KeyCode.KC_QUOTE);
        AWT_TO_JAGEX.put(KeyEvent.VK_COMMA, KeyCode.KC_COMMA);
        AWT_TO_JAGEX.put(KeyEvent.VK_PERIOD, KeyCode.KC_PERIOD);
        AWT_TO_JAGEX.put(KeyEvent.VK_SLASH, KeyCode.KC_SLASH);
        AWT_TO_JAGEX.put(KeyEvent.VK_BACK_SLASH, KeyCode.KC_BACK_SLASH);
        AWT_TO_JAGEX.put(KeyEvent.VK_INSERT, KeyCode.KC_INSERT);
        AWT_TO_JAGEX.put(KeyEvent.VK_DELETE, KeyCode.KC_DELETE);
        AWT_TO_JAGEX.put(KeyEvent.VK_HOME, KeyCode.KC_HOME);
        AWT_TO_JAGEX.put(KeyEvent.VK_END, KeyCode.KC_END);
        AWT_TO_JAGEX.put(KeyEvent.VK_PAGE_UP, KeyCode.KC_PAGE_UP);
        AWT_TO_JAGEX.put(KeyEvent.VK_PAGE_DOWN, KeyCode.KC_PAGE_DOWN);
        AWT_TO_JAGEX.put(KeyEvent.VK_LEFT, KeyCode.KC_LEFT);
        AWT_TO_JAGEX.put(KeyEvent.VK_RIGHT, KeyCode.KC_RIGHT);
        AWT_TO_JAGEX.put(KeyEvent.VK_UP, KeyCode.KC_UP);
        AWT_TO_JAGEX.put(KeyEvent.VK_DOWN, KeyCode.KC_DOWN);
    }

    // Invoked on the AWT event thread whenever the hotkey is released or focus is lost.
    // The plugin wires this to reset the cursor and cancel any in-progress drag, so a
    // stationary mouse on Alt-release can't strand the move/resize cursor.
    private volatile Runnable onReleased;

    @Inject
    AnchorKeyListener(RuneLiteConfig runeLiteConfig, Client client) {
        this.runeLiteConfig = runeLiteConfig;
        this.client = client;
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

    /**
     * Robust "is the drag hotkey currently down?" check for use from the mouse listeners.
     *
     * Unlike {@link #isHeld()} (which depends on this listener actually receiving both the
     * KEY_PRESSED and KEY_RELEASED events), this consults the client's live key state via
     * {@link Client#isKeyPressed(int)} and the AWT mouse event's own modifier flags. That
     * makes anchor drag/resize immune to the failure modes where the tracked flag desyncs:
     *   - a KEY_PRESSED that never reached us (e.g. consumed by RuneLite's OverlayRenderer
     *     HotkeyListener for non-modifier drag hotkeys, which breaks the KeyManager chain),
     *   - focus blips around the 117 HD GPU canvas clearing the flag mid-edit (GitHub #6/#10),
     *   - a missed KEY_RELEASED leaving it stuck.
     *
     * The result is the OR of the live signal and the tracked flag, so it is strictly more
     * permissive than the previous tracked-only behaviour (it can only START allowing drags
     * that used to silently fail, never block ones that worked). The tracked flag is retained
     * as the fallback for login-screen editing and for any key absent from {@link #AWT_TO_JAGEX}.
     */
    public boolean isHotkeyDownLive(MouseEvent e) {
        return liveHotkeyDown(e) || held;
    }

    /**
     * No-event variant for callers without a MouseEvent (e.g. the overlay render pass deciding
     * whether to draw anchor highlights). Relies on the client's live key state plus the tracked
     * flag; the per-event modifier shortcut isn't available here.
     */
    public boolean isHotkeyDownLive() {
        return liveHotkeyDown(null) || held;
    }

    private boolean liveHotkeyDown(MouseEvent e) {
        Keybind kb = dragHotkey();
        int keyCode = kb.getKeyCode();
        int modifiers = kb.getModifiers();

        // Modifier-only bind (the default Alt, plus Ctrl/Shift/Meta): the Keybind stores
        // the modifier in its mask with keyCode == VK_UNDEFINED.
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            return modifiers != 0 && modifiersDown(modifiers, e);
        }

        // Bind with a primary (non-modifier) key, optionally with modifiers.
        Integer jagex = AWT_TO_JAGEX.get(keyCode);
        if (jagex == null || client == null || !client.isKeyPressed(jagex)) {
            return false;
        }
        return modifiers == 0 || modifiersDown(modifiers, e);
    }

    /**
     * Every modifier required by {@code modifiers} counts as held if either the client
     * reports it pressed or the originating AWT event carries it.
     */
    private boolean modifiersDown(int modifiers, MouseEvent e) {
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0
                && !(clientKeyPressed(KeyCode.KC_ALT) || (e != null && e.isAltDown()))) {
            return false;
        }
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0
                && !(clientKeyPressed(KeyCode.KC_CONTROL) || (e != null && e.isControlDown()))) {
            return false;
        }
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0
                && !(clientKeyPressed(KeyCode.KC_SHIFT) || (e != null && e.isShiftDown()))) {
            return false;
        }
        // No Jagex key code for Meta; rely on the AWT event alone.
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0 && !(e != null && e.isMetaDown())) {
            return false;
        }
        return true;
    }

    private boolean clientKeyPressed(int jagexKeyCode) {
        return client != null && client.isKeyPressed(jagexKeyCode);
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
