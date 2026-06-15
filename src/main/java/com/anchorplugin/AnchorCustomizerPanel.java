/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class AnchorCustomizerPanel extends PluginPanel {
    private final AnchorCustomizerPlugin plugin;
    private final DefaultListModel<AnchorRegion> listModel = new DefaultListModel<>();
    private final JList<AnchorRegion> regionList;

    // Properties Panel
    private final JPanel propertiesPanel;
    private final JTextField nameField;
    private final JSpinner xSpinner;
    private final JSpinner ySpinner;
    private final JSpinner widthSpinner;
    private final JSpinner heightSpinner;
    private final ArrowGridPicker<AnchorConstraint> constraintPicker;
    private final ArrowGridPicker<AnchorAlignment> alignmentPicker;
    private final JComboBox<AnchorStacking> stackingComboBox;

    // Width of the clickable padlock zone at the right edge of each list row.
    private static final int LOCK_ZONE_WIDTH = 28;

    private AnchorRegion selectedRegion;
    private boolean isUpdating = false;

    public AnchorCustomizerPanel(AnchorCustomizerPlugin plugin) {
        super();
        this.plugin = plugin;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // --- List Panel ---
        JPanel listContainer = new JPanel(new BorderLayout());
        listContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listContainer.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Visibility is now automatic (Panel Open OR Alt Held)
        JPanel topControlPanel = new JPanel(new BorderLayout());
        topControlPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        // topControlPanel.setBorder(new EmptyBorder(0, 0, 10, 0));
        // No longer strictly needed if empty, but keeping structure for spacing

        listContainer.add(topControlPanel, BorderLayout.NORTH);

        regionList = new JList<>(listModel);
        regionList.setCellRenderer(new AnchorRegionListRenderer());
        regionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        regionList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        regionList.setToolTipText(
                "Drag entries to reorder layering: the top of the list renders above lower entries, "
                        + "and overlapping anchors are picked top-first on the canvas. New anchors are added at the bottom. "
                        + "Click the padlock to lock/unlock an anchor (locked anchors can't be moved or resized on the canvas).");

        // Click-to-toggle lock: a click landing in the padlock zone at the right edge of
        // a row flips that anchor's lock. Uses mouseClicked (press+release without
        // movement) so starting a reorder drag from the icon doesn't also toggle it.
        regionList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int idx = regionList.locationToIndex(e.getPoint());
                if (idx < 0 || idx >= listModel.size()) {
                    return;
                }
                java.awt.Rectangle cell = regionList.getCellBounds(idx, idx);
                if (cell == null || !cell.contains(e.getPoint())) {
                    return;
                }
                if (e.getPoint().x >= cell.x + cell.width - LOCK_ZONE_WIDTH) {
                    AnchorRegion region = listModel.get(idx);
                    // Immediate EDT write for instant visual feedback (volatile boolean,
                    // no geometry/list mutation involved), then persist via the client
                    // thread. No origin rebaseline needed — geometry is untouched.
                    region.setLocked(!region.isLocked());
                    plugin.saveRegionsFromAnyThread();
                    regionList.repaint();
                }
            }
        });

        // Drag-and-drop reordering: list order IS the layer stack (index 0 = topmost).
        regionList.setDragEnabled(true);
        regionList.setDropMode(DropMode.INSERT);
        regionList.setTransferHandler(new ListReorderHandler());

        regionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !isUpdating) {
                // Route through the plugin so the selected region id is persisted (Fix SEL).
                // plugin.selectAnchor schedules our setSelectedRegion back on the EDT, which
                // is a no-op if the selection is already where we set it here.
                plugin.selectAnchor(regionList.getSelectedValue());
            }
        });

        JScrollPane scrollPane = new JScrollPane(regionList);
        scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listContainer.add(scrollPane, BorderLayout.CENTER);

        JButton addButton = new JButton("Add New Region Anchor");
        addButton.setToolTipText("Create a new anchor box. Hold Alt in-game to see and drag anchors, or drag overlays into a box to assign them.");
        addButton.addActionListener(e -> {
            plugin.createNewAnchor();
            // Plugin will trigger updateList()
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        btnPanel.add(addButton);
        listContainer.add(btnPanel, BorderLayout.SOUTH);

        add(listContainer, BorderLayout.NORTH);

        // --- Properties Panel ---
        propertiesPanel = new JPanel();
        propertiesPanel.setLayout(new GridBagLayout());
        propertiesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        propertiesPanel.setBorder(BorderFactory.createTitledBorder("Properties"));
        propertiesPanel.setVisible(false);

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);
        c.weightx = 1;
        c.gridx = 0;
        c.gridy = 0;

        // Name
        JLabel nameLabel = new JLabel("Name:");
        nameLabel.setToolTipText("Display name for this anchor (only shown in this sidebar).");
        propertiesPanel.add(nameLabel, c);
        c.gridy++;
        nameField = new JTextField();
        nameField.setToolTipText("Display name for this anchor (only shown in this sidebar).");
        nameField.addActionListener(e -> saveChanges());
        nameField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                saveChanges();
            }
        });
        propertiesPanel.add(nameField, c);

        // Constraint picker (3x3 grid)
        c.gridy++;
        JLabel constraintLabel = new JLabel("Constraint:");
        constraintLabel.setToolTipText(
                "Which part of the game window this anchor pins to. When the window is resized, "
                        + "the anchor stays glued to the chosen corner / edge / center.");
        propertiesPanel.add(constraintLabel, c);
        c.gridy++;
        AnchorConstraint[] constraintGrid = {
                AnchorConstraint.TOP_LEFT, AnchorConstraint.TOP_CENTER, AnchorConstraint.TOP_RIGHT,
                AnchorConstraint.CENTER_LEFT, AnchorConstraint.CENTER, AnchorConstraint.CENTER_RIGHT,
                AnchorConstraint.BOTTOM_LEFT, AnchorConstraint.BOTTOM_CENTER, AnchorConstraint.BOTTOM_RIGHT,
        };
        String[] constraintCellTips = {
                "Pin to the top-left corner of the game window.",
                "Pin to the top edge, horizontally centered.",
                "Pin to the top-right corner of the game window.",
                "Pin to the left edge, vertically centered.",
                "Pin to the center of the game window.",
                "Pin to the right edge, vertically centered.",
                "Pin to the bottom-left corner of the game window.",
                "Pin to the bottom edge, horizontally centered.",
                "Pin to the bottom-right corner of the game window.",
        };
        constraintPicker = new ArrowGridPicker<>(constraintGrid, constraintCellTips);
        constraintPicker.setToolTipText(constraintLabel.getToolTipText());
        constraintPicker.setOnChange(v -> saveChanges());
        propertiesPanel.add(constraintPicker, c);

        // Alignment picker (3x3 grid)
        c.gridy++;
        JLabel alignLabel = new JLabel("Alignment:");
        alignLabel.setToolTipText("Where overlays are placed inside this anchor box.");
        propertiesPanel.add(alignLabel, c);
        c.gridy++;
        AnchorAlignment[] alignGrid = {
                AnchorAlignment.TOP_LEFT, AnchorAlignment.TOP_CENTER, AnchorAlignment.TOP_RIGHT,
                AnchorAlignment.CENTER_LEFT, AnchorAlignment.CENTER, AnchorAlignment.CENTER_RIGHT,
                AnchorAlignment.BOTTOM_LEFT, AnchorAlignment.BOTTOM_CENTER, AnchorAlignment.BOTTOM_RIGHT,
        };
        String[] alignCellTips = {
                "Align overlays to the top-left of this box.",
                "Align overlays to the top, horizontally centered.",
                "Align overlays to the top-right of this box.",
                "Align overlays to the left, vertically centered.",
                "Align overlays to the center of this box.",
                "Align overlays to the right, vertically centered.",
                "Align overlays to the bottom-left of this box.",
                "Align overlays to the bottom, horizontally centered.",
                "Align overlays to the bottom-right of this box.",
        };
        alignmentPicker = new ArrowGridPicker<>(alignGrid, alignCellTips);
        alignmentPicker.setToolTipText(alignLabel.getToolTipText());
        alignmentPicker.setOnChange(v -> {
            if (isUpdating) return;
            saveChanges();
        });
        propertiesPanel.add(alignmentPicker, c);

        // Stacking
        c.gridy++;
        JLabel stackingLabel = new JLabel("Stacking:");
        stackingLabel.setToolTipText(
                "How multiple overlays inside this anchor are arranged. "
                        + "Vertical = stacked top-to-bottom. Horizontal = left-to-right. "
                        + "Fill-Horizontal = rows that wrap when the box fills up. "
                        + "Fill-Vertical = columns that wrap when the box fills up.");
        propertiesPanel.add(stackingLabel, c);
        c.gridy++;
        stackingComboBox = new JComboBox<>(AnchorStacking.values());
        stackingComboBox.setToolTipText(stackingLabel.getToolTipText());
        stackingComboBox.addActionListener(e -> saveChanges());
        propertiesPanel.add(stackingComboBox, c);

        // Position
        c.gridy++;
        JPanel posPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        posPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        String posTooltip = "Offset from the constraint corner, in pixels. Positive X goes right, positive Y goes down.";
        xSpinner = createSpinner("X");
        xSpinner.setToolTipText(posTooltip);
        ySpinner = createSpinner("Y");
        ySpinner.setToolTipText(posTooltip);
        JLabel xLabel = new JLabel("X: ");
        xLabel.setToolTipText(posTooltip);
        JLabel yLabel = new JLabel("  Y: ");
        yLabel.setToolTipText(posTooltip);
        posPanel.add(xLabel);
        posPanel.add(xSpinner);
        posPanel.add(yLabel);
        posPanel.add(ySpinner);
        propertiesPanel.add(posPanel, c);

        // Size
        c.gridy++;
        JPanel sizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sizePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        String sizeTooltip = "Width and height of the anchor box, in pixels.";
        widthSpinner = createSpinner("W");
        widthSpinner.setToolTipText(sizeTooltip);
        heightSpinner = createSpinner("H");
        heightSpinner.setToolTipText(sizeTooltip);
        JLabel wLabel = new JLabel("W: ");
        wLabel.setToolTipText(sizeTooltip);
        JLabel hLabel = new JLabel("  H: ");
        hLabel.setToolTipText(sizeTooltip);
        sizePanel.add(wLabel);
        sizePanel.add(widthSpinner);
        sizePanel.add(hLabel);
        sizePanel.add(heightSpinner);
        propertiesPanel.add(sizePanel, c);

        // Delete Button
        c.gridy++;
        JButton deleteButton = new JButton("Delete Region Anchor");
        deleteButton.setToolTipText("Permanently delete this anchor. Overlays assigned to it will be released back to free-floating.");
        deleteButton.setBackground(Color.RED.darker());
        deleteButton.setForeground(Color.WHITE);
        deleteButton.addActionListener(e -> deleteSelectedRegion());
        propertiesPanel.add(deleteButton, c);

        add(propertiesPanel, BorderLayout.CENTER);
    }

    private JSpinner createSpinner(String title) {
        // Range widened to comfortably cover ultra-wide and multi-monitor canvas sizes.
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, -50000, 50000, 1));
        spinner.setPreferredSize(new Dimension(85, 25));
        spinner.addChangeListener(e -> saveChanges());
        return spinner;
    }

    public void updateList(List<AnchorRegion> regions) {
        isUpdating = true;
        AnchorRegion currentlySelected = selectedRegion;

        listModel.clear();
        for (AnchorRegion r : regions) {
            listModel.addElement(r);
        }

        // Reselect if possible
        if (currentlySelected != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).getId() == currentlySelected.getId()) {
                    regionList.setSelectedIndex(i);
                    selectedRegion = listModel.get(i);
                    break;
                }
            }
        }

        isUpdating = false;
        refreshPropertiesUI();
    }

    /**
     * Refresh property spinners/combos for the currently selected region without
     * touching JList selection. Safe to call frequently (e.g., during anchor drag).
     */
    public void refreshSelectedRegionProperties(AnchorRegion region) {
        if (region == null || selectedRegion == null || region.getId() != selectedRegion.getId()) {
            return;
        }
        refreshPropertiesUI();
    }

    public void setSelectedRegion(AnchorRegion region) {
        if (isUpdating)
            return;

        this.selectedRegion = region;

        // Sync selection in list if set externally
        if (region != null) {
            regionList.setSelectedValue(region, true);
        } else {
            regionList.clearSelection();
        }

        refreshPropertiesUI();
    }

    private void refreshPropertiesUI() {
        if (selectedRegion == null) {
            propertiesPanel.setVisible(false);
            return;
        }

        isUpdating = true; // Prevent loop
        nameField.setText(selectedRegion.getName());
        xSpinner.setValue(selectedRegion.getX());
        ySpinner.setValue(selectedRegion.getY());
        widthSpinner.setValue(selectedRegion.getWidth());
        heightSpinner.setValue(selectedRegion.getHeight());
        constraintPicker.setSelectedValue(
                selectedRegion.getConstraint() != null ? selectedRegion.getConstraint() : AnchorConstraint.TOP_LEFT);

        AnchorAlignment currentAlign = selectedRegion.getAlignment() != null
                ? selectedRegion.getAlignment() : AnchorAlignment.CENTER;
        // Legacy STRETCH values from older configs are displayed (and saved) as CENTER
        // now that the Stretch toggle has been removed from the UI.
        if (currentAlign == AnchorAlignment.STRETCH) {
            currentAlign = AnchorAlignment.CENTER;
        }
        alignmentPicker.setSelectedValue(currentAlign);

        stackingComboBox.setSelectedItem(
                selectedRegion.getStacking() != null ? selectedRegion.getStacking() : AnchorStacking.VERTICAL);
        isUpdating = false;

        propertiesPanel.setVisible(true);
        revalidate();
        repaint();
    }

    private void saveChanges() {
        if (selectedRegion == null || isUpdating)
            return;

        // Snapshot values off the EDT fields before handing the mutation to the client
        // thread, so Swing can't update the spinners mid-apply.
        final String newName = nameField.getText();
        final int newX = (Integer) xSpinner.getValue();
        final int newY = (Integer) ySpinner.getValue();
        final int newW = (Integer) widthSpinner.getValue();
        final int newH = (Integer) heightSpinner.getValue();
        AnchorConstraint newConstraint = constraintPicker.getSelectedValue();
        if (newConstraint == null) newConstraint = AnchorConstraint.TOP_LEFT;
        final AnchorConstraint finalConstraint = newConstraint;

        AnchorAlignment picked = alignmentPicker.getSelectedValue();
        final AnchorAlignment newAlignment = picked != null ? picked : AnchorAlignment.CENTER;
        final AnchorStacking newStacking = (AnchorStacking) stackingComboBox.getSelectedItem();

        plugin.updateRegion(selectedRegion, r -> {
            r.setName(newName);
            r.setX(newX);
            r.setY(newY);
            r.setWidth(newW);
            r.setHeight(newH);
            r.setConstraint(finalConstraint);
            r.setAlignment(newAlignment);
            r.setStacking(newStacking);
        });
        regionList.repaint(); // Repaint list for name changes
    }

    private void deleteSelectedRegion() {
        if (selectedRegion != null) {
            plugin.deleteRegion(selectedRegion);
            setSelectedRegion(null);
        }
    }

    /**
     * Drag-and-drop reordering for the region list. The list order is the layer stack
     * (index 0 = topmost on canvas), so a reorder here is routed to
     * {@link AnchorCustomizerPlugin#moveRegionToIndex} which mutates the authoritative
     * region list on the client thread, persists it, and refreshes this panel.
     */
    private class ListReorderHandler extends TransferHandler {
        private final DataFlavor flavor = new DataFlavor(Integer.class, "RegionListIndex");
        private int fromIndex = -1;

        @Override
        protected Transferable createTransferable(JComponent c) {
            fromIndex = regionList.getSelectedIndex();
            if (fromIndex < 0) {
                return null;
            }
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] { flavor };
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return flavor.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) {
                    return fromIndex;
                }
            };
        }

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(flavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support) || fromIndex < 0 || fromIndex >= listModel.size()) {
                return false;
            }
            JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
            int dropIndex = dl.getIndex();
            if (dropIndex < 0) {
                return false;
            }
            // INSERT drop mode gives the gap index; account for the removal of the
            // dragged element when it sits above the drop gap.
            int target = dropIndex > fromIndex ? dropIndex - 1 : dropIndex;
            if (target == fromIndex) {
                return true;
            }
            plugin.moveRegionToIndex(listModel.get(fromIndex), target);
            return true;
        }
    }

    private static class AnchorRegionListRenderer extends JPanel implements javax.swing.ListCellRenderer<AnchorRegion> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel lockLabel = new JLabel();

        public AnchorRegionListRenderer() {
            super(new BorderLayout());
            setOpaque(true);
            setBorder(new EmptyBorder(5, 10, 5, 6));
            nameLabel.setOpaque(false);
            lockLabel.setOpaque(false);
            lockLabel.setHorizontalAlignment(JLabel.CENTER);
            lockLabel.setPreferredSize(new Dimension(LOCK_ZONE_WIDTH - 6, 16));
            add(nameLabel, BorderLayout.CENTER);
            add(lockLabel, BorderLayout.EAST);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<? extends AnchorRegion> list, AnchorRegion value,
                int index, boolean isSelected, boolean cellHasFocus) {
            nameLabel.setText(value.getName() + " (ID: " + value.getId() + ")");

            Color fg = isSelected ? Color.BLACK : Color.WHITE;
            setBackground(isSelected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
            nameLabel.setForeground(fg);

            // Locked: solid icon in the row's foreground color. Unlocked: dimmed open
            // padlock so the toggle is discoverable without shouting.
            Color iconColor = value.isLocked()
                    ? fg
                    : (isSelected ? new Color(0, 0, 0, 110) : ColorScheme.MEDIUM_GRAY_COLOR);
            lockLabel.setIcon(new LockToggleIcon(value.isLocked(), iconColor));
            return this;
        }
    }

    /**
     * Painted padlock for the list rows: closed shackle when locked, shackle swung
     * open to the right when unlocked.
     */
    private static class LockToggleIcon implements javax.swing.Icon {
        private final boolean closed;
        private final Color color;

        LockToggleIcon(boolean closed, Color color) {
            this.closed = closed;
            this.color = color;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new java.awt.BasicStroke(1.6f));

            final int bodyW = 9;
            final int bodyH = 7;
            final int shackleW = 6;
            final int shackleH = 8;
            int cx = x + getIconWidth() / 2;
            int bodyY = y + 6;

            if (closed) {
                // Shackle centered over the body.
                g2.drawArc(cx - shackleW / 2, y + 1, shackleW, shackleH, 0, 180);
            } else {
                // Shackle swung open: anchored near the body's right edge, arcing away.
                g2.drawArc(cx - shackleW / 2 + 5, y, shackleW, shackleH, 0, 180);
            }
            g2.fillRect(cx - bodyW / 2, bodyY, bodyW, bodyH);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }
}
