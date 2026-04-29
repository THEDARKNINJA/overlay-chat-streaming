package com.chatoverlaystreaming.emotes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché de badges de Twitch para un canal.
 *
 * Carga en el constructor los badges globales de Twitch y los específicos
 * del canal indicado usando la Helix API. El mapa resultante asocia la clave
 * IRC del badge (ej. "moderator/1") con la URL de su imagen en 1x.
 *
 * Autenticación:
 *   Usa un app token (Client Credentials) obtenido con clientId y clientSecret.
 *   El token se cachea en memoria para no solicitarlo en cada petición.
 *   No requiere token de usuario.
 *
 * Formato de la cabecera IRC de Twitch:
 *   {@code badges=moderator/1,subscriber/12,vip/1}
 *   Cada entrada es "set_id/version_id", coincidiendo con las claves del mapa.
 */
public class TwitchBadgeCache {

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final String GLOBAL_URL  =
            "https://api.twitch.tv/helix/chat/badges/global";
    private static final String CHANNEL_URL =
            "https://api.twitch.tv/helix/chat/badges?broadcaster_id=%s";
    private static final String TOKEN_URL   =
            "https://id.twitch.tv/oauth2/token";

    // ── Estado ────────────────────────────────────────────────────────────────

    /**
     * Mapa de clave IRC de badge → URL de imagen 1x.
     * Clave ejemplo: "moderator/1", "subscriber/12".
     */
    private final Map<String, String> badgeMap = new ConcurrentHashMap<>();

    private final String clientId;
    private final String clientSecret;

    /** App token cacheado para no solicitarlo en cada petición. */
    private String cachedToken = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Carga los badges globales y los del canal indicado.
     *
     * @param clientId     Client ID de la aplicación registrada en dev.twitch.tv.
     * @param clientSecret Client Secret de la aplicación.
     * @param channelId    ID numérico del canal, o null para cargar solo los globales.
     */
    public TwitchBadgeCache(String clientId, String clientSecret, String channelId) {
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        loadGlobal();
        if (channelId != null && !channelId.isBlank()) {
            loadChannel(channelId);
        }
    }

    // ── Carga de badges ───────────────────────────────────────────────────────

    /** Carga los badges globales de Twitch desde la Helix API. */
    private void loadGlobal() {
        try {
            parseBadges(fetchObject(GLOBAL_URL));
            System.out.println("[Badges] " + badgeMap.size() + " badges globales cargados.");
        } catch (Exception e) {
            System.err.println("[Badges] Error cargando globales: " + e.getMessage());
        }
    }

    /**
     * Carga los badges específicos del canal desde la Helix API.
     * Los badges de canal sobreescriben los globales si tienen el mismo set_id,
     * lo que permite versiones personalizadas de badges estándar.
     *
     * @param channelId ID numérico del canal.
     */
    private void loadChannel(String channelId) {
        try {
            parseBadges(fetchObject(CHANNEL_URL.formatted(channelId)));
        } catch (Exception e) {
            System.err.println("[Badges] Error cargando badges del canal: " + e.getMessage());
        }
    }

    /**
     * Parsea la respuesta de la Helix API y añade los badges al mapa.
     * Cada badge set tiene múltiples versiones (ej. subscriber tiene 1, 3, 6, 12 meses).
     * La clave del mapa es "set_id/version_id" para coincidir con el formato IRC.
     */
    private void parseBadges(JSONObject response) {
        JSONArray data = response.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject badgeSet  = data.getJSONObject(i);
            String     setId     = badgeSet.getString("set_id");
            JSONArray  versions  = badgeSet.getJSONArray("versions");

            for (int j = 0; j < versions.length(); j++) {
                JSONObject version   = versions.getJSONObject(j);
                String     versionId = version.getString("id");
                String     imageUrl  = version.getString("image_url_1x");
                badgeMap.put(setId + "/" + versionId, imageUrl);
            }
        }
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Traduce la cabecera IRC de badges a una lista de URLs de imagen.
     *
     * @param badgesHeader Valor de la cabecera @badges del IRC de Twitch,
     *                     ej. "moderator/1,subscriber/12,vip/1". Puede ser null.
     * @return Lista de URLs de imagen en el mismo orden que los badges de la cabecera.
     *         Vacía si la cabecera es null, vacía o no hay badges reconocidos.
     */
    public List<String> getBadgeUrls(String badgesHeader) {
        List<String> urls = new ArrayList<>();
        if (badgesHeader == null || badgesHeader.isBlank()) return urls;

        for (String badge : badgesHeader.split(",")) {
            String url = badgeMap.get(badge.trim());
            if (url != null) urls.add(url);
        }
        return urls;
    }

    // ── Utilidades HTTP ───────────────────────────────────────────────────────

    /**
     * Realiza una petición GET autenticada a la Helix API de Twitch.
     *
     * @param url Endpoint de la Helix API.
     * @return JSONObject con la respuesta.
     * @throws Exception Si la conexión falla o el servidor devuelve error.
     */
    private JSONObject fetchObject(String url) throws Exception {
        HttpURLConnection conn =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Client-Id", clientId);
        conn.setRequestProperty("Authorization", "Bearer " + getAppToken());
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (InputStream is = conn.getInputStream()) {
            return new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Obtiene un app token de Twitch usando el flujo Client Credentials.
     * El token se cachea en memoria — solo se solicita una vez por sesión.
     *
     * @return App token válido para la Helix API.
     * @throws Exception Si Twitch rechaza las credenciales o hay error de red.
     */
    private String getAppToken() throws Exception {
        if (cachedToken != null) return cachedToken;

        String body = "client_id="     + clientId.trim() +
                      "&client_secret=" + clientSecret.trim() +
                      "&grant_type=client_credentials";

        HttpURLConnection conn =
                (HttpURLConnection) URI.create(TOKEN_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        int statusCode = conn.getResponseCode();
        if (statusCode != 200) {
            try (InputStream err = conn.getErrorStream()) {
                String errorBody = err != null
                        ? new String(err.readAllBytes(), StandardCharsets.UTF_8)
                        : "sin detalle";
                throw new Exception("HTTP " + statusCode + ": " + errorBody);
            }
        }

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(
                    new String(is.readAllBytes(), StandardCharsets.UTF_8));
            cachedToken = response.getString("access_token");
            return cachedToken;
        }
    }
}