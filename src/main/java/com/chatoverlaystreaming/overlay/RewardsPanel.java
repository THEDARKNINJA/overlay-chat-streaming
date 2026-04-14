package com.chatoverlaystreaming.overlay;

import com.chatoverlaystreaming.readers.TwitchEventSub;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.nio.file.*;
import java.util.List;

public class RewardsPanel extends JDialog {

    private static final String NUEVO = "— NUEVO —";
    private static final Color BG      = new Color(14, 14, 16);
    private static final Color BG2     = new Color(24, 24, 28);
    private static final Color ACCENT  = new Color(200, 140, 255);
    private static final Color FG      = new Color(240, 240, 240);
    private static final Color RED     = new Color(255, 80, 80);
    private static final Font  FONT    = new Font("Segoe UI", Font.PLAIN, 13);

    private final TwitchEventSub eventSub;
    private final Config config;

    // Combo de recompensas
    private JComboBox<String> rewardCombo;
    private List<JSONObject>  rewardsList;

    // Campos del formulario
    private JTextField  titleField;
    private JTextArea   promptField;
    private JSpinner    costSpinner;
    private JCheckBox   userInputCheck;
    private JCheckBox   skipQueueCheck;
    private JCheckBox   enabledCheck;
    private JTextField  colorField;
    private JCheckBox   cooldownCheck;
    private JSpinner    cooldownSpinner;
    private JComboBox<String> typeCombo;
    private JTextField  folderField;
    

    // Botones
    private JButton saveBtn;
    private JButton deleteBtn;

    public RewardsPanel(Window owner, TwitchEventSub eventSub, Config config) {
        super(owner, "Gestión de recompensas", ModalityType.MODELESS);
        this.eventSub = eventSub;
        this.config   = config;

        setUndecorated(false);
        setBackground(BG);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        buildUI();
        pack();
        setLocationRelativeTo(owner);

        // Excluir de la captura de OBS igual que el overlay principal
        excludeFromCapture();

        // Cargar recompensas en background
        loadRewards();
    }

    private void excludeFromCapture() {
        // Necesita que la ventana sea visible primero
        SwingUtilities.invokeLater(() -> {
            try {
                com.sun.jna.Pointer pointer =
                        com.sun.jna.Native.getComponentPointer(this);
                if (pointer != null) {
                    com.sun.jna.platform.win32.WinDef.HWND hwnd =
                            new com.sun.jna.platform.win32.WinDef.HWND(pointer);
                    WindowClickThrough.User32Extra.INSTANCE
                            .SetWindowDisplayAffinity(hwnd, 0x00000011);
                }
            } catch (Exception e) {
                System.err.println("[RewardsPanel] No se pudo excluir de captura: "
                        + e.getMessage());
            }
        });
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        // ── Selector de recompensa ──────────────────────────────────────────
        JPanel topPanel = new JPanel(new BorderLayout(6, 0));
        topPanel.setBackground(BG);

        JLabel comboLabel = styledLabel("Recompensa:");
        rewardCombo = new JComboBox<>();
        styleCombo(rewardCombo);
        rewardCombo.addItem(NUEVO);

        deleteBtn = styledButton("Borrar", RED);
        deleteBtn.setVisible(false);
        deleteBtn.addActionListener(e -> onDelete());

        topPanel.add(comboLabel, BorderLayout.WEST);
        topPanel.add(rewardCombo, BorderLayout.CENTER);
        topPanel.add(deleteBtn,   BorderLayout.EAST);

        rewardCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                onRewardSelected();
            }
        });

        root.add(topPanel, BorderLayout.NORTH);

        // ── Formulario ──────────────────────────────────────────────────────
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets  = new Insets(4, 4, 4, 4);
        gbc.anchor  = GridBagConstraints.WEST;
        gbc.fill    = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Título
        titleField = new JTextField(24);
        styleField(titleField);
        addFormRow(form, gbc, row++, "Título:", titleField);

        // Prompt
        promptField = new JTextArea(3, 24);
        promptField.setLineWrap(true);
        promptField.setWrapStyleWord(true);
        styleTextArea(promptField);
        JScrollPane promptScroll = new JScrollPane(promptField);
        promptScroll.setBackground(BG2);
        promptScroll.getViewport().setBackground(BG2);
        promptScroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70)));
        addFormRow(form, gbc, row++, "Descripción:", promptScroll);

        // Coste
        costSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 1000000, 50));
        styleSpinner(costSpinner);
        addFormRow(form, gbc, row++, "Coste (puntos):", costSpinner);

        // Color de fondo
        JPanel colorPanel = new JPanel(new BorderLayout(4, 0));
        colorPanel.setBackground(BG);
        colorField = new JTextField("#9147FF", 8);
        styleField(colorField);
        JButton colorPickerBtn = styledButton("🎨", ACCENT);
        colorPickerBtn.setPreferredSize(new Dimension(36, 26));
        colorPickerBtn.addActionListener(e -> openColorPicker());
        colorPanel.add(colorField,      BorderLayout.CENTER);
        colorPanel.add(colorPickerBtn,  BorderLayout.EAST);
        addFormRow(form, gbc, row++, "Color fondo:", colorPanel);

        // Checkboxes
        userInputCheck = styledCheckBox("Requiere texto del usuario");
        addFormRow(form, gbc, row++, "", userInputCheck);

        skipQueueCheck = styledCheckBox("Completar automáticamente");
        addFormRow(form, gbc, row++, "", skipQueueCheck);

        enabledCheck = styledCheckBox("Recompensa activa");
        enabledCheck.setSelected(true); // por defecto activa
        addFormRow(form, gbc, row++, "", enabledCheck);

        // Cooldown
        JPanel cooldownPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cooldownPanel.setBackground(BG);
        cooldownCheck = styledCheckBox("Cooldown global (seg):");
        cooldownSpinner = new JSpinner(new SpinnerNumberModel(60, 1, 86400, 10));
        styleSpinner(cooldownSpinner);
        cooldownSpinner.setPreferredSize(new Dimension(80, 26));
        cooldownCheck.addActionListener(e ->
                cooldownSpinner.setEnabled(cooldownCheck.isSelected()));
        cooldownSpinner.setEnabled(false);
        cooldownPanel.add(cooldownCheck);
        cooldownPanel.add(cooldownSpinner);
        addFormRow(form, gbc, row++, "", cooldownPanel);

        // Separador
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(60, 60, 70));
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        form.add(sep, gbc);
        gbc.gridwidth = 1;

        // Tipo de media
        typeCombo = new JComboBox<>(new String[]{"audio", "video"});
        styleCombo(typeCombo);
        addFormRow(form, gbc, row++, "Tipo:", typeCombo);

        // Carpeta
        folderField = new JTextField(24);
        styleField(folderField);
        JLabel folderHint = styledLabel("  (se crea en rewards/)");
        folderHint.setForeground(new Color(140, 140, 150));
        folderHint.setFont(FONT.deriveFont(11f));
        JPanel folderPanel = new JPanel(new BorderLayout());
        folderPanel.setBackground(BG);
        folderPanel.add(folderField, BorderLayout.CENTER);
        folderPanel.add(folderHint,  BorderLayout.EAST);
        addFormRow(form, gbc, row++, "Carpeta:", folderPanel);

        root.add(form, BorderLayout.CENTER);

        // ── Botón guardar ───────────────────────────────────────────────────
        saveBtn = styledButton("Guardar", ACCENT);
        saveBtn.addActionListener(e -> onSave());
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBackground(BG);
        bottomPanel.add(saveBtn);
        root.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(root);
        getContentPane().setBackground(BG);
    }

    private void loadRewards() {
        SwingWorker<List<JSONObject>, Void> worker = new SwingWorker<>() {
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
                    rewardCombo.setSelectedIndex(0); // forzar selección de NUEVO
                    deleteBtn.setVisible(false);
                    System.out.println("[RewardsPanel] Lista cargada: " + rewardsList.size() + " recompensas");
                } catch (Exception e) {
                    System.err.println("[RewardsPanel] Error cargando: " + e.getMessage());
                    JOptionPane.showMessageDialog(RewardsPanel.this,
                            "No se pudieron cargar las recompensas:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void onRewardSelected() {
        int idx = rewardCombo.getSelectedIndex();

        if (idx <= 0 || rewardsList == null || idx - 1 >= rewardsList.size()) {
            // NUEVO seleccionado
            clearForm();
            deleteBtn.setVisible(false);
            return;
        }

        // Cargar datos de la recompensa seleccionada
        JSONObject reward = rewardsList.get(idx - 1);
        String rewardId   = reward.getString("id");

        titleField.setText(reward.optString("title", ""));
        promptField.setText(reward.optString("prompt", ""));
        costSpinner.setValue(reward.optInt("cost", 100));
        colorField.setText(reward.optString("background_color", "#9147FF"));
        userInputCheck.setSelected(reward.optBoolean("is_user_input_required", false));
        skipQueueCheck.setSelected(
                reward.optBoolean("should_redemptions_skip_request_queue", false));
        enabledCheck.setSelected(reward.optBoolean("is_enabled", true));

        boolean hasCooldown = reward.optBoolean("is_global_cooldown_enabled", false);
        cooldownCheck.setSelected(hasCooldown);
        cooldownSpinner.setEnabled(hasCooldown);
        cooldownSpinner.setValue(reward.optInt("global_cooldown_seconds", 60));

        // Datos locales del config
        String type   = config.getRewardType(rewardId);
        String folder = config.getRewardFolder(rewardId);
        typeCombo.setSelectedItem(type != null ? type : "audio");
        folderField.setText(folder != null ? folder : "");

        deleteBtn.setVisible(true);
    }

    private void clearForm() {
        titleField.setText("");
        promptField.setText("");
        costSpinner.setValue(100);
        colorField.setText("#9147FF");
        userInputCheck.setSelected(false);
        enabledCheck.setSelected(true);
        skipQueueCheck.setSelected(false);
        cooldownCheck.setSelected(false);
        cooldownSpinner.setEnabled(false);
        cooldownSpinner.setValue(60);
        typeCombo.setSelectedIndex(0);
        folderField.setText("");
    }

    private void onSave() {
        String title  = titleField.getText().trim();
        String prompt = promptField.getText().trim();
        int cost      = (int) costSpinner.getValue();
        String color  = colorField.getText().trim();
        boolean userInput  = userInputCheck.isSelected();
        boolean enabled = enabledCheck.isSelected();
        boolean skipQueue  = skipQueueCheck.isSelected();
        boolean hasCooldown = cooldownCheck.isSelected();
        int cooldownSecs   = (int) cooldownSpinner.getValue();
        String type   = (String) typeCombo.getSelectedItem();
        String folder = folderField.getText().trim();

        if (title.isBlank()) {
            JOptionPane.showMessageDialog(this, "El título no puede estar vacío.",
                    "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (folder.isBlank()) {
            JOptionPane.showMessageDialog(this, "El nombre de carpeta no puede estar vacío.",
                    "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        saveBtn.setEnabled(false);
        saveBtn.setText("Guardando...");

        int idx = rewardCombo.getSelectedIndex();
        boolean isNew = (idx <= 0 || rewardsList == null || idx - 1 >= rewardsList.size());

        SwingWorker<JSONObject, Void> worker = new SwingWorker<>() {
            @Override
            protected JSONObject doInBackground() throws Exception {
                // Crear directorio si no existe
                Path dir = Paths.get("rewards", folder);
                if (!Files.exists(dir)) Files.createDirectories(dir);

                if (isNew) {
                    return eventSub.createReward(title, prompt, cost,
                            userInput, skipQueue, color, hasCooldown, cooldownSecs, enabled);
                } else {
                    String rewardId = rewardsList.get(idx - 1).getString("id");
                    return eventSub.updateReward(rewardId, title, prompt, cost,
                            userInput, skipQueue, color, hasCooldown, cooldownSecs, enabled);
                }
            }

            @Override
            protected void done() {
                try {
                    JSONObject result = get();
                    String rewardId = result.getString("id");
                    config.saveTwitchReward(rewardId, type, folder);
                    System.out.println("[RewardsPanel] Guardado OK: " + rewardId);
                    JOptionPane.showMessageDialog(RewardsPanel.this,
                            "Recompensa guardada correctamente.",
                            "OK", JOptionPane.INFORMATION_MESSAGE);
                    loadRewards();
                } catch (Exception e) {
                    System.err.println("[RewardsPanel] Error en done(): " + e.getMessage());
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(RewardsPanel.this,
                            "Error guardando recompensa:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    saveBtn.setEnabled(true);
                    saveBtn.setText("Guardar");
                }
            }
        };
        worker.execute();
    }

    private void onDelete() {
        int idx = rewardCombo.getSelectedIndex();
        if (idx <= 0 || rewardsList == null) return;

        JSONObject reward = rewardsList.get(idx - 1);
        String rewardId   = reward.getString("id");
        String title      = reward.getString("title");

        int confirm = JOptionPane.showConfirmDialog(this,
                "¿Borrar la recompensa \"" + title + "\"?\nEsta acción no se puede deshacer.",
                "Confirmar borrado", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) return;

        deleteBtn.setEnabled(false);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
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
                    JOptionPane.showMessageDialog(RewardsPanel.this,
                            "Error borrando recompensa:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    deleteBtn.setEnabled(true);
                    deleteBtn.setVisible(false);
                }
            }
        };
        worker.execute();
    }

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

    // ── Helpers de estilo ────────────────────────────────────────────────────

    private void addFormRow(JPanel panel, GridBagConstraints gbc,
                             int row, String labelText, Component field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(styledLabel(labelText), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(field, gbc);
    }

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
        JComponent editor = s.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
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
            public Component getListCellRendererComponent(JList<?> list,
                    Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index,
                        isSelected, cellHasFocus);
                setBackground(isSelected ? ACCENT.darker() : BG2);
                setForeground(FG);
                return this;
            }
        });
    }
}