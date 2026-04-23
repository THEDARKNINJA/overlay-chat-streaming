package com.chatoverlaystreaming.overlay;

import com.chatoverlaystreaming.emotes.*;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.model.EmoteToken;
import com.chatoverlaystreaming.readers.TwitchEventSub;
import com.chatoverlaystreaming.service.ViewerCountService;

import org.json.JSONObject;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Ventana principal del overlay de chat.
 *
 * Es un JFrame sin decoración, siempre encima, con fondo magenta como color
 * key para la transparencia en Windows. Consume mensajes de una BlockingQueue
 * y los renderiza en un JTextPane con emotes, badges y eventos especiales.
 *
 * Arquitectura interna:
 *   - El constructor monta la UI completa y arranca el hilo consumidor.
 *   - initNativeFeatures() se llama desde Main tras setVisible para aplicar
 *     APIs nativas de Windows (click-through, exclusión de OBS).
 *   - Los métodos públicos de conexión son llamados desde Main para separar
 *     la lógica de red de la UI.
 */
public class ChatOverlay extends JFrame {

    // ── Constantes ────────────────────────────────────────────────────────────

    /** Número máximo de mensajes antes de empezar a eliminar los más antiguos. */
    private static final int MAX_MESSAGES = 20;

    /** Identificador de la card de viewers en el CardLayout de cada plataforma. */
    private static final String CARD_VIEWERS = "viewers";

    /** Identificador de la card del botón de conexión en el CardLayout. */
    private static final String CARD_BUTTON  = "button";

    // ── Estado de la ventana ──────────────────────────────────────────────────

    private boolean showBackground;
    private boolean canClickLink;
    private boolean isLocked     = true;
    private boolean hasFocus     = false;
    private boolean visibleToObs = false;

    // ── Componentes UI ────────────────────────────────────────────────────────

    private final JTextPane textPane;
    private StyledDocument doc;

    private JPanel dragBar;
    private final Rectangle closeButtonRect  = new Rectangle();
    private final Rectangle resizeHandleRect = new Rectangle();

    /** Panel inferior izquierdo con los widgets de estado de cada plataforma. */
    private JPanel viewerPanel;

    private ImageIcon twitchIcon;
    private ImageIcon youtubeIcon;

    // Botones de la barra inferior (visibles solo con foco)
    private JButton rewardsButton;
    private JButton configButton;
    private JButton obsVisibilityBtn;

    // Widgets de estado por plataforma: alternan entre botón y contador
    private JPanel  twitchStatusPanel;
    private JPanel  youtubeStatusPanel;
    private JLabel  twitchViewersLabel;
    private JLabel  youtubeViewersLabel;
    private JButton twitchConnectBtn;
    private JButton youtubeConnectBtn;

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final Config config;
    private final EmoteRenderer     emoteRenderer;
    private final TwitchEmoteCache  twitchEmoteCache = new TwitchEmoteCache();
    private final BTTVEmoteCache    bttvEmoteCache;
    private final TwitchBadgeCache  badgeCache;

    private TwitchEventSub     eventSub;
    private ViewerCountService viewerCountService;
    private WindowClickThrough clickThrough;

    /** Timer con debounce de 500ms para guardar posición/tamaño al mover/redimensionar. */
    private final javax.swing.Timer saveTimer;

    // ── Moderación: seguimiento de mensajes por ID y por usuario ─────────────

    /** messageId → [startOffset, endOffset] para borrado puntual (CLEARMSG). */
    private final Map<String, int[]> messageOffsets = new LinkedHashMap<>();

    /** usuario → lista de messageIds para borrado por usuario (CLEARCHAT). */
    private final Map<String, List<String>> userMessages = new LinkedHashMap<>();

    // ── Limpieza automática por timeout ───────────────────────────────────────

    /**
     * Cola de entradas de mensajes para el sistema de limpieza por timeout.
     * Usa Position en lugar de offsets enteros para que se actualicen
     * automáticamente al borrar texto del documento.
     */
    private final java.util.Deque<MessageEntry> messageEntries = new java.util.ArrayDeque<>();

    /**
     * Entrada de mensaje para el sistema de limpieza automática.
     * Almacena el timestamp de inserción y las posiciones del documento
     * (que se actualizan automáticamente al modificar el documento).
     */
    private static class MessageEntry {
        final long     timestamp;
        final Position startPos;
        final Position endPos;

        MessageEntry(long timestamp, Position startPos, Position endPos) {
            this.timestamp = timestamp;
            this.startPos  = startPos;
            this.endPos    = endPos;
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Construye el overlay completo.
     *
     * @param queue           Cola de mensajes compartida con los readers.
     * @param twitchChannelId ID numérico del canal de Twitch (para badges/BTTV).
     * @param twitchClientId  Client ID de la app de Twitch (para badges).
     * @param twitchClientSecret Client Secret de Twitch (para badges).
     * @param config          Configuración de la aplicación.
     * @param sharedImageCache Cache de imágenes compartida con los readers.
     */
    public ChatOverlay(BlockingQueue<ChatMessage> queue,
                       String twitchChannelId,
                       String twitchClientId,
                       String twitchClientSecret,
                       Config config,
                       ImageCache sharedImageCache) {

        this.config      = config;
        this.canClickLink = config.getCanClickLink();
        this.showBackground = config.getShowBackground();

        // Cargar iconos de plataforma
        twitchIcon  = loadIcon("/icons/twitch.png",  14);
        youtubeIcon = loadIcon("/icons/youtube.png", 14);

        // Inicializar componentes antes de construir la UI
        textPane      = new JTextPane();
        emoteRenderer = new EmoteRenderer(config.getIconSize(), textPane, sharedImageCache);

        // Cachés de emotes y badges (pueden ser lentas — se inicializan en background implícitamente)
        bttvEmoteCache = config.getLoadBTTV()
                ? new BTTVEmoteCache(twitchChannelId)
                : null;
        badgeCache = new TwitchBadgeCache(twitchClientId, twitchClientSecret, twitchChannelId);

        // Timer de guardado con debounce para no escribir en cada pixel del drag
        saveTimer = new javax.swing.Timer(500, e -> savePosition());
        saveTimer.setRepeats(false);

        // Configurar ventana
        configureWindow();

        // Construir y añadir la UI
        add(buildMainPanel());
        doc = textPane.getStyledDocument();

        // Listeners de foco, arrastre y resize
        addWindowFocusListener(buildFocusListener());
        addComponentListener(buildPositionSaveListener());

        // Arrancar el consumidor de mensajes
        startMessageConsumer(queue);

        // Arrancar la limpieza automática si está configurada
        startMessageCleanup();
    }

    // ── Configuración de la ventana ───────────────────────────────────────────

    /** Aplica las propiedades base del JFrame. */
    private void configureWindow() {
        setIconImage(new ImageIcon("icon.png").getImage());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setUndecorated(true);
        setBackground(Color.MAGENTA); // color key para transparencia en Windows
        setAlwaysOnTop(true);
        setType(Window.Type.NORMAL);
        setSize(config.getPanelWidth(), config.getPanelHeight());
        setLocation(config.getPanelX(), config.getPanelY());
    }

    // ── Construcción de la UI ─────────────────────────────────────────────────

    /**
     * Construye el panel principal con todas las subzonas:
     * dragBar (norte), área de chat (centro), barra inferior (sur).
     */
    private JPanel buildMainPanel() {
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Fondo magenta en toda la superficie (color key de Windows)
                g2.setColor(Color.MAGENTA);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (showBackground) {
                    // Superponer rectángulo oscuro semitransparente para el fondo del chat
                    g2.setColor(new Color(10, 10, 10));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                }
                g2.dispose();
            }
        };
        panel.setOpaque(true);

        dragBar = buildDragBar();
        JPanel resizeHandle = buildResizeHandle();

        // Barra inferior: espectadores a la izquierda, botones de control al centro
        panel.add(dragBar,           BorderLayout.NORTH);
        panel.add(buildScrollPane(), BorderLayout.CENTER);
        panel.add(buildBottomBar(),  BorderLayout.SOUTH);

        // El resize handle flota sobre todo en la esquina inferior derecha
        installResizeHandle(resizeHandle);

        // Listeners de clic en la dragBar (cerrar y cursor)
        installDragBarListeners();

        return panel;
    }

    /**
     * Construye la barra de arrastre superior.
     * Pinta puntos decorativos y el botón de cierre solo cuando tiene foco.
     */
    private JPanel buildDragBar() {
        JPanel bar = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                if (!hasFocus) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                // Fondo de la barra
                g2.setColor(new Color(100, 60, 200, 170));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                // Tres puntos decorativos en el centro
                g2.setColor(new Color(220, 200, 255, 200));
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g2.fillOval(cx - 12, cy - 2, 5, 5);
                g2.fillOval(cx - 2,  cy - 2, 5, 5);
                g2.fillOval(cx + 8,  cy - 2, 5, 5);

                // Botón X de cierre a la derecha
                paintCloseButton(g2);

                g2.dispose();
            }

            /** Dibuja el botón X y actualiza closeButtonRect para detección de clics. */
            private void paintCloseButton(Graphics2D g2) {
                int size   = 14;
                int margin = 3;
                int bx = getWidth() - size - margin;
                int by = (getHeight() - size) / 2;
                closeButtonRect.setBounds(bx, by, size, size);

                g2.setColor(new Color(180, 60, 60, 200));
                g2.fillRoundRect(bx, by, size, size, 4, 4);

                g2.setColor(new Color(255, 255, 255, 230));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                int p = 3;
                g2.drawLine(bx + p, by + p, bx + size - p, by + size - p);
                g2.drawLine(bx + size - p, by + p, bx + p, by + size - p);
            }
        };

        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(0, 15));
        makeDraggable(bar);
        return bar;
    }

    /**
     * Añade los listeners de ratón a la dragBar para gestionar
     * el clic en el botón de cierre y el cambio de cursor.
     */
    private void installDragBarListeners() {
        dragBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (closeButtonRect.contains(e.getPoint())) {
                    System.exit(0);
                }
            }
        });

        dragBar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                dragBar.setCursor(Cursor.getPredefinedCursor(
                        closeButtonRect.contains(e.getPoint())
                                ? Cursor.HAND_CURSOR
                                : Cursor.MOVE_CURSOR));
            }
        });
    }

    /**
     * Construye el triángulo de redimensionado de la esquina inferior derecha.
     * Solo visible cuando la ventana tiene foco.
     */
    private JPanel buildResizeHandle() {
        JPanel handle = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                if (!hasFocus) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(100, 60, 200, 180));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                int w = getWidth();
                int h = getHeight();
                // Tres líneas diagonales características del resize handle
                for (int i = 1; i <= 3; i++) {
                    int offset = i * 4;
                    g2.drawLine(w - offset, h, w, h - offset);
                }
                g2.dispose();
            }
        };
        handle.setOpaque(false);
        handle.setPreferredSize(new Dimension(16, 16));
        return handle;
    }

    /**
     * Instala el handle de resize en el JLayeredPane del frame y
     * conecta el MouseAdapter que gestiona el arrastre para redimensionar.
     */
    private void installResizeHandle(JPanel resizeHandle) {
        getLayeredPane().add(resizeHandle, JLayeredPane.DRAG_LAYER);

        // Reposicionar cuando cambia el tamaño del layeredPane
        getLayeredPane().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int lw = getLayeredPane().getWidth();
                int lh = getLayeredPane().getHeight();
                resizeHandle.setBounds(lw - 16, lh - 16, 16, 16);
                resizeHandleRect.setBounds(lw - 16, lh - 16, 16, 16);
            }
        });

        MouseAdapter resizeAdapter = new MouseAdapter() {
            private Point     dragStart;
            private Dimension startSize;

            @Override public void mouseEntered(MouseEvent e)  { setCursor(new Cursor(Cursor.SE_RESIZE_CURSOR)); }
            @Override public void mouseExited(MouseEvent e)   { setCursor(new Cursor(Cursor.DEFAULT_CURSOR)); }

            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getLocationOnScreen();
                startSize = getSize();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                Point current = e.getLocationOnScreen();
                int newWidth  = Math.max(200, startSize.width  + current.x - dragStart.x);
                int newHeight = Math.max(150, startSize.height + current.y - dragStart.y);
                setSize(newWidth, newHeight);
                revalidate();
            }

            @Override public void mouseReleased(MouseEvent e) { dragStart = null; }
        };

        resizeHandle.addMouseListener(resizeAdapter);
        resizeHandle.addMouseMotionListener(resizeAdapter);
    }

    /** Construye el JScrollPane con el JTextPane del chat. */
    private JScrollPane buildScrollPane() {
        // Configurar el JTextPane
        textPane.setEditable(false);
        textPane.setEditorKit(new WrapEditorKitPro()); // wrap inteligente para URLs largas
        textPane.setOpaque(false);
        textPane.setBackground(new Color(0, 0, 0, 0));
        textPane.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 13));

        // Espaciado entre líneas
        MutableAttributeSet lineSpacing = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(lineSpacing, 0.3f);
        textPane.setParagraphAttributes(lineSpacing, false);

        // Ocultar el caret de edición
        textPane.setCaret(new DefaultCaret() {
            @Override public void paint(Graphics g) {}
            @Override public boolean isVisible() { return false; }
            @Override public boolean isSelectionVisible() { return false; }
        });

        // Listeners de click en URLs si está habilitado
        if (canClickLink) {
            installLinkClickListeners();
        }

        JScrollPane scroll = new JScrollPane(textPane);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(0, 0));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 0));
        return scroll;
    }

    /**
     * Instala los listeners de ratón en el textPane para abrir
     * URLs al hacer clic y mostrar el tooltip con la URL al pasar el ratón.
     */
    private void installLinkClickListeners() {
        textPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    int pos = textPane.viewToModel2D(e.getPoint());
                    if (pos < 0) return;
                    Element elem = textPane.getStyledDocument()
                            .getCharacterElement(pos);
                    String url = (String) elem.getAttributes().getAttribute("link");
                    if (url != null) Desktop.getDesktop().browse(new URI(url));
                } catch (Exception ex) {
                    System.err.println("[Overlay] Error abriendo URL: " + ex.getMessage());
                }
            }
        });

        textPane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                try {
                    int pos = textPane.viewToModel2D(e.getPoint());
                    if (pos < 0) { textPane.setToolTipText(null); return; }
                    Element elem = textPane.getStyledDocument()
                            .getCharacterElement(pos);
                    String url = (String) elem.getAttributes().getAttribute("link");
                    textPane.setToolTipText(url);
                } catch (Exception ex) {
                    textPane.setToolTipText(null);
                }
            }
        });
    }

    /**
     * Construye la barra inferior con los widgets de espectadores a la izquierda
     * y los botones de control (recompensas, config, OBS) al centro.
     */
    private JPanel buildBottomBar() {
        // Widgets de estado de cada plataforma
        twitchViewersLabel  = buildViewersLabel(new Color(200, 140, 255));
        youtubeViewersLabel = buildViewersLabel(new Color(255, 80,  80));
        twitchConnectBtn    = buildConnectButton(twitchIcon,  new Color(200, 140, 255));
        youtubeConnectBtn   = buildConnectButton(youtubeIcon, new Color(255, 80,  80));
        twitchStatusPanel   = buildPlatformStatusWidget(twitchIcon,  twitchViewersLabel,  twitchConnectBtn);
        youtubeStatusPanel  = buildPlatformStatusWidget(youtubeIcon, youtubeViewersLabel, youtubeConnectBtn);

        // Ocultar paneles de plataformas deshabilitadas
        if (!config.isTwitchEnabled())  twitchStatusPanel.setVisible(false);
        if (!config.isYoutubeEnabled()) youtubeStatusPanel.setVisible(false);

        viewerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        viewerPanel.setOpaque(false);
        viewerPanel.add(twitchStatusPanel);
        viewerPanel.add(youtubeStatusPanel);

        // Botones de control (inicialmente ocultos, se muestran al ganar foco)
        rewardsButton    = buildRewardsButton();
        configButton     = buildConfigButton();
        obsVisibilityBtn = buildObsVisibilityButton();

        JPanel buttonsWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        buttonsWrapper.setBackground(new Color(14, 14, 16));
        buttonsWrapper.add(rewardsButton);
        buttonsWrapper.add(configButton);
        buttonsWrapper.add(obsVisibilityBtn);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.add(viewerPanel,    BorderLayout.WEST);
        bottomBar.add(buttonsWrapper, BorderLayout.CENTER);
        return bottomBar;
    }

    /** Crea un JLabel para el contador de viewers con el color de la plataforma. */
    private JLabel buildViewersLabel(Color color) {
        JLabel label = new JLabel("...");
        label.setForeground(color);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        return label;
    }

    /**
     * Crea el botón de conexión de una plataforma con el icono y color dados.
     * Empieza deshabilitado hasta que el usuario lo pulse.
     */
    private JButton buildConnectButton(ImageIcon icon, Color color) {
        JButton btn = new JButton();
        if (icon != null) btn.setIcon(icon);
        btn.setBackground(new Color(24, 24, 28));
        btn.setBorder(BorderFactory.createLineBorder(color, 1));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Conectar — pulsa tras configurar tus datos");
        btn.setPreferredSize(new Dimension(28, 20));
        return btn;
    }

    /**
     * Construye el widget CardLayout para una plataforma.
     * Alterna entre la card de viewers (icono + número) y la card del botón de conexión.
     */
    private JPanel buildPlatformStatusWidget(ImageIcon icon,
                                              JLabel viewersLabel,
                                              JButton connectBtn) {
        JPanel card = new JPanel(new CardLayout());
        card.setOpaque(false);

        JPanel viewersCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        viewersCard.setOpaque(false);
        if (icon != null) viewersCard.add(new JLabel(icon));
        viewersCard.add(viewersLabel);

        JPanel buttonCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttonCard.setOpaque(false);
        buttonCard.add(connectBtn);

        card.add(viewersCard, CARD_VIEWERS);
        card.add(buttonCard,  CARD_BUTTON);
        return card;
    }

    /** Construye el botón ★ para abrir el gestor de recompensas. */
    private JButton buildRewardsButton() {
        JButton btn = new JButton("★");
        btn.setForeground(new Color(255, 200, 50));
        btn.setBackground(new Color(24, 24, 28));
        Dimension d = btn.getPreferredSize();
        btn.setPreferredSize(new Dimension(d.width + 2, d.height));
        btn.setBorder(BorderFactory.createLineBorder(new Color(255, 200, 50), 1));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Gestionar recompensas");
        btn.setVisible(false); // visible solo con OAuth activo y foco
        btn.addActionListener(e -> {
            if (eventSub == null) {
                ObsAwareDialog.showMessage(this,
                        "El EventSub no está disponible.\n" +
                        "Comprueba que el OAuth está configurado.",
                        "Sin conexión", JOptionPane.WARNING_MESSAGE);
                return;
            }
            new RewardsPanel(this, eventSub, config).setVisible(true);
        });
        return btn;
    }

    /** Construye el botón ⚙ para abrir el panel de configuración. */
    private JButton buildConfigButton() {
        JButton btn = new JButton("⚙");
        btn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 13));
        btn.setForeground(new Color(200, 200, 210));
        btn.setBackground(new Color(24, 24, 28));
        Dimension d = btn.getPreferredSize();
        btn.setPreferredSize(new Dimension(d.width, d.height + 1));
        btn.setBorder(BorderFactory.createLineBorder(new Color(100, 100, 110), 1));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Configuración");
        btn.setVisible(false);
        btn.addActionListener(e -> new ConfigPanel(this, config).setVisible(true));
        return btn;
    }

    /** Construye el botón 👁 para alternar la visibilidad en OBS. */
    private JButton buildObsVisibilityButton() {
        JButton btn = new JButton("👁");
        btn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 13));
        btn.setForeground(new Color(120, 120, 130)); // gris = oculto por defecto
        btn.setBackground(new Color(24, 24, 28));
        Dimension d = btn.getPreferredSize();
        btn.setPreferredSize(new Dimension(d.width, d.height + 1));
        btn.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 90), 1));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Mostrar/ocultar chat en OBS");
        btn.setVisible(false);
        btn.addActionListener(e -> toggleObsVisibility());
        return btn;
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    /**
     * Construye el WindowFocusListener que gestiona:
     * - El click-through (activo sin foco, inactivo con foco)
     * - La visibilidad de los botones de control
     * - El repintado de la dragBar y el resize handle
     */
    private WindowFocusListener buildFocusListener() {
        return new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                hasFocus = true;
                isLocked = false;
                if (clickThrough != null) clickThrough.setClickThrough(false);
                dragBar.repaint();

                // Mostrar botones de control
                Boolean oauthReady = (Boolean) rewardsButton.getClientProperty("oauthReady");
                if (Boolean.TRUE.equals(oauthReady)) rewardsButton.setVisible(true);
                obsVisibilityBtn.setVisible(true);
                configButton.setVisible(true);
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                hasFocus = false;
                isLocked = true;
                if (clickThrough != null) clickThrough.setClickThrough(true);
                dragBar.repaint();

                // Ocultar botones de control
                rewardsButton.setVisible(false);
                obsVisibilityBtn.setVisible(false);
                configButton.setVisible(false);
            }
        };
    }

    /**
     * Construye el ComponentListener que reinicia el timer de guardado
     * al mover o redimensionar la ventana.
     */
    private ComponentListener buildPositionSaveListener() {
        return new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e)   { saveTimer.restart(); }
            @Override public void componentResized(ComponentEvent e) { saveTimer.restart(); }
        };
    }

    // ── Hilo consumidor de mensajes ───────────────────────────────────────────

    /**
     * Arranca el hilo daemon que consume mensajes de la cola
     * y los despacha al EDT para renderizarlos.
     */
    private void startMessageConsumer(BlockingQueue<ChatMessage> queue) {
        Thread consumer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ChatMessage msg = queue.take();
                    SwingUtilities.invokeLater(() -> appendMessage(msg));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "chat-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    // ── Procesado de mensajes ─────────────────────────────────────────────────

    /**
     * Procesa un ChatMessage y lo renderiza en el documento.
     * Gestiona eventos de moderación, recompensas y mensajes normales.
     * Debe llamarse siempre desde el EDT.
     */
    private void appendMessage(ChatMessage msg) {
        try {
            // Eventos de moderación: actuar y salir
            switch (msg.eventType() != null ? msg.eventType() : "") {
                case "clearchat" -> { clearUserMessages(msg.targetUser()); return; }
                case "clearall"  -> { clearAllMessages();                  return; }
                case "clearmsg"  -> { clearSingleMessage(msg.messageId()); return; }
            }

            // Recompensas con media asociada: reproducir en background
            if ("reward".equals(msg.eventType()) && msg.eventExtra() != null) {
                triggerRewardMedia(msg.eventExtra());
            }

            // Eliminar el mensaje más antiguo si se supera el límite
            trimOldestMessageIfNeeded();

            // Tokenizar emotes y obtener badges
            int startOffset = doc.getLength();
            List<EmoteToken> tokens  = resolveTokens(msg);
            List<String>     badges  = resolveBadges(msg);

            // Renderizar el mensaje
            emoteRenderer.render(doc, msg.platform(), msg.user(), msg.text(),
                    tokens, badges, msg.userColor(),
                    msg.eventType(), msg.eventExtra(), showBackground);

            int endOffset = doc.getLength();

            // Registrar para limpieza por timeout
            registerForCleanup(doc, startOffset, endOffset);

            // Registrar para moderación (solo mensajes con ID)
            if (msg.messageId() != null) {
                messageOffsets.put(msg.messageId(), new int[]{startOffset, endOffset});
            }
            if (msg.user() != null && msg.messageId() != null) {
                userMessages.computeIfAbsent(msg.user().toLowerCase(),
                        k -> new ArrayList<>()).add(msg.messageId());
            }

            // Scroll al final
            textPane.setCaretPosition(doc.getLength());

        } catch (Exception e) {
            System.err.println("[Overlay] Error añadiendo mensaje: " + e.getMessage());
        }
    }

    /**
     * Extrae el rewardId del eventExtra y reproduce el media asociado
     * si hay configuración para esa recompensa.
     *
     * @param eventExtra Cadena con formato "titulo|rewardId|redemptionId"
     */
    private void triggerRewardMedia(String eventExtra) {
        String[] parts    = eventExtra.split("\\|", 3);
        String   rewardId = parts.length > 1 ? parts[1] : null;
        if (rewardId == null) return;
        JSONObject rewardConfig = config.getRewardConfig(rewardId);
        if (rewardConfig != null) {
            RewardMediaPlayer.play(rewardId, rewardConfig);
        }
    }

    /**
     * Elimina el mensaje más antiguo del documento si se supera MAX_MESSAGES.
     * Esto previene que el documento crezca indefinidamente.
     */
    private void trimOldestMessageIfNeeded() throws BadLocationException {
        if (doc.getDefaultRootElement().getElementCount() > MAX_MESSAGES) {
            Element first = doc.getDefaultRootElement().getElement(0);
            doc.remove(0, first.getEndOffset());
        }
    }

    /**
     * Resuelve los EmoteTokens del mensaje.
     * Si hay tokens precomputados (YouTube), los usa directamente.
     * Si no, tokeniza con Twitch y BTTV.
     */
    private List<EmoteToken> resolveTokens(ChatMessage msg) {
        if (msg.precomputedTokens() != null) return msg.precomputedTokens();
        List<EmoteToken> tokens = twitchEmoteCache.tokenize(msg.emotesHeader(), msg.text());
        if (bttvEmoteCache != null) tokens = bttvEmoteCache.process(tokens);
        return tokens;
    }

    /**
     * Resuelve las URLs de badges del mensaje.
     * Solo aplica a mensajes de Twitch con cabecera de badges.
     */
    private List<String> resolveBadges(ChatMessage msg) {
        if (msg.precomputedTokens() != null) return null; // YouTube no tiene badges
        return badgeCache.getBadgeUrls(msg.badgesHeader());
    }

    // ── Moderación ────────────────────────────────────────────────────────────

    /**
     * Reemplaza el mensaje con el ID dado por texto tachado gris.
     * Usado para CLEARMSG (borrado de un mensaje concreto).
     */
    private void clearSingleMessage(String messageId) {
        if (messageId == null || !messageOffsets.containsKey(messageId)) return;
        try {
            int[] offsets = messageOffsets.get(messageId);
            int start  = offsets[0];
            int length = offsets[1] - start;

            if (start < doc.getLength() && length > 0) {
                SimpleAttributeSet strikeStyle = new SimpleAttributeSet();
                StyleConstants.setStrikeThrough(strikeStyle, true);
                StyleConstants.setForeground(strikeStyle, new Color(120, 120, 120));
                StyleConstants.setFontFamily(strikeStyle, "Segoe UI Emoji");
                StyleConstants.setFontSize(strikeStyle, 13);
                doc.remove(start, length);
                doc.insertString(start, "<mensaje eliminado>\n", strikeStyle);
            }
            messageOffsets.remove(messageId);
        } catch (Exception e) {
            System.err.println("[Overlay] Error borrando mensaje: " + e.getMessage());
        }
    }

    /**
     * Borra todos los mensajes del usuario dado.
     * Usado para CLEARCHAT (ban o timeout de usuario).
     */
    private void clearUserMessages(String username) {
        if (username == null) return;
        List<String> ids = userMessages.get(username.toLowerCase());
        if (ids == null) return;
        new ArrayList<>(ids).forEach(this::clearSingleMessage);
        userMessages.remove(username.toLowerCase());
    }

    /** Borra todos los mensajes del chat. Usado para /clear del moderador. */
    private void clearAllMessages() {
        try {
            doc.remove(0, doc.getLength());
            messageOffsets.clear();
            userMessages.clear();
        } catch (Exception e) {
            System.err.println("[Overlay] Error limpiando chat: " + e.getMessage());
        }
    }

    // ── Limpieza automática por timeout ───────────────────────────────────────

    /**
     * Registra un rango de texto del documento para su eliminación automática
     * cuando expire el timeout configurado.
     * Usa Position en lugar de offsets enteros para que se actualicen
     * automáticamente al modificar el documento.
     */
    private void registerForCleanup(StyledDocument doc, int startOffset, int endOffset) {
        if (config.getMessageTimeout() <= 0) return;
        try {
            messageEntries.addLast(new MessageEntry(
                    System.currentTimeMillis(),
                    doc.createPosition(startOffset),
                    doc.createPosition(endOffset)));
        } catch (BadLocationException e) {
            System.err.println("[Cleanup] Error registrando posición: " + e.getMessage());
        }
    }

    /**
     * Arranca el timer de limpieza automática de mensajes (cada segundo).
     * Solo activo si messageTimeoutSeconds > 0 en la configuración.
     * Borra los mensajes de atrás hacia adelante para no desplazar los offsets
     * de los mensajes pendientes.
     */
    private void startMessageCleanup() {
        int timeoutSecs = config.getMessageTimeout();
        if (timeoutSecs <= 0) return;

        long limitMs = timeoutSecs * 1000L;

        new Timer(1000, e -> {
            if (messageEntries.isEmpty()) return;
            long now = System.currentTimeMillis();

            // Recopilar rangos expirados
            List<int[]> toDelete = new ArrayList<>();
            while (!messageEntries.isEmpty()) {
                MessageEntry entry = messageEntries.peekFirst();
                if (now - entry.timestamp < limitMs) break;
                messageEntries.pollFirst();
                int start = entry.startPos.getOffset();
                int end   = entry.endPos.getOffset();
                if (end > start) toDelete.add(new int[]{start, end});
            }

            if (toDelete.isEmpty()) return;

            // Borrar de atrás hacia adelante para no desplazar offsets anteriores
            for (int i = toDelete.size() - 1; i >= 0; i--) {
                int start  = toDelete.get(i)[0];
                int length = toDelete.get(i)[1] - start;
                try {
                    if (start + length <= doc.getLength()) {
                        doc.remove(start, length);
                    }
                } catch (BadLocationException ex) {
                    System.err.println("[Cleanup] Error borrando: " + ex.getMessage());
                }
            }
        }).start();
    }

    // ── Visibilidad OBS ───────────────────────────────────────────────────────

    /** Alterna la visibilidad del chat en OBS y actualiza el aspecto del botón. */
    private void toggleObsVisibility() {
        visibleToObs = !visibleToObs;
        applyObsVisibility();
        if (visibleToObs) {
            obsVisibilityBtn.setForeground(new Color(80, 200, 120));
            obsVisibilityBtn.setBorder(BorderFactory.createLineBorder(
                    new Color(80, 200, 120), 1));
            obsVisibilityBtn.setToolTipText("Chat visible en OBS — click para ocultar");
        } else {
            obsVisibilityBtn.setForeground(new Color(120, 120, 130));
            obsVisibilityBtn.setBorder(BorderFactory.createLineBorder(
                    new Color(80, 80, 90), 1));
            obsVisibilityBtn.setToolTipText("Chat oculto en OBS — click para mostrar");
        }
    }

    /**
     * Aplica la afinidad de captura de Windows para excluir o incluir
     * la ventana en la captura de OBS.
     * WDA_EXCLUDEFROMCAPTURE (0x11) = oculto para OBS.
     * WDA_NONE (0x00) = visible para OBS.
     */
    private void applyObsVisibility() {
        try {
            com.sun.jna.Pointer pointer = com.sun.jna.Native.getComponentPointer(this);
            if (pointer == null) return;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    new com.sun.jna.platform.win32.WinDef.HWND(pointer);
            int affinity = visibleToObs ? 0x00000000 : 0x00000011;
            WindowClickThrough.User32Extra.INSTANCE.SetWindowDisplayAffinity(hwnd, affinity);
        } catch (Exception e) {
            System.err.println("[OBS] Error cambiando visibilidad: " + e.getMessage());
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Carga un icono desde los resources del jar y lo escala al tamaño indicado.
     *
     * @param path Ruta del recurso (ej. "/icons/twitch.png")
     * @param size Tamaño en píxeles (ancho y alto)
     * @return ImageIcon escalado, o null si no se puede cargar
     */
    private ImageIcon loadIcon(String path, int size) {
        try {
            var url = getClass().getResource(path);
            if (url == null) return null;
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(url);
            return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            System.err.println("[Overlay] No se pudo cargar icono: " + path);
            return null;
        }
    }

    /**
     * Hace arrastrable un componente para mover la ventana.
     * El arrastre se calcula como diferencia entre la posición inicial del
     * press y la posición actual durante el drag.
     */
    private void makeDraggable(JComponent target) {
        final Point[] dragStart = {null};
        MouseAdapter adapter = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { dragStart[0] = e.getPoint(); }
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart[0] == null) return;
                Point loc = getLocation();
                setLocation(loc.x + e.getX() - dragStart[0].x,
                            loc.y + e.getY() - dragStart[0].y);
            }
        };
        target.addMouseListener(adapter);
        target.addMouseMotionListener(adapter);
    }

    /** Guarda la posición y tamaño actual en config.json. */
    private void savePosition() {
        try {
            Point loc = getLocation();
            Dimension d = getSize();
            config.savePanel(loc.x, loc.y, d.width, d.height);
        } catch (IOException ex) {
            System.err.println("[Config] Error guardando posición: " + ex.getMessage());
        }
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Inicializa las funciones nativas de Windows.
     * Debe llamarse desde Main justo después de setVisible(true).
     * Aplica click-through, exclusión de captura y listeners de foco para botones.
     */
    public void initNativeFeatures() {
        SwingUtilities.invokeLater(() -> {
            // Pequeña espera para que la ventana esté completamente inicializada
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            try {
                clickThrough = new WindowClickThrough(this);
                clickThrough.setClickThrough(isLocked);
                clickThrough.setExcludeFromCapture(true, config.getPanelAlpha());
                visibleToObs = false;
                applyObsVisibility();
            } catch (Exception e) {
                System.err.println("[Overlay] Funciones nativas no disponibles: "
                        + e.getMessage());
            }
        });
    }

    /**
     * Muestra un mensaje de sistema en el chat (en cursiva gris).
     * Registra el mensaje para limpieza automática igual que los mensajes normales.
     *
     * @param message Texto a mostrar
     */
    public void appendSystemMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            try {
                int startOffset = doc.getLength();
                SimpleAttributeSet style = new SimpleAttributeSet();
                StyleConstants.setFontFamily(style, "Segoe UI Emoji");
                StyleConstants.setFontSize(style, 13);
                StyleConstants.setForeground(style, new Color(160, 160, 180));
                StyleConstants.setItalic(style, true);
                doc.insertString(doc.getLength(), message + "\n", style);
                registerForCleanup(doc, startOffset, doc.getLength());
            } catch (Exception e) {
                System.err.println("[ChatOverlay] Error mostrando mensaje sistema: "
                        + e.getMessage());
            }
        });
    }

    /** Inyecta el EventSub activo para que el gestor de recompensas pueda usarlo. */
    public void setEventSub(TwitchEventSub eventSub) {
        this.eventSub = eventSub;
        emoteRenderer.setEventSub(eventSub);
    }

    /**
     * Habilita el botón de recompensas (solo visible cuando hay OAuth activo).
     * Si la ventana ya tiene el foco, muestra el botón inmediatamente.
     */
    public void enableRewardsButton() {
        rewardsButton.putClientProperty("oauthReady", true);
        if (hasFocus) rewardsButton.setVisible(true);
    }

    /** Inyecta el servicio de viewers para arrancarlo cuando conecte cada plataforma. */
    public void setViewerCountService(ViewerCountService service) {
        this.viewerCountService = service;
    }

    /** Arranca el polling de viewers de Twitch. Llamar tras conexión exitosa. */
    public void startTwitchViewers() {
        if (viewerCountService != null) viewerCountService.startTwitch();
    }

    /** Arranca el polling de viewers de YouTube. Llamar tras conexión exitosa. */
    public void startYoutubeViewers() {
        if (viewerCountService != null) viewerCountService.startYoutube();
    }

    // Métodos de control de estado de los widgets de plataforma

    /** Registra la acción a ejecutar al pulsar el botón de conexión de Twitch. */
    public void setTwitchConnectAction(Runnable action) {
        twitchConnectBtn.addActionListener(e -> {
            twitchConnectBtn.setEnabled(false);
            action.run();
        });
    }

    /** Registra la acción a ejecutar al pulsar el botón de conexión de YouTube. */
    public void setYoutubeConnectAction(Runnable action) {
        youtubeConnectBtn.addActionListener(e -> {
            youtubeConnectBtn.setEnabled(false);
            action.run();
        });
    }

    /** Muestra el botón de conexión de Twitch (CardLayout → CARD_BUTTON). */
    public void showTwitchButton() {
        ((CardLayout) twitchStatusPanel.getLayout()).show(twitchStatusPanel, CARD_BUTTON);
    }

    /** Muestra el contador de viewers de Twitch (CardLayout → CARD_VIEWERS). */
    public void showTwitchViewers() {
        twitchConnectBtn.setEnabled(true);
        ((CardLayout) twitchStatusPanel.getLayout()).show(twitchStatusPanel, CARD_VIEWERS);
    }

    /** Muestra el botón de conexión de YouTube (CardLayout → CARD_BUTTON). */
    public void showYoutubeButton() {
        ((CardLayout) youtubeStatusPanel.getLayout()).show(youtubeStatusPanel, CARD_BUTTON);
    }

    /** Muestra el contador de viewers de YouTube (CardLayout → CARD_VIEWERS). */
    public void showYoutubeViewers() {
        youtubeConnectBtn.setEnabled(true);
        ((CardLayout) youtubeStatusPanel.getLayout()).show(youtubeStatusPanel, CARD_VIEWERS);
    }

    /** Rehabilita el botón de conexión de Twitch tras un fallo. */
    public void enableTwitchButton()  { twitchConnectBtn.setEnabled(true); }

    /** Rehabilita el botón de conexión de YouTube tras un fallo. */
    public void enableYoutubeButton() { youtubeConnectBtn.setEnabled(true); }

    /**
     * Muestra el botón de Twitch con aspecto naranja para indicar modo anónimo.
     * El usuario puede pulsarlo para reintentar el OAuth.
     */
    public void showTwitchButtonAnon() {
        twitchConnectBtn.setEnabled(true);
        twitchConnectBtn.setToolTipText(
                "Conectado en modo anónimo — pulsa para reintentar con OAuth");
        twitchConnectBtn.setBorder(
                BorderFactory.createLineBorder(new Color(200, 150, 50), 1));
        ((CardLayout) twitchStatusPanel.getLayout()).show(twitchStatusPanel, CARD_BUTTON);
    }

    /** Actualiza el texto del contador de viewers de Twitch. */
    public void setTwitchViewersLabel(String count)  { twitchViewersLabel.setText(count); }

    /** Actualiza el texto del contador de viewers de YouTube. */
    public void setYoutubeViewersLabel(String count) { youtubeViewersLabel.setText(count); }
}