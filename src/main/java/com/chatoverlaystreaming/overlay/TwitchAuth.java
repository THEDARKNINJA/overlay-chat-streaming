package com.chatoverlaystreaming.overlay;

import org.json.JSONObject;

import javax.swing.SwingUtilities;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gestiona la autenticación OAuth 2.0 con PKCE para Twitch.
 *
 * Flujo de autenticación:
 *   1. Si hay un token guardado en config, se valida contra el endpoint de Twitch.
 *      Si el token pertenece a un clientId diferente al configurado, se invalida.
 *   2. Si el token está expirado pero hay refresh token, se refresca silenciosamente.
 *   3. Si no hay token válido, se lanza el flujo OAuth completo:
 *      - Se genera un code_verifier aleatorio y su code_challenge (SHA-256, PKCE).
 *      - Se abre {@link OAuthBrowser} con la URL de autorización de Twitch.
 *      - El usuario autoriza en el navegador integrado.
 *      - Twitch redirige a localhost con el código de autorización.
 *      - Se intercambia el código por access token y refresh token.
 *      - Ambos tokens se guardan en config.json.
 *
 * Los tokens se guardan cifrados en Base64 en config.json y se reutilizan
 * entre sesiones hasta que expiran o cambia el clientId.
 */
public class TwitchAuth {

    // ── Constantes OAuth ──────────────────────────────────────────────────────

    private static final int    REDIRECT_PORT = 7734;
    private static final String REDIRECT_URI  = "http://localhost:" + REDIRECT_PORT;

    /** Scopes requeridos por la aplicación. */
    private static final String SCOPE =
            "chat:read channel:read:redemptions channel:manage:redemptions";

    private static final String AUTH_URL     = "https://id.twitch.tv/oauth2/authorize";
    private static final String TOKEN_URL    = "https://id.twitch.tv/oauth2/token";
    private static final String VALIDATE_URL = "https://id.twitch.tv/oauth2/validate";

    /** Timeout máximo esperando que el usuario autorice en el browser. */
    private static final int OAUTH_TIMEOUT_MINUTES = 5;

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final String clientId;
    private final Config config;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param clientId Client ID de la aplicación registrada en dev.twitch.tv.
     * @param config   Configuración donde se leen y guardan los tokens.
     */
    public TwitchAuth(String clientId, Config config) {
        this.clientId = clientId;
        this.config   = config;
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Devuelve un access token válido para la API de Twitch.
     *
     * Intenta reutilizar el token guardado, refrescarlo si expiró,
     * o lanzar el flujo OAuth completo si no hay ninguno válido.
     *
     * @return Access token válido.
     * @throws Exception Si el flujo OAuth falla o el usuario cancela.
     */
    public String getValidToken() throws Exception {
        String accessToken  = config.getTwitchAccessToken();
        String refreshToken = config.getTwitchRefreshToken();

        if (accessToken != null && !accessToken.isBlank()) {
            if (validateToken(accessToken)) {
                System.out.println("[Auth] Token existente válido.");
                return accessToken;
            }
            System.out.println("[Auth] Token expirado, refrescando...");
            if (refreshToken != null && !refreshToken.isBlank()) {
                try {
                    String newToken = refreshAccessToken(refreshToken);
                    System.out.println("[Auth] Token refrescado correctamente.");
                    return newToken;
                } catch (Exception e) {
                    System.err.println("[Auth] No se pudo refrescar: " + e.getMessage());
                }
            }
        }

        System.out.println("[Auth] Iniciando flujo OAuth...");
        return doOAuthFlow();
    }

    /**
     * Obtiene el nombre de usuario (login) asociado a un access token.
     * Útil para mostrar con qué cuenta se autenticó el usuario.
     *
     * @param accessToken Token válido de Twitch.
     * @return Nombre de usuario en minúsculas.
     * @throws Exception Si la petición falla o el token no es válido.
     */
    public String getLoginFromToken(String accessToken) throws Exception {
        JSONObject response = getJson(VALIDATE_URL, accessToken);
        return response.getString("login");
    }

    // ── Validación y refresco ─────────────────────────────────────────────────

    /**
     * Valida un token contra el endpoint de Twitch y verifica que pertenece
     * al clientId configurado. Si pertenece a otro clientId (por ejemplo, si
     * el usuario cambió las credenciales), se considera inválido.
     *
     * @param token Access token a validar.
     * @return true si el token es válido y pertenece al clientId actual.
     */
    private boolean validateToken(String token) {
        try {
            HttpURLConnection conn = openConnection(VALIDATE_URL, token);
            if (conn.getResponseCode() != 200) return false;

            try (InputStream is = conn.getInputStream()) {
                JSONObject response = parseJson(is);
                String tokenClientId = response.optString("client_id", "");
                if (!tokenClientId.equals(clientId)) {
                    System.out.println("[Auth] Token de otro clientId — requiriendo nueva autorización.");
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Refresca el access token usando el refresh token guardado.
     * Actualiza ambos tokens en config.json.
     *
     * @param refreshToken Refresh token guardado en config.
     * @return Nuevo access token.
     * @throws Exception Si Twitch rechaza el refresh token.
     */
    private String refreshAccessToken(String refreshToken) throws Exception {
        String body = "client_id="     + clientId +
                      "&client_secret=" + config.getTwitchClientSecret() +
                      "&grant_type=refresh_token" +
                      "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);

        JSONObject response = postForm(TOKEN_URL, body);
        String accessToken  = response.getString("access_token");
        String newRefresh   = response.optString("refresh_token", refreshToken);

        config.saveTwitchTokens(accessToken, newRefresh);
        return accessToken;
    }

    // ── Flujo OAuth completo ──────────────────────────────────────────────────

    /**
     * Lanza el flujo OAuth completo con PKCE:
     *   1. Genera code_verifier y code_challenge.
     *   2. Abre OAuthBrowser con la URL de autorización.
     *   3. Espera hasta OAUTH_TIMEOUT_MINUTES minutos.
     *   4. Intercambia el código por tokens y los guarda.
     *
     * @return Access token obtenido.
     * @throws Exception Si el usuario cancela, hay timeout o Twitch devuelve error.
     */
    private String doOAuthFlow() throws Exception {
        String codeVerifier  = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state         = generateState();

        String authUrl = buildAuthUrl(state, codeChallenge);

        AtomicReference<String> codeRef  = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            OAuthBrowser browser = new OAuthBrowser(
                    null, authUrl, state, REDIRECT_PORT,
                    code  -> { codeRef.set(code);   latch.countDown(); },
                    error -> { errorRef.set(error);  latch.countDown(); }
            );
            browser.setVisible(true);
        });

        if (!latch.await(OAUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            throw new Exception("Timeout esperando autorización OAuth ("
                    + OAUTH_TIMEOUT_MINUTES + " minutos).");
        }
        if (errorRef.get() != null) {
            throw new Exception("OAuth fallido: " + errorRef.get());
        }

        String code = codeRef.get();
        if (code == null) throw new Exception("No se recibió código de autorización.");

        return exchangeCodeForToken(code, codeVerifier);
    }

    /**
     * Construye la URL de autorización de Twitch con todos los parámetros OAuth.
     *
     * @param state         Token CSRF aleatorio.
     * @param codeChallenge Hash SHA-256 del code_verifier (PKCE).
     */
    private String buildAuthUrl(String state, String codeChallenge) throws Exception {
        return AUTH_URL +
                "?client_id="             + clientId +
                "&redirect_uri="          + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&response_type=code" +
                "&scope="                 + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8) +
                "&state="                 + state +
                "&code_challenge="        + codeChallenge +
                "&code_challenge_method=S256";
    }

    /**
     * Intercambia el código de autorización por access token y refresh token.
     * Guarda ambos en config.json.
     *
     * @param code         Código de autorización recibido de Twitch.
     * @param codeVerifier Code verifier original (PKCE).
     * @return Access token obtenido.
     */
    private String exchangeCodeForToken(String code, String codeVerifier) throws Exception {
        String body = "client_id="     + clientId +
                      "&client_secret=" + config.getTwitchClientSecret() +
                      "&code="          + code +
                      "&grant_type=authorization_code" +
                      "&redirect_uri="  + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                      "&code_verifier=" + codeVerifier;

        JSONObject response = postForm(TOKEN_URL, body);
        String accessToken  = response.getString("access_token");
        String refreshToken = response.getString("refresh_token");

        config.saveTwitchTokens(accessToken, refreshToken);
        System.out.println("[Auth] Token obtenido y guardado.");
        return accessToken;
    }

    // ── Generadores criptográficos PKCE ───────────────────────────────────────

    /**
     * Genera un code_verifier aleatorio de 64 bytes codificado en Base64 URL-safe.
     * Según RFC 7636, debe tener entre 43 y 128 caracteres.
     */
    private String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Genera el code_challenge como hash SHA-256 del verifier, codificado en Base64 URL-safe.
     * Implementa el método S256 de PKCE (RFC 7636).
     *
     * @param verifier Code verifier generado por {@link #generateCodeVerifier()}.
     */
    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    /**
     * Genera un token de estado aleatorio para prevenir ataques CSRF.
     * Se verifica al recibir la respuesta de Twitch en {@link OAuthBrowser}.
     */
    private String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ── Utilidades HTTP ───────────────────────────────────────────────────────

    /**
     * Envía una petición POST con cuerpo application/x-www-form-urlencoded.
     *
     * @param url  Endpoint al que enviar la petición.
     * @param body Cuerpo de la petición ya codificado.
     * @return JSONObject con la respuesta de Twitch.
     * @throws Exception Si el servidor devuelve un código de error o falla la conexión.
     */
    private JSONObject postForm(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        int statusCode = conn.getResponseCode();
        InputStream is = statusCode == 200
                ? conn.getInputStream()
                : conn.getErrorStream();
        String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (statusCode != 200) {
            throw new Exception("HTTP " + statusCode + ": " + responseBody);
        }
        return new JSONObject(responseBody);
    }

    /**
     * Abre una conexión GET al endpoint dado con el token de autorización.
     *
     * @param url   Endpoint a consultar.
     * @param token Access token para el header Authorization.
     */
    private HttpURLConnection openConnection(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Authorization", "OAuth " + token);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    /**
     * Hace GET al endpoint con el token y devuelve la respuesta como JSONObject.
     */
    private JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection conn = openConnection(url, token);
        try (InputStream is = conn.getInputStream()) {
            return parseJson(is);
        }
    }

    /** Parsea un InputStream como JSON. */
    private JSONObject parseJson(InputStream is) throws Exception {
        return new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
    }
}