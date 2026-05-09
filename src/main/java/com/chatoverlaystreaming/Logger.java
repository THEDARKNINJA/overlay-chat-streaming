package com.chatoverlaystreaming;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;

/**
 * Redirige {@code System.out} y {@code System.err} para escribir simultáneamente
 * en la consola y en un archivo de log diario dentro del directorio {@code logs/}.
 *
 * <p>Uso típico:
 * <pre>
 *   Logger.init();                        // al arrancar la aplicación
 *   if (!config.getLogActivity()) Logger.close(); // si el log está desactivado en config
 *   // ...
 *   Logger.close();                       // al cerrar la aplicación
 * </pre>
 *
 * <p>El archivo de log se llama {@code overlay-yyyy-MM-dd.log} y se abre en modo
 * append, por lo que varias sesiones del mismo día se acumulan en el mismo archivo.
 * Cada línea impresa con {@code println} lleva un prefijo de timestamp.
 *
 * <p>{@link #init()} se llama antes de cargar el config para poder registrar
 * errores de inicialización. Si tras leer el config la opción {@code logActivity}
 * está desactivada, basta con llamar a {@link #close()} para restaurar los streams
 * originales y dejar de escribir en disco.
 */
public class Logger {

    // ── Formato de timestamp ──────────────────────────────────────────────────

    /** Formato usado como prefijo en cada línea de log. */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── Streams originales ────────────────────────────────────────────────────

    /** Referencia al {@code System.out} original, guardada antes de redirigir. */
    private static PrintStream originalOut;

    /** Referencia al {@code System.err} original, guardada antes de redirigir. */
    private static PrintStream originalErr;

    // ── Inicialización ────────────────────────────────────────────────────────

    /**
     * Inicializa el sistema de log.
     *
     * <p>Crea el directorio {@code logs/} si no existe, abre (o continúa) el archivo
     * de log del día actual y reemplaza {@code System.out} y {@code System.err} por
     * streams que escriben en paralelo en la consola y en el archivo.
     *
     * <p>Si ocurre algún error al crear el archivo, se imprime un aviso por
     * {@code System.err} y la aplicación sigue sin log en disco.
     */
    public static void init() {
        try {
            // Asegurar que la consola usa UTF-8 antes de guardar las referencias
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

            // Crear directorio de logs si no existe
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) Files.createDirectory(logsDir);

            // Nombre de archivo basado en la fecha; la hora va solo al mensaje de inicio
            String logDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String logTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm-ss"));
            String filename = "logs/overlay-" + logDate + ".log";

            // Guardar referencias originales antes de redirigir
            originalOut = System.out;
            originalErr = System.err;

            FileOutputStream fos        = new FileOutputStream(filename, true);
            PrintStream      fileStream = new PrintStream(fos, true, "UTF-8");

            // Reemplazar System.out con un stream que escribe en consola y archivo,
            // añadiendo timestamp automáticamente en cada println
            System.setOut(new PrintStream(new TeeOutputStream(originalOut, fileStream), true, "UTF-8") {
                @Override
                public void println(String x) {
                    super.println(timestamp() + x);
                }
            });

            // Igual para System.err
            System.setErr(new PrintStream(new TeeOutputStream(originalErr, fileStream), true, "UTF-8") {
                @Override
                public void println(String x) {
                    super.println(timestamp() + x);
                }
            });

            System.out.println("Log iniciado: overlay-" + logDate + "_" + logTime);

        } catch (IOException e) {
            System.err.println("No se pudo inicializar el log: " + e.getMessage());
        }
    }

    // ── Cierre ────────────────────────────────────────────────────────────────

    /**
     * Restaura {@code System.out} y {@code System.err} a sus streams originales,
     * desactivando la escritura en disco.
     *
     * <p>Puede usarse en dos situaciones:
     * <ul>
     *   <li>Al cerrar la aplicación, para liberar el archivo de log.</li>
     *   <li>Tras leer el config, si {@code logActivity} está desactivado:
     *       {@code if (!config.getLogActivity()) Logger.close();}</li>
     * </ul>
     */
    public static void close() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /** Devuelve el timestamp actual formateado como prefijo de línea de log. */
    private static String timestamp() {
        return "[" + LocalDateTime.now().format(FORMATTER) + "] ";
    }

    // ── TeeOutputStream ───────────────────────────────────────────────────────

    /**
     * {@code OutputStream} que replica cada escritura en dos streams de destino.
     * Se usa para escribir simultáneamente en la consola y en el archivo de log
     * sin recursión ni bloqueos.
     */
    private static class TeeOutputStream extends OutputStream {

        /** Stream de destino primario (consola). */
        private final OutputStream first;

        /** Stream de destino secundario (archivo de log). */
        private final OutputStream second;

        /**
         * @param first  Stream primario (normalmente la consola).
         * @param second Stream secundario (normalmente el archivo de log).
         */
        TeeOutputStream(OutputStream first, OutputStream second) {
            this.first  = first;
            this.second = second;
        }

        @Override
        public void write(int b) throws IOException {
            first.write(b);
            second.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            first.write(b, off, len);
            second.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            first.flush();
            second.flush();
        }
    }
}