package com.chatoverlaystreaming.overlay;

import com.chatoverlaystreaming.readers.TwitchEventSub;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.File;
import java.util.List;

/**
 * Panel de gestión de recompensas de canal de Twitch.
 *
 * Diálogo MODELESS que permite crear, editar y borrar recompensas de canal points,
 * asociándolas a archivos de audio o vídeo que se reproducen cuando se canjean.
 * Es invisible para OBS mediante SetWindowDisplayAffinity.
 *
 * Estructura del formulario:
 *   - Sección Twitch: título, descripción, coste, color, opciones y cooldown.
 *   - Sección media: tipo (audio/vídeo), carpeta, modo de reproducción, volumen.
 *   - Sección vídeo (solo visible si tipo=vídeo): tamaño, pantalla, posición,
 *     título de ventana, FPS y chroma key.
 */
public class RewardsPanel extends JDialog {

    // ── Constantes de diseño ──────────────────────────────────────────────────

    private static final String NUEVO  = "— NUEVO —";
    private static final Color  BG     = new Color(14,  14,  16);
    private static final Color  BG2    = new Color(24,  24,  28);
    private static final Color  ACCENT = new Color(200, 140, 255);
    private static final Color  FG     = new Color(240, 240, 240);
    private static final Color  RED    = new Color(255, 80,  80);
    private static final Font   FONT   = new Font("Segoe UI", Font.PLAIN, 13);

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final TwitchEventSub eventSub;
    private final Config         config;

    // ── Estado ────────────────────────────────────────────────────────────────

    /** Lista de recompensas cargadas desde Twitch. El índice 0 del combo = NUEVO. */
    private List<JSONObject> rewardsList;

    /** Color actual del chroma key, actualizado por el selector de color. */
    private Color chromaColor = new Color(0, 255, 0);

    // ── Campos del selector de recompensa ────────────────────────────────────

    private JComboBox<String> rewardCombo;
    private JButton           deleteBtn;

    // ── Campos comunes Twitch ─────────────────────────────────────────────────

    private JTextField titleField;
    private JTextArea  promptField;
    private JSpinner   costSpinner;
    private JTextField colorField;
    private JCheckBox  userInputCheck;
    private JCheckBox  skipQueueCheck;
    private JCheckBox  enabledCheck;
    private JCheckBox  cooldownCheck;
    private JSpinner   cooldownSpinner;

    // ── Campos comunes media ──────────────────────────────────────────────────

    private JComboBox<String> typeCombo;
    private JTextField        pathField;
    private JCheckBox         recursiveCheck;
    private JComboBox<String> playModeCombo;
    private JSlider           volumeSlider;
    private JLabel            volumeLabel;

    // ── Campos exclusivos de vídeo ────────────────────────────────────────────

    private JSpinner          videoWidthSpinner;
    private JSpinner          videoHeightSpinner;
    private JComboBox<String> displayCombo;
    private JSpinner          videoPosXSpinner;
    private JSpinner          videoPosYSpinner;
    private JCheckBox         randomPosCheck;
    private JTextField        videoTitleField;
    private JComboBox<Integer> fpsCombo;
    private JCheckBox         chromaCheck;
    private JLabel            chromaColorPreview;
    private JSpinner          chromaToleranceSpinner;

    /** Paneles de vídeo que se muestran/ocultan según el tipo seleccionado. */
    private JPanel videoSizePanel;
    private JPanel videoPosRow;
    private JPanel fpsPanel;
    private JPanel chromaPanel;
    private JPanel displayPanel;

    // ── Botones de acción ─────────────────────────────────────────────────────

    private JButton saveBtn;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea el panel de gestión de recompensas.
     *
     * @param owner    Ventana padre (el overlay principal).
     * @param eventSub EventSub activo para llamadas a la API de Twitch.
     * @param config   Configuración de la aplicación.
     */
    public RewardsPanel(Window owner, TwitchEventSub eventSub, Config config) {
        super(owner, "Gestión de recompensas", ModalityType.MODELESS);
        this.eventSub = eventSub;
        this.config   = config;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        installEscapeKey();

        buildUI();

        SwingUtilities.invokeLater(() -> {
            pack();
            setLocationRelativeTo(owner);
            excludeFromCapture();
            loadRewards();
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
     * Debe llamarse después de que la ventana sea visible.
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
            System.err.println("[RewardsPanel] No se pudo excluir de captura: "
                    + e.getMessage());
        }
    }

    // ── Construcción de la UI ─────────────────────────────────────────────────

    /** Construye y ensambla todos los paneles del diálogo. */
    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        root.add(buildSelectorPanel(), BorderLayout.NORTH);
        root.add(buildFormPanel(),     BorderLayout.CENTER);
        root.add(buildBottomPanel(),   BorderLayout.SOUTH);

        setContentPane(root);
        getContentPane().setBackground(BG);
    }

    /**
     * Construye el panel superior con el combo de selección de recompensa
     * y el botón de borrado.
     */
    private JPanel buildSelectorPanel() {
        rewardCombo = new JComboBox<>();
        styleCombo(rewardCombo);
        rewardCombo.addItem(NUEVO);
        rewardCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) onRewardSelected();
        });

        deleteBtn = styledButton("Borrar", RED);
        deleteBtn.setVisible(false);
        deleteBtn.addActionListener(e -> onDelete());

        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBackground(BG);
        panel.add(styledLabel("Recompensa:"), BorderLayout.WEST);
        panel.add(rewardCombo,                BorderLayout.CENTER);
        panel.add(deleteBtn,                  BorderLayout.EAST);
        return panel;
    }

    /**
     * Construye el formulario principal con todos los campos.
     * Los campos exclusivos de vídeo empiezan ocultos.
     */
    private JPanel buildFormPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(BG);
        GridBagConstraints gbc = defaultGbc();
        int row = 0;

        // ── Sección Twitch ────────────────────────────────────────────────────
        row = buildTwitchSection(form, gbc, row);

        // Separador visual entre sección Twitch y sección media
        row = addSeparator(form, gbc, row);

        // ── Sección media (común a audio y vídeo) ─────────────────────────────
        row = buildMediaSection(form, gbc, row);

        // ── Sección exclusiva de vídeo ────────────────────────────────────────
        row = buildVideoSection(form, gbc, row);

        // Listener que muestra/oculta los campos de vídeo según el tipo elegido
        typeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) updateVideoFieldsVisibility();
        });

        // Estado inicial: campos de vídeo ocultos (tipo por defecto = audio)
        updateVideoFieldsVisibility();

        return form;
    }

    /**
     * Añade los campos de la sección Twitch al formulario.
     *
     * @return La siguiente fila disponible tras añadir todos los campos.
     */
    private int buildTwitchSection(JPanel form, GridBagConstraints gbc, int row) {
        // Título de la recompensa
        titleField = new JTextField(24);
        styleField(titleField);
        addFormRow(form, gbc, row++, "Título:", titleField);

        // Prompt/descripción
        promptField = new JTextArea(3, 24);
        promptField.setLineWrap(true);
        promptField.setWrapStyleWord(true);
        styleTextArea(promptField);
        JScrollPane promptScroll = new JScrollPane(promptField);
        promptScroll.setBackground(BG2);
        promptScroll.getViewport().setBackground(BG2);
        promptScroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70)));
        addFormRow(form, gbc, row++, "Descripción:", promptScroll);

        // Coste en puntos de canal
        costSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 1_000_000, 50));
        styleSpinner(costSpinner);
        addFormRow(form, gbc, row++, "Coste (puntos):", costSpinner);

        // Color de fondo con selector visual
        addFormRow(form, gbc, row++, "Color fondo:", buildColorPanel());

        // Opciones booleanas
        userInputCheck = styledCheckBox("Requiere texto del usuario");
        addFormRow(form, gbc, row++, "", userInputCheck);

        skipQueueCheck = styledCheckBox("Completar automáticamente");
        addFormRow(form, gbc, row++, "", skipQueueCheck);

        enabledCheck = styledCheckBox("Recompensa activa");
        enabledCheck.setSelected(true);
        addFormRow(form, gbc, row++, "", enabledCheck);

        // Cooldown global con spinner inline
        addFormRow(form, gbc, row++, "", buildCooldownPanel());

        return row;
    }

    /**
     * Construye el panel de selección de color de fondo de la recompensa.
     * Incluye un campo de texto con el hex y un botón para abrir el selector.
     */
    private JPanel buildColorPanel() {
        colorField = new JTextField("#9147FF", 8);
        styleField(colorField);

        JButton pickerBtn = styledButton("🎨", ACCENT);
        pickerBtn.setPreferredSize(new Dimension(36, 26));
        pickerBtn.addActionListener(e -> openColorPicker());

        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setBackground(BG);
        panel.add(colorField, BorderLayout.CENTER);
        panel.add(pickerBtn,  BorderLayout.EAST);
        return panel;
    }

    /**
     * Construye el panel de cooldown global con checkbox y spinner inline.
     * El spinner se habilita/deshabilita según el estado del checkbox.
     */
    private JPanel buildCooldownPanel() {
        cooldownCheck   = styledCheckBox("Cooldown global (seg):");
        cooldownSpinner = new JSpinner(new SpinnerNumberModel(60, 1, 86400, 10));
        styleSpinner(cooldownSpinner);
        cooldownSpinner.setPreferredSize(new Dimension(80, 26));
        cooldownSpinner.setEnabled(false);
        cooldownCheck.addActionListener(e ->
                cooldownSpinner.setEnabled(cooldownCheck.isSelected()));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setBackground(BG);
        panel.add(cooldownCheck);
        panel.add(cooldownSpinner);
        return panel;
    }

    /**
     * Añade los campos de la sección de media (comunes a audio y vídeo).
     *
     * @return La siguiente fila disponible.
     */
    private int buildMediaSection(JPanel form, GridBagConstraints gbc, int row) {
        // Tipo: audio o vídeo
        typeCombo = new JComboBox<>(new String[]{"audio", "video"});
        styleCombo(typeCombo);
        addFormRow(form, gbc, row++, "Tipo:", typeCombo);

        // Ruta de la carpeta con explorador
        addFormRow(form, gbc, row++, "Carpeta:", buildPathPanel());

        // Opción de búsqueda recursiva en subcarpetas
        recursiveCheck = styledCheckBox("Buscar en subcarpetas");
        addFormRow(form, gbc, row++, "", recursiveCheck);

        // Modo de reproducción con etiquetas en español
        playModeCombo = new JComboBox<>(new String[]{"random", "sequential", "random_no_repeat"});
        playModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setBackground(isSelected ? ACCENT.darker() : BG2);
                setForeground(FG);
                setText(switch ((String) value) {
                    case "random"           -> "Aleatorio";
                    case "sequential"       -> "Secuencial";
                    case "random_no_repeat" -> "Aleatorio sin repetir";
                    default                 -> (String) value;
                });
                return this;
            }
        });
        styleCombo(playModeCombo);
        addFormRow(form, gbc, row++, "Reproducción:", playModeCombo);

        // Slider de volumen con etiqueta de porcentaje
        addFormRow(form, gbc, row++, "Volumen:", buildVolumePanel());

        return row;
    }

    /**
     * Construye el panel de ruta con campo de texto y botón de explorador.
     */
    private JPanel buildPathPanel() {
        pathField = new JTextField(20);
        styleField(pathField);

        JButton browseBtn = styledButton("📁", ACCENT);
        browseBtn.setPreferredSize(new Dimension(36, 26));
        browseBtn.addActionListener(e -> browsePath());

        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setBackground(BG);
        panel.add(pathField, BorderLayout.CENTER);
        panel.add(browseBtn, BorderLayout.EAST);
        return panel;
    }

    /**
     * Construye el panel de volumen con slider y etiqueta de porcentaje.
     */
    private JPanel buildVolumePanel() {
        volumeSlider = new JSlider(0, 100, 100);
        volumeSlider.setBackground(BG);
        volumeSlider.setForeground(ACCENT);
        volumeLabel = styledLabel("100%");
        volumeSlider.addChangeListener(e ->
                volumeLabel.setText(volumeSlider.getValue() + "%"));

        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBackground(BG);
        panel.add(volumeSlider, BorderLayout.CENTER);
        panel.add(volumeLabel,  BorderLayout.EAST);
        return panel;
    }

    /**
     * Añade los campos exclusivos de vídeo al formulario.
     * Todos estos campos empiezan ocultos y se muestran cuando tipo = vídeo.
     *
     * @return La siguiente fila disponible.
     */
    private int buildVideoSection(JPanel form, GridBagConstraints gbc, int row) {
        // Tamaño del panel de vídeo
        videoSizePanel = buildVideoSizePanel();
        addFormRow(form, gbc, row++, "Tamaño vídeo:", videoSizePanel);

        // Pantalla de reproducción
        displayPanel = buildDisplayPanel();
        addFormRow(form, gbc, row++, "Reproducir en:", displayPanel);

        // Posición y opción de posición aleatoria
        videoPosRow = buildVideoPosRow();
        addFormRow(form, gbc, row++, "Posición:", videoPosRow);

        // Título de la ventana (para captura por nombre en OBS)
        videoTitleField = new JTextField(24);
        styleField(videoTitleField);
        videoTitleField.setText("OverlayVideo");
        addFormRow(form, gbc, row++, "Título ventana:", videoTitleField);

        // FPS de captura del snapshot
        fpsPanel = buildFpsPanel();
        addFormRow(form, gbc, row++, "FPS captura:", fpsPanel);

        // Chroma key
        chromaPanel = buildChromaPanel();
        addFormRow(form, gbc, row++, "Fondo croma:", chromaPanel);

        return row;
    }

    /** Construye el panel de tamaño del vídeo (ancho y alto). */
    private JPanel buildVideoSizePanel() {
        videoWidthSpinner  = new JSpinner(new SpinnerNumberModel(480, 160, 3840, 10));
        videoHeightSpinner = new JSpinner(new SpinnerNumberModel(270, 90,  2160, 10));
        styleSpinner(videoWidthSpinner);
        styleSpinner(videoHeightSpinner);
        videoWidthSpinner.setPreferredSize(new Dimension(80, 26));
        videoHeightSpinner.setPreferredSize(new Dimension(80, 26));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setBackground(BG);
        panel.add(styledLabel("Ancho:"));
        panel.add(videoWidthSpinner);
        panel.add(styledLabel("  Alto:"));
        panel.add(videoHeightSpinner);
        return panel;
    }

    /**
     * Construye el panel de selección de pantalla de reproducción.
     * Detecta las pantallas disponibles en el sistema.
     */
    private JPanel buildDisplayPanel() {
        displayCombo = new JComboBox<>();
        styleCombo(displayCombo);

        GraphicsDevice[] screens = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();
        displayCombo.addItem("Pantalla principal");
        for (int i = 1; i < screens.length; i++) {
            DisplayMode dm = screens[i].getDisplayMode();
            displayCombo.addItem("Pantalla " + (i + 1)
                    + " (" + dm.getWidth() + "x" + dm.getHeight() + ")");
        }

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);
        panel.add(displayCombo);
        return panel;
    }

    /**
     * Construye la fila de posición del vídeo con spinners X/Y,
     * opción de posición aleatoria y hint de resolución de pantalla.
     * Los spinners se actualizan con los límites de la pantalla seleccionada.
     */
    private JPanel buildVideoPosRow() {
        videoPosXSpinner = new JSpinner(new SpinnerNumberModel(0, -9999, 9999, 10));
        videoPosYSpinner = new JSpinner(new SpinnerNumberModel(0, -9999, 9999, 10));
        styleSpinner(videoPosXSpinner);
        styleSpinner(videoPosYSpinner);
        videoPosXSpinner.setPreferredSize(new Dimension(80, 26));
        videoPosYSpinner.setPreferredSize(new Dimension(80, 26));

        randomPosCheck = styledCheckBox("Posición aleatoria");
        randomPosCheck.addActionListener(e -> {
            boolean random = randomPosCheck.isSelected();
            videoPosXSpinner.setEnabled(!random);
            videoPosYSpinner.setEnabled(!random);
        });

        JLabel resHint = styledLabel("");
        resHint.setForeground(new Color(140, 140, 150));
        resHint.setFont(FONT.deriveFont(11f));

        // Actualizar límites de los spinners y el hint al cambiar pantalla o tamaño
        GraphicsDevice[] screens = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();
        Runnable updateLimits = () -> {
            int idx = displayCombo.getSelectedIndex();
            int targetIdx = (idx > 0 && idx < screens.length) ? idx : 0;
            Rectangle bounds = screens[targetIdx].getDefaultConfiguration().getBounds();
            int maxX = bounds.width  - (int) videoWidthSpinner.getValue();
            int maxY = bounds.height - (int) videoHeightSpinner.getValue();
            ((SpinnerNumberModel) videoPosXSpinner.getModel()).setMaximum(maxX);
            ((SpinnerNumberModel) videoPosYSpinner.getModel()).setMaximum(maxY);
            resHint.setText("  " + bounds.width + "x" + bounds.height);
        };

        displayCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) updateLimits.run();
        });
        videoWidthSpinner.addChangeListener(e  -> updateLimits.run());
        videoHeightSpinner.addChangeListener(e -> updateLimits.run());

        JPanel posPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        posPanel.setBackground(BG);
        posPanel.add(styledLabel("X:"));
        posPanel.add(videoPosXSpinner);
        posPanel.add(styledLabel("  Y:"));
        posPanel.add(videoPosYSpinner);
        posPanel.add(randomPosCheck);

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(BG);
        row.add(posPanel,  BorderLayout.WEST);
        row.add(resHint,   BorderLayout.CENTER);
        return row;
    }

    /** Construye el panel de selección de FPS de captura del vídeo. */
    private JPanel buildFpsPanel() {
        fpsCombo = new JComboBox<>(new Integer[]{30, 60});
        styleCombo(fpsCombo);
        fpsCombo.setSelectedItem(30);

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setBackground(BG);
        panel.add(fpsCombo);
        return panel;
    }

    /**
     * Construye el panel de configuración del chroma key.
     * Incluye activación, preview de color, selector y tolerancia.
     */
    private JPanel buildChromaPanel() {
        chromaCheck = styledCheckBox("Activar croma");

        chromaColorPreview = new JLabel("  ");
        chromaColorPreview.setOpaque(true);
        chromaColorPreview.setBackground(chromaColor);
        chromaColorPreview.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70)));
        chromaColorPreview.setPreferredSize(new Dimension(24, 18));

        JButton chromaColorBtn = styledButton("Color croma", new Color(100, 200, 100));
        chromaColorBtn.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, "Color de croma", chromaColor);
            if (chosen != null) {
                chromaColor = chosen;
                chromaColorPreview.setBackground(chromaColor);
            }
        });

        chromaToleranceSpinner = new JSpinner(new SpinnerNumberModel(40, 0, 255, 5));
        styleSpinner(chromaToleranceSpinner);
        chromaToleranceSpinner.setPreferredSize(new Dimension(60, 26));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setBackground(BG);
        panel.add(chromaCheck);
        panel.add(chromaColorPreview);
        panel.add(chromaColorBtn);
        panel.add(styledLabel("  Tolerancia:"));
        panel.add(chromaToleranceSpinner);
        return panel;
    }

    /** Construye el panel inferior con el botón de guardar. */
    private JPanel buildBottomPanel() {
        saveBtn = styledButton("Guardar", ACCENT);
        saveBtn.addActionListener(e -> onSave());

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.setBackground(BG);
        panel.add(saveBtn);
        return panel;
    }

    /**
     * Muestra u oculta los campos exclusivos de vídeo según el tipo seleccionado.
     * Se llama al cambiar el typeCombo y al limpiar el formulario.
     */
    private void updateVideoFieldsVisibility() {
        boolean isVideo = "video".equals(typeCombo.getSelectedItem());
        videoSizePanel.setVisible(isVideo);
        displayPanel.setVisible(isVideo);
        videoPosRow.setVisible(isVideo);
        videoTitleField.setVisible(isVideo);
        fpsPanel.setVisible(isVideo);
        chromaPanel.setVisible(isVideo);
        pack();
    }

    // ── Carga y selección de recompensas ─────────────────────────────────────

    /**
     * Carga la lista de recompensas desde Twitch en background
     * y repuebla el combo al terminar.
     */
    private void loadRewards() {
        new SwingWorker<List<JSONObject>, Void>() {
            @Override
            protected List<JSONObject> doInBackground() throws Exception {
                return eventSub.refreshRewards();
            }

            @Override
            protected void done() {
                try {
                    rewardsList = get();
                    rewardCombo.removeAllItems();
                    rewardCombo.addItem(NUEVO);
                    for (JSONObject r : rewardsList) {
                        rewardCombo.addItem(r.getString("title"));
                    }
                    rewardCombo.setSelectedIndex(0);
                    deleteBtn.setVisible(false);
                    System.out.println("[RewardsPanel] " + rewardsList.size()
                            + " recompensas cargadas.");
                } catch (Exception e) {
                    System.err.println("[RewardsPanel] Error cargando: " + e.getMessage());
                    ObsAwareDialog.showMessage(RewardsPanel.this,
                            "No se pudieron cargar las recompensas:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Reacciona al cambio de selección en el combo de recompensas.
     * Si se selecciona NUEVO limpia el formulario; si se selecciona una
     * existente carga sus datos.
     */
    private void onRewardSelected() {
        int idx = rewardCombo.getSelectedIndex();
        if (idx <= 0 || rewardsList == null || idx - 1 >= rewardsList.size()) {
            clearForm();
            deleteBtn.setVisible(false);
            return;
        }
        loadRewardIntoForm(rewardsList.get(idx - 1));
        deleteBtn.setVisible(true);
    }

    /**
     * Carga los datos de una recompensa de Twitch y de la config local en el formulario.
     *
     * @param reward JSONObject con los datos de la recompensa de Twitch.
     */
    private void loadRewardIntoForm(JSONObject reward) {
        String rewardId     = reward.getString("id");
        JSONObject localCfg = config.getRewardConfig(rewardId);

        // Campos de Twitch
        titleField.setText(reward.optString("title", ""));
        promptField.setText(reward.optString("prompt", ""));
        costSpinner.setValue(reward.optInt("cost", 100));
        colorField.setText(reward.optString("background_color", "#9147FF"));
        userInputCheck.setSelected(reward.optBoolean("is_user_input_required", false));
        skipQueueCheck.setSelected(reward.optBoolean("should_redemptions_skip_request_queue", false));
        enabledCheck.setSelected(reward.optBoolean("is_enabled", true));

        boolean hasCooldown = reward.optBoolean("is_global_cooldown_enabled", false);
        cooldownCheck.setSelected(hasCooldown);
        cooldownSpinner.setEnabled(hasCooldown);
        cooldownSpinner.setValue(reward.optInt("global_cooldown_seconds", 60));

        // Campos locales (config.json)
        if (localCfg == null) return;

        String type = localCfg.optString("type", "audio");
        typeCombo.setSelectedItem(type);
        pathField.setText(localCfg.optString("path", ""));
        recursiveCheck.setSelected(localCfg.optBoolean("recursive", false));
        playModeCombo.setSelectedItem(localCfg.optString("playMode", "random"));
        volumeSlider.setValue((int)(localCfg.optDouble("volume", 1.0) * 100));

        if ("video".equals(type)) {
            videoWidthSpinner.setValue(localCfg.optInt("width",  480));
            videoHeightSpinner.setValue(localCfg.optInt("height", 270));
            videoTitleField.setText(localCfg.optString("windowTitle", "OverlayVideo"));
            videoPosXSpinner.setValue(localCfg.optInt("posX", 0));
            videoPosYSpinner.setValue(localCfg.optInt("posY", 0));
            fpsCombo.setSelectedItem(localCfg.optInt("fps", 30));

            boolean hasChroma = localCfg.optBoolean("chromaEnabled", false);
            chromaCheck.setSelected(hasChroma);
            chromaColor = new Color(localCfg.optInt("chromaColor", 0x00FF00));
            chromaColorPreview.setBackground(chromaColor);
            chromaToleranceSpinner.setValue(localCfg.optInt("chromaTolerance", 40));

            boolean randomPos = localCfg.optBoolean("randomPos", false);
            randomPosCheck.setSelected(randomPos);
            videoPosXSpinner.setEnabled(!randomPos);
            videoPosYSpinner.setEnabled(!randomPos);

            int displayIndex = localCfg.optInt("displayIndex", 0);
            if (displayIndex < displayCombo.getItemCount()) {
                displayCombo.setSelectedIndex(displayIndex);
            }
        }

        updateVideoFieldsVisibility();
        pack();
    }

    /**
     * Restablece todos los campos del formulario a sus valores por defecto
     * y oculta los campos de vídeo.
     */
    private void clearForm() {
        titleField.setText("");
        promptField.setText("");
        costSpinner.setValue(100);
        colorField.setText("#9147FF");
        userInputCheck.setSelected(false);
        skipQueueCheck.setSelected(false);
        enabledCheck.setSelected(true);
        cooldownCheck.setSelected(false);
        cooldownSpinner.setEnabled(false);
        cooldownSpinner.setValue(60);
        typeCombo.setSelectedIndex(0);
        pathField.setText("");
        recursiveCheck.setSelected(false);
        playModeCombo.setSelectedIndex(0);
        volumeSlider.setValue(100);
        videoWidthSpinner.setValue(480);
        videoHeightSpinner.setValue(270);
        videoPosXSpinner.setValue(0);
        videoPosYSpinner.setValue(0);
        videoPosXSpinner.setEnabled(true);
        videoPosYSpinner.setEnabled(true);
        randomPosCheck.setSelected(false);
        videoTitleField.setText("OverlayVideo");
        fpsCombo.setSelectedItem(30);
        chromaCheck.setSelected(false);
        chromaColor = new Color(0, 255, 0);
        chromaColorPreview.setBackground(chromaColor);
        chromaToleranceSpinner.setValue(40);
        displayCombo.setSelectedIndex(0);
        updateVideoFieldsVisibility();
    }

    // ── Acciones de guardado y borrado ────────────────────────────────────────

    /**
     * Valida el formulario y guarda la recompensa.
     * Si es nueva la crea en Twitch; si existe la actualiza.
     * También guarda la configuración local (tipo, ruta, etc.) en config.json.
     */
    private void onSave() {
        // Recoger valores del formulario
        String  title       = titleField.getText().trim();
        String  prompt      = promptField.getText().trim();
        int     cost        = (int) costSpinner.getValue();
        String  color       = colorField.getText().trim();
        boolean userInput   = userInputCheck.isSelected();
        boolean skipQueue   = skipQueueCheck.isSelected();
        boolean enabled     = enabledCheck.isSelected();
        boolean hasCooldown = cooldownCheck.isSelected();
        int     cooldownSecs = (int) cooldownSpinner.getValue();
        String  type        = (String) typeCombo.getSelectedItem();
        String  path        = pathField.getText().trim();
        boolean recursive   = recursiveCheck.isSelected();
        String  playMode    = (String) playModeCombo.getSelectedItem();
        double  volume      = volumeSlider.getValue() / 100.0;
        int     vidWidth    = (int) videoWidthSpinner.getValue();
        int     vidHeight   = (int) videoHeightSpinner.getValue();
        int     displayIndex = displayCombo.getSelectedIndex();

        // Validaciones
        if (title.isBlank()) {
            ObsAwareDialog.showMessage(this, "El título no puede estar vacío.",
                    "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (path.isBlank()) {
            ObsAwareDialog.showMessage(this, "La carpeta no puede estar vacía.",
                    "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        saveBtn.setEnabled(false);
        saveBtn.setText("Guardando...");

        int     idx       = rewardCombo.getSelectedIndex();
        boolean isNew     = (idx <= 0 || rewardsList == null || idx - 1 >= rewardsList.size());
        String  existingId = isNew ? null : rewardsList.get(idx - 1).getString("id");

        new SwingWorker<JSONObject, Void>() {
            @Override
            protected JSONObject doInBackground() throws Exception {
                return isNew
                        ? eventSub.createReward(title, prompt, cost, userInput,
                                skipQueue, color, hasCooldown, cooldownSecs, enabled)
                        : eventSub.updateReward(existingId, title, prompt, cost, userInput,
                                skipQueue, color, hasCooldown, cooldownSecs, enabled);
            }

            @Override
            protected void done() {
                try {
                    String rewardId = get().getString("id");
                    config.saveTwitchReward(rewardId, buildLocalConfig(
                            type, path, recursive, playMode, volume,
                            vidWidth, vidHeight, displayIndex));
                    System.out.println("[RewardsPanel] Guardado OK: " + rewardId);
                    ObsAwareDialog.showMessage(RewardsPanel.this,
                            "Recompensa guardada correctamente.",
                            "OK", JOptionPane.INFORMATION_MESSAGE);
                    loadRewards();
                } catch (Exception e) {
                    System.err.println("[RewardsPanel] Error: " + e.getMessage());
                    ObsAwareDialog.showMessage(RewardsPanel.this,
                            "Error guardando:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    saveBtn.setEnabled(true);
                    saveBtn.setText("Guardar");
                }
            }
        }.execute();
    }

    /**
     * Construye el JSONObject de configuración local de la recompensa
     * con todos los parámetros de media (tipo, ruta, opciones de reproducción
     * y, si es vídeo, opciones adicionales).
     */
    private JSONObject buildLocalConfig(String type, String path, boolean recursive,
                                         String playMode, double volume,
                                         int vidWidth, int vidHeight, int displayIndex) {
        JSONObject cfg = new JSONObject();
        cfg.put("type",      type);
        cfg.put("path",      path);
        cfg.put("recursive", recursive);
        cfg.put("playMode",  playMode);
        cfg.put("volume",    volume);

        if ("video".equals(type)) {
            cfg.put("width",        vidWidth);
            cfg.put("height",       vidHeight);
            cfg.put("displayIndex", displayIndex);
            cfg.put("randomPos",    randomPosCheck.isSelected());
            cfg.put("posX",         videoPosXSpinner.getValue());
            cfg.put("posY",         videoPosYSpinner.getValue());
            cfg.put("fps",          fpsCombo.getSelectedItem());
            String windowTitle = videoTitleField.getText().trim();
            cfg.put("windowTitle",      windowTitle.isBlank() ? "OverlayVideo" : windowTitle);
            cfg.put("chromaEnabled",    chromaCheck.isSelected());
            cfg.put("chromaColor",      chromaColor.getRGB() & 0xFFFFFF);
            cfg.put("chromaTolerance",  chromaToleranceSpinner.getValue());
        }
        return cfg;
    }

    /**
     * Confirma y borra la recompensa seleccionada tanto en Twitch
     * como en la configuración local.
     */
    private void onDelete() {
        int idx = rewardCombo.getSelectedIndex();
        if (idx <= 0 || rewardsList == null) return;

        JSONObject reward   = rewardsList.get(idx - 1);
        String     rewardId = reward.getString("id");
        String     title    = reward.getString("title");

        int confirm = ObsAwareDialog.showConfirm(this,
                "¿Borrar la recompensa \"" + title + "\"?\nEsta acción no se puede deshacer.",
                "Confirmar borrado", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        deleteBtn.setEnabled(false);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                eventSub.deleteReward(rewardId);
                config.deleteTwitchReward(rewardId);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadRewards();
                    clearForm();
                } catch (Exception e) {
                    ObsAwareDialog.showMessage(RewardsPanel.this,
                            "Error borrando recompensa:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    deleteBtn.setEnabled(true);
                    deleteBtn.setVisible(false);
                }
            }
        }.execute();
    }

    // ── Acciones menores ──────────────────────────────────────────────────────

    /** Abre el selector de color del sistema para el color de fondo de la recompensa. */
    private void openColorPicker() {
        Color initial;
        try {
            initial = Color.decode(colorField.getText().trim());
        } catch (Exception e) {
            initial = new Color(145, 71, 255);
        }
        Color chosen = JColorChooser.showDialog(this, "Color de fondo", initial);
        if (chosen != null) {
            colorField.setText(String.format("#%02X%02X%02X",
                    chosen.getRed(), chosen.getGreen(), chosen.getBlue()));
        }
    }

    /** Abre el explorador de archivos para seleccionar la carpeta de media. */
    private void browsePath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Seleccionar carpeta de medios");
        String current = pathField.getText().trim();
        if (!current.isBlank()) {
            File dir = new File(current);
            if (dir.exists()) chooser.setCurrentDirectory(dir);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ── Helpers de formulario ─────────────────────────────────────────────────

    /**
     * Añade una fila de formulario con etiqueta en la columna 0 y campo en la columna 1.
     */
    private void addFormRow(JPanel panel, GridBagConstraints gbc,
                             int row, String labelText, Component field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(styledLabel(labelText), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(field, gbc);
    }

    /**
     * Añade un separador horizontal ocupando las dos columnas del formulario.
     *
     * @return La siguiente fila disponible.
     */
    private int addSeparator(JPanel panel, GridBagConstraints gbc, int row) {
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(60, 60, 70));
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        panel.add(sep, gbc);
        gbc.gridwidth = 1;
        return row + 1;
    }

    /** Crea un GridBagConstraints con los valores por defecto del formulario. */
    private GridBagConstraints defaultGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets  = new Insets(4, 4, 4, 4);
        gbc.anchor  = GridBagConstraints.WEST;
        gbc.fill    = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    // ── Helpers de estilo ─────────────────────────────────────────────────────

    private JLabel styledLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        l.setFont(FONT);
        return l;
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

    private void styleField(JTextField f) {
        f.setBackground(BG2);
        f.setForeground(FG);
        f.setCaretColor(FG);
        f.setFont(FONT);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(60, 60, 70)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
    }

    private void styleTextArea(JTextArea a) {
        a.setBackground(BG2);
        a.setForeground(FG);
        a.setCaretColor(FG);
        a.setFont(FONT);
        a.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    }

    private void styleSpinner(JSpinner s) {
        s.setBackground(BG2);
        s.setForeground(FG);
        s.setFont(FONT);
        if (s.getEditor() instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setBackground(BG2);
            de.getTextField().setForeground(FG);
            de.getTextField().setCaretColor(FG);
            de.getTextField().setFont(FONT);
        }
    }

    private JCheckBox styledCheckBox(String text) {
        JCheckBox cb = new JCheckBox(text);
        cb.setBackground(BG);
        cb.setForeground(FG);
        cb.setFont(FONT);
        cb.setFocusPainted(false);
        return cb;
    }

    private void styleCombo(JComboBox<?> cb) {
        cb.setBackground(BG2);
        cb.setForeground(FG);
        cb.setFont(FONT);
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setBackground(isSelected ? ACCENT.darker() : BG2);
                setForeground(FG);
                return this;
            }
        });
    }
}