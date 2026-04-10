package com.chatoverlaystreaming.emotes;

import javax.swing.*;
import java.awt.*;

public class CenteredIcon implements Icon {

    private final ImageIcon icon;
    private final int lineHeight;

    public CenteredIcon(ImageIcon icon, int lineHeight) {
        this.icon       = icon;
        this.lineHeight = lineHeight;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        // Calcular offset para centrar verticalmente
        int offsetY = (lineHeight - icon.getIconHeight()) / 2;
        icon.paintIcon(c, g, x, y + offsetY);
    }

    @Override
    public int getIconWidth() {
        return icon.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        // Devolvemos la altura de la línea para que el layout
        // reserve el espacio correcto
        return lineHeight;
    }
}