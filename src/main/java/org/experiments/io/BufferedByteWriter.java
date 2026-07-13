package org.experiments.io;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Buffers appended records in memory and writes them to a file, hitting the channel only when the
 * buffer fills — so callers don't pay a syscall per record. Every record written before
 * {@link #close()} is present in the file afterward, in write order.
 *
 * <p>Thread-safe: {@code write} and {@code close} are serialized, so concurrent writers each append
 * a whole record atomically. A single lock is the right tool here — writes are non-droppable and
 * I/O-bound, so a lock-free front buffer would only add unbounded memory without relieving the real
 * (disk) bottleneck.
 *
 * <p>Durability: on overflow {@code write} moves bytes to the OS page cache (enough to survive a
 * process crash); {@code close} additionally forces them to the physical disk ({@code fsync}), so
 * data survives a power loss too — synced once at close, not per record.
 */
public class BufferedByteWriter implements Closeable {
    private final ByteBuffer byteBuffer;
    private final FileOutputStream writer;
    private final FileChannel channel;

    public BufferedByteWriter(Path path, int bufferSize) throws FileNotFoundException {
        if (bufferSize <= 0) throw new IllegalArgumentException("bufferSize must be > 0");
        this.byteBuffer = ByteBuffer.allocate(bufferSize);
        this.writer = new FileOutputStream(path.toFile());
        this.channel = writer.getChannel();
    }

    public synchronized void write(byte[] record) {
        int bytesLeft = record.length;
        int offset = 0;

        while (bytesLeft > 0) {
            if (!byteBuffer.hasRemaining()) flush();

            //write as much as possible
            int bytesWritten = Math.min(byteBuffer.remaining(), bytesLeft);
            byteBuffer.put(record, offset, bytesWritten);

            bytesLeft -= bytesWritten;
            offset += bytesWritten;
        }
    }

    private void flush() {
        byteBuffer.flip();

        while (byteBuffer.hasRemaining()) {
            try {
                channel.write(byteBuffer);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        byteBuffer.clear();
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            flush();
            channel.force(false);
        } finally {
            try {
                channel.close();
                writer.close();
            } catch(IOException e){
                throw new RuntimeException(e);
            }
        }
    }
}
