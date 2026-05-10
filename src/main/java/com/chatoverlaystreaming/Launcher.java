package com.chatoverlaystreaming;

// import org.json.JSONObject;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Launcher de Chat Overlay con comprobación e instalación de actualizaciones.
 *
 * Flujo de ejecución:
 *   1. Muestra una pequeña ventana de estado.
 *   2. Consulta la API de GitHub para ver si hay versión nueva.
 *   3. Si hay actualización, descarga el .zip, lo extrae y copia los archivos
 *      directamente desde Java (excepto los que están en uso por el propio Launcher).
 *   4. Los archivos que no se pueden copiar (Launcher.exe, Launcher.jar y similares)
 *      se pasan como argumentos a ChatOverlay.exe, que los copiará en un hilo
 *      en background hasta conseguirlo y luego cerrará ese hilo.
 *   5. Lanza ChatOverlay.exe y se cierra.
 *   6. Si no hay actualización, lanza ChatOverlay.exe directamente.
 *
 * Todo el código está en una sola clase para minimizar los archivos propios
 * del Launcher que podrían quedar bloqueados durante una actualización.
 */
public class Launcher {

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final String GITHUB_API =
            "https://api.github.com/repos/THEDARKNINJA/overlay-chat-streaming/releases/latest";
    private static final String VERSION_FILE    = "version.txt";
    private static final String CHAT_OVERLAY_EXE = "ChatOverlay.exe";

    /** Prefijos de archivos propios del Launcher que no puede sobreescribirse a sí mismo. */
    private static final String[] LAUNCHER_FILES = {"Launcher", "launcher"};

    // ── UI ────────────────────────────────────────────────────────────────────

    private final JFrame      frame;
    private final JLabel      statusLabel;
    private final JProgressBar progressBar;

    /** Datos mínimos de un release de GitHub que necesita el Launcher. */
    private record Release(String version, String zipUrl) {}

    // ── Constructor ───────────────────────────────────────────────────────────

    public Launcher() {
        // ── Ventana principal ─────────────────────────────────────────────────
        frame = new JFrame("Chat Overlay");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setUndecorated(false);
        frame.setResizable(false);

        // ── Panel principal ───────────────────────────────────────────────────
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(14, 14, 16));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        // Título
        JLabel titleLabel = new JLabel("Chat Overlay");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(new Color(200, 140, 255));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Estado
        statusLabel = new JLabel("Iniciando...");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(new Color(200, 200, 210));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Barra de progreso
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(false);
        progressBar.setForeground(new Color(200, 140, 255));
        progressBar.setBackground(new Color(30, 30, 35));
        progressBar.setBorder(BorderFactory.createEmptyBorder());
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);

        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(12));
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(10));
        panel.add(progressBar);

        frame.setContentPane(panel);
        frame.pack();
        frame.setMinimumSize(new Dimension(320, 120));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Recoger argumentos pasados por ChatOverlay para auto-actualización del launcher
        List<String> pendingLauncherFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--pending-file=")) {
                pendingLauncherFiles.add(arg.substring("--pending-file=".length()));
            }
        }

        SwingUtilities.invokeLater(() -> {
            Launcher launcher = new Launcher();

            // Si ChatOverlay nos pasó archivos pendientes de copiar sobre el launcher,
            // copiarlos ahora que el launcher anterior ya cerró
            if (!pendingLauncherFiles.isEmpty()) {
                launcher.applyPendingLauncherFiles(pendingLauncherFiles);
            }

            Thread.ofVirtual().name("launcher-main").start(launcher::run);
        });
    }

    // ── Flujo principal ───────────────────────────────────────────────────────

    /**
     * Flujo principal del launcher:
     *   1. Comprobar actualización.
     *   2. Si hay, descargar, aplicar y lanzar con args de archivos pendientes.
     *   3. Si no hay, lanzar directamente.
     */
    private void run() {
        try {
            setStatus("Comprobando actualizaciones...", -1);

            Release release = checkForUpdate();

            if (release != null) {
                setStatus("Actualización disponible: " + release.version, 0);
                //String version = release.getString("tag_name");

                Path tempDir = downloadAndExtract(release);
                List<String> pendingFiles = applyUpdate(tempDir);
                launchChatOverlay(pendingFiles);
            } else {
                setStatus("Sin actualizaciones. Iniciando...", 100);
                launchChatOverlay(List.of());
            }

        } catch (Exception e) {
            System.err.println("[Launcher] Error: " + e.getMessage());
            setStatus("Error: " + e.getMessage(), 0);
            // Esperar unos segundos para que el usuario lea el error y lanzar igualmente
            sleep(4000);
            try {
                launchChatOverlay(List.of());
            } catch (Exception ex) {
                System.err.println("[Launcher] No se pudo lanzar ChatOverlay: "
                        + ex.getMessage());
                setStatus("No se pudo lanzar ChatOverlay.", 0);
            }
        }
    }

    // ── Comprobación de actualización ─────────────────────────────────────────

    /**
     * Consulta la API de GitHub Releases y compara con la versión local.
     *
     * @return JSONObject del release si hay versión nueva, null si está al día.
     */
    private Release checkForUpdate() throws Exception {
        String current = getCurrentVersion();
        System.out.println("[Launcher] Versión actual: " + current);

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API))
                .header("User-Agent", "ChatOverlayLauncher")
                .timeout(java.time.Duration.ofSeconds(6))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("[Launcher] GitHub API respondió: " + response.statusCode());
            return null;
        }

        String body   = response.body();
        String latest = extractJsonString(body, "tag_name");
        System.out.println("[Launcher] Última versión: " + latest);

        if (latest == null || latest.equals(current)) return null;

        // Devolver un objeto simple con lo que necesitamos
        // En lugar de JSONObject usamos un record interno
        return new Launcher.Release(latest, findZipUrl(body));
    }

    /** Lee la versión actual desde version.txt junto al exe o desde los resources del jar. */
    private String getCurrentVersion() {
        try {
            Path f = Path.of(VERSION_FILE);
            if (Files.exists(f)) return Files.readString(f).trim();
            try (InputStream is = getClass().getResourceAsStream("/version.txt")) {
                if (is != null) return new String(is.readAllBytes()).trim();
            }
        } catch (Exception e) {
            System.err.println("[Launcher] No se pudo leer version.txt: " + e.getMessage());
        }
        return "0.0.0";
    }

    // ── Descarga y extracción ─────────────────────────────────────────────────

    /**
     * Descarga el .zip del release y lo extrae en una carpeta temporal.
     *
     * @param release JSONObject del release de GitHub.
     * @return Path del directorio extraído.
     */
    private Path downloadAndExtract(Release release) throws Exception {
        String zipUrl = release.zipUrl();
        if (zipUrl == null) throw new Exception("No hay .zip en el release de GitHub.");

        Path tempDir    = Files.createTempDirectory("chatoverlay_update_");
        Path zipPath    = tempDir.resolve("update.zip");
        Path extractDir = tempDir.resolve("extracted");

        // Descargar con progreso
        setStatus("Descargando actualización...", 0);
        downloadWithProgress(zipUrl, zipPath);

        // Extraer
        setStatus("Extrayendo archivos...", 50);
        Files.createDirectories(extractDir);
        unzip(zipPath, extractDir);

        return extractDir;
    }

    /**
     * Descarga un archivo mostrando progreso en la barra.
     * Si el servidor no informa el Content-Length, la barra es indeterminada.
     */
    private void downloadWithProgress(String url, Path dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "ChatOverlayLauncher");
        long total = conn.getContentLengthLong();

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dest)) {

            byte[] buf       = new byte[8192];
            long   read      = 0;
            int    n;

            if (total <= 0) {
                setProgress(true, 0);
            }

            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0) {
                    int pct = (int)(read * 50 / total); // 0-50% para descarga
                    setStatus("Descargando actualización... " + pct * 2 + "%", pct);
                }
            }
        }
    }

    /**
     * Extrae el valor de un campo string del JSON de GitHub.
     * Uso exclusivo para los campos simples de la respuesta de releases:
     * tag_name, browser_download_url, name.
     * No es un parser JSON completo, solo extrae valores de campos conocidos.
     */
    private static String extractJsonString(String json, String field) {
        String search = "\"" + field + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    /**
     * Extrae el array "assets" del JSON del release y devuelve
     * la browser_download_url del primer asset que termina en .zip.
     */
    private static String findZipUrl(String releaseJson) {
        int searchPos = 0;
        String nameKey = "\"name\":\"";
        String urlKey  = "\"browser_download_url\":\"";

        while (true) {
            int namePos = releaseJson.indexOf(nameKey, searchPos);
            if (namePos == -1) return null;

            int nameStart = namePos + nameKey.length();
            int nameEnd = releaseJson.indexOf("\"", nameStart);
            if (nameEnd == -1) return null;

            String name = releaseJson.substring(nameStart, nameEnd);

            if (name.endsWith(".zip")) {
                int urlPos = releaseJson.indexOf(urlKey, nameEnd);
                if (urlPos == -1) return null;

                int urlStart = urlPos + urlKey.length();
                int urlEnd = releaseJson.indexOf("\"", urlStart);
                if (urlEnd == -1) return null;

                return releaseJson.substring(urlStart, urlEnd);
            }

            searchPos = nameEnd;
        }
    }

    // ── Aplicación de la actualización ────────────────────────────────────────

    /**
     * Copia los archivos extraídos sobre el directorio actual de la app.
     * Los archivos propios del Launcher (Launcher.exe, Launcher.jar, etc.)
     * no se pueden copiar porque están en uso. Se devuelven como lista de rutas
     * absolutas del directorio temporal para que ChatOverlay los copie después.
     *
     * @param extractDir Directorio con los archivos extraídos.
     * @return Lista de rutas absolutas de archivos que no se pudieron copiar
     *         (archivos del Launcher en uso).
     */
    private List<String> applyUpdate(Path extractDir) throws Exception {
        setStatus("Aplicando actualización...", 50);

        Path       appDir      = Path.of(System.getProperty("user.dir"));
        List<String> pending   = new ArrayList<>();
        List<Path>   allFiles  = listAllFiles(extractDir);
        int          total     = allFiles.size();
        int          done      = 0;

        for (Path srcFile : allFiles) {
            Path relative = extractDir.relativize(srcFile);
            Path destFile = appDir.resolve(relative);
            String name   = srcFile.getFileName().toString();

            Files.createDirectories(destFile.getParent());

            if (isLauncherFile(name)) {
                // Archivo propio del Launcher: no podemos sobreescribirlo en uso
                // Si el contenido es diferente, lo añadimos a la lista de pendientes
                if (!filesAreEqual(srcFile, destFile)) {
                    System.out.println("[Launcher] Pendiente (en uso): " + name);
                    pending.add(srcFile.toAbsolutePath().toString()
                            + "|" + destFile.toAbsolutePath().toString());
                }
            } else {
                try {
                    Files.copy(srcFile, destFile, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Launcher] Copiado: " + relative);
                } catch (IOException e) {
                    // Archivo en uso (dll, jar de JavaFX, etc.)
                    // Si el contenido es igual no hace falta copiarlo
                    if (!filesAreEqual(srcFile, destFile)) {
                        System.out.println("[Launcher] En uso, pendiente: " + name);
                        pending.add(srcFile.toAbsolutePath() + "|"
                                + destFile.toAbsolutePath());
                    } else {
                        System.out.println("[Launcher] En uso pero igual, saltando: " + name);
                    }
                }
            }

            done++;
            int pct = 50 + (done * 45 / total); // 50-95%
            setStatus("Aplicando actualización... " + done + "/" + total, pct);
        }

        return pending;
    }

    /**
     * Comprueba si un nombre de archivo corresponde a un archivo propio del Launcher
     * que no puede sobreescribirse mientras está en ejecución.
     */
    private boolean isLauncherFile(String filename) {
        String lower = filename.toLowerCase();
        for (String prefix : LAUNCHER_FILES) {
            if (lower.startsWith(prefix.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Compara dos archivos por tamaño y hash MD5 para detectar si son iguales.
     * Si el destino no existe, se considera diferente.
     */
    private boolean filesAreEqual(Path a, Path b) {
        try {
            if (!Files.exists(b)) return false;
            if (Files.size(a) != Files.size(b)) return false;
            return md5(a).equals(md5(b));
        } catch (Exception e) {
            return false;
        }
    }

    private String md5(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Copia los archivos pendientes de la lista pasada como args por ChatOverlay.
     * Reintenta en bucle con espera entre intentos hasta conseguirlo.
     * Se llama al inicio del launcher si recibe --pending-file= como argumento.
     */
    private void applyPendingLauncherFiles(List<String> pendingFiles) {
        Thread.ofVirtual().name("pending-copy").start(() -> {
            for (String entry : pendingFiles) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length != 2) continue;
                Path src  = Path.of(parts[0]);
                Path dest = Path.of(parts[1]);

                // Reintentar hasta conseguir copiar
                while (true) {
                    try {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("[Launcher] Archivo pendiente copiado: "
                                + dest.getFileName());
                        break;
                    } catch (IOException e) {
                        System.out.println("[Launcher] Reintentando copiar "
                                + dest.getFileName() + "...");
                        sleep(1000);
                    }
                }
            }
        });
    }

    // ── Lanzar ChatOverlay ────────────────────────────────────────────────────

    /**
     * Lanza ChatOverlay.exe con los argumentos de archivos pendientes si los hay,
     * y cierra el launcher.
     *
     * @param pendingFiles Lista de entradas "src|dest" de archivos que ChatOverlay
     *                     debe copiar cuando pueda (normalmente archivos del Launcher).
     */
    private void launchChatOverlay(List<String> pendingFiles) throws Exception {
        setStatus("Abriendo Chat Overlay...", 100);

        Path chatOverlay = Path.of(System.getProperty("user.dir"), CHAT_OVERLAY_EXE);
        if (!Files.exists(chatOverlay)) {
            throw new Exception(CHAT_OVERLAY_EXE + " no encontrado en: "
                    + chatOverlay.getParent());
        }

        List<String> command = new ArrayList<>();
        command.add(chatOverlay.toAbsolutePath().toString());

        // Pasar archivos pendientes como argumentos para que ChatOverlay los copie
        for (String pending : pendingFiles) {
            command.add("--pending-launcher-file=" + pending);
        }

        System.out.println("[Launcher] Lanzando: " + String.join(" ", command));
        new ProcessBuilder(command)
                .directory(chatOverlay.getParent().toFile())
                .start();

        sleep(500); // pequeña pausa para que el proceso arranque
        System.exit(0);
    }

    // ── Utilidades de archivos ────────────────────────────────────────────────

    /** Lista recursivamente todos los archivos (no directorios) bajo un directorio. */
    private List<Path> listAllFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Extrae un .zip en el directorio destino.
     * Incluye protección contra zip slip.
     */
    private void unzip(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destDir)) {
                    throw new IOException("Zip slip detectado: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    // ── Utilidades de UI ──────────────────────────────────────────────────────

    /**
     * Actualiza el texto de estado y el progreso de la barra en el EDT.
     *
     * @param message Texto a mostrar.
     * @param pct     Porcentaje (0-100), o -1 para modo indeterminado.
     */
    private void setStatus(String message, int pct) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(message);
            setProgress(pct < 0, Math.max(0, pct));
        });
        System.out.println("[Launcher] " + message);
    }

    /**
     * Configura la barra de progreso.
     *
     * @param indeterminate Si true, la barra muestra animación indeterminada.
     * @param value         Valor de progreso (0-100), ignorado si indeterminate=true.
     */
    private void setProgress(boolean indeterminate, int value) {
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) progressBar.setValue(value);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}