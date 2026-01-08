/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AnchorRegion {
    private int id;
    private String name;
    private int x;
    private int y;
    private int width;
    private int height;
    private AnchorConstraint constraint;
    private AnchorAlignment alignment = AnchorAlignment.CENTER; // Default to Center
    private AnchorStacking stacking = AnchorStacking.VERTICAL; // Default to Vertical

    // Helper to get bounds as AWT Rectangle
    public java.awt.Rectangle getBounds() {
        return new java.awt.Rectangle(x, y, width, height);
    }
}
