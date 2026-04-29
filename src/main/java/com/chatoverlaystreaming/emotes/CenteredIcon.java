package com.chatoverlaystreaming.emotes;

import javax.swing.*;
import java.awt.*;

/**
 * Wrapper de Icon que centra verticalmente una imagen dentro de una línea de texto.
 *
 * Problema que resuelve: cuando se insertan iconos en un JTextPane con
 * StyleConstants.setIcon(), el layout reserva espacio según getIconHeight().
 * Si el icono es más pequeño que la línea de texto, queda alineado al borde
 * superior en lugar de centrarse.
 *
 * Solución: getIconHeight() devuelve la altura de la línea completa para que
 * el layout reserve el espacio correcto, y paintIcon() aplica un offset
 * vertical para pintar la imagen centrada dentro de ese espacio.
 */
public class CenteredIcon implements Icon {

    private final ImageIcon icon;
    private final int       lineHeight;

    /**
     * @param icon       Icono a centrar verticalmente.
     * @param lineHeight Altura de la línea de texto en la que se inserta el icono.
     */
    public CenteredIcon(ImageIcon icon, int lineHeight) {
        this.icon       = icon;
        this.lineHeight = lineHeight;
    }

    /**
     * Pinta el icono desplazado verticalmente para centrarlo en la línea.
     * El offset es la mitad de la diferencia entre la altura de la línea
     * y la altura real del icono.
     */
    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        int offsetY = (lineHeight - icon.getIconHeight()) / 2;
        icon.paintIcon(c, g, x, y + offsetY);
    }

    /** Ancho real del icono (sin modificar). */
    @Override
    public int getIconWidth() {
        return icon.getIconWidth();
    }

    /**
     * Devuelve la altura de la línea en lugar de la altura real del icono,
     * para que el layout del JTextPane reserve el espacio correcto
     * y no comprima la línea al tamaño del icono.
     */
    @Override
    public int getIconHeight() {
        return lineHeight;
    }
}