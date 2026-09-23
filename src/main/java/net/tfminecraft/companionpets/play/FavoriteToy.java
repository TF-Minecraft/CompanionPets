package net.tfminecraft.companionpets.play;

import java.util.List;
import java.util.Random;

public final class FavoriteToy {
    private FavoriteToy() {
    }

    public record Result(String toy, boolean notifyLost) {
    }

    public static Result reconcile(String current, List<String> toys, Random random) {
        if (toys == null || toys.isEmpty()) {
            return new Result(null, false);
        }
        if (current != null && toys.contains(current)) {
            return new Result(current, false);
        }
        String rolled = toys.get(random.nextInt(toys.size()));
        return new Result(rolled, current != null);
    }
}
