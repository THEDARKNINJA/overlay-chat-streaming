package com.chatoverlaystreaming.overlay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceso y persistencia de la configuración de la aplicación (config.json).
 *
 * El archivo config.json se lee una vez al arrancar y se mantiene en memoria
 * como un árbol de JSONObjects. Cada método save* actualiza los valores en
 * memoria y escribe el árbol completo al disco con indentación de 2 espacios.
 *
 * Estructura del JSON:
 * <pre>
 * {
 *   "twitch":       { channel, channelId, clientId, clientSecret, accessToken, refreshToken, enabled },
 *   "youtube":      { channelId, videoId, apiKeys[], enabled, lastPageToken, lastVideoId },
 *   "panel":        { x, y, width, height, alpha, showBackground, iconSize },
 *   "misc":         { minPollingInterval, showViewerCount, canClickLink, loadBTTV,
 *                     messageTimeoutSeconds, logActivity },
 *   "twitchRewards":{ rewardId: { type, path, volume, ... } }
 * }
 * </pre>
 *
 * Si el archivo no existe, {@link #createDefault()} crea uno con valores vacíos.
 */
public class Config {

    // ── JSON interno ──────────────────────────────────────────────────────────

    private final JSONObject root;
    private final JSONObject twitch;
    private final JSONObject youtube;
    private final JSONObject panel;
    private final JSONObject misc;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Carga el config.json desde el directorio de trabajo de la aplicación.
     *
     * @throws IOException Si el archivo no existe o no se puede leer.
     */
    public Config() throws IOException {
        Path configPath = configPath();
        if (!Files.exists(configPath)) {
            throw new IOException(
                    "No se encontró config.json en: " + configPath.toAbsolutePath() + "\n" +
                    "Usa el botón ⚙ para configurar la aplicación.");
        }
        root    = new JSONObject(Files.readString(configPath));
        twitch  = root.getJSONObject("twitch");
        youtube = root.getJSONObject("youtube");
        panel   = root.getJSONObject("panel");
        misc    = root.getJSONObject("misc");
    }

    // ── Ruta del archivo ──────────────────────────────────────────────────────

    /** Ruta absoluta del config.json en el directorio de trabajo actual. */
    private static Path configPath() {
        return Paths.get(System.getProperty("user.dir")).resolve("config.json");
    }

    /** Escribe el árbol JSON completo al disco con indentación de 2 espacios. */
    private void save() throws IOException {
        Files.writeString(configPath(), root.toString(2));
    }

    // ── Getters de Twitch ─────────────────────────────────────────────────────

    public boolean isTwitchEnabled()       { return twitch.optBoolean("enabled",      true); }
    public String  getTwitchChannel()      { return twitch.optString("channel",         ""); }
    public String  getTwitchChannelId()    { return twitch.optString("channelId",       ""); }
    public String  getTwitchClientId()     { return twitch.optString("clientId",        ""); }
    public String  getTwitchClientSecret() { return twitch.optString("clientSecret",    ""); }
    public String  getTwitchAccessToken()  { return twitch.optString("accessToken",   null); }
    public String  getTwitchRefreshToken() { return twitch.optString("refreshToken",  null); }
    public long    getTwitchSpectatorUpdate() { return twitch.optLong("timeSpectatorUpdate", 60); }

    // ── Getters de YouTube ────────────────────────────────────────────────────

    public boolean isYoutubeEnabled()        { return youtube.optBoolean("enabled",      true); }
    public String  getYoutubeChannelId()     { return youtube.optString("channelId",       ""); }
    public String  getYoutubeVideoId()       { return youtube.optString("videoId",         ""); }
    public String  getYoutubeLastPageToken() { return youtube.optString("lastPageToken", null); }
    public String  getYoutubeLastVideoId()   { return youtube.optString("lastVideoId",   null); }
    public long    getYoutubeSpectatorUpdate() { return youtube.optLong("timeSpectatorUpdate", 60); }

    /**
     * Devuelve la lista de API keys de YouTube configuradas.
     * Soporta tanto el formato nuevo (array "apiKeys") como el antiguo (string "apiKey").
     *
     * @throws RuntimeException Si no hay ninguna API key configurada.
     */
    public List<String> getYoutubeApiKeys() {
        if (youtube.has("apiKeys")) {
            JSONArray keys = youtube.getJSONArray("apiKeys");
            List<String> result = new ArrayList<>();
            for (int i = 0; i < keys.length(); i++) result.add(keys.getString(i));
            return result;
        }
        if (youtube.has("apiKey")) {
            return List.of(youtube.getString("apiKey"));
        }
        throw new RuntimeException("No se encontró ninguna API key de YouTube en el config.");
    }

    // ── Getters de panel ──────────────────────────────────────────────────────

    public int     getPanelX()          { return panel.optInt("x",              10); }
    public int     getPanelY()          { return panel.optInt("y",             150); }
    public int     getPanelWidth()      { return panel.optInt("width",         400); }
    public int     getPanelHeight()     { return panel.optInt("height",        450); }
    public int     getPanelAlpha()      { return panel.optInt("alpha",         200); }
    public boolean getShowBackground()  { return panel.optBoolean("showBackground", true); }
    public int     getIconSize()        { return panel.optInt("iconSize",       13); }

    // ── Getters de misc ───────────────────────────────────────────────────────

    public int     getMinPollingInterval() { return misc.optInt("minPollingInterval",   8); }
    public boolean getShowViewerCount()    { return misc.optBoolean("showViewerCount", true); }
    public boolean getCanClickLink()       { return misc.optBoolean("canClickLink",    true); }
    public boolean getLoadBTTV()           { return misc.optBoolean("loadBTTV",        true); }
    public int     getMessageTimeout()     { return misc.optInt("messageTimeoutSeconds", 0); }
    public boolean getLogActivity()        { return misc.optBoolean("logActivity",    false); }

    // ── Getters de recompensas ────────────────────────────────────────────────

    /**
     * Devuelve el nodo "twitchRewards" del JSON, creándolo si no existe.
     * Es el mapa de rewardId → configuración local (tipo, ruta, volumen, etc.).
     */
    public JSONObject getTwitchRewards() {
        if (!root.has("twitchRewards")) root.put("twitchRewards", new JSONObject());
        return root.getJSONObject("twitchRewards");
    }

    /**
     * Devuelve la configuración local de una recompensa concreta.
     *
     * @param rewardId ID numérico de la recompensa de Twitch.
     * @return JSONObject con los campos locales, o null si no está configurada.
     */
    public JSONObject getRewardConfig(String rewardId) {
        JSONObject rewards = getTwitchRewards();
        return rewards.has(rewardId) ? rewards.getJSONObject(rewardId) : null;
    }

    // ── Métodos de guardado ───────────────────────────────────────────────────

    /** Guarda la posición y tamaño del panel del overlay. */
    public void savePanel(int x, int y, int width, int height) throws IOException {
        panel.put("x", x);
        panel.put("y", y);
        panel.put("width",  width);
        panel.put("height", height);
        save();
    }

    /** Guarda el videoId activo de YouTube. */
    public void saveYouTube(String videoId) throws IOException {
        youtube.put("videoId", videoId);
        save();
    }

    /** Guarda el pageToken y videoId de YouTube para reanudar sin reprocesar histórico. */
    public void saveYoutubePageToken(String videoId, String pageToken) throws IOException {
        youtube.put("lastVideoId",   videoId);
        youtube.put("lastPageToken", pageToken);
        save();
    }

    /** Guarda los tokens OAuth de Twitch. */
    public void saveTwitchTokens(String accessToken, String refreshToken) throws IOException {
        twitch.put("accessToken",  accessToken);
        twitch.put("refreshToken", refreshToken);
        save();
    }

    /**
     * Guarda o actualiza la configuración local de una recompensa.
     * La configuración local incluye tipo, ruta, volumen y opciones de reproducción.
     * Los datos de Twitch (título, coste, etc.) se obtienen en tiempo real de la API.
     *
     * @param rewardId     ID de la recompensa de Twitch.
     * @param rewardConfig JSONObject con la configuración local.
     */
    public void saveTwitchReward(String rewardId, JSONObject rewardConfig) throws IOException {
        getTwitchRewards().put(rewardId, rewardConfig);
        save();
    }

    /** Elimina la configuración local de una recompensa. */
    public void deleteTwitchReward(String rewardId) throws IOException {
        getTwitchRewards().remove(rewardId);
        save();
    }

    /**
     * Guarda toda la configuración editable desde el panel de configuración.
     * Si cambian el clientId o clientSecret, invalida los tokens OAuth guardados
     * para forzar una nueva autorización.
     */
    public void saveAll(
            boolean twitchEnabled,  boolean youtubeEnabled,
            String  twitchChannel,  String  twitchChannelId,
            String  twitchClientId, String  twitchClientSecret,
            String  ytChannelId,    String  ytVideoId, List<String> apiKeys,
            int     alpha,          boolean showBackground, int iconSize,
            int     minPollingInterval, boolean showViewerCount,
            boolean canClickLink,   boolean loadBTTV,
            int     messageTimeoutSeconds, boolean logActivity) throws IOException {

        twitch.put("enabled",   twitchEnabled);
        twitch.put("channel",   twitchChannel);
        twitch.put("channelId", twitchChannelId);

        // Si cambian las credenciales, invalidar tokens para forzar re-autorización
        boolean credentialsChanged = !getTwitchClientId().equals(twitchClientId)
                || !getTwitchClientSecret().equals(twitchClientSecret);
        twitch.put("clientId",     twitchClientId);
        twitch.put("clientSecret", twitchClientSecret);
        if (credentialsChanged) {
            twitch.put("accessToken",  "");
            twitch.put("refreshToken", "");
            System.out.println("[Config] Credenciales cambiadas — tokens OAuth invalidados.");
        }

        youtube.put("enabled",   youtubeEnabled);
        youtube.put("channelId", ytChannelId);
        youtube.put("videoId",   ytVideoId);
        JSONArray keys = new JSONArray();
        apiKeys.forEach(keys::put);
        youtube.put("apiKeys", keys);

        panel.put("alpha",          alpha);
        panel.put("showBackground", showBackground);
        panel.put("iconSize",       iconSize);

        misc.put("minPollingInterval",    minPollingInterval);
        misc.put("showViewerCount",       showViewerCount);
        misc.put("canClickLink",          canClickLink);
        misc.put("loadBTTV",              loadBTTV);
        misc.put("messageTimeoutSeconds", messageTimeoutSeconds);
        misc.put("logActivity",           logActivity);

        save();
    }

    // ── Creación de config por defecto ────────────────────────────────────────

    /**
     * Crea un config.json con valores vacíos si no existe, y devuelve un Config cargado.
     * Usado cuando la aplicación arranca por primera vez sin configuración.
     *
     * @throws IOException Si no se puede crear o leer el archivo.
     */
    public static Config createDefault() throws IOException {
        Path configPath = configPath();
        if (!Files.exists(configPath)) {
            Files.writeString(configPath, DEFAULT_CONFIG);
            System.out.println("[Config] Creado config.json por defecto en: "
                    + configPath.toAbsolutePath());
        }
        return new Config();
    }

    /** Contenido del config.json por defecto con todos los campos vacíos. */
    private static final String DEFAULT_CONFIG = """
            {
              "twitch": {
                "enabled": true,
                "channel": "",
                "channelId": "",
                "clientId": "",
                "clientSecret": "",
                "accessToken": "",
                "refreshToken": ""
              },
              "youtube": {
                "enabled": true,
                "apiKeys": [],
                "videoId": "",
                "channelId": ""
              },
              "panel": {
                "x": 100, "y": 100,
                "width": 380, "height": 450,
                "alpha": 200,
                "showBackground": true,
                "iconSize": 16
              },
              "misc": {
                "showViewerCount": true,
                "minPollingInterval": 10000,
                "loadBTTV": true,
                "canClickLink": true,
                "messageTimeoutSeconds": 0,
                "logActivity": false
              },
              "twitchRewards": {}
            }
            """;
}