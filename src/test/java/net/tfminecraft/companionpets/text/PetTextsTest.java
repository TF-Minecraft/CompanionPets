package net.tfminecraft.companionpets.text;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.MockBukkit;

class PetTextsTest {
    @Test
    void ageUsesTheTimeSinceBirth() {
        long born = 1_000_000L;
        assertEquals("Unknown", PetTexts.age(0L, born));
        assertEquals("Newborn", PetTexts.age(born, born + 30_000L));
        assertEquals("1 minute", PetTexts.age(born, born + 60_000L));
        assertEquals("12 minutes", PetTexts.age(born, born + 12 * 60_000L));
        assertEquals("1 hour", PetTexts.age(born, born + 60 * 60_000L));
        assertEquals("2 hours and 5 minutes", PetTexts.age(born, born + (2 * 60 + 5) * 60_000L));
        assertEquals("1 day", PetTexts.age(born, born + 24 * 60 * 60_000L));
        assertEquals("3 days and 4 hours", PetTexts.age(born, born + (3 * 24 + 4) * 60 * 60_000L));
    }

    @Test
    void speciesAndItemNamesReadNaturally() {
        assertEquals("Wolf", PetTexts.speciesName("wolf"));
        assertEquals("Snow fox", PetTexts.speciesName("snow_fox"));
        assertEquals("Pet", PetTexts.speciesName(""));
        assertEquals("glow berries", PetTexts.itemName("GLOW_BERRIES"));
    }

    @Test
    void aConfiguredTypeMadeOnlyOfSeparatorsCanHatchAndOpenItsPetProfile() throws Exception {
        var server = MockBukkit.mock();
        PetStore store = null;
        PetActions actions = null;
        try {
            server.addSimpleWorld("world");
            var plugin = MockBukkit.createMockPlugin();
            var owner = server.addPlayer();
            var yaml = new YamlConfiguration();
            yaml.loadFromString("pets: {'_-_': {entity: WOLF, egg: WOLF_SPAWN_EGG, sex: choose}}");
            var config = CompanionConfig.load(plugin, yaml);
            assertNotNull(config.type("_-_"), "The real config loader accepts this type ID");
            store = new PetStore(plugin);
            assertTrue(store.load());
            var visual = new IdleVisual();
            var key = new NamespacedKey(plugin, "pet");
            var runtime = new PetRuntime(plugin, config, store, new Sessions(), new Bodies(plugin, key, visual),
                    visual, key, new NamespacedKey(plugin, "toy"));
            actions = new PetActions(runtime);
            var egg = new ItemStack(Material.WOLF_SPAWN_EGG);
            owner.getInventory().setItemInMainHand(egg);
            actions.useWorld(owner, egg, null, null, false, true);
            assertTrue(owner.nextMessage().contains("Will your new pet be a boy or a girl?"));
            assertEquals("_-_", runtime.sessions().hatch(owner.getUniqueId()).typeId());
            actions.onChat(owner, "cancel");
            assertNull(runtime.sessions().hatch(owner.getUniqueId()));
            assertEquals(Material.WOLF_SPAWN_EGG, owner.getInventory().getItemInMainHand().getType());
            assertEquals(1, owner.getInventory().getItemInMainHand().getAmount());
            assertTrue(owner.nextMessage().contains("Hatching cancelled. Your egg is safe."));
            var pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "_-_", "Luna", PetSex.FEMALE);
            pet.stored(true);
            store.add(pet);
            actions.menus().openCare(owner, pet);
            var species = owner.getOpenInventory().getTopInventory().getItem(4).getItemMeta().displayName();
            assertEquals("Pet", PlainTextComponentSerializer.plainText().serialize(species));
            assertTrue(pet.stored());
            assertNull(pet.entityId());
        } finally {
            if (actions != null) actions.holograms().clear();
            if (store != null) assertTrue(store.close());
            MockBukkit.unmock();
        }
    }

    @Test
    void pronounsAndSexLabelsMatchBothSupportedSexes() {
        assertEquals(List.of("she", "She", "her", "her", "Female"), List.of(PetTexts.he(PetSex.FEMALE),
                PetTexts.He(PetSex.FEMALE), PetTexts.him(PetSex.FEMALE), PetTexts.his(PetSex.FEMALE), PetTexts.sexName(PetSex.FEMALE)));
        assertEquals(List.of("he", "He", "him", "his", "Male"), List.of(PetTexts.he(PetSex.MALE),
                PetTexts.He(PetSex.MALE), PetTexts.him(PetSex.MALE), PetTexts.his(PetSex.MALE), PetTexts.sexName(PetSex.MALE)));
    }

    @Test
    void absentNamesUseReadableFallbacksAndMaterialFormattingIgnoresServerLocale() {
        assertEquals("Pet", PetTexts.speciesName(null));
        assertEquals("Pet", PetTexts.speciesName("   "));
        assertEquals("Pet", PetTexts.speciesName("-_-"));
        assertEquals("Pet", PetTexts.speciesName("_"));
        assertEquals("nothing", PetTexts.itemName(null));
        assertEquals("nothing", PetTexts.itemName(""));
        assertEquals("nothing", PetTexts.itemName("   "));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("Arctic fox", PetTexts.speciesName("-ARCTIC_FOX-"));
            assertEquals("iron ingot", PetTexts.itemName("IRON_INGOT"));
            assertEquals("high five", PetTexts.trickName(Trick.valueOf("HIGH_FIVE")));
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void needNoticesNameTheActualNeedAndExplainCareInNaturalLanguage() {
        record Notice(Need need, String label, String text) { }
        var notices = List.of(
                new Notice(Need.HUNGER, "Hunger", "Luna's stomach is rumbling"),
                new Notice(Need.MOOD, "Mood", "Luna is craving some attention"),
                new Notice(Need.ENERGY, "Energy", "Luna is worn out. Let her sit or lie down to rest"),
                new Notice(Need.CLEANLINESS, "Cleanliness", "Luna could really use a good brushing"),
                new Notice(Need.HEALTH, "Health", "Luna isn't looking well"));
        for (var notice : notices) {
            assertEquals(notice.label(), PetTexts.needName(notice.need()));
            assertEquals(notice.text(), PetTexts.lowNeed("Luna", PetSex.FEMALE, notice.need()));
        }
        assertEquals("Toby is worn out. Let him sit or lie down to rest", PetTexts.lowNeed("Toby", PetSex.MALE, Need.ENERGY));
    }

    @Test
    void checkingNeedsDistinguishesLowAndCriticalCareWithoutSuggestingMedicineForHealthyPets() {
        record Check(Need need, String low, String critical) { }
        var checks = List.of(
                new Check(Need.HUNGER, "Luna sniffs your empty hand, hoping for food", "Luna's stomach growls loudly. She is starving"),
                new Check(Need.MOOD, "Luna barely reacts. She is bored and wants to play", "Luna looks miserable. She needs some fun and attention"),
                new Check(Need.ENERGY, "Luna yawns and can barely keep her eyes open", "Luna is exhausted and can barely stand"),
                new Check(Need.CLEANLINESS, "Luna's coat is getting matted. A brush would help", "Luna is filthy and itchy. She badly needs a brush"),
                new Check(Need.HEALTH, "Luna doesn't look well", "Luna doesn't look well"));
        for (var check : checks) {
            assertEquals(check.low(), PetTexts.needCheck("Luna", PetSex.FEMALE, check.need(), false));
            assertEquals(check.critical(), PetTexts.needCheck("Luna", PetSex.FEMALE, check.need(), true));
        }
        assertEquals("Toby yawns and can barely keep his eyes open", PetTexts.needCheck("Toby", PetSex.MALE, Need.ENERGY, false));
    }

    @Test
    void illnessStagesGiveSpecificMedicineAndCareGuidance() {
        record Check(Illness illness, String notice, String check) { }
        for (var check : List.of(
                new Check(Illness.NONE, "Luna", "Luna seems fine"),
                new Check(Illness.UNWELL, "Luna seems a little under the weather", "Luna feels warm and sluggish. Look after her needs and she'll perk up"),
                new Check(Illness.SICK, "Luna is sick and needs medicine", "Luna whimpers weakly. She is sick and needs Honey tonic"),
                new Check(Illness.WEAKENED, "Luna is too weak to stand", "Luna is too weak to stand. She needs Honey tonic and care right away"))) {
            assertEquals(check.notice(), PetTexts.illness("Luna", PetSex.FEMALE, check.illness()));
            assertEquals(check.check(), PetTexts.illnessCheck("Luna", PetSex.FEMALE, check.illness(), "Honey tonic"));
        }
        assertEquals("Toby feels warm and sluggish. Look after his needs and he'll perk up",
                PetTexts.illnessCheck("Toby", PetSex.MALE, Illness.UNWELL, "Honey tonic"));
    }

    @Test
    void pettingReactionsReflectSpeciesSexAndBondAndHaveACustomTypeFallback() {
        record Reaction(String type, String normal, String devoted) { }
        for (var reaction : List.of(
                new Reaction("WOLF", "You pet Luna. She wags her tail happily", "You pet Luna. She rolls over for belly rubs, tail wagging wildly"),
                new Reaction("CAT", "You pet Luna. She purrs and rubs against your hand", "You pet Luna. She purrs loudly and headbutts your hand"),
                new Reaction("FOX", "You pet Luna. She nuzzles your hand with a happy chirp", "You pet Luna. She yips with joy and nuzzles into your hand"))) {
            assertEquals(reaction.normal(), PetTexts.petted("Luna", PetSex.FEMALE, reaction.type(), false));
            assertEquals(reaction.devoted(), PetTexts.petted("Luna", PetSex.FEMALE, reaction.type(), true));
        }
        assertEquals("You pet Toby. He wags his tail happily", PetTexts.petted("Toby", PetSex.MALE, "wolf", false));
        assertEquals("You pet Toby. He leans happily into your hand", PetTexts.petted("Toby", PetSex.MALE, "custom_model", false));
        assertEquals("You pet Toby. He leans happily into your hand", PetTexts.petted("Toby", PetSex.MALE, null, true));
    }

    @Test
    void trainingAndCareMessagesKeepThePetNameAndCorrectPronouns() {
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Lúna {owner}", PetSex.FEMALE);
        assertEquals("Look at Lúna {owner} and say a command", PetTexts.trainingPrompt(pet));
        assertEquals("Lúna {owner} whines and looks around for her owner", PetTexts.missesOwner(pet.name(), pet.sex()));
        assertEquals("Lúna {owner} leans into your hand, worn out from training. Let her rest a while", PetTexts.restingCheck(pet.name(), pet.sex()));
        assertEquals("Lúna {owner} ate too much. She feels awful", PetTexts.overfed(pet.name(), pet.sex()));
        assertEquals("Lúna {owner} yelps and flinches", PetTexts.struck(pet.name()));
        pet.name("Toby");
        assertEquals("Look at Toby and say a command", PetTexts.trainingPrompt(pet));
        assertEquals("Toby whines and looks around for his owner", PetTexts.missesOwner("Toby", PetSex.MALE));
        assertEquals("Toby leans into your hand, worn out from training. Let him rest a while", PetTexts.restingCheck("Toby", PetSex.MALE));
    }

    @Test
    void trickNamesDescriptionsAndReactionsCoverBuiltinsLegacySpinAndCustomGestures() {
        record Display(Trick trick, String name, String description, String reaction) { }
        for (var display : List.of(
                new Display(Trick.SIT, "Sit", "Sits down and rests", "Luna sits down and looks up at you"),
                new Display(Trick.FOLLOW, "Follow", "Wakes up and follows you again", "Luna gets up and follows you"),
                new Display(Trick.COME, "Come", "Comes to you, then resumes its pose", "Luna comes to you, then resumes her previous posture"),
                new Display(Trick.STAY, "Stay", "Stands still until you say follow", "Luna stands still, watching you"),
                new Display(Trick.SPEAK, "Speak", "Barks, meows or yips on cue", "Luna speaks up proudly"),
                new Display(Trick.JUMP, "Jump", "Leaps up into the air", "Luna leaps into the air"),
                new Display(Trick.SPIN, "Spin", "Twirls around in a circle", "Luna chases her own tail in a circle"),
                new Display(Trick.LAY, "Lay", "Lies down awake and rests", "Luna lies down"),
                new Display(Trick.PAW, "Shake Paw", "Gives you a paw", "Luna lifts a paw and gives it to you"),
                new Display(Trick.valueOf("high_five"), "high five", "Performs a learned gesture", "Luna performs high five"))) {
            assertEquals(display.name(), PetTexts.trickName(display.trick()));
            assertEquals(display.description(), PetTexts.trickDescription(display.trick()));
            assertEquals(display.reaction(), PetTexts.reaction("Luna", PetSex.FEMALE, display.trick()));
        }
        assertEquals("Toby comes to you, then resumes his previous posture", PetTexts.reaction("Toby", PetSex.MALE, Trick.COME));
        assertEquals("Toby chases his own tail in a circle", PetTexts.reaction("Toby", PetSex.MALE, Trick.SPIN));
        assertEquals("Lay", PetTexts.trickName(Trick.valueOf("sleep")));
    }

    @Test
    void refusalReasonsExplainTheRemedyAndPreserveCustomFeedback() {
        String[][] reasons = {
                {"sick", "Luna is too ill to play. Pet her and give medicine first"},
                {"tired", "Luna is too tired to keep going"},
                {"sleepy", "Luna isn't sleepy right now"},
                {"food", "Luna turns up her nose at that"},
                {"medicine", "Luna isn't sick and doesn't need medicine"},
                {"attention", "Luna is too distracted to listen"},
                {"owner", "Only her owner can do that"},
                {"full-out", "You already have as many pets out as you can look after"},
                {"full-stored", "Your Pet House has no room left"},
                {"bored", "Luna has had enough training for now"}
        };
        for (String[] reason : reasons) assertEquals(reason[1], PetTexts.refusal("Luna", PetSex.FEMALE, reason[0]));
        assertEquals("Toby needs a quieter place", PetTexts.refusal("Toby", PetSex.MALE, "Toby needs a quieter place"));
        assertEquals("Only his owner can do that", PetTexts.refusal("Toby", PetSex.MALE, "owner"));
        assertEquals("Toby turns up his nose at that", PetTexts.refusal("Toby", PetSex.MALE, "food"));
    }

    @Test
    void ageBoundariesUseCompleteUnitsAndHandleMissingOrFutureBirthTimes() {
        long born = 1_000_000;
        assertEquals("Unknown", PetTexts.age(-1, born));
        assertEquals("Unknown", PetTexts.age(born, born - 1));
        assertEquals("Newborn", PetTexts.age(born, born));
        assertEquals("Newborn", PetTexts.age(born, born + 59_999));
        assertEquals("59 minutes", PetTexts.age(born, born + 3_599_999));
        assertEquals("1 hour and 1 minute", PetTexts.age(born, born + 3_660_000));
        assertEquals("23 hours and 59 minutes", PetTexts.age(born, born + 86_399_999));
        assertEquals("1 day", PetTexts.age(born, born + 86_400_000));
        assertEquals("1 day and 1 hour", PetTexts.age(born, born + 90_000_000));
        assertEquals("2 days", PetTexts.age(born, born + 172_800_000));
    }
}
