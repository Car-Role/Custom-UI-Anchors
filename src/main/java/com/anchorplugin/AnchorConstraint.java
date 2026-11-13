/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

public enum AnchorConstraint {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT;

    /**
     * The pin nearest to a box on a canvas. Each axis is decided on its own: the box's
     * left/top edge distance to the canvas start, its right/bottom edge distance to the
     * canvas end, and its centre distance to the canvas centre — the smallest wins. Ties
     * go to left/top, then right/bottom, so the result is stable.
     */
    public static AnchorConstraint nearest(java.awt.Rectangle box, int canvasW, int canvasH) {
        int h = nearestAxis(box.x, box.width, canvasW);
        int v = nearestAxis(box.y, box.height, canvasH);
        AnchorConstraint[][] grid = {
                { TOP_LEFT, TOP_CENTER, TOP_RIGHT },
                { CENTER_LEFT, CENTER, CENTER_RIGHT },
                { BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT },
        };
        return grid[v][h];
    }

    /** 0 = left, 1 = centre, 2 = right. */
    private int column() {
        switch (this) {
            case TOP_LEFT:
            case CENTER_LEFT:
            case BOTTOM_LEFT:
                return 0;
            case TOP_RIGHT:
            case CENTER_RIGHT:
            case BOTTOM_RIGHT:
                return 2;
            default:
                return 1;
        }
    }

    /** 0 = top, 1 = centre, 2 = bottom. */
    private int row() {
        switch (this) {
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                return 0;
            case BOTTOM_LEFT:
            case BOTTOM_CENTER:
            case BOTTOM_RIGHT:
                return 2;
            default:
                return 1;
        }
    }

    /**
     * The point of the canvas this pin holds on to (corner, edge midpoint or centre),
     * moved {@code inset} pixels in from the edges so a marker drawn there stays visible.
     */
    public java.awt.Point canvasPoint(int canvasW, int canvasH, int inset) {
        return new java.awt.Point(along(column(), inset, canvasW - 1 - inset),
                along(row(), inset, canvasH - 1 - inset));
    }

    /** The matching point of a box: the same corner, edge midpoint or centre. */
    public java.awt.Point boxPoint(java.awt.Rectangle box) {
        return new java.awt.Point(along(column(), box.x, box.x + box.width),
                along(row(), box.y, box.y + box.height));
    }

    private static int along(int index, int start, int end) {
        return index == 0 ? start : index == 2 ? end : (start + end) / 2;
    }

    /** 0 = start (left/top), 1 = centre, 2 = end (right/bottom). */
    private static int nearestAxis(int pos, int size, int canvas) {
        // Doubled values keep the centre distance in whole numbers.
        int start = 2 * Math.abs(pos);
        int end = 2 * Math.abs(canvas - (pos + size));
        int mid = Math.abs(2 * pos + size - canvas);
        if (start <= mid && start <= end) {
            return 0;
        }
        return end <= mid ? 2 : 1;
    }
}
