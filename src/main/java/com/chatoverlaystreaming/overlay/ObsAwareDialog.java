package com.chatoverlaystreaming.overlay;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Reemplazos de JOptionPane que excluyen el diálogo de la captura de OBS.
 *
 * Los diálogos estándar de JOptionPane crean un JDialog propio que no hereda
 * la exclusión de captura de la ventana padre. Esta clase los envuelve aplicando
 * SetWindowDisplayAffinity con WDA_EXCLUDEFROMCAPTURE al abrirse,
 * de modo que no aparezcan en OBS aunque la ventana principal esté visible.
 *
 * Uso:
 * <pre>
 *   // En lugar de:
 *   JOptionPane.showMessageDialog(parent, message, title, type);
 *
 *   // Usar:
 *   ObsAwareDialog.showMessage(parent, message, title, type);
 * </pre>
 */
public class ObsAwareDialog {

    /** Constructor privado: clase de utilidad, no instanciable. */
    private ObsAwareDialog() {}

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Muestra un diálogo de mensaje invisible para OBS.
     * Equivalente a {@link JOptionPane#showMessageDialog}.
     *
     * @param parent      Componente padre para posicionamiento, puede ser null.
     * @param message     Mensaje a mostrar.
     * @param title       Título del diálogo.
     * @param messageType Tipo de icono: {@link JOptionPane#INFORMATION_MESSAGE},
     *                    {@link JOptionPane#WARNING_MESSAGE}, etc.
     */
    public static void showMessage(Component parent, String message,
                                    String title, int messageType) {
        JOptionPane pane   = new JOptionPane(message, messageType);
        JDialog     dialog = pane.createDialog(parent, title);
        excludeFromCapture(dialog);
        dialog.setVisible(true);
    }

    /**
     * Muestra un diálogo de confirmación invisible para OBS.
     * Equivalente a {@link JOptionPane#showConfirmDialog}.
     *
     * @param parent     Componente padre para posicionamiento, puede ser null.
     * @param message    Mensaje a mostrar.
     * @param title      Título del diálogo.
     * @param optionType Botones a mostrar: {@link JOptionPane#YES_NO_OPTION}, etc.
     * @return La opción elegida: {@link JOptionPane#YES_OPTION},
     *         {@link JOptionPane#NO_OPTION} o {@link JOptionPane#CANCEL_OPTION}.
     */
    public static int showConfirm(Component parent, String message,
                                   String title, int optionType) {
        JOptionPane pane   = new JOptionPane(message,
                JOptionPane.QUESTION_MESSAGE, optionType);
        JDialog     dialog = pane.createDialog(parent, title);
        excludeFromCapture(dialog);
        dialog.setVisible(true);

        Object value = pane.getValue();
        if (value instanceof Integer i) return i;
        return JOptionPane.CANCEL_OPTION;
    }

    // ── Implementación ────────────────────────────────────────────────────────

    /**
     * Registra un WindowListener que aplica SetWindowDisplayAffinity
     * con WDA_EXCLUDEFROMCAPTURE cuando el diálogo se abre.
     *
     * Debe hacerse en windowOpened (no en el constructor del diálogo) porque
     * el handle nativo de Windows solo existe cuando la ventana es visible.
     *
     * @param dialog Diálogo al que aplicar la exclusión de captura.
     */
    private static void excludeFromCapture(JDialog dialog) {
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                try {
                    com.sun.jna.Pointer pointer = Native.getComponentPointer(dialog);
                    if (pointer == null) return;
                    WinDef.HWND hwnd = new WinDef.HWND(pointer);
                    WindowClickThrough.User32Extra.INSTANCE
                            .SetWindowDisplayAffinity(hwnd, 0x00000011);
                } catch (Exception ex) {
                    System.err.println("[ObsAwareDialog] Error excluyendo de captura: "
                            + ex.getMessage());
                }
            }
        });
    }
}