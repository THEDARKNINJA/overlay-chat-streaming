package com.chatoverlaystreaming.overlay;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Panel de configuración general de la aplicación.
 *
 * Diálogo MODELESS que permite editar todos los parámetros del config.json
 * sin necesidad de editarlo a mano. Invisible para OBS.
 * Al guardar, ofrece reiniciar la aplicación para aplicar los cambios.
 *
 * Secciones:
 *   - Twitch: canal, IDs y credenciales OAuth, con opción de deshabilitar.
 *   - YouTube: IDs de canal y vídeo, API keys con rotación, con opción de deshabilitar.
 *   - Panel: alpha, fondo y tamaño de iconos.
 *   - Misc: polling, viewers, links, BTTV, timeout de mensajes y log.
 */
public class ConfigPanel extends JDialog {

    // ── Constantes de diseño ──────────────────────────────────────────────────

    private static final Color BG     = new Color(14,  14,  16);
    private static final Color BG2    = new Color(24,  24,  28);
    private static final Color ACCENT = new Color(200, 140, 255);
    private static final Color FG     = new Color(240, 240, 240);
    private static final Font  FONT   = new Font("Segoe UI", Font.PLAIN, 13);

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final Config config;

    // ── Campos de Twitch ──────────────────────────────────────────────────────

    private JCheckBox     twitchEnabledCheck;
    private JTextField    twitchChannelField;
    private JTextField    twitchChannelIdField;
    private JTextField    twitchClientIdField;
    private JPasswordField twitchClientSecretField;

    // ── Campos de YouTube ─────────────────────────────────────────────────────

    private JCheckBox  youtubeEnabledCheck;
    private JTextField youtubeChannelIdField;
    private JTextField youtubeVideoIdField;
    private JTextArea  youtubeApiKeysField;

    // ── Campos de panel ───────────────────────────────────────────────────────

    private JSpinner  alphaSpinner;
    private JCheckBox showBackgroundCheck;
    private JSpinner  iconSizeSpinner;

    // ── Campos de misc ────────────────────────────────────────────────────────

    private JSpinner  minPollingSpinner;
    private JCheckBox showViewerCountCheck;
    private JCheckBox canClickLinkCheck;
    private JCheckBox loadBTTVCheck;
    private JSpinner  messageTimeoutSpinner;
    private JCheckBox logActivityCheck;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea el panel de configuración.
     *
     * @param owner  Ventana padre (el overlay principal).
     * @param config Configuración actual de la aplicación.
     */
    public ConfigPanel(Window owner, Config config) {
        super(owner, "Configuración", ModalityType.MODELESS);
        this.config = config;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(true);
        getContentPane().setBackground(BG);
        installEscapeKey();

        buildUI();
        loadValues();

        // pack y posicionamiento tras que el EDT haya procesado el layout
        SwingUtilities.invokeLater(() -> {
            pack();
            Dimension preferred = getPreferredSize();
            setSize(preferred.width, Math.min(preferred.height, 650));
            setLocationRelativeTo(owner);
            excludeFromCapture();
        });
    }

    // ── Inicialización ────────────────────────────────────────────────────────

    /** Registra la tecla Escape para cerrar el diálogo. */
    private void installEscapeKey() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        dispose();
                    }
                });
    }

    /**
     * Excluye la ventana de la captura de OBS usando SetWindowDisplayAffinity.
     * Debe llamarse cuando la ventana ya es visible.
     */
    private void excludeFromCapture() {
        try {
            com.sun.jna.Pointer pointer = com.sun.jna.Native.getComponentPointer(this);
            if (pointer == null) return;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    new com.sun.jna.platform.win32.WinDef.HWND(pointer);
            WindowClickThrough.User32Extra.INSTANCE
                    .SetWindowDisplayAffinity(hwnd, 0x00000011);
        } catch (Exception e) {
            System.err.println("[ConfigPanel] Error excluyendo de captura: "
                    + e.getMessage());
        }
    }

    // ── Construcción de la UI ─────────────────────────────────────────────────

    /** Construye y ensambla todos los paneles del diálogo en un JScrollPane. */
    private void buildUI() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        root.add(buildTwitchSection());
        root.add(Box.createVerticalStrut(8));
        root.add(buildYouTubeSection());
        root.add(Box.createVerticalStrut(8));
        root.add(buildPanelSection());
        root.add(Box.createVerticalStrut(8));
        root.add(buildMiscSection());
        root.add(Box.createVerticalStrut(10));
        root.add(buildButtonsPanel());

        JScrollPane scroll = new JScrollPane(root);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll);
    }

    /**
     * Construye la sección de Twitch con checkbox de habilitación
     * y campos de canal y credenciales.
     * Los campos se deshabilitan cuando Twitch está desactivado.
     */
    private JPanel buildTwitchSection() {
        JPanel panel = createSection("Twitch");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        twitchEnabledCheck = createCheckBox("Twitch habilitado");
        twitchEnabledCheck.setSelected(config.isTwitchEnabled());
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(twitchEnabledCheck, gbc);
        gbc.gridwidth = 1;

        twitchChannelField      = createTextField(20);
        twitchChannelIdField    = createTextField(20);
        twitchClientIdField     = createTextField(20);
        twitchClientSecretField = createPasswordField(20);

        addRow(panel, gbc, row++, "Canal:",         twitchChannelField);
        addRow(panel, gbc, row++, "ID Canal:",       twitchChannelIdField);
        addRow(panel, gbc, row++, "Client ID:",      twitchClientIdField);
        addRow(panel, gbc, row++, "Client Secret:",  twitchClientSecretField);

        // Habilitar/deshabilitar campos según el checkbox
        Component[] twitchFields = {
            twitchChannelField, twitchChannelIdField,
            twitchClientIdField, twitchClientSecretField
        };
        twitchEnabledCheck.addActionListener(e ->
                setFieldsEnabled(twitchEnabledCheck.isSelected(), twitchFields));
        setFieldsEnabled(config.isTwitchEnabled(), twitchFields);

        return panel;
    }

    /**
     * Construye la sección de YouTube con checkbox de habilitación,
     * campos de IDs y área de API keys (una por línea).
     * Los campos se deshabilitan cuando YouTube está desactivado.
     */
    private JPanel buildYouTubeSection() {
        JPanel panel = createSection("YouTube");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        youtubeEnabledCheck = createCheckBox("YouTube habilitado");
        youtubeEnabledCheck.setSelected(config.isYoutubeEnabled());
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(youtubeEnabledCheck, gbc);
        gbc.gridwidth = 1;

        youtubeChannelIdField = createTextField(20);
        youtubeVideoIdField   = createTextField(20);

        // API Keys: textarea estilada con scroll, una key por línea
        youtubeApiKeysField = new JTextArea(3, 20);
        youtubeApiKeysField.setBackground(BG2);
        youtubeApiKeysField.setForeground(FG);
        youtubeApiKeysField.setCaretColor(FG);
        youtubeApiKeysField.setFont(FONT);
        youtubeApiKeysField.setLineWrap(true);
        youtubeApiKeysField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        JScrollPane keysScroll = new JScrollPane(youtubeApiKeysField);
        keysScroll.setBackground(BG2);
        keysScroll.getViewport().setBackground(BG2);
        keysScroll.setBorder(null);

        addRow(panel, gbc, row++, "ID Canal:",             youtubeChannelIdField);
        addRow(panel, gbc, row++, "Video ID:",             youtubeVideoIdField);
        addRow(panel, gbc, row++, "API Keys (una/línea):", keysScroll);

        Component[] youtubeFields = {
            youtubeChannelIdField, youtubeVideoIdField, youtubeApiKeysField
        };
        youtubeEnabledCheck.addActionListener(e ->
                setFieldsEnabled(youtubeEnabledCheck.isSelected(), youtubeFields));
        setFieldsEnabled(config.isYoutubeEnabled(), youtubeFields);

        return panel;
    }

    /** Construye la sección de ajustes visuales del panel overlay. */
    private JPanel buildPanelSection() {
        JPanel panel = createSection("Panel");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        alphaSpinner        = createSpinner(0, 255, 1);
        showBackgroundCheck = createCheckBox("Mostrar fondo");
        iconSizeSpinner     = createSpinner(8, 64, 1);

        addRow(panel, gbc, row++, "Alpha:",         alphaSpinner);
        addRow(panel, gbc, row++, "",               showBackgroundCheck);
        addRow(panel, gbc, row++, "Tamaño iconos:", iconSizeSpinner);

        return panel;
    }

    /** Construye la sección de opciones misceláneas. */
    private JPanel buildMiscSection() {
        JPanel panel = createSection("Misc");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        minPollingSpinner     = createSpinner(1, 120, 1);
        showViewerCountCheck  = createCheckBox("Mostrar contador de viewers");
        canClickLinkCheck     = createCheckBox("Links clicables");
        loadBTTVCheck         = createCheckBox("Cargar emotes BTTV");
        messageTimeoutSpinner = createSpinner(0, 3600, 5);
        logActivityCheck      = createCheckBox("Registrar actividad en log");

        addRow(panel, gbc, row++, "Intervalo polling (seg):",    minPollingSpinner);
        addRow(panel, gbc, row++, "",                           showViewerCountCheck);
        addRow(panel, gbc, row++, "",                           canClickLinkCheck);
        addRow(panel, gbc, row++, "",                           loadBTTVCheck);
        addRow(panel, gbc, row++, "Timeout mensajes (seg):",    messageTimeoutSpinner);
        addRow(panel, gbc, row++, "",                           logActivityCheck);

        return panel;
    }

    /** Construye el panel inferior con los botones Cancelar y Guardar. */
    private JPanel buildButtonsPanel() {
        JButton cancelBtn = styledButton("Cancelar", new Color(120, 120, 130));
        cancelBtn.addActionListener(e -> dispose());

        JButton saveBtn = styledButton("Guardar y reiniciar", ACCENT);
        saveBtn.addActionListener(e -> onSave());

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        panel.setBackground(BG);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(cancelBtn);
        panel.add(saveBtn);
        return panel;
    }

    // ── Carga y guardado de valores ───────────────────────────────────────────

    /** Carga los valores actuales del config en todos los campos del formulario. */
    private void loadValues() {
        twitchChannelField.setText(safe(config.getTwitchChannel()));
        twitchChannelIdField.setText(safe(config.getTwitchChannelId()));
        twitchClientIdField.setText(safe(config.getTwitchClientId()));
        twitchClientSecretField.setText(safe(config.getTwitchClientSecret()));

        youtubeChannelIdField.setText(safe(config.getYoutubeChannelId()));
        youtubeVideoIdField.setText(safe(config.getYoutubeVideoId()));
        youtubeApiKeysField.setText(String.join("\n", config.getYoutubeApiKeys()));

        alphaSpinner.setValue(config.getPanelAlpha());
        showBackgroundCheck.setSelected(config.getShowBackground());
        iconSizeSpinner.setValue(config.getIconSize());

        minPollingSpinner.setValue(config.getMinPollingInterval());
        showViewerCountCheck.setSelected(config.getShowViewerCount());
        canClickLinkCheck.setSelected(config.getCanClickLink());
        loadBTTVCheck.setSelected(config.getLoadBTTV());
        messageTimeoutSpinner.setValue(config.getMessageTimeout());
        logActivityCheck.setSelected(config.getLogActivity());
    }

    /**
     * Valida el formulario, guarda la configuración y ofrece reiniciar.
     * Si alguna validación falla muestra un error y no guarda.
     */
    private void onSave() {
        try {
            boolean twitchEnabled  = twitchEnabledCheck.isSelected();
            boolean youtubeEnabled = youtubeEnabledCheck.isSelected();

            // Validaciones: el canal/ID son obligatorios si la plataforma está habilitada
            if (twitchEnabled && twitchChannelField.getText().trim().isBlank()) {
                showError("El canal de Twitch es obligatorio si Twitch está habilitado.");
                return;
            }
            if (youtubeEnabled && youtubeChannelIdField.getText().trim().isBlank()) {
                showError("El ID de canal de YouTube es obligatorio si YouTube está habilitado.");
                return;
            }

            // Recoger valores
            String twitchChannel  = twitchChannelField.getText().trim();
            String twitchChannelId = twitchChannelIdField.getText().trim();
            String twitchClientId  = twitchClientIdField.getText().trim();
            String twitchSecret    = new String(twitchClientSecretField.getPassword()).trim();

            String       ytChannelId = youtubeChannelIdField.getText().trim();
            String       ytVideoId   = youtubeVideoIdField.getText().trim();
            List<String> apiKeys     = Arrays.stream(youtubeApiKeysField.getText().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());

            int     alpha       = (int) alphaSpinner.getValue();
            boolean showBg      = showBackgroundCheck.isSelected();
            int     iconSize    = (int) iconSizeSpinner.getValue();

            int     minPoll     = (int) minPollingSpinner.getValue();
            boolean showViewers = showViewerCountCheck.isSelected();
            boolean canClick    = canClickLinkCheck.isSelected();
            boolean loadBTTV    = loadBTTVCheck.isSelected();
            int     msgTimeout  = (int) messageTimeoutSpinner.getValue();
            boolean logActivity = logActivityCheck.isSelected();

            config.saveAll(
                    twitchEnabled,  youtubeEnabled,
                    twitchChannel,  twitchChannelId, twitchClientId, twitchSecret,
                    ytChannelId,    ytVideoId,       apiKeys,
                    alpha,          showBg,          iconSize,
                    minPoll,        showViewers,     canClick,
                    loadBTTV,       msgTimeout,      logActivity
            );

            int confirm = ObsAwareDialog.showConfirm(this,
                    "Configuración guardada.\n" +
                    "Es necesario reiniciar la aplicación para aplicar los cambios.\n\n" +
                    "¿Reiniciar ahora?",
                    "Guardado", JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                restartApplication();
            } else {
                dispose();
            }

        } catch (Exception e) {
            System.err.println("[ConfigPanel] Error guardando: " + e.getMessage());
            showError("Error guardando configuración:\n" + e.getMessage());
        }
    }

    /**
     * Relanza la aplicación con los mismos argumentos de JVM y cierra la instancia actual.
     * Usa ProcessHandle para obtener el comando y RuntimeMXBean para los JVM args.
     * Si falla, muestra un mensaje pidiendo reinicio manual.
     */
    private void restartApplication() {
        try {
            String javaBin = ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home") + "/bin/java");

            List<String> command = new ArrayList<>();
            command.add(javaBin);
            command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("com.chatoverlaystreaming.Main");

            new ProcessBuilder(command).inheritIO().start();
            System.exit(0);

        } catch (Exception e) {
            System.err.println("[ConfigPanel] Error reiniciando: " + e.getMessage());
            showError("No se pudo reiniciar automáticamente.\n" +
                      "Por favor reinicia la aplicación manualmente.\n\n" + e.getMessage());
            dispose();
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Habilita o deshabilita un conjunto de componentes.
     * Usado para bloquear los campos de una plataforma cuando está desactivada.
     */
    private void setFieldsEnabled(boolean enabled, Component... fields) {
        for (Component f : fields) f.setEnabled(enabled);
    }

    /** Muestra un diálogo de error invisible para OBS. */
    private void showError(String msg) {
        ObsAwareDialog.showMessage(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    /** Devuelve el string dado, o "" si es null. */
    private String safe(String s) {
        return s != null ? s : "";
    }

    // ── Helpers de UI ─────────────────────────────────────────────────────────

    /**
     * Crea un panel de sección con borde titulado en color ACCENT.
     * El panel usa GridBagLayout para las filas de formulario.
     */
    private JPanel createSection(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                panel.getPreferredSize().height));

        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70)), title);
        border.setTitleColor(ACCENT);
        border.setTitleFont(FONT.deriveFont(Font.BOLD));
        panel.setBorder(BorderFactory.createCompoundBorder(
                border, new EmptyBorder(6, 8, 8, 8)));
        return panel;
    }

    /** Crea un GridBagConstraints con los valores por defecto del formulario. */
    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets  = new Insets(3, 4, 3, 4);
        gbc.anchor  = GridBagConstraints.WEST;
        gbc.fill    = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    /** Añade una fila etiqueta+campo al panel de formulario. */
    private void addRow(JPanel panel, GridBagConstraints gbc,
                         int row, String labelText, Component field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        JLabel label = new JLabel(labelText);
        label.setForeground(FG);
        label.setFont(FONT);
        panel.add(label, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(field, gbc);
    }

    private JTextField createTextField(int cols) {
        JTextField f = new JTextField(cols);
        f.setBackground(BG2);
        f.setForeground(FG);
        f.setCaretColor(FG);
        f.setFont(FONT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        return f;
    }

    private JPasswordField createPasswordField(int cols) {
        JPasswordField f = new JPasswordField(cols);
        f.setBackground(BG2);
        f.setForeground(FG);
        f.setCaretColor(FG);
        f.setFont(FONT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        return f;
    }

    private JSpinner createSpinner(int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(min, min, max, step));
        s.setBackground(BG2);
        s.setFont(FONT);
        if (s.getEditor() instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setBackground(BG2);
            de.getTextField().setForeground(FG);
            de.getTextField().setCaretColor(FG);
            de.getTextField().setFont(FONT);
        }
        return s;
    }

    private JCheckBox createCheckBox(String text) {
        JCheckBox cb = new JCheckBox(text);
        cb.setBackground(BG);
        cb.setForeground(FG);
        cb.setFont(FONT);
        cb.setFocusPainted(false);
        return cb;
    }

    private JButton styledButton(String text, Color fg) {
        JButton b = new JButton(text);
        b.setForeground(fg);
        b.setBackground(BG2);
        b.setFont(FONT);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createLineBorder(fg.darker(), 1));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
}