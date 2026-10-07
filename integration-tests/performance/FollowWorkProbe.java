package net.tfminecraft.companionpets.runtime;

import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.behavior.Locomotion;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/** Counts plugin path submissions only; does not time native AI or ModelEngine. */
public final class FollowWorkProbe {
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    public static void main(String[] args) throws Exception {
        var fixture = new PetInteractionTest(); fixture.setup();
        try {
            var runtime = (PetRuntime) field(fixture, "runtime");
            var actions = (PetActions) field(fixture, "actions");
            var body = (Mob) field(fixture, "body");
            var owner = (Player) field(fixture, "player");
            var pet = (Pet) field(fixture, "pet");
            body.teleport(owner.getLocation().add(8, 0, 0));
            var ticker = new PetTicker(runtime, actions);
            var follow = PetTicker.class.getDeclaredMethod("stepMode", Pet.class, Mob.class, Player.class, Locomotion.Mode.class, long.class);
            follow.setAccessible(true);
            int before = (int) field(fixture, "navigationRequests");
            long now = System.currentTimeMillis();
            for (int i = 0; i < 240; i++) follow.invoke(ticker, pet, body, owner, Locomotion.Mode.FOLLOW, now + i * 250L);
            int submitted = (int) field(fixture, "navigationRequests") - before;
            System.out.println("FOLLOW_WORK_PROBE opportunities=240 distance=8 pathSubmissions=" + submitted);
        } finally { fixture.teardown(); }
    }
}
