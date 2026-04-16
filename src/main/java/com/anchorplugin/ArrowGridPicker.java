/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import net.runelite.client.ui.ColorScheme;

/**
 * A 3x3 grid of toggle-button cells each labeled with a Unicode directional arrow.
 * Mirrors the 3x3 nine-patch idea used by many design tools (Figma alignment picker,
 * CSS align-content pickers, etc.): diagonal arrows for corners, straight arrows for
 * edges, and a dot for the center.
 *
 * The picker works with any enum (or any reference type) where exactly nine values
 * map to the nine cells in row-major top-to-bottom, left-to-right order.
 *
 * Purely visual replacement for a {@code JComboBox}; emits a single {@code Consumer}
 * callback when the user clicks a cell. Use {@link #setSelectedValue(Object)} to
 * update the highlight without firing the callback (for model-driven refreshes).
 */
public class ArrowGridPicker<E> extends JPanel {
    // ↖ ↑ ↗  ← ● →  ↙ ↓ ↘
    private static final String[] ARROW_GLYPHS = {
            "\u2196", "\u2191", "\u2197",
            "\u2190", "\u25CF", "\u2192",
            "\u2199", "\u2193", "\u2198",
    };

    private final JToggleButton[] buttons = new JToggleButton[9];
    private final E[] values;
    private Consumer<E> onChange;
    private int selectedIndex = -1;
    private boolean suppressEvents = false;
    private boolean gridEnabled = true;

    public ArrowGridPicker(E[] valuesInGridOrder, String[] cellTooltips) {
        if (valuesInGridOrder == null || valuesInGridOrder.length != 9) {
            throw new IllegalArgumentException("ArrowGridPicker requires exactly 9 values in row-major order");
        }
        this.values = valuesInGridOrder;

        setLayout(new GridLayout(3, 3, 2, 2));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < 9; i++) {
            final int idx = i;
            JToggleButton btn = new JToggleButton(ARROW_GLYPHS[i]);
            btn.setFont(btn.getFont().deriveFont(Font.BOLD, 16f));
            btn.setFocusPainted(false);
            btn.setMargin(new Insets(4, 4, 4, 4));
            btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            btn.setForeground(Color.WHITE);
            if (cellTooltips != null && cellTooltips.length == 9 && cellTooltips[i] != null) {
                btn.setToolTipText(cellTooltips[i]);
            }
            btn.addActionListener(e -> {
                if (suppressEvents || !gridEnabled) return;
                selectedIndex = idx;
                updateVisuals();
                if (onChange != null) onChange.accept(values[idx]);
            });
            group.add(btn);
            buttons[i] = btn;
            add(btn);
        }
        updateVisuals();
    }

    public void setOnChange(Consumer<E> callback) {
        this.onChange = callback;
    }

    public E getSelectedValue() {
        return selectedIndex >= 0 ? values[selectedIndex] : null;
    }

    /**
     * Set the highlighted cell without firing the change callback.
     */
    public void setSelectedValue(E value) {
        suppressEvents = true;
        try {
            int found = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == value) {
                    found = i;
                    break;
                }
            }
            selectedIndex = found;
            for (int i = 0; i < buttons.length; i++) {
                buttons[i].setSelected(i == found);
            }
            updateVisuals();
        } finally {
            suppressEvents = false;
        }
    }

    /**
     * Enable/disable the entire grid. Disabled grids grey out their cells and ignore
     * clicks. Used when a companion toggle (e.g. "Stretch") overrides the positional
     * choice.
     */
    public void setGridEnabled(boolean enabled) {
        this.gridEnabled = enabled;
        for (JToggleButton btn : buttons) {
            btn.setEnabled(enabled);
        }
        updateVisuals();
    }

    private void updateVisuals() {
        for (int i = 0; i < buttons.length; i++) {
            JToggleButton btn = buttons[i];
            boolean sel = btn.isSelected() && gridEnabled;
            if (sel) {
                btn.setBackground(ColorScheme.BRAND_ORANGE);
                btn.setForeground(Color.BLACK);
            } else {
                btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                btn.setForeground(gridEnabled ? Color.WHITE : Color.GRAY);
            }
        }
    }
}
