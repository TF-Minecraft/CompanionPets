package net.tfminecraft.companionpets.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import net.tfminecraft.companionpets.pet.*;

/** Development-only timings; snapshots run on the caller, YAML writes on a separate thread. */
public final class SaveSnapshotProbe {
    private static final int WARMUP = 30;

    public static void main(String[] args) throws Exception {
        int repeats = args.length == 0 ? 100 : Integer.parseInt(args[0]);
        if (repeats < 50) throw new IllegalArgumentException("At least 50 repetitions are required");
        Files.createDirectories(Path.of("target"));
        Path directory = Files.createTempDirectory(Path.of("target"), "save-snapshot-probe-");
        System.out.println("SAVE_SNAPSHOT_PROBE directory=" + directory.toAbsolutePath()
                + " jvm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + " heapMiB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        for (int size : new int[]{500, 2000, 5000}) measure(directory, size, repeats);
    }

    static PetStore populate(Path file, int size) {
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        var store = new PetStore(file.toFile(), logger);
        if (!store.load()) throw new IllegalStateException("Cannot load probe store");
        for (int i = 0; i < size; i++) {
            var pet = new Pet(new UUID(2, i + 1), new UUID(1, i % 100 + 1), i % 2 == 0 ? "dog" : "cat", "Pet " + i,
                    i % 2 == 0 ? PetSex.MALE : PetSex.FEMALE);
            pet.bornAt(1_700_000_000_000L + i); pet.lastOwnerNearbyMillis(1_750_000_000_000L + i);
            pet.lastGreetingMillis(1_750_000_000_000L + i);
            pet.place("world", i * 1.5, 64, -i * .5, i % 360);
            pet.entityId(i % 10 < 5 ? null : new UUID(3, i + 1));
            pet.stored(i % 10 < 5); pet.staying(i % 10 == 6);
            pet.order(i % 10 == 6 ? PetOrder.STAY : PetOrder.FOLLOW);
            for (Need need : Need.values()) pet.need(need, 55 + (i + need.ordinal()) % 40);
            pet.bond(35 + i % 60); pet.dirtyMillis(i * 1000L); pet.criticalMillis(i % 3 * 1000L);
            pet.illness(i % 20 == 0 ? Illness.UNWELL : Illness.NONE); pet.treated(i % 20 == 0);
            pet.favoriteToy(i % 2 == 0 ? "STICK" : "BONE"); pet.carriedToy(i % 20 == 7 ? "STICK" : null);
            pet.announcedLow(i % 20 == 0 ? EnumSet.of(Need.HUNGER) : EnumSet.noneOf(Need.class));
            for (Trick trick : Trick.values()) {
                pet.bindWord(trick.name().toLowerCase(Locale.ROOT), trick);
                pet.progress(trick, 30 + (i + trick.name().length()) % 71);
            }
            for (int friend = 0; friend < 5; friend++) {
                var memory = new RelationshipMemory.Memory(15 + friend * 10, 1_750_000_000_000L + i, 1_750_000_000_100L + i, 1_750_000_000_200L + i);
                pet.carers().restore(new UUID(4, friend + 1), memory);
                pet.friends().restore(new UUID(2, (i + friend + 1) % size + 1), memory);
            }
            store.add(pet);
        }
        return store;
    }

    private static void measure(Path directory, int size, int repeats) throws Exception {
        Path file = directory.resolve(Integer.toString(size)).resolve("pets.yml");
        Files.createDirectories(file.getParent());
        var store = populate(file, size);
        var writer = Executors.newSingleThreadExecutor();
        try {
            long[] snapshots = new long[repeats];
            PetStore.Snapshot snapshot = null;
            var pets = store.all();
            // 50% stored, 20% with simulated offline owners, 30% changing between snapshots.
            for (int sample = -WARMUP; sample < repeats; sample++) {
                for (Pet pet : pets) {
                    if ((pet.id().getLeastSignificantBits() - 1) % 10 >= 7) {
                        pet.need(Need.HUNGER, pet.need(Need.HUNGER) - .001);
                        pet.place(pet.worldName(), pet.x() + .01, pet.y(), pet.z(), pet.yaw());
                    }
                }
                long start = System.nanoTime();
                snapshot = store.snapshot();
                long elapsed = System.nanoTime() - start;
                if (snapshot == null || snapshot.pets().size() != size) throw new AssertionError("Incomplete snapshot");
                if (sample >= 0) snapshots[sample] = elapsed;
            }
            report("SNAPSHOT", size, WARMUP, snapshots);
            long[] writes = new long[repeats];
            final var captured = snapshot;
            for (int sample = -5; sample < repeats; sample++) {
                long elapsed = writer.submit(() -> {
                    long start = System.nanoTime();
                    if (!store.write(captured)) throw new AssertionError("Write failed");
                    return System.nanoTime() - start;
                }).get();
                if (sample >= 0) writes[sample] = elapsed;
            }
            report("WRITE", size, 5, writes);
            System.out.println("SAVE_SNAPSHOT_DATA pets=" + size + " bytes=" + Files.size(file)
                    + " stored=" + size / 2 + " offlineOut=" + size / 5 + " changing=" + size * 3 / 10);
        } finally {
            writer.shutdown();
            if (!store.close()) throw new AssertionError("Probe store did not close cleanly");
        }
    }

    static void report(String operation, int size, int warmup, long[] samples) {
        Arrays.sort(samples);
        double median = (samples[(samples.length - 1) / 2] + samples[samples.length / 2]) / 2.0 / 1_000_000;
        double p95 = samples[(int) Math.ceil(samples.length * .95) - 1] / 1_000_000.0;
        System.out.printf(Locale.ROOT, "%s pets=%d samples=%d warmup=%d medianMs=%.3f p95Ms=%.3f%n",
                operation, size, samples.length, warmup, median, p95);
    }
}
