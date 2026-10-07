package net.tfminecraft.companionpets.staff;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.visual.IdleVisual;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Wolf;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class TestCommandsCoverageTest {
    private GoalServerMock server;
    private WorldMock world;
    private JavaPlugin plugin;
    private PlayerMock staff;
    private PetRuntime runtime;
    private TestCommands commands;
    private Pet existing;
    private Entity lookedAt;
    private final List<Particle> particles = new ArrayList<>();
    private final List<Sound> sounds = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new WorldMock() {
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return !getType().isSolid(); }
                };
            }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                if (type != Wolf.class) return super.spawn(at, type);
                var body = new WolfMock(server, UUID.randomUUID()) {
                    @Override public void setRemoveWhenFarAway(boolean remove) { }
                };
                server.registerEntity(body); body.teleport(at);
                return type.cast(body);
            }
            @Override public RayTraceResult rayTraceBlocks(Location from, Vector direction, double distance,
                    FluidCollisionMode fluids, boolean ignorePassable) { return null; }
            @Override public RayTraceResult rayTraceEntities(Location from, Vector direction, double distance,
                    Predicate<? super Entity> filter) {
                return lookedAt != null && filter.test(lookedAt)
                        && lookedAt.getLocation().distance(from) <= distance
                        ? new RayTraceResult(lookedAt.getLocation().toVector(), lookedAt) : null;
            }
            @Override public void spawnParticle(Particle particle, Location at, int count,
                    double x, double y, double z, double speed) { particles.add(particle); }
            @Override public void playSound(Location at, Sound sound, float volume, float pitch) { sounds.add(sound); }
        };
        server.addWorld(world);
        plugin = MockBukkit.createMockPlugin();
        staff = server.addPlayer("Staff"); staff.setOp(true);
        staff.teleport(new Location(world, 0, 64, 0));
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                limits: {max-out: 1}
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                """);
        var key = new NamespacedKey(plugin, "pet"); var visual = new IdleVisual();
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        var actions = new PetActions(runtime);
        commands = new TestCommands(runtime, actions, new StaffCommands(runtime, actions));
        existing = new Pet(UUID.randomUUID(), staff.getUniqueId(), "wolf", "Existing", PetSex.MALE);
        existing.stored(true); store.add(existing);
    }

    @AfterEach void cleanup() {
        try {
            if (runtime != null) runtime.store().close();
            if (staff != null) PetFx.clearPlayer(staff.getUniqueId());
        } finally { MockBukkit.unmock(); }
    }

    private void execute(String... args) { assertTrue(commands.execute(staff, args)); }
    private Path auditFile() { return plugin.getDataFolder().toPath().resolve("staff-audit.yml.log"); }
    private String audit() throws Exception { return Files.readString(auditFile()); }
    private void bringOut(Pet pet) {
        var body = runtime.bodies().spawn(pet, runtime.config().type("wolf"), staff.getLocation().add(0, 0, 2), Bukkit.getPlayer(pet.ownerId()));
        assertNotNull(body); pet.stored(false); runtime.remember(pet, body); lookedAt = body;
    }

    @Test void spawnLearnsCompatibleTricksPersistsTheBodyAndAuditsOnlyTheNewPet() throws Exception {
        execute("SpAwN", "wolf", "Toby", "Junior");
        var spawned = runtime.store().all().stream().filter(p -> !p.id().equals(existing.id())).findFirst().orElseThrow();
        assertEquals("Toby Junior", spawned.name()); assertFalse(spawned.stored());
        assertEquals(staff.getUniqueId(), spawned.ownerId());
        assertEquals(100, spawned.progress(Trick.SIT)); assertEquals(Trick.SIT, spawned.trickFor("sit"));
        assertEquals(100, spawned.progress(Trick.FOLLOW)); assertEquals(Trick.FOLLOW, spawned.trickFor("follow"));
        var body = assertInstanceOf(Wolf.class, runtime.entity(spawned));
        assertEquals(staff.getUniqueId(), body.getOwner().getUniqueId());
        assertEquals(spawned.id(), runtime.bodies().readId(body));
        var reloaded = new PetStore(plugin); assertTrue(reloaded.load());
        assertEquals(body.getUniqueId(), reloaded.get(spawned.id()).entityId());
        assertEquals(100, reloaded.get(spawned.id()).progress(Trick.SIT));
        assertTrue(reloaded.get(existing.id()).stored());
        assertTrue(audit().contains("action: spawn-learned-requested"));
        assertTrue(audit().contains("action: spawn-learned\n"));
        assertTrue(audit().contains(spawned.id().toString())); assertFalse(audit().contains(existing.id().toString()));
        assertTrue(staff.nextMessage().contains("Toby Junior (wolf) spawned with every compatible trick learned."));
    }

    @Test void defaultSpawnNameUsesTheTypeAndQuotaFailureHasNoSuccessAudit() throws Exception {
        execute("spawn", "wolf");
        assertEquals(List.of("Existing", "wolf"), runtime.store().of(staff.getUniqueId()).stream().map(Pet::name).sorted().toList());
        while (staff.nextMessage() != null) { }
        String successfulAudit = audit();
        execute("spawn", "wolf", "OneTooMany");
        assertTrue(staff.nextMessage().contains("active pet limit"));
        assertEquals(2, runtime.store().all().size());
        assertTrue(audit().substring(successfulAudit.length()).contains("spawn-learned-requested"));
        assertFalse(audit().substring(successfulAudit.length()).contains("action: spawn-learned\n"));
    }

    @Test void unknownSpawnTypeIsExplainedWithoutMutatingExistingPets() throws Exception {
        execute("spawn", "missing");
        assertTrue(staff.nextMessage().contains("Unknown pet type: missing"));
        assertEquals(List.of(existing), runtime.store().all());
        assertTrue(existing.stored()); assertNull(existing.entityId());
        assertTrue(audit().contains("spawn-learned-requested"));
        assertFalse(audit().contains("action: spawn-learned\n"));
    }

    @Test void affectionCommandProducesVisibleEffectsAndAuditsTheSelectedOwnedPet() throws Exception {
        bringOut(existing);
        execute("MoMeNt", "AfFeCtIoN");
        assertTrue(particles.contains(runtime.config().moments().affectionParticle()));
        assertFalse(sounds.isEmpty());
        var bar = staff.nextActionBar(); assertNotNull(bar);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(bar).contains("Existing whimpers and looks at you"));
        assertTrue(audit().contains("action: moment-requested")); assertTrue(audit().contains("action: moment\n"));
        assertTrue(audit().contains(existing.id().toString()));
        assertFalse(existing.stored()); assertEquals(PetOrder.FOLLOW, existing.order());
    }

    @Test void sittingPetRejectsMomentAndRecordsOnlyTheRequest() throws Exception {
        bringOut(existing); existing.order(PetOrder.SIT);
        execute("moment", "affection");
        assertTrue(staff.nextMessage().contains("awake, standing, and following"));
        assertTrue(particles.isEmpty()); assertTrue(sounds.isEmpty()); assertNull(staff.nextActionBar());
        assertEquals(PetOrder.SIT, existing.order());
        assertTrue(audit().contains("action: moment-requested")); assertFalse(audit().contains("action: moment\n"));
    }

    @Test void unownedOrAbsentTargetNeverTriggersAMomentOrWritesAnAudit() {
        execute("moment", "affection");
        assertEquals("Look at one of your pets first.", staff.nextMessage());
        var otherOwner = server.addPlayer("Other");
        var other = new Pet(UUID.randomUUID(), otherOwner.getUniqueId(), "wolf", "OtherPet", PetSex.FEMALE);
        runtime.store().add(other); bringOut(other);
        execute("moment", "affection");
        assertEquals("Look at one of your pets first.", staff.nextMessage());
        assertFalse(Files.exists(auditFile())); assertTrue(particles.isEmpty()); assertTrue(sounds.isEmpty());
    }

    @Test void unavailableAuditPreventsBothSpawningAndMomentEffects() throws Exception {
        Files.createDirectory(auditFile());
        execute("spawn", "wolf");
        assertEquals("The staff audit could not be written. Check the server log.", staff.nextMessage());
        assertEquals(List.of(existing), runtime.store().all()); assertNull(existing.entityId());
        bringOut(existing);
        execute("moment", "affection");
        assertEquals("The staff audit could not be written. Check the server log.", staff.nextMessage());
        assertTrue(particles.isEmpty()); assertTrue(sounds.isEmpty()); assertNull(staff.nextActionBar());
    }

    @Test void argumentPermissionAndConsoleErrorsHaveNoSideEffects() {
        staff.setOp(false); execute("spawn", "wolf");
        assertEquals("You do not have permission to use these staff commands.", staff.nextMessage());
        assertTrue(commands.complete(staff, new String[]{"spawn", ""}).isEmpty());
        staff.setOp(true);
        assertTrue(commands.execute(server.getConsoleSender(), "spawn", "wolf"));
        assertEquals("This command can only be used in game.", server.getConsoleSender().nextMessage());
        execute(); assertTrue(staff.nextMessage().startsWith("Use /companionpets"));
        execute("unknown"); assertTrue(staff.nextMessage().startsWith("Use /companionpets"));
        execute("spawn"); assertTrue(staff.nextMessage().startsWith("Usage: /companionpets testpet"));
        for (String[] invalid : List.of(new String[]{"moment"}, new String[]{"moment", "missing"},
                new String[]{"moment", "affection", "extra"})) {
            execute(invalid); assertTrue(staff.nextMessage().startsWith("Usage: /companionpets moment"));
        }
        assertFalse(Files.exists(auditFile())); assertEquals(List.of(existing), runtime.store().all());
        assertTrue(particles.isEmpty()); assertNull(existing.entityId());
    }

    @Test void completionFiltersTypesAndSupportedMomentsByCaseInsensitivePrefix() {
        assertEquals(List.of("wolf"), commands.complete(staff, new String[]{"SpAwN", "W"}));
        assertEquals(List.of("bark", "belly"), commands.complete(staff, new String[]{"MoMeNt", "B"}));
        assertEquals(List.of("pet-greeting"), commands.complete(staff, new String[]{"moment", "pet-"}));
        assertTrue(commands.complete(staff, new String[]{"unknown", ""}).isEmpty());
        assertTrue(commands.complete(staff, new String[]{"spawn"}).isEmpty());
        assertTrue(commands.complete(staff, new String[]{"spawn", "wolf", ""}).isEmpty());
    }
}
