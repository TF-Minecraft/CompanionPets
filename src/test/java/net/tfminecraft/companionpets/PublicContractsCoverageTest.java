package net.tfminecraft.companionpets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.behavior.*;
import net.tfminecraft.companionpets.care.*;
import net.tfminecraft.companionpets.chat.SpokenOrder;
import net.tfminecraft.companionpets.config.*;
import net.tfminecraft.companionpets.gui.StatLook;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.play.*;
import net.tfminecraft.companionpets.text.Names;
import net.tfminecraft.companionpets.visual.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class PublicContractsCoverageTest {
    JavaPlugin plugin;
    @BeforeEach void start() { MockBukkit.mock(); plugin=MockBukkit.createMockPlugin(); }
    @AfterEach void stop() { MockBukkit.unmock(); }
    Pet pet() { return new Pet(UUID.randomUUID(),UUID.randomUUID(),"dog","Toby",PetSex.MALE); }
    CompanionConfig config(String yaml) throws Exception {
        var source=new YamlConfiguration(); source.loadFromString(yaml); return CompanionConfig.load(plugin,source);
    }
    @Test void idleVisualHonorsOptionalCapabilitiesAndStillDeletesBodies() throws Exception {
        PetVisual visual=new IdleVisual(); Entity body=mock(Entity.class);
        var settings=config("pets:\n  dog: {species: dog, egg: EGG}\n  model: {species: dog, egg: BONE, model: beagle}\n");
        var type=settings.type("dog");
        visual.apply(body,type); visual.update(body,type,PetAnimation.IDLE); visual.trainingAttention(body,type,true);
        assertFalse(visual.play(body,type,"bark")); assertFalse(visual.holdsMovement(body)); assertFalse(visual.attached(body));
        assertFalse(visual.hasClip(body,type,"idle")); assertEquals(Set.of(),visual.clips(type));
        assertTrue(visual.modelAvailable(type)); assertFalse(visual.modelAvailable(settings.type("model")));
        assertFalse(visual.hasTail(type)); assertFalse(visual.playClip(body,type,"idle",1));
        assertFalse(visual.belly(body)); assertFalse(visual.startBelly(body,type,100)); assertFalse(visual.rubBelly(body));
        visual.cancelAction(body); visual.wagTail(body,2); visual.animateTails();
        visual.holdHeadLook(body,1,2,3); visual.releaseHeadLook(body); visual.retain(Set.of()); visual.close();
        verifyNoInteractions(body);
        visual.removeBody(null); visual.removeBody(body); verify(body).remove();
        PetVisual broken=new PetVisual() {
            public void apply(Entity entity,PetTypeDef t) {}
            public void remove(Entity entity) { throw new IllegalStateException("visual unavailable"); }
        };
        Entity second=mock(Entity.class);
        assertThrows(IllegalStateException.class,()->broken.removeBody(second)); verify(second).remove();
    }
    @Test void animationBoundaryDefaultsHoldAsLoopAndUseOneSecondDuration() {
        List<String> calls=new ArrayList<>();
        AnimationPlayer player=new AnimationPlayer() {
            public boolean play(PetAppearance.Clip clip,boolean loop) { calls.add(clip.name()+":"+loop); return true; }
            public void stop(String clip) {} public boolean playing(String clip) { return false; }
        };
        assertTrue(player.hold(new PetAppearance.Clip("sit",1,0))); assertEquals(List.of("sit:true"),calls);
        assertEquals(1,player.length("sit"));
    }
    @Test void customTrickCompatibilityConstructorHasImmediateSilentAudio() {
        var trick = new CustomTrick("Wave", "wave", "waves", 2);
        assertNull(trick.sound()); assertEquals(List.of(0.0), trick.at());
        assertThrows(UnsupportedOperationException.class, () -> trick.at().add(1.0));
        assertEquals("social-tail-wag", PetBehavior.SOCIAL_TAIL_WAG.id());
    }

    @Test void namesAndSpokenCommandsRejectMissingInputAndBoundPlayerNames() {
        assertEquals("",Names.sanitize(null)); assertEquals("",SpokenOrder.line(null));
        assertFalse(SpokenOrder.matches("sit",null)); assertFalse(SpokenOrder.matches("sit","  "));
        assertEquals("A".repeat(32),Names.sanitize("A".repeat(50)));
        assertEquals("Sir Dog",Names.sanitize("  Sir  § Dog  "));
        assertTrue(SpokenOrder.matches("  SIT!! ","sit"));
    }
    @Test void throwCurvesAndSettingsRespectDocumentedBoundsAndCompatibilityConstructors() {
        assertEquals(0,ThrowSpeed.aim(-100)); assertEquals(.15,ThrowSpeed.aim(90),.0001);
        assertEquals(1,ThrowSpeed.aim(-25),.0001); assertEquals(.7,ThrowSpeed.aim(-12.5f),.0001);
        assertEquals(.275,ThrowSpeed.aim(15),.0001);
        assertEquals(new PlaySettings(.6,1.1,20,35,1.2),new PlaySettings(.6,1.1));
        assertEquals(1.2,new PlaySettings(.6,1.1,5,10).fetchSpeedMultiplier());
        assertThrows(IllegalArgumentException.class,()->new PlaySettings(.6,1.1,0,10,1.2));
        assertThrows(IllegalArgumentException.class,()->new PlaySettings(.6,1.1,5,10,Double.NaN));
    }
    @Test void careIgnoresZeroElapsedTimeAndCriticalChecksIncludeEnergy() {
        Pet pet=pet(); pet.need(Need.ENERGY,10);
        assertTrue(HealthRecovery.physicalNeedsCritical(pet));
        assertFalse(HealthRecovery.physicalNeedsCritical(pet,true));
        assertEquals(List.of(),NeedClock.advance(pet,new CareInput(Presence.NEAR,true,false,false,false,true,0,CareSettings.defaults(),1)));
        assertEquals(10,pet.need(Need.ENERGY));
        assertEquals(Locomotion.Mode.SIT,Locomotion.choose(Illness.NONE,100,100,100,Activity.NONE,false,true,PetOrder.FOLLOW,false));
    }
    @Test void petIdentityTimersAndTemperRemainConsistent() {
        Pet pet=pet(); assertNull(pet.trickFor(null));
        pet.nextCriticalSoundAtMillis(1234); assertEquals(1234,pet.nextCriticalSoundAtMillis());
        pet.pauseUntilMillis(5678); assertEquals(5678,pet.pauseUntilMillis());
        assertTrue(PetPersonality.TERRITORIAL.badTemper()); assertTrue(PetPersonality.GRUMPY.badTemper());
        assertFalse(PetPersonality.FRIENDLY.badTemper());
        assertEquals(1.6,new PetMeetingMood(PetMeetingMood.Reaction.BRIEF,.5,false,false).personalSpace());
    }
    @Test void bellySettingsRejectInvalidValuesWithExplicitFallbackWarnings() {
        var yaml=new YamlConfiguration(); Logger logger=mock(Logger.class);
        yaml.set("chance",Double.NaN); yaml.set("idle-seconds",0); yaml.set("cooldown-seconds","bad"); yaml.set("min-mood",101);
        assertEquals(new BellySettings(true,25,5,60,70),BellySettings.read(yaml,logger));
        verify(logger,times(4)).warning(anyString());
    }
    @Test void statDisplayKeepsAllNeedStatesAndClampsProgressBars() {
        String[][] expected={{"Well fed","Peckish","Starving"},{"Cheerful","Restless","Miserable"},{"Full of energy","Tired","Exhausted"},{"Spotless","Scruffy","Filthy"},{"Healthy","Weak","In danger"}};
        Need[] needs={Need.HUNGER,Need.MOOD,Need.ENERGY,Need.CLEANLINESS,Need.HEALTH};
        for(int i=0;i<needs.length;i++) {
            assertNotNull(StatLook.theme(needs[i]));
            assertEquals(expected[i][0],StatLook.state(needs[i],100));
            assertEquals(expected[i][1],StatLook.state(needs[i],40));
            assertEquals(expected[i][2],StatLook.state(needs[i],0));
        }
        assertEquals("Wary",StatLook.bondState(0)); assertEquals("Friendly",StatLook.bondState(30));
        assertEquals("Loyal",StatLook.bondState(60)); assertEquals("Devoted",StatLook.bondState(85));
        assertEquals("■".repeat(10),PlainTextComponentSerializer.plainText().serialize(StatLook.bar(50)));
        assertEquals("■".repeat(5),PlainTextComponentSerializer.plainText().serialize(StatLook.bar(.01,StatLook.BOND,5)));
        assertEquals("■".repeat(10),PlainTextComponentSerializer.plainText().serialize(StatLook.bar(-1,StatLook.BOND)));
    }
    @Test void configuredEggModelsAndYamlTokensPreserveTheirIdentity() throws Exception {
        var type=config("pets:\n  dog: {species: dog, egg: EGG, egg-custom-model-data: 42}\n").type("dog");
        // Legacy model-data eggs retain their configured marker when rendered in menus.
        ItemStack icon=type.eggIcon();
        assertTrue(type.matchesEgg(icon)); assertEquals(42,icon.getItemMeta().getCustomModelData());
        assertFalse(type.matchesEgg(new ItemStack(Material.EGG)));
        assertEquals("minecraft:bone",ItemRef.yamlToken(Map.of("minecraft","bone")));
        assertEquals("bare",ItemRef.yamlToken(Map.of("bare",List.of())));
        var yaml=new YamlConfiguration(); yaml.set("minecraft","bone"); assertEquals("minecraft:bone",ItemRef.yamlToken(yaml));
        assertEquals(ItemRef.vanilla(Material.BONE),ItemRef.parse("minecraft:bone"));
        assertThrows(IllegalArgumentException.class,()->ItemRef.parse("m.type."));
        assertThrows(IllegalArgumentException.class,()->ItemRef.parse("ia.invalid"));
        assertEquals("stone",ItemRef.parse("STONE").name());
        assertEquals(Component.translatable(Material.STONE.translationKey()),ItemRef.parse("STONE").displayName());
    }
}
