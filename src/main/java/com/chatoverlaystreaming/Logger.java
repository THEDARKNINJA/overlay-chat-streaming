package com.chatoverlaystreaming;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static PrintStream originalOut;
    private static PrintStream originalErr;

    public static void init() {
        try {
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) Files.createDirectory(logsDir);

            String filename = "logs/overlay-" +
                LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".log";

            // Guardar referencias originales antes de redirigir
            originalOut = System.out;
            originalErr = System.err;

            FileOutputStream fos = new FileOutputStream(filename, true);
            PrintStream fileStream = new PrintStream(fos, true, "UTF-8");

            // TeeOutputStream: escribe en dos sitios a la vez sin recursión
            System.setOut(new PrintStream(new TeeOutputStream(originalOut, fileStream), true, "UTF-8") {
                @Override
                public void println(String x) {
                    super.println(timestamp() + x);
                }
            });

            System.setErr(new PrintStream(new TeeOutputStream(originalErr, fileStream), true, "UTF-8") {
                @Override
                public void println(String x) {
                    super.println(timestamp() + x);
                }
            });

            System.out.println("Log iniciado: " + filename);

        } catch (IOException e) {
            System.err.println("No se pudo inicializar el log: " + e.getMessage());
        }
    }

    private static String timestamp() {
        return "[" + LocalDateTime.now().format(FORMATTER) + "] ";
    }

    public static void close() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // Escribe en dos OutputStreams a la vez
    private static class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

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