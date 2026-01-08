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
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
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
    private final JComboBox<AnchorConstraint> constraintComboBox;
    private final JComboBox<AnchorAlignment> alignmentComboBox;
    private final JComboBox<AnchorStacking> stackingComboBox;

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

        regionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !isUpdating) {
                setSelectedRegion(regionList.getSelectedValue());
            }
        });

        JScrollPane scrollPane = new JScrollPane(regionList);
        scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listContainer.add(scrollPane, BorderLayout.CENTER);

        JButton addButton = new JButton("Add New Region Anchor");
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
        propertiesPanel.add(new JLabel("Name:"), c);
        c.gridy++;
        nameField = new JTextField();
        nameField.addActionListener(e -> saveChanges());
        nameField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                saveChanges();
            }
        });
        propertiesPanel.add(nameField, c);

        // Constraints
        c.gridy++;
        propertiesPanel.add(new JLabel("Constraint:"), c);
        c.gridy++;
        constraintComboBox = new JComboBox<>(AnchorConstraint.values());
        constraintComboBox.setToolTipText("Which corner of the game window this box attaches to");
        constraintComboBox.addActionListener(e -> saveChanges());
        propertiesPanel.add(constraintComboBox, c);

        // Alignment
        c.gridy++;
        propertiesPanel.add(new JLabel("Alignment:"), c);
        c.gridy++;
        alignmentComboBox = new JComboBox<>(AnchorAlignment.values());
        alignmentComboBox.setToolTipText("Position of overlays inside this box");
        alignmentComboBox.addActionListener(e -> saveChanges());
        propertiesPanel.add(alignmentComboBox, c);

        // Stacking
        c.gridy++;
        propertiesPanel.add(new JLabel("Stacking:"), c);
        c.gridy++;
        stackingComboBox = new JComboBox<>(AnchorStacking.values());
        stackingComboBox.setToolTipText("How multiple overlays are arranged (e.g. Vertical/Horizontal/Flow)");
        stackingComboBox.addActionListener(e -> saveChanges());
        propertiesPanel.add(stackingComboBox, c);

        // Position
        c.gridy++;
        JPanel posPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        posPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        xSpinner = createSpinner("X");
        xSpinner.setToolTipText("Horizontal offset from constraint anchor");
        ySpinner = createSpinner("Y");
        ySpinner.setToolTipText("Vertical offset from constraint anchor");
        posPanel.add(new JLabel("X: "));
        posPanel.add(xSpinner);
        posPanel.add(new JLabel("  Y: "));
        posPanel.add(ySpinner);
        propertiesPanel.add(posPanel, c);

        // Size
        c.gridy++;
        JPanel sizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sizePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        widthSpinner = createSpinner("W");
        widthSpinner.setToolTipText("Width of the region");
        heightSpinner = createSpinner("H");
        heightSpinner.setToolTipText("Height of the region");
        sizePanel.add(new JLabel("W: "));
        sizePanel.add(widthSpinner);
        sizePanel.add(new JLabel("  H: "));
        sizePanel.add(heightSpinner);
        propertiesPanel.add(sizePanel, c);

        // Delete Button
        c.gridy++;
        JButton deleteButton = new JButton("Delete Region Anchor");
        deleteButton.setBackground(Color.RED.darker());
        deleteButton.setForeground(Color.WHITE);
        deleteButton.addActionListener(e -> deleteSelectedRegion());
        propertiesPanel.add(deleteButton, c);

        add(propertiesPanel, BorderLayout.CENTER);
    }

    private JSpinner createSpinner(String title) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, -10000, 10000, 1));
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
        constraintComboBox.setSelectedItem(selectedRegion.getConstraint());
        alignmentComboBox.setSelectedItem(
                selectedRegion.getAlignment() != null ? selectedRegion.getAlignment() : AnchorAlignment.CENTER);
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

        selectedRegion.setName(nameField.getText());
        selectedRegion.setX((Integer) xSpinner.getValue());
        selectedRegion.setY((Integer) ySpinner.getValue());
        selectedRegion.setWidth((Integer) widthSpinner.getValue());
        selectedRegion.setHeight((Integer) heightSpinner.getValue());
        selectedRegion.setConstraint((AnchorConstraint) constraintComboBox.getSelectedItem());
        selectedRegion.setAlignment((AnchorAlignment) alignmentComboBox.getSelectedItem());
        selectedRegion.setStacking((AnchorStacking) stackingComboBox.getSelectedItem());

        plugin.saveRegions(); // Persist and redraw
        regionList.repaint(); // Repaint list for name changes
    }

    private void deleteSelectedRegion() {
        if (selectedRegion != null) {
            plugin.deleteRegion(selectedRegion);
            setSelectedRegion(null);
        }
    }

    private static class AnchorRegionListRenderer extends JLabel implements javax.swing.ListCellRenderer<AnchorRegion> {
        public AnchorRegionListRenderer() {
            setOpaque(true);
            setBorder(new EmptyBorder(5, 10, 5, 10));
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<? extends AnchorRegion> list, AnchorRegion value,
                int index, boolean isSelected, boolean cellHasFocus) {
            setText(value.getName() + " (ID: " + value.getId() + ")");

            if (isSelected) {
                setBackground(ColorScheme.BRAND_ORANGE);
                setForeground(Color.BLACK);
            } else {
                setBackground(ColorScheme.DARKER_GRAY_COLOR);
                setForeground(Color.WHITE);
            }
            return this;
        }
    }
}
