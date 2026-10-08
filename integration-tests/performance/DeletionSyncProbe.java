package net.tfminecraft.companionpets.store;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Development-only journal timings; excludes opening/closing the channel and directory creation. */
public final class DeletionSyncProbe {
    public static void main(String[] args) throws Exception {
        int repeats = args.length == 0 ? 200 : Integer.parseInt(args[0]);
        if (repeats < 50) throw new IllegalArgumentException("At least 50 repetitions are required");
        Files.createDirectories(Path.of("target"));
        Path file = Files.createTempDirectory(Path.of("target"), "deletion-sync-probe-").resolve("pet-deletions.log");
        long[] samples = new long[repeats];
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (int sample = -30; sample < repeats; sample++) {
                String entry = new UUID(5, sample + 31).toString() + "\n";
                long start = System.nanoTime();
                PetStore.writeAndForce(channel, entry);
                long elapsed = System.nanoTime() - start;
                if (sample >= 0) samples[sample] = elapsed;
            }
        }
        if (Files.readAllLines(file).size() != repeats + 30) throw new AssertionError("Incomplete deletion journal");
        System.out.println("DELETION_SYNC_PROBE file=" + file.toAbsolutePath() + " filesystem=" + Files.getFileStore(file).type()
                + " jvm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        SaveSnapshotProbe.report("WRITE_AND_FORCE", 0, 30, samples);
    }
}
