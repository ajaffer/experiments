import org.experiments.io.BufferedByteWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BufferedByteWriterTest {

    @TempDir
    Path dir;

    @Test
    void allRecordsPresentAndInOrderAfterClose() throws IOException {
        Path file = dir.resolve("ordered.bin");
        try (var writer = new BufferedByteWriter(file, 8)) {
            writer.write(bytes("aa"));
            writer.write(bytes("bbb"));
            writer.write(bytes("cccc"));       // spans the 8-byte buffer
            writer.write(bytes("d"));
        }
        assertArrayEquals(bytes("aabbbccccd"), Files.readAllBytes(file));
    }

    @Test
    void recordLargerThanBufferIsWrittenWhole() throws IOException {
        Path file = dir.resolve("big.bin");
        byte[] big = bytes("0123456789ABCDEFGHIJ");     // 20 bytes into a 4-byte buffer
        try (var writer = new BufferedByteWriter(file, 4)) {
            writer.write(big);
        }
        assertArrayEquals(big, Files.readAllBytes(file));
    }

    // The buffering contract: a record smaller than the buffer stays in memory (no syscall per
    // record) and only reaches the file on close.
    @Test
    void dataIsBufferedUntilClose() throws IOException {
        Path file = dir.resolve("buffered.bin");
        try (var writer = new BufferedByteWriter(file, 100)) {
            writer.write(bytes("small"));
            assertEquals(0, Files.size(file), "record should still be buffered, not yet flushed");
        }
        assertArrayEquals(bytes("small"), Files.readAllBytes(file));
    }

    @Test
    void noWritesProducesEmptyFile() throws IOException {
        Path file = dir.resolve("empty.bin");
        try (var writer = new BufferedByteWriter(file, 8)) {
            writer.write(new byte[0]);          // empty record is a no-op
        }
        assertEquals(0, Files.size(file));
    }

    @Test
    void rejectsNonPositiveBufferSize() {
        assertThrows(IllegalArgumentException.class, () -> new BufferedByteWriter(dir.resolve("x"), 0));
        assertThrows(IllegalArgumentException.class, () -> new BufferedByteWriter(dir.resolve("x"), -1));
    }

    // Thread-safety: many threads writing fixed-size records concurrently. Every record must land
    // in the file intact (never interleaved with another's bytes) and none may be lost. A small,
    // odd buffer size forces records to straddle flush boundaries, so a broken lock would corrupt.
    @Test
    void concurrentWritesKeepEveryRecordIntact() throws Exception {
        Path file = dir.resolve("concurrent.bin");
        int threads = 8;
        int perThread = 1_000;
        int recordSize = 8;                     // two ints: threadId, seq
        var threadCounter = new AtomicInteger();

        try (var writer = new BufferedByteWriter(file, 30)) {   // 30 is not a multiple of 8
            CacheConcurrency.runConcurrently(threads, () -> {
                int id = threadCounter.getAndIncrement();
                for (int seq = 0; seq < perThread; seq++) writer.write(record(id, seq));
            });
        }

        byte[] all = Files.readAllBytes(file);
        assertEquals((long) threads * perThread * recordSize, all.length, "byte count mismatch");

        Set<Long> found = new HashSet<>();
        var buf = ByteBuffer.wrap(all);
        while (buf.hasRemaining()) {
            long id = buf.getInt();
            long seq = buf.getInt();
            found.add((id << 32) | (seq & 0xFFFFFFFFL));    // pack (id, seq) into one key; mask low half
        }
        assertEquals(threads * perThread, found.size(), "records lost, duplicated, or corrupted");
    }

    private static byte[] record(int id, int seq) {
        return ByteBuffer.allocate(8).putInt(id).putInt(seq).array();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
