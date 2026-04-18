package com.chatoverlaystreaming.overlay;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.*;
import java.awt.*;

public class ObsAwareDialog {

    /**
     * Equivalente a JOptionPane.showMessageDialog pero invisible para OBS.
     */
    public static void showMessage(Component parent, String message,
                                    String title, int messageType) {
        JOptionPane pane = new JOptionPane(message, messageType);
        JDialog dialog = pane.createDialog(parent, title);
        excludeFromCapture(dialog);
        dialog.setVisible(true);
    }

    /**
     * Equivalente a JOptionPane.showConfirmDialog pero invisible para OBS.
     * Devuelve JOptionPane.YES_OPTION / NO_OPTION / CANCEL_OPTION.
     */
    public static int showConfirm(Component parent, String message,
                                   String title, int optionType) {
        JOptionPane pane = new JOptionPane(message,
                JOptionPane.QUESTION_MESSAGE, optionType);
        JDialog dialog = pane.createDialog(parent, title);
        excludeFromCapture(dialog);
        dialog.setVisible(true);

        Object value = pane.getValue();
        if (value == null) return JOptionPane.CANCEL_OPTION;
        if (value instanceof Integer) return (Integer) value;
        return JOptionPane.CANCEL_OPTION;
    }

    private static void excludeFromCapture(JDialog dialog) {
        // Necesita ser visible para obtener el handle nativo
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                try {
                    com.sun.jna.Pointer pointer =
                            Native.getComponentPointer(dialog);
                    if (pointer == null) return;
                    WinDef.HWND hwnd = new WinDef.HWND(pointer);
                    WindowClickThrough.User32Extra.INSTANCE
                            .SetWindowDisplayAffinity(hwnd, 0x00000011);
                } catch (Exception ex) {
                    System.err.println("[ObsAwareDialog] Error: "
                            + ex.getMessage());
                }
            }
        });
    }
}