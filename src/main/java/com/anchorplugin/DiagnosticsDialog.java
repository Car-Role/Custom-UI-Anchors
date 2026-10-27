/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;

/**
 * Preview of the diagnostics report, so users can see exactly what they're sharing
 * before copying it or opening the GitHub form.
 */
final class DiagnosticsDialog {
    private DiagnosticsDialog() {
    }

    static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    static void show(Component parent, String report, Consumer<String> openIssueForm) {
        JDialog dialog = build(SwingUtilities.getWindowAncestor(parent), report, openIssueForm);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    static JDialog build(Window owner, String report, Consumer<String> openIssueForm) {
        JDialog dialog = new JDialog(owner, "Custom UI Anchors diagnostics");
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel intro = new JLabel("<html>Paste this into your GitHub issue. Your account name and folders are left out,"
                + " and nothing is sent anywhere until you submit it.</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JTextArea text = new JTextArea(report);
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(680, 420));

        JButton copyButton = new JButton("Copy");
        String copied = copyButton.getFont().canDisplay('\u2713') ? "Copied \u2713" : "Copied!";
        // Size for the longer label so the confirmation isn't cut off.
        copyButton.setText(copied);
        copyButton.setPreferredSize(copyButton.getPreferredSize());
        copyButton.setText("Copy");
        Timer revert = new Timer(2000, e -> copyButton.setText("Copy"));
        revert.setRepeats(false);
        copyButton.addActionListener(e -> {
            copy(report);
            copyButton.setText(copied);
            revert.restart();
        });

        JButton issueButton = new JButton("Open GitHub issue");
        issueButton.setToolTipText("Copies the report and opens the bug form. If its Diagnostics box is empty, paste there.");
        issueButton.addActionListener(e -> {
            copy(report);
            openIssueForm.accept(report);
        });

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        buttons.add(copyButton);
        buttons.add(issueButton);
        buttons.add(closeButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(intro, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        return dialog;
    }
}
