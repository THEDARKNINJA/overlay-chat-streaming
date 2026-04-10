package com.chatoverlaystreaming.overlay;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

public class WindowClickThrough {

    private static final int GWL_EXSTYLE       = -20;
    private static final int WS_EX_LAYERED     = 0x00080000;
    private static final int WS_EX_TRANSPARENT = 0x00000020;

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

        // Usar la clase User32 de jna-platform directamente
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);

        if (enabled) {
            style |= WS_EX_LAYERED | WS_EX_TRANSPARENT;
        } else {
            style &= ~WS_EX_TRANSPARENT;
        }

        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, style);
    }

    public boolean isClickThrough() {
        return clickThrough;
    }
}