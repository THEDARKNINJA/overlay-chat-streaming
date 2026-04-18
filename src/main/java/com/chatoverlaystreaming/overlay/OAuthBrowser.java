package com.chatoverlaystreaming.overlay;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class OAuthBrowser extends JDialog {

    private static CefApp cefApp;
    private CefBrowser browser;
    private CefClient  client;

    private final Consumer<String> onCode;    // callback con el código OAuth
    private final Consumer<String> onError;   // callback con el error
    private final String           state;     // para verificar CSRF
    private final int              port;

    public OAuthBrowser(Window owner, String url, String state, int port,
                        Consumer<String> onCode, Consumer<String> onError) {
        super(owner, "Autorización Twitch", ModalityType.APPLICATION_MODAL);
        this.onCode  = onCode;
        this.onError = onError;
        this.state   = state;
        this.port    = port;

        setSize(700, 550);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        excludeFromCapture();
        initBrowser(url);
    }

    private void initBrowser(String url) {
        try {
            if (cefApp == null) {
                CefAppBuilder builder = new CefAppBuilder();
                builder.getCefSettings().windowless_rendering_enabled = false;
                cefApp = builder.build();
            }

            client = cefApp.createClient();

            // Interceptar la redirección a localhost
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadStart(CefBrowser browser, CefFrame frame,
                                        org.cef.network.CefRequest.TransitionType transitionType) {
                    String loadUrl = browser.getURL();
                    if (loadUrl != null && loadUrl.startsWith(
                            "http://localhost:" + port)) {
                        // Parsear parámetros
                        String query = loadUrl.contains("?")
                                ? loadUrl.substring(loadUrl.indexOf('?') + 1)
                                : "";

                        String code        = extractParam(query, "code");
                        String errorParam  = extractParam(query, "error");
                        String receivedState = extractParam(query, "state");

                        SwingUtilities.invokeLater(() -> {
                            if (code != null && state.equals(receivedState)) {
                                onCode.accept(code);
                            } else if (errorParam != null) {
                                String desc = extractParam(query, "error_description");
                                onError.accept(desc != null ? desc : errorParam);
                            } else if (code != null) {
                                onError.accept("State no coincide (posible CSRF).");
                            }
                            dispose();
                        });
                    }
                }

                @Override
                public void onLoadError(CefBrowser browser, CefFrame frame,
                                        org.cef.handler.CefLoadHandler.ErrorCode errorCode,
                                        String errorText, String failedUrl) {
                    // Ignorar errores de la redirección a localhost
                    // (el servidor HTTP puede no estar levantado en ese momento)
                    if (failedUrl != null && failedUrl.startsWith(
                            "http://localhost:" + port)) return;

                    SwingUtilities.invokeLater(() -> {
                        onError.accept("Error cargando página: " + errorText);
                        dispose();
                    });
                }
            });

            browser = client.createBrowser(url, false, false);
            Component browserUI = browser.getUIComponent();

            JPanel content = new JPanel(new BorderLayout());
            content.add(browserUI, BorderLayout.CENTER);

            // Botón cancelar por si el usuario cierra sin autorizar
            JButton cancelBtn = new JButton("Cancelar");
            cancelBtn.addActionListener(e -> {
                onError.accept("El usuario canceló la autorización.");
                dispose();
            });
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottom.add(cancelBtn);
            content.add(bottom, BorderLayout.SOUTH);

            setContentPane(content);

        } catch (Exception e) {
            System.err.println("[OAuthBrowser] Error inicializando JCEF: "
                    + e.getMessage());
            // Fallback: abrir en navegador externo
            onError.accept("No se pudo abrir el navegador interno: "
                    + e.getMessage());
            dispose();
        }
    }

    private void excludeFromCapture() {
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                try {
                    com.sun.jna.Pointer pointer =
                            com.sun.jna.Native.getComponentPointer(
                                    OAuthBrowser.this);
                    if (pointer == null) return;
                    com.sun.jna.platform.win32.WinDef.HWND hwnd =
                            new com.sun.jna.platform.win32.WinDef.HWND(pointer);
                    WindowClickThrough.User32Extra.INSTANCE
                            .SetWindowDisplayAffinity(hwnd, 0x00000011);
                } catch (Exception ex) {
                    System.err.println("[OAuthBrowser] Error excluyendo: "
                            + ex.getMessage());
                }
            }
        });
    }

    @Override
    public void dispose() {
        if (browser != null) {
            browser.close(true);
            browser = null;
        }
        if (client != null) {
            client.dispose();
            client = null;
        }
        super.dispose();
    }

    private static String extractParam(String query, String param) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) {
                try {
                    return java.net.URLDecoder.decode(kv[1],
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}