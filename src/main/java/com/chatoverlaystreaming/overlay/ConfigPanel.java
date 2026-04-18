package com.chatoverlaystreaming.overlay;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

public class ConfigPanel extends JDialog {

    private static final Color BG    = new Color(14, 14, 16);
    private static final Color BG2   = new Color(24, 24, 28);
    private static final Color ACCENT = new Color(200, 140, 255);
    private static final Color FG    = new Color(240, 240, 240);
    private static final Font  FONT  = new Font("Segoe UI", Font.PLAIN, 13);

    private final Config config;

    // Twitch
    private JTextField twitchChannelField;
    private JTextField twitchChannelIdField;
    private JTextField twitchClientIdField;
    private JPasswordField twitchClientSecretField;

    // YouTube
    private JTextField youtubeChannelIdField;
    private JTextField youtubeVideoIdField;
    private JTextArea  youtubeApiKeysField;

    // Panel
    private JSpinner  alphaSpinner;
    private JCheckBox showBackgroundCheck;
    private JSpinner  iconSizeSpinner;

    // Misc
    private JSpinner  minPollingSpinner;
    private JCheckBox showViewerCountCheck;
    private JCheckBox canClickLinkCheck;
    private JCheckBox loadBTTVCheck;
    private JSpinner  messageTimeoutSpinner;
    private JCheckBox logActivityCheck;

    public ConfigPanel(Window owner, Config config) {
        super(owner, "Configuración", ModalityType.MODELESS);
        this.config = config;

        setUndecorated(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(true);
        getContentPane().setBackground(BG);
        // Cerrar con Escape
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");

        getRootPane().getActionMap().put("close",
            new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    dispose();
                }
            }
        );

        buildUI();
        loadValues();
        // pack();
        // setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> {
            pack();
            // Limitar alto máximo a 650px pero respetar el ancho natural
            Dimension preferred = getPreferredSize();
            setSize(preferred.width,
                    Math.min(preferred.height, 650));
            setLocationRelativeTo(owner);
            excludeFromCapture();
        });
        excludeFromCapture();
    }

    private void excludeFromCapture() {
        SwingUtilities.invokeLater(() -> {
            try {
                com.sun.jna.Pointer pointer =
                        com.sun.jna.Native.getComponentPointer(this);
                if (pointer == null) return;
                com.sun.jna.platform.win32.WinDef.HWND hwnd =
                        new com.sun.jna.platform.win32.WinDef.HWND(pointer);
                WindowClickThrough.User32Extra.INSTANCE
                        .SetWindowDisplayAffinity(hwnd, 0x00000011);
            } catch (Exception e) {
                System.err.println("[ConfigPanel] Error excluyendo de captura: "
                        + e.getMessage());
            }
        });
    }

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
        root.add(buildButtons());

        JScrollPane scroll = new JScrollPane(root);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // scroll.setPreferredSize(new Dimension(500, 600));
        /*
        scroll.setPreferredSize(new Dimension(
            root.getPreferredSize().width + 20, // +20 por el scrollbar vertical
            Math.min(root.getPreferredSize().height + 20, 650) // máximo 650px de alto
        ));
        */
        add(scroll);
    }

    private JPanel buildTwitchSection() {
        JPanel panel = createSection("Twitch");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        twitchChannelField    = createTextField(20);
        twitchChannelIdField  = createTextField(20);
        twitchClientIdField   = createTextField(20);
        twitchClientSecretField = createPasswordField(20);

        addRow(panel, gbc, row++, "Canal:",         twitchChannelField);
        addRow(panel, gbc, row++, "ID Canal:",       twitchChannelIdField);
        addRow(panel, gbc, row++, "Client ID:",      twitchClientIdField);
        addRow(panel, gbc, row++, "Client Secret:",  twitchClientSecretField);

        return panel;
    }

    private JPanel buildYouTubeSection() {
        JPanel panel = createSection("YouTube");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        youtubeChannelIdField = createTextField(20);
        youtubeVideoIdField   = createTextField(20);

        // API Keys como textarea (una por línea)
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

        addRow(panel, gbc, row++, "ID Canal:", youtubeChannelIdField);
        addRow(panel, gbc, row++, "Video ID:", youtubeVideoIdField);
        addRow(panel, gbc, row++, "API Keys\n(una por línea):", keysScroll);

        return panel;
    }

    private JPanel buildPanelSection() {
        JPanel panel = createSection("Panel");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        alphaSpinner      = createSpinner(0, 255, 1);
        showBackgroundCheck = createCheckBox("Mostrar fondo");
        iconSizeSpinner   = createSpinner(8, 64, 1);

        addRow(panel, gbc, row++, "Alpha:", alphaSpinner);
        addRow(panel, gbc, row++, "",       showBackgroundCheck);
        addRow(panel, gbc, row++, "Tamaño iconos:", iconSizeSpinner);

        return panel;
    }

    private JPanel buildMiscSection() {
        JPanel panel = createSection("Misc");
        GridBagConstraints gbc = createGbc();
        int row = 0;

        minPollingSpinner     = createSpinner(1000, 60000, 1000);
        showViewerCountCheck  = createCheckBox("Mostrar contador de viewers");
        canClickLinkCheck     = createCheckBox("Links clicables");
        loadBTTVCheck         = createCheckBox("Cargar emotes BTTV");
        messageTimeoutSpinner = createSpinner(0, 3600, 5);
        logActivityCheck      = createCheckBox("Registrar actividad en log");

        addRow(panel, gbc, row++, "Intervalo polling (ms):", minPollingSpinner);
        addRow(panel, gbc, row++, "", showViewerCountCheck);
        addRow(panel, gbc, row++, "", canClickLinkCheck);
        addRow(panel, gbc, row++, "", loadBTTVCheck);
        addRow(panel, gbc, row++, "Timeout mensajes (seg)\n0 = no borrar:",
                messageTimeoutSpinner);
        addRow(panel, gbc, row++, "", logActivityCheck);

        return panel;
    }

    private JPanel buildButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        panel.setBackground(BG);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton cancelBtn = styledButton("Cancelar", new Color(120, 120, 130));
        cancelBtn.addActionListener(e -> dispose());

        JButton saveBtn = styledButton("Guardar y reiniciar", ACCENT);
        saveBtn.addActionListener(e -> onSave());

        panel.add(cancelBtn);
        panel.add(saveBtn);
        return panel;
    }

    private void loadValues() {
        // Twitch
        twitchChannelField.setText(safe(config.getTwitchChannel()));
        twitchChannelIdField.setText(safe(config.getTwitchChannelId()));
        twitchClientIdField.setText(safe(config.getTwitchClientId()));
        twitchClientSecretField.setText(safe(config.getTwitchClientSecret()));

        // YouTube
        youtubeChannelIdField.setText(safe(config.getYoutubeChannelId()));
        youtubeVideoIdField.setText(safe(config.getYoutubeVideoId()));
        youtubeApiKeysField.setText(
                String.join("\n", config.getYoutubeApiKeys()));

        // Panel
        alphaSpinner.setValue(config.getPanelAlpha());
        showBackgroundCheck.setSelected(config.getShowBackground());
        iconSizeSpinner.setValue(config.getIconSize());

        // Misc
        minPollingSpinner.setValue(config.getMinPollingInterval());
        showViewerCountCheck.setSelected(config.getShowViewerCount());
        canClickLinkCheck.setSelected(config.getCanClickLink());
        loadBTTVCheck.setSelected(config.getLoadBTTV());
        messageTimeoutSpinner.setValue(config.getMessageTimeout());
        logActivityCheck.setSelected(config.getLogActivity());
    }

    private void onSave() {
        try {
            // Recoger valores
            String twitchChannel    = twitchChannelField.getText().trim();
            String twitchChannelId  = twitchChannelIdField.getText().trim();
            String twitchClientId   = twitchClientIdField.getText().trim();
            String twitchSecret     = new String(
                    twitchClientSecretField.getPassword()).trim();

            String ytChannelId = youtubeChannelIdField.getText().trim();
            String ytVideoId   = youtubeVideoIdField.getText().trim();
            List<String> apiKeys = Arrays.stream(
                    youtubeApiKeysField.getText().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(java.util.stream.Collectors.toList());

            int alpha       = (int) alphaSpinner.getValue();
            boolean showBg  = showBackgroundCheck.isSelected();
            int iconSize    = (int) iconSizeSpinner.getValue();

            int minPoll     = (int) minPollingSpinner.getValue();
            boolean showViewers = showViewerCountCheck.isSelected();
            boolean canClick    = canClickLinkCheck.isSelected();
            boolean loadBTTV    = loadBTTVCheck.isSelected();
            int msgTimeout  = (int) messageTimeoutSpinner.getValue();
            boolean logActivity = logActivityCheck.isSelected();

            // Validaciones básicas
            if (twitchChannel.isBlank()) {
                showError("El canal de Twitch no puede estar vacío.");
                return;
            }

            // Guardar
            config.saveAll(
                twitchChannel, twitchChannelId, twitchClientId, twitchSecret,
                ytChannelId, ytVideoId, apiKeys,
                alpha, showBg, iconSize,
                minPoll, showViewers, canClick, loadBTTV, msgTimeout, logActivity
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
            showError("Error guardando configuración:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void restartApplication() {
        try {
            // Obtener el comando con el que se lanzó la JVM
            String javaBin = ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home")
                            + "/bin/java");
            String classpath = System.getProperty("java.class.path");
            List<String> command = new ArrayList<>();
            command.add(javaBin);

            // Recuperar argumentos de JVM del proceso actual
            java.lang.management.RuntimeMXBean runtime =
                java.lang.management.ManagementFactory.getRuntimeMXBean();
            command.addAll(runtime.getInputArguments());

            command.add("-cp");
            command.add(classpath);
            command.add("com.chatoverlaystreaming.Main");

            new ProcessBuilder(command)
                    .inheritIO()
                    .start();

            System.exit(0);
        } catch (Exception e) {
            showError("No se pudo reiniciar automáticamente.\n" +
                      "Por favor reinicia la aplicación manualmente.\n\n"
                      + e.getMessage());
            dispose();
        }
    }

    private void showError(String msg) {
        ObsAwareDialog.showMessage(this, msg, "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    // ── Helpers de UI ────────────────────────────────────────────────────────

    private JPanel createSection(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                panel.getPreferredSize().height));

        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70)),
                title);
        border.setTitleColor(ACCENT);
        border.setTitleFont(FONT.deriveFont(Font.BOLD));
        panel.setBorder(BorderFactory.createCompoundBorder(
                border, new EmptyBorder(6, 8, 8, 8)));
        return panel;
    }

    private GridBagConstraints createGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets  = new Insets(3, 4, 3, 4);
        gbc.anchor  = GridBagConstraints.WEST;
        gbc.fill    = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

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
        JComponent editor = s.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
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