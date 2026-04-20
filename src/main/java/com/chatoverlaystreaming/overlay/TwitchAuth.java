package com.chatoverlaystreaming.overlay;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

public class TwitchAuth {

    private static final int    REDIRECT_PORT  = 7734;
    private static final String REDIRECT_URI   = "http://localhost:" + REDIRECT_PORT;
    private static final String SCOPE          = "chat:read channel:read:redemptions channel:manage:redemptions";
    private static final String AUTH_URL       = "https://id.twitch.tv/oauth2/authorize";
    private static final String TOKEN_URL      = "https://id.twitch.tv/oauth2/token";
    private static final String VALIDATE_URL   = "https://id.twitch.tv/oauth2/validate";

    private final String clientId;
    private final Config config;

    public TwitchAuth(String clientId, Config config) {
        this.clientId = clientId;
        this.config   = config;
    }

    /**
     * Devuelve un access token válido.
     * Si hay uno guardado y válido lo reutiliza.
     * Si está expirado lo refresca.
     * Si no hay ninguno, lanza el flujo OAuth completo.
     */
    public String getValidToken() throws Exception {
        String accessToken  = config.getTwitchAccessToken();
        String refreshToken = config.getTwitchRefreshToken();

        // Si tenemos token, validarlo
        if (accessToken != null && !accessToken.isBlank()) {
            if (validateToken(accessToken)) {
                System.out.println("[Auth] Token existente válido.");
                return accessToken;
            }
            System.out.println("[Auth] Token expirado, refrescando...");
            // Intentar refrescar
            if (refreshToken != null && !refreshToken.isBlank()) {
                try {
                    String newToken = refreshAccessToken(refreshToken);
                    System.out.println("[Auth] Token refrescado correctamente.");
                    return newToken;
                } catch (Exception e) {
                    System.err.println("[Auth] No se pudo refrescar: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // Flujo completo OAuth
        System.out.println("[Auth] Iniciando flujo OAuth...");
        return doOAuthFlow();
    }

    private boolean validateToken(String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    URI.create(VALIDATE_URL).toURL().openConnection();
            conn.setRequestProperty("Authorization", "OAuth " + token);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) return false;

            // Verificar que el clientId del token coincide con el configurado
            try (InputStream is = conn.getInputStream()) {
                JSONObject response = new JSONObject(
                        new String(is.readAllBytes(), StandardCharsets.UTF_8));
                String tokenClientId = response.optString("client_id", "");
                if (!tokenClientId.equals(clientId)) {
                    System.out.println("[Auth] Token de otro clientId, " +
                            "requiriendo nueva autorización.");
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String refreshAccessToken(String refreshToken) throws Exception {
        String body = "client_id=" + clientId +
                      "&client_secret=" + config.getTwitchClientSecret() +
                      "&grant_type=refresh_token" +
                      "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);

        JSONObject response = postForm(TOKEN_URL, body);
        String accessToken  = response.getString("access_token");
        String newRefresh   = response.optString("refresh_token", refreshToken);

        config.saveTwitchTokens(accessToken, newRefresh);
        return accessToken;
    }

    private String doOAuthFlow() throws Exception {
        String codeVerifier  = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state         = generateState();

        String authUrl = AUTH_URL +
                "?client_id=" + clientId +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI,
                        StandardCharsets.UTF_8) +
                "&response_type=code" +
                "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8) +
                "&state=" + state +
                "&code_challenge=" + codeChallenge +
                "&code_challenge_method=S256";

        // Resultado del flujo
        AtomicReference<String> codeRef  = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            OAuthBrowser browser = new OAuthBrowser(
                null,      // owner — pasar la ventana principal si la tienes accesible
                authUrl,
                state,
                REDIRECT_PORT,
                code -> {
                    codeRef.set(code);
                    latch.countDown();
                },
                error -> {
                    errorRef.set(error);
                    latch.countDown();
                }
            );
            browser.setVisible(true);
        });

        // Esperar resultado (máximo 5 minutos)
        if (!latch.await(5, TimeUnit.MINUTES)) {
            throw new Exception("Timeout esperando autorización OAuth.");
        }

        if (errorRef.get() != null) {
            throw new Exception("OAuth fallido: " + errorRef.get());
        }

        String code = codeRef.get();
        if (code == null) {
            throw new Exception("No se recibió código de autorización.");
        }

        return exchangeCodeForToken(code, codeVerifier);
    }

    private String exchangeCodeForToken(String code, String codeVerifier) throws Exception {
        String body = "client_id=" + clientId +
                      "&client_secret=" + config.getTwitchClientSecret() +
                      "&code=" + code +
                      "&grant_type=authorization_code" +
                      "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                      "&code_verifier=" + codeVerifier;

        JSONObject response = postForm(TOKEN_URL, body);
        String accessToken  = response.getString("access_token");
        String refreshToken = response.getString("refresh_token");

        config.saveTwitchTokens(accessToken, refreshToken);
        System.out.println("[Auth] Token obtenido y guardado.");
        return accessToken;
    }

    private JSONObject postForm(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        InputStream is = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (code != 200) {
            throw new Exception("HTTP " + code + ": " + responseBody);
        }
        return new JSONObject(responseBody);
    }

    private String extractParam(String query, String param) {
        if (query == null) return null;
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

    private String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildHtmlPage(String title, String message, boolean success) {
        String color = success ? "#5cb85c" : "#d9534f";
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"><title>%s</title></head>
            <body style="font-family:sans-serif;text-align:center;padding:60px;background:#0e0e10;color:#efeff1">
              <h1 style="color:%s">%s</h1>
              <p>%s</p>
            </body>
            </html>
            """.formatted(title, color, title, message);
    }

    public String getLoginFromToken(String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                URI.create(VALIDATE_URL).toURL().openConnection();
        conn.setRequestProperty("Authorization", "OAuth " + accessToken);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(
                    new String(is.readAllBytes(), StandardCharsets.UTF_8));
            return response.getString("login");
        }
    }
}