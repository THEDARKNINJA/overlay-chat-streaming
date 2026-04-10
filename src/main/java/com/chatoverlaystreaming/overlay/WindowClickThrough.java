package com.chatoverlaystreaming.overlay;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;

public class WindowClickThrough {

    private static final int GWL_EXSTYLE            = -20;
    private static final int WS_EX_LAYERED          = 0x00080000;
    private static final int WS_EX_TRANSPARENT      = 0x00000020;
    private static final int WDA_NONE               = 0x00000000;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;
    private static final int WDA_MONITOR            = 0x00000001;

    interface User32Extra extends StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class);
        boolean SetWindowDisplayAffinity(HWND hWnd, int dwAffinity);
        boolean SetLayeredWindowAttributes(HWND hWnd, int crKey, byte bAlpha, int dwFlags);
    }

    private final HWND hwnd;
    private boolean clickThrough = false;

    public WindowClickThrough(java.awt.Window window) {
        Pointer pointer = Native.getComponentPointer(window);
        if (pointer == null) {
            throw new IllegalStateException(
                "No se pudo obtener el handle nativo. " +
                "¿Está la ventana visible?");
        }
        this.hwnd = new HWND(pointer);
    }

    public void setClickThrough(boolean enabled) {
        this.clickThrough = enabled;
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        if (enabled) {
            style |= WS_EX_LAYERED | WS_EX_TRANSPARENT;
        } else {
            style &= ~WS_EX_TRANSPARENT;
        }
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style);
    }

    /*
    public void setExcludeFromCapture(boolean exclude) {
        // Asegurarse de que WS_EX_LAYERED está activo, es requisito
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        style |= WS_EX_LAYERED;
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style);

        int affinity = exclude ? WDA_MONITOR : WDA_NONE;
        boolean result = User32Extra.INSTANCE.SetWindowDisplayAffinity(hwnd, affinity);
        System.err.println("[Overlay] Con WDA_MONITOR: " + result);
    }
    */

    public void setExcludeFromCapture(boolean exclude) {
        // Activar WS_EX_LAYERED
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
        style |= WS_EX_LAYERED;
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style);

        // Establecer color key: magenta = transparente
        // SetLayeredWindowAttributes(hwnd, colorKey, alpha, flags)
        // flags: 0x1 = usar color key, 0x2 = usar alpha, 0x3 = ambos
        User32Extra.INSTANCE.SetLayeredWindowAttributes(hwnd, 0x00FF00FF, (byte)100, 0x3);

        int affinity = exclude ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE;
        boolean result = User32Extra.INSTANCE.SetWindowDisplayAffinity(hwnd, affinity);
        //System.out.println("[Overlay] SetWindowDisplayAffinity resultado: " + result);
    }

    public boolean isClickThrough() {
        return clickThrough;
    }
}