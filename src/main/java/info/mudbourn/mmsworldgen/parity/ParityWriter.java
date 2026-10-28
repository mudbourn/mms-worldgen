package info.mudbourn.mmsworldgen.parity;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// Background writer of per-dimension, per-stage parity rows.
final class ParityWriter {
    private record Row(String dimension, ParityStage stage, int x, int z, long[] hashes) {
    }

    private static final Row STOP = new Row("", ParityStage.NOISE, 0, 0, new long[0]);

    private static final int FLUSH_ROWS = 1000;

    private final Path dir;
    private final BlockingQueue<Row> queue = new LinkedBlockingQueue<>();
    private final Map<String, DataOutputStream> streams = new HashMap<>();
    private final Thread thread;
    private volatile boolean failed;
    private int sinceFlush;

    ParityWriter(Path dir) {
        this.dir = dir;
        this.thread = new Thread(this::run, "mms-parity-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    static String dimensionName(String id) {
        return id.replace(':', '_').replace('/', '_');
    }

    static String fileName(String dimension, ParityStage stage) {
        return dimension + "." + stage.id + ".bin";
    }

    void submit(String dimension, ParityStage stage, int x, int z, long[] hashes) {
        if (failed) {
            return;
        }
        queue.add(new Row(dimension, stage, x, z, hashes));
    }

    // Writes everything queued so far, closes the files and stops the thread.
    void close() {
        queue.add(STOP);
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        try {
            Files.createDirectories(dir);
            while (true) {
                Row row = queue.poll(1, TimeUnit.SECONDS);
                if (row == null) {
                    flushStreams();
                    continue;
                }
                if (row == STOP) {
                    break;
                }
                try {
                    write(row);
                    Parity.RECORDED.incrementAndGet();
                    sinceFlush++;
                    if (sinceFlush >= FLUSH_ROWS) {
                        flushStreams();
                    }
                } catch (IOException e) {
                    if (!failed) {
                        failed = true;
                        MmsWorldgen.LOG.error("Parity writer failed, dropping further rows", e);
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            MmsWorldgen.LOG.error("Parity writer stopped", e);
        } finally {
            closeStreams();
        }
    }

    private void write(Row row) throws IOException {
        String name = fileName(row.dimension(), row.stage());
        DataOutputStream out = streams.get(name);
        if (out == null) {
            out = new DataOutputStream(
                new BufferedOutputStream(
                    Files.newOutputStream(
                        dir.resolve(name),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
                )
            );
            streams.put(name, out);
        }
        out.writeInt(row.x());
        out.writeInt(row.z());
        out.writeShort(row.hashes().length);
        for (long hash : row.hashes()) {
            out.writeLong(hash);
        }
    }

    private void flushStreams() {
        sinceFlush = 0;
        for (DataOutputStream out : streams.values()) {
            try {
                out.flush();
            } catch (IOException e) {
                MmsWorldgen.LOG.error("Parity writer could not flush a file", e);
            }
        }
    }

    private void closeStreams() {
        for (DataOutputStream out : streams.values()) {
            try {
                out.close();
            } catch (IOException e) {
                MmsWorldgen.LOG.error("Parity writer could not close a file", e);
            }
        }
        streams.clear();
    }
}
