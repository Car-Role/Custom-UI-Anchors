package com.customuianchors;

import net.runelite.client.ui.PluginPanel;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

public class CustomUIAnchorsPanel extends PluginPanel
{
	private final CustomUIAnchorsConfig config;
	private final JPanel anchorListPanel;

	@Inject
	public CustomUIAnchorsPanel(CustomUIAnchorsConfig config)
	{
		this.config = config;

		setLayout(new BorderLayout());

		JButton newAnchorButton = new JButton("Add New Anchor");
		newAnchorButton.addActionListener(e -> showNewAnchorDialog());

		anchorListPanel = new JPanel();
		anchorListPanel.setLayout(new BoxLayout(anchorListPanel, BoxLayout.Y_AXIS));

		JScrollPane scrollPane = new JScrollPane(anchorListPanel);

		add(newAnchorButton, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);

		updateAnchorList();
	}

	private void updateAnchorList()
	{
		anchorListPanel.removeAll();
		for (UIAnchor anchor : config.anchors())
		{
			anchorListPanel.add(createAnchorPanel(anchor));
		}
		anchorListPanel.revalidate();
		anchorListPanel.repaint();
	}

	private JPanel createAnchorPanel(UIAnchor anchor)
	{
		JPanel panel = new JPanel(new GridLayout(3, 2));
		JTextField nameField = new JTextField(anchor.getName());
		JSpinner xSpinner = new JSpinner(new SpinnerNumberModel(anchor.getX(), 0, 10000, 1));
		JSpinner ySpinner = new JSpinner(new SpinnerNumberModel(anchor.getY(), 0, 10000, 1));
		JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(anchor.getWidth(), 1, 10000, 1));
		JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(anchor.getHeight(), 1, 10000, 1));
		JButton deleteButton = new JButton("Delete");

		panel.add(new JLabel("Name:"));
		panel.add(nameField);
		panel.add(new JLabel("X:"));
		panel.add(xSpinner);
		panel.add(new JLabel("Y:"));
		panel.add(ySpinner);
		panel.add(new JLabel("Width:"));
		panel.add(widthSpinner);
		panel.add(new JLabel("Height:"));
		panel.add(heightSpinner);
		panel.add(deleteButton);

		nameField.addActionListener(e -> {
			anchor.setName(nameField.getText());
			updateConfig();
		});

		xSpinner.addChangeListener(e -> {
			anchor.setX((int) xSpinner.getValue());
			updateConfig();
		});

		ySpinner.addChangeListener(e -> {
			anchor.setY((int) ySpinner.getValue());
			updateConfig();
		});

		widthSpinner.addChangeListener(e -> {
			anchor.setWidth((int) widthSpinner.getValue());
			updateConfig();
		});

		heightSpinner.addChangeListener(e -> {
			anchor.setHeight((int) heightSpinner.getValue());
			updateConfig();
		});

		deleteButton.addActionListener(e -> {
			List<UIAnchor> anchors = new ArrayList<>(config.anchors());
			anchors.remove(anchor);
			config.setAnchors(anchors);
			updateAnchorList();
		});

		return panel;
	}

	private void showNewAnchorDialog()
	{
		JTextField nameField = new JTextField();
		JSpinner xSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
		JSpinner ySpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
		JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 10000, 1));
		JSpinner heightSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 10000, 1));

		JPanel panel = new JPanel(new GridLayout(5, 2));
		panel.add(new JLabel("Name:"));
		panel.add(nameField);
		panel.add(new JLabel("X:"));
		panel.add(xSpinner);
		panel.add(new JLabel("Y:"));
		panel.add(ySpinner);
		panel.add(new JLabel("Width:"));
		panel.add(widthSpinner);
		panel.add(new JLabel("Height:"));
		panel.add(heightSpinner);

		int result = JOptionPane.showConfirmDialog(null, panel, "Add New Anchor",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result == JOptionPane.OK_OPTION)
		{
			String name = nameField.getText();
			int x = (int) xSpinner.getValue();
			int y = (int) ySpinner.getValue();
			int width = (int) widthSpinner.getValue();
			int height = (int) heightSpinner.getValue();

			UIAnchor newAnchor = new UIAnchor(name, x, y, width, height);
			List<UIAnchor> anchors = new ArrayList<>(config.anchors());
			anchors.add(newAnchor);
			config.setAnchors(anchors);
			updateAnchorList();
		}
	}

	private void updateConfig()
	{
		List<UIAnchor> anchors = new ArrayList<>(config.anchors());
		config.setAnchors(anchors);
	}
}