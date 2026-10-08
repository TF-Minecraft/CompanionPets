package net.tfminecraft.companionpets.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import net.tfminecraft.companionpets.behavior.GreetingMood;
import net.tfminecraft.companionpets.behavior.ToyInvitation;
import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.config.PetBehavior;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.item.HeldItem;
import net.tfminecraft.companionpets.pet.*;

/** A held toy takes priority until played with, put away or ignored long enough to become boring. */
final class PetToyAnticipation {
    private static final long HANDS_MILLIS = 250L;
    static final long OWNER_COMMAND_LOCKOUT = 15_000L;
    static final long STRANGER_RETRY = 20_000L;
    static final double STRANGER_LEASH = 12;
    static final long THROW_GRACE = 1_500L;
    private final Map<UUID, Long> ownerCommands = new HashMap<>();
    private final Map<Encounter, Long> invitations = new HashMap<>();
    private final Map<UUID, RecentFocus> recent = new HashMap<>();
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Map<UUID, Focus> active = new HashMap<>();
    private final Map<UUID, Interest> interests = new HashMap<>();
    private final Map<UUID, Long> nextReaction = new HashMap<>();
    private final Map<UUID, Long> nextPlayerVoice = new HashMap<>();
    PetToyAnticipation(PetRuntime runtime, PetActions actions) { this.runtime = runtime; this.actions = actions; }
    void tick(long now) {
        Map<UUID, Hands> hands = new HashMap<>();
        Map<World, List<Player>> carriers = new HashMap<>();
        // HeldItem checks vanilla material before consulting providers and memoizes identity.
        for (Player player : Bukkit.getOnlinePlayers()) {
            Hands held = Hands.of(player);
            hands.put(player.getUniqueId(), held);
            if (held.main().empty() && held.off().empty()) continue;
            for (PetTypeDef type : runtime.config().types().values()) {
                if (toy(type, held.main()) != null || toy(type, held.off()) != null) {
                    carriers.computeIfAbsent(player.getWorld(), world -> new ArrayList<>()).add(player);
                    break;
                }
            }
        }
        invitations.entrySet().removeIf(e -> e.getValue() != 0 && now >= e.getValue());
        Set<Encounter> candidates = new HashSet<>();
        for (Pet pet : runtime.store().active()) {
            Player owner = Bukkit.getPlayer(pet.ownerId());
            if (!(runtime.entity(pet) instanceof Mob body) || !ready(pet, body, owner, now)) {
                breakFocus(pet, now); continue;
            }
            Focus current = active.get(pet.id());
            Player player = owner;
            ItemRef toy = inRange(body, owner, current) ? heldToy(pet, hands.get(owner.getUniqueId())) : null;
            if (toy == null) {
                if (current != null && !pet.ownerId().equals(current.player.getUniqueId())
                        && !strangerAllowed(pet, current.player)) breakFocus(pet, now);
                current = active.get(pet.id());
                player = null;
                if (now >= ownerCommands.getOrDefault(pet.id(), 0L)) {
                    double bestDistance = Double.POSITIVE_INFINITY;
                    boolean bestFavorite = false;
                    for (Player carrier : carriers.getOrDefault(body.getWorld(), List.of())) {
                        if (carrier.getUniqueId().equals(pet.ownerId()) || !inRange(body, carrier, current)
                                || !strangerAllowed(pet, carrier)) continue;
                        ItemRef offered = heldToy(pet, hands.get(carrier.getUniqueId()));
                        if (offered == null) continue;
                        Encounter encounter = new Encounter(pet.id(), carrier.getUniqueId());
                        candidates.add(encounter);
                        if (now < invitations.getOrDefault(encounter, 0L)) continue;
                        boolean favorite = offered.key().equals(pet.favoriteToy());
                        double distance = body.getLocation().distanceSquared(carrier.getLocation());
                        if (current != null && current.player == carrier) {
                            player = carrier; toy = offered; break;
                        }
                        if (player == null || favorite && !bestFavorite || favorite == bestFavorite && distance < bestDistance) {
                            player = carrier; toy = offered; bestDistance = distance; bestFavorite = favorite;
                        }
                    }
                    if (player != null) {
                        Encounter encounter = new Encounter(pet.id(), player.getUniqueId());
                        if (!invitations.containsKey(encounter)) {
                            if (runtime.random().nextDouble() >= invitation(pet, player, toy).chance()) {
                                invitations.put(encounter, now + STRANGER_RETRY);
                                player = null;
                            } else invitations.put(encounter, 0L);
                        }
                    }
                }
            }
            if (player == null || toy == null) { interests.remove(pet.id()); cancel(pet, now); continue; }
            if (!interested(pet, player, toy, now)) { cancel(pet, now); continue; }
            Focus focus = active.get(pet.id());
            if (focus == null || focus.body != body || focus.player != player || !focus.toy.equals(toy.key())) {
                actions.clearInteractions(pet);
                PetFx.stopLooking(body);
                PetMotion.stop(body);
                focus = new Focus(body, toy.key(), player);
                active.put(pet.id(), focus);
                recent.remove(pet.id());
                ToyNavigationGoal.ensure(runtime, pet, body, this);
            }
            focus.toyAt(toy, now);
            focus.invitation = invitation(pet, player, toy);
            if (pet.fetch() == null) pet.activity(Activity.TOY_FOCUS);
        }
        active.keySet().removeIf(id -> {
            Pet pet = runtime.store().get(id);
            if (pet != null && !pet.stored() && !pet.dead()) return false;
            release(active.get(id)); interests.remove(id); recent.remove(id); return true;
        });
        Map<UUID, List<UUID>> formations = new HashMap<>();
        for (var entry : active.entrySet()) {
            Pet pet = runtime.store().get(entry.getKey());
            if (pet.fetch() == null) formations.computeIfAbsent(entry.getValue().player.getUniqueId(), id -> new ArrayList<>()).add(pet.id());
        }
        for (List<UUID> group : formations.values()) {
            group.sort(UUID::compareTo);
            for (int slot = 0; slot < group.size(); slot++) active.get(group.get(slot)).slot = slot;
        }
        for (UUID id : List.copyOf(active.keySet())) advance(runtime.store().get(id), now);
        nextReaction.keySet().removeIf(id -> runtime.store().get(id) == null);
        interests.keySet().removeIf(id -> runtime.store().get(id) == null);
        nextPlayerVoice.values().removeIf(until -> now >= until);
        ownerCommands.entrySet().removeIf(e -> now >= e.getValue() || runtime.store().get(e.getKey()) == null);
        invitations.entrySet().removeIf(e -> runtime.store().get(e.getKey().pet()) == null
                || (e.getValue() == 0 ? !candidates.contains(e.getKey()) : now >= e.getValue()));
        recent.entrySet().removeIf(e -> now - e.getValue().lostAt() > THROW_GRACE || runtime.store().get(e.getKey()) == null);
    }

    boolean active(Pet pet) { return active.containsKey(pet.id()) && pet.fetch() == null; }

    boolean attentive(Pet pet, Player player, long now) {
        if (!pet.ownerId().equals(player.getUniqueId()) && !strangerAllowed(pet, player)) return false;
        Focus focus = active.get(pet.id());
        RecentFocus previous = recent.get(pet.id());
        return focus != null ? focus.player.getUniqueId().equals(player.getUniqueId())
                : previous != null && previous.player().equals(player.getUniqueId()) && now - previous.lostAt() <= THROW_GRACE;
    }

    int slot(Pet pet, Player player) {
        Focus focus = active.get(pet.id());
        return focus != null && focus.player.getUniqueId().equals(player.getUniqueId()) ? focus.slot : 0;
    }
    void ownerCommanded(Pet pet, long now) {
        ownerCommands.put(pet.id(), now + OWNER_COMMAND_LOCKOUT);
        breakFocus(pet, now);
        if (pet.fetch() != null && !pet.ownerId().equals(pet.fetch().throwerId()))
            actions.releaseFetch(pet, null, false);
    }

    private void breakFocus(Pet pet, long now) {
        cancel(pet, now); recent.remove(pet.id()); interests.remove(pet.id());
    }

    boolean strangerAllowed(Pet pet, Player player) {
        Player owner = Bukkit.getPlayer(pet.ownerId());
        return owner != null && owner.isOnline() && player.isOnline() && owner.getWorld().equals(player.getWorld())
                && owner.getLocation().distanceSquared(player.getLocation()) <= STRANGER_LEASH * STRANGER_LEASH
                && runtime.followingAllowed(pet, owner);
    }

    private static boolean inRange(Mob body, Player player, Focus current) {
        double range = current != null && current.player == player ? 16 : 6;
        return body.getWorld().equals(player.getWorld()) && body.getLocation().distanceSquared(player.getLocation()) <= range * range;
    }

    private static ToyInvitation invitation(Pet pet, Player player, ItemRef toy) {
        return ToyInvitation.of(pet, pet.carers().trust(player.getUniqueId()), toy.key().equals(pet.favoriteToy()));
    }
    void advance(Pet pet, long now) {
        Focus focus = active.get(pet.id());
        if (focus == null) return;
        Player player = focus.player;
        if (pet.fetch() != null) return;
        // The navigation goal advances every AI tick; the player's hands are rechecked a few times a second.
        if (now >= focus.handsCheckedAt + HANDS_MILLIS || now < focus.handsCheckedAt)
            focus.toyAt(player.isOnline() ? heldToy(pet, Hands.of(player)) : null, now);
        ItemRef toy = focus.held;
        if (toy == null) interests.remove(pet.id());
        if (pet.activity() != Activity.TOY_FOCUS || toy == null || !focus.toy.equals(toy.key()) || runtime.entity(pet) != focus.body) {
            cancel(pet, now); return;
        }
        Mob body = focus.body;
        body.setAware(true);
        PetFx.sit(body, false); PetFx.lie(body, false);
        PetFx.look(body, player.getEyeLocation().subtract(0, 0.55, 0));
        boolean exuberant = pet.ownerId().equals(player.getUniqueId()) || focus.invitation.exuberant();
        boolean favorite = exuberant && toy.key().equals(pet.favoriteToy());
        if (favorite && !focus.favorite) focus.reactionPending = true;
        focus.favorite = favorite;
        if (!favorite) { focus.reactionPending = false; focus.reactionUntil = 0; }
        boolean approaching = followToy(pet, focus, player, now);
        boolean jumps = exuberant && runtime.behaves(pet, PetBehavior.TOY_JUMPS);
        if (jumps && !approaching && focus.nextWaitingJumpAt == 0)
            focus.nextWaitingJumpAt = now + (favorite ? 8000 : 1500) + runtime.random().nextInt(1000);
        if (favorite && focus.reactionPending && !approaching) {
            focus.reactionPending = false;
            if (now >= nextReaction.getOrDefault(pet.id(), 0L)) {
                focus.reactionUntil = now + 6000;
                focus.sounds = focus.jumps = 0;
                focus.nextSoundAt = now + 350 + runtime.random().nextInt(600);
                focus.nextJumpAt = now + 1000;
                nextReaction.put(pet.id(), now + 15_000);
            }
        }
        pet.toyExcitedUntilMillis(favorite ? focus.reactionUntil : 0);
        boolean reacting = favorite && now < focus.reactionUntil;
        var feeling = GreetingMood.of(pet, pet.ownerId().equals(player.getUniqueId()) ? pet.bond() : pet.carers().trust(player.getUniqueId()));
        if (reacting && runtime.behaves(pet, PetBehavior.TOY_VOCALIZING) && focus.sounds < 2 && now >= focus.nextSoundAt
                && now >= nextPlayerVoice.getOrDefault(player.getUniqueId(), 0L)) {
            runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.TOY,
                    (float) (.75 + .25 * feeling.intensity()), (float) (1 + runtime.random().nextDouble() * .1));
            focus.sounds++;
            focus.nextSoundAt = now + 3000 + runtime.random().nextInt(1000);
            nextPlayerVoice.put(player.getUniqueId(), now + 2800);
        }
        boolean reactionHop = reacting && focus.jumps < 2 && now >= focus.nextJumpAt;
        boolean waitingHop = focus.nextWaitingJumpAt > 0 && now >= focus.nextWaitingJumpAt;
        if (!approaching && jumps && (reactionHop || waitingHop)
                && pet.need(Need.ENERGY) >= 50 && pet.need(Need.HEALTH) >= 70 && safeJump(body)) {
            body.setVelocity(body.getVelocity().setY(0.30 + 0.06 * feeling.intensity()));
            pet.need(Need.ENERGY, pet.need(Need.ENERGY) - 0.2);
            if (reactionHop) focus.jumps++;
            focus.nextJumpAt = now + 2300;
            focus.nextWaitingJumpAt = now + (favorite ? 5500 : 8500) + runtime.random().nextInt(2500);
        }
    }

    private boolean followToy(Pet pet, Focus focus, Player player, long now) {
        Mob body = focus.body;
        Location playerAt = player.getLocation();
        boolean playerMoving = focus.playerAt != null && horizontalDistance(focus.playerAt, playerAt) > 0.12;
        // Sample player movement at the navigation cadence, rather than individual AI ticks.
        if (now >= focus.nextMoveAt) {
            focus.playerMoving = playerMoving;
            focus.playerAt = playerAt;
        }
        double extraDistance = pet.ownerId().equals(player.getUniqueId()) ? 0 : focus.invitation.extraDistance();
        Location front = PetSpacing.toyFront(runtime, pet, player, slot(pet, player), extraDistance);
        boolean approachingPlayer = front == null || horizontalDistance(body.getLocation(), front) > 1
                || Math.abs(body.getLocation().getY() - front.getY()) > 1;
        Location target = front;
        boolean wiggle = (pet.ownerId().equals(player.getUniqueId()) || focus.invitation.exuberant()) && runtime.behaves(pet, PetBehavior.TOY_WIGGLE);
        if (wiggle && front != null && !approachingPlayer && !focus.playerMoving && now >= focus.nextWiggleAt) {
            focus.wiggleStartedAt = now;
            focus.wiggleUntil = now + 2600;
            focus.nextWiggleAt = now + (focus.favorite ? 6500 : 9500) + runtime.random().nextInt(2000);
            focus.wiggleSide = runtime.random().nextBoolean() ? 1 : -1;
        }
        boolean playful = !focus.playerMoving && wiggle && now < focus.wiggleUntil;
        if (playful && front != null) {
            int step = (int) ((now - focus.wiggleStartedAt) / 850);
            double offset = (step == 0 ? 0.85 : step == 1 ? -0.75 : 0.15) * focus.wiggleSide * (focus.favorite ? 1 : 0.7);
            double approach = step == 1 ? -0.45 : 0;
            double yaw = Math.toRadians(playerAt.getYaw());
            Location fidget = PetGreetings.safeGround(front.clone().add(
                    Math.cos(yaw) * offset - Math.sin(yaw) * approach, 0,
                    Math.sin(yaw) * offset + Math.cos(yaw) * approach));
            if (fidget != null && PetSpacing.free(runtime, pet, fidget)) target = fidget;
        }
        boolean moving = target != null && (horizontalDistance(body.getLocation(), target) > (playful ? 0.3 : 0.65)
                || Math.abs(body.getLocation().getY() - target.getY()) > 1);
        if (!moving) {
            PetMotion.settle(body);
        } else if (now >= focus.nextMoveAt) {
            var path = body.getPathfinder().findPath(target);
            if (path != null && path.canReachFinalPoint())
                body.getPathfinder().moveTo(path, Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), false));
            else PetMotion.stop(body);
        }
        if (now >= focus.nextMoveAt) focus.nextMoveAt = now + 250;
        return approachingPlayer || focus.playerMoving;
    }

    private static double horizontalDistance(Location a, Location b) { return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()); }
    private boolean interested(Pet pet, Player player, ItemRef toy, long now) {
        Location at = player.getLocation();
        Interest interest = interests.get(pet.id());
        if (interest == null || !interest.player.equals(player.getUniqueId()) || !interest.toy.equals(toy.key()) || !interest.playerAt.getWorld().equals(at.getWorld())) {
            interest = new Interest(player.getUniqueId(), toy.key(), at, now);
            interests.put(pet.id(), interest);
        } else if (interest.playerAt.distanceSquared(at) >= 0.25) {
            interest.playerAt = at;
            interest.lastStimulusAt = now;
        }
        double seconds = toy.key().equals(pet.favoriteToy()) ? runtime.config().play().favoriteToyAttentionSeconds()
                : runtime.config().play().toyAttentionSeconds();
        // Retain the timestamp after releasing focus so the next behavior tick
        // cannot immediately reacquire the same boring toy.
        if (!pet.ownerId().equals(player.getUniqueId())) seconds *= invitation(pet, player, toy).attentionFactor();
        return now - interest.lastStimulusAt < seconds * 1000;
    }

    void threw(Player player) {
        for (var entry : active.entrySet())
            if (entry.getValue().player.getUniqueId().equals(player.getUniqueId())) interests.remove(entry.getKey());
        for (var entry : recent.entrySet())
            if (entry.getValue().player().equals(player.getUniqueId())) interests.remove(entry.getKey());
    }
    private boolean ready(Pet pet, Mob body, Player owner, long now) {
        return runtime.behaves(pet, PetBehavior.TOY_ANTICIPATION) && !pet.stored() && !pet.dead()
                && body.isValid() && !body.isDead()
                && pet.activity() != Activity.SLEEPING && pet.activity() != Activity.TRICK && pet.order() == PetOrder.FOLLOW
                && !pet.staying() && now >= pet.forcedSitUntilMillis() && GreetingMood.of(pet, pet.bond()).mobile()
                && !body.isInWater() && body.getTarget() == null && owner != null && owner.isOnline()
                && owner.getWorld().equals(body.getWorld()) && runtime.followingAllowed(pet, owner)
                && !runtime.sessions().resting(pet.id(), now)
                && !(runtime.sessions().training(pet.ownerId()) instanceof net.tfminecraft.companionpets.session.TrainingSession s
                        && s.petId().equals(pet.id()));
    }
    private record Hands(HeldItem main, HeldItem off) {
        static Hands of(Player owner) {
            return new Hands(HeldItem.of(owner.getInventory().getItemInMainHand()),
                    HeldItem.of(owner.getInventory().getItemInOffHand()));
        }
    }

    private ItemRef heldToy(Pet pet, Hands hands) {
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (type == null || hands == null) return null;
        ItemRef main = toy(type, hands.main());
        ItemRef off = toy(type, hands.off());
        if (main != null && main.key().equals(pet.favoriteToy())) return main;
        if (off != null && off.key().equals(pet.favoriteToy())) return off;
        return main == null ? off : main;
    }

    private static ItemRef toy(PetTypeDef type, HeldItem held) {
        for (ItemRef toy : type.toys()) if (held.matches(toy)) return toy;
        return null;
    }
    private static boolean safeJump(Mob body) {
        Location feet = body.getLocation();
        return body.isOnGround() && feet.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid()
                && feet.clone().add(0, 1, 0).getBlock().isPassable()
                && feet.clone().add(0, 2, 0).getBlock().isPassable();
    }

    void cancel(Pet pet) { cancel(pet, System.currentTimeMillis()); }
    void cancel(Pet pet, long now) {
        Focus focus = active.remove(pet.id());
        pet.toyExcitedUntilMillis(0);
        if (focus == null) return;
        recent.put(pet.id(), new RecentFocus(focus.player.getUniqueId(), now));
        release(focus);
        if (pet.activity() == Activity.TOY_FOCUS) pet.activity(Activity.NONE);
    }
    private void release(Focus focus) {
        // The native goal becomes inactive when its focus entry is removed. Removing
        // it here can corrupt Paper's running-goal iterator and delete the body.
        PetFx.stopLooking(focus.body);
        runtime.visual().wagTail(focus.body, 0);
        PetMotion.stop(focus.body);
    }
    void clear() { for (Pet pet : List.copyOf(runtime.store().all())) cancel(pet); active.clear(); interests.clear(); nextReaction.clear(); nextPlayerVoice.clear(); recent.clear(); ownerCommands.clear(); invitations.clear(); }

    private record Encounter(UUID pet, UUID player) { }
    private record RecentFocus(UUID player, long lostAt) { }

    private static final class Interest {
        final UUID player;
        final String toy;
        Location playerAt;
        long lastStimulusAt;
        Interest(UUID player, String toy, Location playerAt, long now) { this.player = player; this.toy = toy; this.playerAt = playerAt; lastStimulusAt = now; }
    }

    private static final class Focus {
        final Mob body;
        final String toy;
        final Player player;
        ItemRef held;
        ToyInvitation invitation;
        long handsCheckedAt;
        void toyAt(ItemRef toy, long now) { held = toy; handsCheckedAt = now; }
        long nextSoundAt, nextJumpAt, nextMoveAt, nextWaitingJumpAt;
        long wiggleStartedAt, wiggleUntil, nextWiggleAt;
        int wiggleSide;
        Location playerAt;
        boolean playerMoving;
        boolean favorite, reactionPending;
        long reactionUntil;
        int sounds, jumps, slot;
        Focus(Mob body, String toy, Player player) { this.body = body; this.toy = toy; this.player = player; }
    }
}
