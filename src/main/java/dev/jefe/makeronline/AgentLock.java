package dev.jefe.makeronline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Candado compartido entre los programas que usan chrome-profile/
 * (Uploader, Downloader, SaveSession y Codegen).
 *
 * Solo uno puede estar en marcha a la vez: el primero que arranca bloquea
 * .agente.lock y los demás avisan y salen (o esperan si se lanzan con --esperar).
 * El bloqueo lo gestiona el sistema operativo, así que si el programa se cuelga
 * o lo cierras a la fuerza, se libera solo: nunca se queda "pillado".
 */
public final class AgentLock implements AutoCloseable {

    private static final Path FILE = Paths.get(".agente.lock");

    /**
     * Se bloquea un byte muy por detrás del texto. En Windows un bloqueo impide
     * leer la zona bloqueada, y así el otro programa puede leer quién lo tiene.
     */
    private static final long REGION = 1L << 20;
    private static final int INFO_BYTES = 256;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final FileChannel channel;
    private final FileLock lock;

    private AgentLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Coge el candado o termina el programa si otro lo tiene.
     *
     * @param programa nombre que verán los demás ("Uploader", "Downloader"...)
     * @param esperar  true para esperar a que quede libre en vez de salir
     */
    public static AgentLock acquire(String programa, boolean esperar) throws IOException, InterruptedException {
        FileChannel ch = FileChannel.open(FILE,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        boolean avisado = false;
        while (true) {
            FileLock l = ch.tryLock(REGION, 1, false);
            if (l != null) {
                escribirInfo(ch, programa + " (PID " + ProcessHandle.current().pid() + ", desde las "
                        + LocalDateTime.now().format(HORA) + ")");
                return new AgentLock(ch, l);
            }
            String quien = leerInfo(ch);
            if (!esperar) {
                ch.close();
                System.err.println("✘ Ya hay otro programa del agente en marcha: " + quien);
                System.err.println("  Espera a que termine o lánzalo con --esperar para que arranque cuando quede libre.");
                System.exit(2);
            }
            if (!avisado) {
                System.out.println("⏳ Esperando a que termine " + quien + "...");
                avisado = true;
            }
            Thread.sleep(10_000);
        }
    }

    private static void escribirInfo(FileChannel ch, String info) throws IOException {
        byte[] texto = info.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(INFO_BYTES);
        buf.put(texto, 0, Math.min(texto.length, INFO_BYTES));
        while (buf.hasRemaining()) buf.put((byte) ' '); // relleno: machaca restos de textos anteriores
        buf.flip();
        ch.write(buf, 0);
        ch.force(false);
    }

    private static String leerInfo(FileChannel ch) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(INFO_BYTES);
            int n = ch.read(buf, 0);
            String s = n <= 0 ? "" : new String(buf.array(), 0, n, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? "(otro programa)" : s;
        } catch (IOException e) {
            return "(otro programa)";
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
