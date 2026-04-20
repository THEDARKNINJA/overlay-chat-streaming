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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final java.util.concurrent.atomic.AtomicBoolean resultHandled = 
        new AtomicBoolean(false);

    public OAuthBrowser(Window owner, String url, String state, int port,
                        Consumer<String> onCode, Consumer<String> onError) {
        super(owner, "Autorización Twitch", ModalityType.APPLICATION_MODAL);
        this.onCode  = onCode;
        this.onError = onError;
        this.state   = state;
        this.port    = port;

        setSize(700, 550);
        setIconImage( new ImageIcon("icon.png").getImage() );
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
            } else {
                org.cef.network.CefCookieManager cookieManager = 
                        org.cef.network.CefCookieManager.getGlobalManager();
                if (cookieManager != null) {
                    cookieManager.deleteCookies("", "");
                }
            }

            client = cefApp.createClient();
            browser = client.createBrowser(url, false, false);
            Component browserUI = browser.getUIComponent();

            // Panel con OverlayLayout: browser debajo, loading encima
            JPanel content = new JPanel();
            content.setLayout(new OverlayLayout(content));

            // Panel de loading encima (z-order superior)
            JPanel loadingPanel = new JPanel(new BorderLayout());
            loadingPanel.setBackground(new Color(14, 14, 16));
            loadingPanel.setOpaque(true);
            // Necesita ser opaco y estar al máximo tamaño
            loadingPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            loadingPanel.setAlignmentX(0.5f);
            loadingPanel.setAlignmentY(0.5f);
            JLabel loadingLabel = new JLabel(
                    "Cargando, espere un momento...", SwingConstants.CENTER);
            loadingLabel.setForeground(new Color(200, 140, 255));
            loadingLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            loadingPanel.add(loadingLabel, BorderLayout.CENTER);

            // El browser ocupa todo el espacio
            JPanel browserWrapper = new JPanel(new BorderLayout());
            browserWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            browserWrapper.setAlignmentX(0.5f);
            browserWrapper.setAlignmentY(0.5f);
            browserWrapper.add(browserUI, BorderLayout.CENTER);

            // OverlayLayout pinta en orden inverso: primero browserWrapper, encima loadingPanel
            content.add(loadingPanel);
            content.add(browserWrapper);

            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadingStateChange(CefBrowser b, boolean isLoading,
                                                boolean canGoBack,
                                                boolean canGoForward) {
                    if (!isLoading) {
                        // Página cargada: ocultar el loading
                        SwingUtilities.invokeLater(() -> {
                            loadingPanel.setVisible(false);
                            content.revalidate();
                            content.repaint();
                        });
                    }
                }

                @Override
                public void onLoadStart(CefBrowser b, CefFrame frame,
                                        org.cef.network.CefRequest.TransitionType tt) {
                    String loadUrl = b.getURL();
                    if (loadUrl == null) return;

                    SwingUtilities.invokeLater(() -> {
                        loadingPanel.setVisible(true);
                        content.revalidate();
                        content.repaint();
                    });

                    if (loadUrl.startsWith("http://localhost:" + port)) {
                        String query = loadUrl.contains("?")
                                ? loadUrl.substring(loadUrl.indexOf('?') + 1) : "";
                        String code          = extractParam(query, "code");
                        String errorParam    = extractParam(query, "error");
                        String receivedState = extractParam(query, "state");

                        SwingUtilities.invokeLater(() -> {
                            if (code != null && state.equals(receivedState)) {
                                handleResult(code, null);
                            } else if (errorParam != null) {
                                String desc = extractParam(query, "error_description");
                                handleResult(null, desc != null ? desc : errorParam);
                            } else {
                                handleResult(null, "State no coincide.");
                            }
                        });
                    }
                }

                @Override
                public void onLoadError(CefBrowser b, CefFrame frame,
                                        org.cef.handler.CefLoadHandler.ErrorCode errorCode,
                                        String errorText, String failedUrl) {

                    // Si el error es en localhost, es la redirección OAuth — leer los parámetros
                    if (failedUrl != null && failedUrl.startsWith("http://localhost:" + port)) {
                        String query = failedUrl.contains("?")
                                ? failedUrl.substring(failedUrl.indexOf('?') + 1) : "";
                        String code          = extractParam(query, "code");
                        String errorParam    = extractParam(query, "error");
                        String receivedState = extractParam(query, "state");

                        SwingUtilities.invokeLater(() -> {
                            if (code != null && state.equals(receivedState)) {
                                handleResult(code, null);
                            } else if (errorParam != null) {
                                String desc = extractParam(query, "error_description");
                                handleResult(null, desc != null ? desc : errorParam);
                            } else if (code != null) {
                                handleResult(null, "State no coincide.");
                            } else {
                                handleResult(null, "Respuesta OAuth no reconocida.");
                            }
                        });
                        return;
                    }

                    // Ignorar ERR_ABORTED (redirecciones normales del navegador)
                    if (errorCode == org.cef.handler.CefLoadHandler.ErrorCode.ERR_ABORTED) return;

                    SwingUtilities.invokeLater(() -> {
                        loadingPanel.setVisible(false);
                        handleResult(null, "Error cargando página: " + errorText);
                    });
                }
            });

            // Panel exterior con BorderLayout para añadir el botón cancelar
            JPanel outer = new JPanel(new BorderLayout());
            outer.add(content, BorderLayout.CENTER);

            JButton cancelBtn = new JButton("Cancelar");
            cancelBtn.addActionListener(e ->
                handleResult(null, "El usuario canceló la autorización."));
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottom.add(cancelBtn);
            outer.add(bottom, BorderLayout.SOUTH);

            setContentPane(outer);

        } catch (Exception e) {
            System.err.println("[OAuthBrowser] Error inicializando JCEF: "
                    + e.getMessage());
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
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                handleResult(null, "El usuario cerró la ventana de autorización.");
            }
        });
    }
    
    @Override
    public void dispose() {
        // Llamar handleResult con error si nadie lo hizo aún
        // (por ejemplo si se cierra con la X de la ventana)
        if (!resultHandled.get()) {
            resultHandled.set(true);
            // No llamar onError aquí porque dispose puede llamarse
            // después de un resultado exitoso también
        }
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

    private void handleResult(String code, String error) {
        if (!resultHandled.compareAndSet(false, true)) return; // ya procesado
        dispose();
        if (code != null) {
            onCode.accept(code);
        } else {
            onError.accept(error);
        }
    }
}