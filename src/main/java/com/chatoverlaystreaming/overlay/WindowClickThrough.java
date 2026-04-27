package com.chatoverlaystreaming.overlay;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Gestiona las propiedades nativas de Windows de la ventana del overlay:
 * click-through y exclusión/inclusión de captura de OBS.
 *
 * Click-through (WS_EX_TRANSPARENT):
 *   Cuando está activo, los clics del ratón pasan a través de la ventana
 *   sin interactuar con ella. Se activa automáticamente cuando la ventana
 *   pierde el foco y se desactiva al ganar el foco.
 *
 * Exclusión de captura (SetWindowDisplayAffinity):
 *   Con WDA_EXCLUDEFROMCAPTURE la ventana no aparece en capturas de pantalla
 *   ni en OBS. Se usa para ocultar el panel de chat de OBS por defecto.
 *
 * Color key (SetLayeredWindowAttributes):
 *   El color magenta (0x00FF00FF) se usa como color de transparencia en la
 *   ventana principal del overlay. Los píxeles de ese color se renderizan
 *   como completamente transparentes para el usuario.
 */
public class WindowClickThrough {

    // ── Constantes de la API de Windows ──────────────────────────────────────

    /** Índice para obtener/establecer los estilos extendidos de una ventana. */
    private static final int GWL_EXSTYLE = -20;

    /** Estilo extendido requerido para transparencia y color key. */
    private static final int WS_EX_LAYERED     = 0x00080000;

    /** Estilo extendido que hace la ventana transparente a los eventos de ratón. */
    private static final int WS_EX_TRANSPARENT = 0x00000020;

    /** Afinidad de captura: ventana visible para OBS y capturas. */
    private static final int WDA_NONE               = 0x00000000;

    /** Afinidad de captura: ventana excluida de OBS y capturas de pantalla. */
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    /**
     * Extensión de la interfaz User32 de JNA para las funciones de Windows
     * que no están incluidas en la versión estándar de JNA.
     * Se declara como package-visible para que otras clases del paquete
     * (VideoOverlay, VlcjVideoOverlay, etc.) puedan usar SetWindowDisplayAffinity
     * y SetLayeredWindowAttributes sin duplicar la definición.
     */
    interface User32Extra extends StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class);

        /**
         * Establece la afinidad de captura de una ventana.
         * Con WDA_EXCLUDEFROMCAPTURE, la ventana no aparece en OBS ni capturas.
         */
        boolean SetWindowDisplayAffinity(HWND hWnd, int dwAffinity);

        /**
         * Establece atributos de transparencia de una ventana layered.
         *
         * @param hWnd    Handle de la ventana.
         * @param crKey   Color key en formato COLORREF (0x00BBGGRR).
         * @param bAlpha  Alpha global (0=transparente, 255=opaco).
         * @param dwFlags 0x1=usar color key, 0x2=usar alpha, 0x3=ambos.
         */
        boolean SetLayeredWindowAttributes(HWND hWnd, int crKey, byte bAlpha, int dwFlags);
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    private final HWND    hwnd;
    private       boolean clickThrough = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Obtiene el handle nativo de la ventana para poder aplicar las APIs de Windows.
     *
     * @param window Ventana AWT/Swing ya visible (debe estar visible para tener handle).
     * @throws IllegalStateException Si no se puede obtener el handle nativo.
     */
    public WindowClickThrough(java.awt.Window window) {
        Pointer pointer = Native.getComponentPointer(window);
        if (pointer == null) {
            throw new IllegalStateException(
                    "No se pudo obtener el handle nativo de la ventana. " +
                    "Asegúrate de que la ventana es visible antes de llamar a este constructor.");
        }
        this.hwnd = new HWND(pointer);
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Activa o desactiva el click-through de la ventana.
     *
     * Cuando está activo (WS_EX_TRANSPARENT), los clics pasan a través de la
     * ventana y llegan a las ventanas o el escritorio que hay debajo.
     * WS_EX_LAYERED es requisito de WS_EX_TRANSPARENT y se activa junto con él.
     * Al desactivar, se elimina WS_EX_TRANSPARENT pero se mantiene WS_EX_LAYERED
     * porque lo necesita el color key de transparencia.
     *
     * @param enabled true para activar click-through, false para desactivarlo.
     */
    public void setClickThrough(boolean enabled) {
        this.clickThrough = enabled;
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        if (enabled) {
            style |= WS_EX_LAYERED | WS_EX_TRANSPARENT;
        } else {
            style &= ~WS_EX_TRANSPARENT; // quitar transparent, mantener layered
        }
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style);
    }

    /**
     * Configura la exclusión de captura y el color key de la ventana principal.
     *
     * Activa WS_EX_LAYERED y aplica:
     *   - Color key magenta (0x00FF00FF): los píxeles de ese color son transparentes.
     *   - Alpha global según el parámetro {@code alfa}.
     *   - SetWindowDisplayAffinity para excluir o incluir en capturas de OBS.
     *
     * @param exclude true para excluir la ventana de OBS, false para incluirla.
     * @param alfa    Alpha global de la ventana (0-255). Afecta a todo el contenido.
     */
    public void setExcludeFromCapture(boolean exclude, int alfa) {
        // WS_EX_LAYERED es requisito para SetLayeredWindowAttributes
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        style |= WS_EX_LAYERED;
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style);

        // Color key magenta + alpha global
        // flags 0x3 = LWA_COLORKEY | LWA_ALPHA: aplicar ambos
        User32Extra.INSTANCE.SetLayeredWindowAttributes(
                hwnd, 0x00FF00FF, (byte) alfa, 0x3);

        // Excluir o incluir en capturas de OBS
        int affinity = exclude ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE;
        User32Extra.INSTANCE.SetWindowDisplayAffinity(hwnd, affinity);
    }

    /** @return true si el click-through está activo actualmente. */
    public boolean isClickThrough() {
        return clickThrough;
    }
}