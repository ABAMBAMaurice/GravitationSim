package com.example.gravity;

import java.awt.Color;

final class Palette {

    private Palette() {
    }

    static final Color SPACE_TOP = new Color(6, 8, 20);
    static final Color SPACE_BOTTOM = new Color(16, 14, 32);

    // Flat elevation scale, each tier a touch lighter than the one below:
    // PANEL_BG (app background) < CARD (grouped section) < SURFACE (control,
    // e.g. a button or field at rest) < CARD_HOVER (that control, hovered/active).
    static final Color PANEL_BG = new Color(18, 20, 34);
    static final Color CARD = new Color(28, 31, 48);
    static final Color SURFACE = new Color(36, 40, 60);
    static final Color CARD_HOVER = new Color(48, 53, 80);

    static final Color ACCENT = new Color(122, 162, 255);
    static final Color TEXT = new Color(235, 236, 242);
    static final Color TEXT_MUTED = new Color(150, 154, 172);
    static final Color DIVIDER = new Color(255, 255, 255, 22);

    static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
