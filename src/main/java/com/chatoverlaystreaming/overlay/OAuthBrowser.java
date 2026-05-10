package com.chatoverlaystreaming.overlay;

import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Ventana de autorización OAuth integrada en la aplicación.
 *
 * Embebe un navegador Chromium (JCEF) para mostrar la página de autorización
 * de Twitch. Cuando Twitch redirige a localhost con el código OAuth, el
 * browser lo intercepta y notifica mediante callbacks.
 * La ventana es invisible para OBS mediante SetWindowDisplayAffinity.
 *
 * Seguridad CSRF:
 *   - El parámetro "state" se genera aleatoriamente en TwitchAuth y se verifica
 *     aquí al recibir la redirección. Si no coincide, se trata como error.
 *
 * Ciclo de vida:
 *   - Se muestra modal hasta que el usuario autoriza, cancela o cierra la ventana.
 *   - handleResult() garantiza que los callbacks se invocan una sola vez
 *     gracias a AtomicBoolean.
 *   - dispose() limpia los recursos de JCEF (browser + client).
 *   - cefApp es estático y se reutiliza entre aperturas para evitar
 *     reinicializar Chromium en cada autorización.
 */
public class OAuthBrowser extends JDialog {

    // ── Estado de JCEF (estático: se inicializa una sola vez por proceso) ────

    /** Instancia de CefApp compartida entre todas las aperturas del browser OAuth. */
    private static CefApp cefApp;

    // ── Estado de la instancia ────────────────────────────────────────────────

    private CefBrowser browser;
    private CefClient  client;

    /** Garantiza que onCode u onError se invocan exactamente una vez. */
    private final AtomicBoolean resultHandled = new AtomicBoolean(false);

    // ── Parámetros del flujo OAuth ────────────────────────────────────────────

    private final Consumer<String> onCode;   // llamado con el código OAuth si el usuario autoriza
    private final Consumer<String> onError;  // llamado con el mensaje de error si falla o cancela
    private final String           state;    // token CSRF generado por TwitchAuth
    private final int              port;     // puerto de localhost donde espera la redirección

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea y muestra la ventana de autorización OAuth.
     *
     * @param owner   Ventana padre (el overlay principal).
     * @param url     URL de autorización de Twitch con todos los parámetros.
     * @param state   Token CSRF para verificar la respuesta de Twitch.
     * @param port    Puerto de localhost configurado como redirect URI.
     * @param onCode  Callback invocado con el código de autorización si el usuario acepta.
     * @param onError Callback invocado con el mensaje de error si falla o cancela.
     */
    public OAuthBrowser(Window owner, String url, String state, int port,
                        Consumer<String> onCode, Consumer<String> onError) {
        super(owner, "Autorización Twitch", ModalityType.APPLICATION_MODAL);
        this.onCode  = onCode;
        this.onError = onError;
        this.state   = state;
        this.port    = port;

        setSize(700, 550);
        setIconImage(new ImageIcon("icon.png").getImage());
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        installWindowListeners();
        initBrowser(url);
    }

    // ── Inicialización ────────────────────────────────────────────────────────

    /**
     * Instala los listeners de ventana para:
     * - Excluir la ventana de la captura de OBS al abrirse.
     * - Notificar cancelación si el usuario cierra con la X.
     */
    private void installWindowListeners() {
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                excludeFromCapture();
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                handleResult(null, "El usuario cerró la ventana de autorización.");
            }
        });
    }

    /**
     * Excluye la ventana de la captura de OBS.
     * Debe llamarse cuando la ventana ya es visible para que el handle nativo exista.
     */
    private void excludeFromCapture() {
        try {
            com.sun.jna.Pointer pointer =
                    com.sun.jna.Native.getComponentPointer(this);
            if (pointer == null) return;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    new com.sun.jna.platform.win32.WinDef.HWND(pointer);
            WindowClickThrough.User32Extra.INSTANCE
                    .SetWindowDisplayAffinity(hwnd, 0x00000011);
        } catch (Exception e) {
            System.err.println("[OAuthBrowser] Error excluyendo de captura: "
                    + e.getMessage());
        }
    }

    /**
     * Inicializa JCEF y construye la UI del browser.
     *
     * Si cefApp ya existe (segunda autorización), limpia las cookies para
     * forzar un login fresco y evitar que Twitch reutilice la sesión anterior.
     * La UI usa OverlayLayout para mostrar un panel de "Cargando..." encima
     * del browser mientras la página navega.
     */
    private void initBrowser(String url) {
        try {
            if (cefApp == null) {
                CefAppBuilder builder = new CefAppBuilder();
                builder.getCefSettings().windowless_rendering_enabled = false;
                cefApp = builder.build();
            } else {
                // Limpiar cookies para forzar login fresco en reautorizaciones
                org.cef.network.CefCookieManager cookies =
                        org.cef.network.CefCookieManager.getGlobalManager();
                if (cookies != null) cookies.deleteCookies("", "");
            }

            client  = cefApp.createClient();
            browser = client.createBrowser(url, false, false);

            JPanel loadingPanel  = buildLoadingPanel();
            JPanel browserWrapper = buildBrowserWrapper();
            JPanel content        = buildContentPanel(loadingPanel, browserWrapper);

            client.addLoadHandler(buildLoadHandler(loadingPanel, content));

            JPanel outer = new JPanel(new BorderLayout());
            outer.add(content,        BorderLayout.CENTER);
            outer.add(buildCancelBar(), BorderLayout.SOUTH);
            setContentPane(outer);

        } catch (Exception e) {
            System.err.println("[OAuthBrowser] Error inicializando JCEF: " + e.getMessage());
            onError.accept("No se pudo abrir el navegador interno: " + e.getMessage());
            dispose();
        }
    }

    // ── Construcción de la UI ─────────────────────────────────────────────────

    /**
     * Panel oscuro con texto "Cargando..." que se muestra mientras navega el browser.
     * Se oculta cuando la página termina de cargar y vuelve a mostrarse en cada
     * navegación para dar feedback al usuario.
     */
    private JPanel buildLoadingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(14, 14, 16));
        panel.setOpaque(true);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        panel.setAlignmentX(0.5f);
        panel.setAlignmentY(0.5f);

        JLabel label = new JLabel("Cargando, espere un momento...", SwingConstants.CENTER);
        label.setForeground(new Color(200, 140, 255));
        label.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    /** Wrapper del componente nativo del browser con BorderLayout para que ocupe todo el espacio. */
    private JPanel buildBrowserWrapper() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        wrapper.setAlignmentX(0.5f);
        wrapper.setAlignmentY(0.5f);
        wrapper.add(browser.getUIComponent(), BorderLayout.CENTER);
        return wrapper;
    }

    /**
     * Panel principal con OverlayLayout que apila el browser (fondo) y el
     * loadingPanel (encima). OverlayLayout pinta en orden inverso al de adición,
     * así que loadingPanel se añade primero para quedar encima.
     */
    private JPanel buildContentPanel(JPanel loadingPanel, JPanel browserWrapper) {
        JPanel content = new JPanel();
        content.setLayout(new OverlayLayout(content));
        content.add(loadingPanel);
        content.add(browserWrapper);
        return content;
    }

    /** Barra inferior con el botón Cancelar. */
    private JPanel buildCancelBar() {
        JButton cancelBtn = new JButton("Cancelar");
        cancelBtn.addActionListener(e ->
                handleResult(null, "El usuario canceló la autorización."));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bar.add(cancelBtn);
        return bar;
    }

    // ── Handler de carga del browser ──────────────────────────────────────────

    /**
     * Construye el CefLoadHandler que gestiona el ciclo de carga del browser:
     * - Muestra/oculta el loadingPanel según el estado de carga.
     * - Detecta la redirección a localhost y extrae el código OAuth o el error.
     * - Ignora ERR_ABORTED (redirecciones normales entre páginas de Twitch).
     */
    private CefLoadHandlerAdapter buildLoadHandler(JPanel loadingPanel, JPanel content) {
        return new CefLoadHandlerAdapter() {

            @Override
            public void onLoadingStateChange(CefBrowser b, boolean isLoading,
                                              boolean canGoBack, boolean canGoForward) {
                // Ocultar loading cuando la página termina de cargar
                if (!isLoading) {
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

                // Mostrar loading en cada navegación
                SwingUtilities.invokeLater(() -> {
                    loadingPanel.setVisible(true);
                    content.revalidate();
                    content.repaint();
                });

                // Detectar redirección a localhost (puede llegar por onLoadStart
                // si el servidor HTTP está levantado)
                if (loadUrl.startsWith("http://localhost:" + port)) {
                    processRedirectUrl(loadUrl);
                }
            }

            @Override
            public void onLoadError(CefBrowser b, CefFrame frame,
                                    org.cef.handler.CefLoadHandler.ErrorCode errorCode,
                                    String errorText, String failedUrl) {

                // La redirección a localhost normalmente falla con ERR_CONNECTION_REFUSED
                // porque no hay servidor HTTP escuchando — pero la URL tiene el código OAuth
                if (failedUrl != null && failedUrl.startsWith("http://localhost:" + port)) {
                    processRedirectUrl(failedUrl);
                    return;
                }

                // ERR_ABORTED ocurre en redirecciones normales entre páginas de Twitch
                if (errorCode == org.cef.handler.CefLoadHandler.ErrorCode.ERR_ABORTED) return;

                SwingUtilities.invokeLater(() -> {
                    loadingPanel.setVisible(false);
                    handleResult(null, "Error cargando página: " + errorText);
                });
            }
        };
    }

    // ── Procesado de la redirección OAuth ─────────────────────────────────────

    /**
     * Extrae los parámetros de la URL de redirección OAuth y notifica el resultado.
     * Verifica el parámetro "state" para prevenir ataques CSRF.
     *
     * @param redirectUrl URL completa de la redirección a localhost.
     */
    private void processRedirectUrl(String redirectUrl) {
        String query        = redirectUrl.contains("?")
                ? redirectUrl.substring(redirectUrl.indexOf('?') + 1) : "";
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
                handleResult(null, "State no coincide — posible ataque CSRF.");
            } else {
                handleResult(null, "Respuesta OAuth no reconocida en: " + redirectUrl);
            }
        });
    }

    // ── Resultado y limpieza ──────────────────────────────────────────────────

    /**
     * Notifica el resultado del flujo OAuth exactamente una vez.
     * Si ya se notificó un resultado anterior, las llamadas siguientes se ignoran.
     * Cierra la ventana y libera recursos de JCEF.
     *
     * @param code  Código OAuth si el usuario autorizó, null si hubo error.
     * @param error Mensaje de error si falló, null si hubo éxito.
     */
    private void handleResult(String code, String error) {
        if (!resultHandled.compareAndSet(false, true)) return;
        dispose();
        if (code != null) {
            onCode.accept(code);
        } else {
            onError.accept(error);
        }
    }

    /**
     * Libera los recursos de JCEF al cerrar la ventana.
     * cefApp no se libera porque se reutiliza entre aperturas.
     */
    @Override
    public void dispose() {
        if (browser != null) { browser.close(true); browser = null; }
        if (client  != null) { client.dispose();    client  = null; }
        super.dispose();
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Extrae el valor de un parámetro de una query string URL-encoded.
     *
     * @param query Query string sin el "?" inicial.
     * @param param Nombre del parámetro a extraer.
     * @return Valor decodificado del parámetro, o null si no existe.
     */
    private static String extractParam(String query, String param) {
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) {
                try {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}