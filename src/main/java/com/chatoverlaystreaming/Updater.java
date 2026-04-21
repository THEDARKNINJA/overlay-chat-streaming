package com.chatoverlaystreaming;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.zip.*;

public class Updater {

    private static final String GITHUB_API =
            "https://api.github.com/repos/THEDARKNINJA/overlay-chat-streaming/releases/latest";
    private static final String CURRENT_VERSION_FILE = "version.txt";

    /**
     * Comprueba si hay una versión más nueva en GitHub.
     * Devuelve el JSONObject del release si hay actualización, null si no.
     */
    public static JSONObject checkForUpdate() throws Exception {
        String currentVersion = getCurrentVersion();
        System.out.println("[Updater] Versión actual: " + currentVersion);

        HttpURLConnection conn = (HttpURLConnection)
                URI.create(GITHUB_API).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "ChatOverlay");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() != 200) return null;

        try (InputStream is = conn.getInputStream()) {
            JSONObject release = new JSONObject(
                    new String(is.readAllBytes()));
            String latestVersion = release.getString("tag_name");
            System.out.println("[Updater] Última versión: " + latestVersion);

            if (!latestVersion.equals(currentVersion)) {
                return release;
            }
        }
        return null;
    }

    /**
     * Descarga el zip del release, extrae en una carpeta temporal
     * y lanza el script updater.bat que reemplaza los archivos y
     * reinicia la app. Después cierra la app actual.
     */
    public static void downloadAndApply(JSONObject release,
                                         java.awt.Window parentWindow)
            throws Exception {
        // Buscar el asset .zip en el release
        JSONArray assets = release.getJSONArray("assets");
        String zipUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            if (name.endsWith(".zip")) {
                zipUrl = asset.getString("browser_download_url");
                break;
            }
        }

        if (zipUrl == null) {
            throw new Exception("No se encontró archivo .zip en el release.");
        }

        // Descargar el zip a una carpeta temporal
        Path tempDir  = Files.createTempDirectory("chatoverlay_update_");
        Path zipPath  = tempDir.resolve("update.zip");

        System.out.println("[Updater] Descargando: " + zipUrl);
        try (InputStream in = URI.create(zipUrl).toURL().openStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Extraer el zip
        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);
        unzip(zipPath, extractDir);
        System.out.println("[Updater] Extraído en: " + extractDir);

        // Directorio actual de la app
        Path appDir = Path.of(
                System.getProperty("user.dir"));

        // Crear updater.bat que espera a que cierre la app,
        // copia los archivos y la reinicia
        Path batPath = tempDir.resolve("updater.bat");
        String bat = """
                @echo off
                echo Esperando a que la aplicacion se cierre...
                timeout /t 3 /nobreak > nul
                echo Aplicando actualizacion...
                xcopy /E /Y /I "%s\\*" "%s\\"
                echo Reiniciando...
                start "" "%s\\ChatOverlay.exe"
                echo Actualizacion completada.
                del "%%~f0"
                """.formatted(
                extractDir.toAbsolutePath(),
                appDir.toAbsolutePath(),
                appDir.toAbsolutePath());

        Files.writeString(batPath, bat);

        // Lanzar el bat en un proceso independiente y cerrar la app
        System.out.println("[Updater] Lanzando updater.bat...");
        new ProcessBuilder("cmd.exe", "/c", "start",
                "/min", batPath.toAbsolutePath().toString())
                .start();

        System.exit(0);
    }

    private static String getCurrentVersion() {
        try {
            Path versionFile = Path.of(CURRENT_VERSION_FILE);
            if (Files.exists(versionFile)) {
                return Files.readString(versionFile).trim();
            }
            // Fallback: leer desde resources del jar
            try (InputStream is = Updater.class
                    .getResourceAsStream("/version.txt")) {
                if (is != null) {
                    return new String(is.readAllBytes()).trim();
                }
            }
        } catch (Exception e) {
            System.err.println("[Updater] No se pudo leer versión: "
                    + e.getMessage());
        }
        return "0.0.0";
    }

    private static void unzip(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(entry.getName())
                        .normalize();
                // Evitar zip slip
                if (!outPath.startsWith(destDir)) {
                    throw new IOException("Zip slip detectado: "
                            + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }
}