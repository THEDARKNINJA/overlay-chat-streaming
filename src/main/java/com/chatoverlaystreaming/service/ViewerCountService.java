package com.chatoverlaystreaming.service;

import com.chatoverlaystreaming.overlay.Config;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * Consulta periódicamente el número de espectadores activos en Twitch y YouTube,
 * e invoca callbacks para que el overlay pueda actualizar el contador en pantalla.
 *
 * <p>Cada plataforma corre en su propio hilo daemon con un intervalo configurable
 * (mínimo 30 segundos para no saturar las APIs).
 *
 * <p>Notas de implementación:
 * <ul>
 *   <li>Twitch usa un token de aplicación (client credentials) que se cachea y
 *       se renueva automáticamente si la API devuelve un 401.</li>
 *   <li>YouTube rota entre las API keys configuradas cuando una devuelve error,
 *       manteniendo su propio índice independiente del de {@code YouTubeChatReader}.
 *       Si todas las keys fallan, devuelve {@code "?"} sin loggear (el log ya lo
 *       genera {@code YouTubeChatReader}).</li>
 *   <li>El videoId activo de YouTube se recibe desde fuera mediante
 *       {@link #setCurrentVideoId(String)}, ya que es {@code YouTubeChatReader}
 *       quien lo resuelve.</li>
 * </ul>
 */
public class ViewerCountService {

    // ── Constantes ────────────────────────────────────────────────────────────

    /** Intervalo mínimo de actualización para cualquier plataforma. */
    private static final long MIN_INTERVAL_MS = 30_000;

    // ── Configuración de intervalos ───────────────────────────────────────────

    /** Milisegundos entre consultas al contador de espectadores de Twitch. */
    private final long twitchInterval;

    /** Milisegundos entre consultas al contador de espectadores de YouTube. */
    private final long youtubeInterval;

    // ── Dependencias ──────────────────────────────────────────────────────────

    /** Configuración de la aplicación (tokens, claves, channel, etc.). */
    private final Config config;

    /**
     * Callback invocado cada vez que se obtiene un nuevo valor de espectadores de Twitch.
     * Recibe el valor formateado (p. ej. {@code "1.2K"}) o {@code "?"} si no hay datos.
     */
    private final Consumer<String> onTwitchUpdate;

    /**
     * Callback invocado cada vez que se obtiene un nuevo valor de espectadores de YouTube.
     * Recibe el valor formateado (p. ej. {@code "3.4K"}) o {@code "?"} si no hay datos.
     */
    private final Consumer<String> onYoutubeUpdate;

    // ── Estado de Twitch ──────────────────────────────────────────────────────

    /**
     * Token de aplicación de Twitch cacheado entre peticiones.
     * Se renueva automáticamente si la API devuelve un 401.
     */
    private String cachedTwitchToken = null;

    // ── Estado de YouTube ─────────────────────────────────────────────────────

    /**
     * VideoId del directo activo, notificado por {@code YouTubeChatReader}.
     * Si es {@code null}, se lee el videoId del config como fallback.
     */
    private volatile String currentVideoId = null;

    /**
     * Índice de la API key de YouTube actualmente en uso.
     * Se avanza cuando una key falla, de forma independiente al índice de
     * {@code YouTubeChatReader}.
     */
    private int youtubeKeyIndex = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea el servicio de conteo de espectadores.
     *
     * @param config          Configuración de la aplicación.
     * @param onTwitchUpdate  Callback para actualizaciones del contador de Twitch.
     * @param onYoutubeUpdate Callback para actualizaciones del contador de YouTube.
     */
    public ViewerCountService(Config config,
                              Consumer<String> onTwitchUpdate,
                              Consumer<String> onYoutubeUpdate) {
        this.config          = config;
        this.onTwitchUpdate  = onTwitchUpdate;
        this.onYoutubeUpdate = onYoutubeUpdate;
        this.twitchInterval  = Math.max(MIN_INTERVAL_MS, config.getTwitchSpectatorUpdate()  * 1000L);
        this.youtubeInterval = Math.max(MIN_INTERVAL_MS, config.getYoutubeSpectatorUpdate() * 1000L);
    }

    // ── Arranque ──────────────────────────────────────────────────────────────

    /**
     * Arranca los hilos de actualización para ambas plataformas.
     * Puede llamarse una sola vez al iniciar la aplicación.
     */
    public void start() {
        startTwitch();
        startYoutube();
    }

    /**
     * Arranca el hilo daemon que actualiza periódicamente el contador de Twitch.
     * El hilo invoca {@link #onTwitchUpdate} con el valor obtenido o {@code "?"} si falla.
     */
    public void startTwitch() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String count = fetchTwitchViewers();
                    onTwitchUpdate.accept(count != null ? count : "?");
                } catch (Exception e) {
                    System.err.println("[Viewers] Error Twitch: " + e.getMessage());
                    onTwitchUpdate.accept("?");
                }
                sleep(twitchInterval);
            }
        }, "twitch-viewers");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Arranca el hilo daemon que actualiza periódicamente el contador de YouTube.
     * El hilo invoca {@link #onYoutubeUpdate} con el valor obtenido o {@code "?"} si falla.
     */
    public void startYoutube() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String count = fetchYoutubeViewers();
                    onYoutubeUpdate.accept(count != null ? count : "?");
                } catch (Exception e) {
                    System.err.println("[Viewers] Error YouTube: " + e.getMessage());
                    onYoutubeUpdate.accept("?");
                }
                sleep(youtubeInterval);
            }
        }, "youtube-viewers");
        t.setDaemon(true);
        t.start();
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Notifica al servicio cuál es el videoId del directo activo de YouTube.
     * Debe ser llamado por {@code YouTubeChatReader} cada vez que resuelva un directo nuevo.
     *
     * @param videoId VideoId del directo activo.
     */
    public void setCurrentVideoId(String videoId) {
        this.currentVideoId = videoId;
    }

    // ── Consulta de espectadores ──────────────────────────────────────────────

    /**
     * Consulta el número de espectadores actuales del canal de Twitch.
     *
     * <p>Usa el token de aplicación cacheado en {@link #cachedTwitchToken},
     * renovándolo automáticamente si la API devuelve un 401.
     *
     * @return Número de espectadores formateado, o {@code "?"} si el canal está offline.
     * @throws Exception Si la petición HTTP falla.
     */
    private String fetchTwitchViewers() throws Exception {
        if (cachedTwitchToken == null) {
            cachedTwitchToken = fetchTwitchToken();
        }

        String url = "https://api.twitch.tv/helix/streams?user_login=" +
                     config.getTwitchChannel();

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Client-Id",     config.getTwitchClientId());
        conn.setRequestProperty("Authorization", "Bearer " + cachedTwitchToken);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);

        if (conn.getResponseCode() == 401) {
            // Token expirado: renovar y reintentar una sola vez
            cachedTwitchToken = fetchTwitchToken();
            return fetchTwitchViewers();
        }

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            org.json.JSONArray data = response.optJSONArray("data");
            if (data == null || data.length() == 0) return "?"; // canal offline
            return formatViewers(data.getJSONObject(0).getInt("viewer_count"));
        }
    }

    /**
     * Obtiene un token de aplicación de Twitch mediante el flujo client credentials.
     *
     * @return Access token de Twitch.
     * @throws Exception Si la petición HTTP falla o la respuesta no contiene el token.
     */
    private String fetchTwitchToken() throws Exception {
        String url  = "https://id.twitch.tv/oauth2/token";
        String body = "client_id="     + config.getTwitchClientId().trim() +
                      "&client_secret=" + config.getTwitchClientSecret().trim() +
                      "&grant_type=client_credentials";

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            return response.getString("access_token");
        }
    }

    /**
     * Consulta el número de espectadores del directo activo de YouTube.
     *
     * <p>Rota entre las API keys configuradas si una falla, manteniendo
     * {@link #youtubeKeyIndex} como índice propio. Si todas las keys fallan,
     * devuelve {@code "?"} sin loggear (el log ya lo genera {@code YouTubeChatReader}).
     *
     * @return Número de espectadores formateado, o {@code "?"} si no hay datos o todas las keys fallan.
     * @throws Exception Si la petición HTTP falla por un motivo distinto a la quota.
     */
    private String fetchYoutubeViewers() throws Exception {
        String videoId = currentVideoId != null ? currentVideoId : config.getYoutubeVideoId();
        if (videoId == null || videoId.isBlank()) return "?";

        List<String> apiKeys = config.getYoutubeApiKeys();

        // Intentar con cada key empezando por la activa, rotando si falla
        for (int attempts = 0; attempts < apiKeys.size(); attempts++) {
            String apiKey = apiKeys.get(youtubeKeyIndex);
            String url = "https://www.googleapis.com/youtube/v3/videos" +
                         "?part=liveStreamingDetails" +
                         "&id=" + videoId +
                         "&key=" + apiKey;

            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 403) {
                    // Key con quota excedida: rotar a la siguiente
                    youtubeKeyIndex = (youtubeKeyIndex + 1) % apiKeys.size();
                    continue;
                }

                try (InputStream is = conn.getInputStream()) {
                    JSONObject response = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                    org.json.JSONArray items = response.optJSONArray("items");
                    if (items == null || items.length() == 0) return "?";

                    JSONObject details = items.getJSONObject(0).optJSONObject("liveStreamingDetails");
                    if (details == null) return "?";

                    String viewers = details.optString("concurrentViewers", null);
                    return viewers != null ? formatViewers(Integer.parseInt(viewers)) : "?";
                }

            } catch (Exception e) {
                // Error de red u otro: rotar y reintentar
                youtubeKeyIndex = (youtubeKeyIndex + 1) % apiKeys.size();
            }
        }

        // Todas las keys fallaron
        return "?";
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /**
     * Formatea un número de espectadores en una cadena legible.
     * Ejemplos: {@code 1234 → "1.2K"}, {@code 1500000 → "1.5M"}, {@code 800 → "800"}.
     *
     * @param count Número de espectadores.
     * @return Cadena formateada.
     */
    private String formatViewers(int count) {
        if (count >= 1_000_000) return String.format("%.1fM", count / 1_000_000.0);
        if (count >= 1_000)     return String.format("%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }

    /**
     * Pausa el hilo actual durante el tiempo indicado.
     * Restaura el flag de interrupción si el hilo es interrumpido durante la espera.
     *
     * @param ms Milisegundos a dormir.
     */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}