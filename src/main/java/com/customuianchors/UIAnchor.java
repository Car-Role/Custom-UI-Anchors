package com.customuianchors;

import lombok.Data;
import java.io.Serializable;

@Data
public class UIAnchor implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private int x;
    private int y;
    private int width;
    private int height;

    public UIAnchor(String name, int x, int y, int width, int height) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    // Getters
    public String getName() { return name; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    // Setters
    public void setName(String name) { this.name = name; }
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void setWidth(int width) { this.width = width; }
    public void setHeight(int height) { this.height = height; }
}