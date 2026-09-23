package net.tfminecraft.companionpets.care;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Presence;

class NeedClockTest {
    @Test
    void hungerReachesCriticalAtTheConfiguredMinute() {
        Pet pet = pet();
        NeedClock.advance(pet, input(Presence.NEAR, false, false, false, true, minutes(30)));
        assertEquals(24.0, pet.need(Need.HUNGER), 0.001);
    }

    @Test
    void awayRateAndFrozenPresenceChangeTheClock() {
        Pet away = pet();
        NeedClock.advance(away, input(Presence.AWAY, false, false, false, false, minutes(30)));
        assertEquals(81.0, away.need(Need.HUNGER), 0.001);

        Pet frozen = pet();
        frozen.need(Need.HUNGER, 40);
        NeedClock.advance(frozen, input(Presence.FROZEN, true, false, false, false, minutes(30)));
        assertEquals(40.0, frozen.need(Need.HUNGER), 0.001);
    }

    @Test
    void playingPausesMoodAndWalkingSpendsEnergy() {
        Pet playing = pet();
        NeedClock.advance(playing, input(Presence.NEAR, false, true, false, true, minutes(30)));
        assertEquals(100.0, playing.need(Need.MOOD), 0.001);
        assertEquals(80.05, playing.need(Need.ENERGY), 0.001);

        Pet walking = pet();
        NeedClock.advance(walking, input(Presence.NEAR, true, false, false, true, minutes(40)));
        assertEquals(24.0, walking.need(Need.ENERGY), 0.001);
    }

    @Test
    void sleepRecoversEnergyAndSlowsHunger() {
        Pet pet = pet();
        pet.need(Need.ENERGY, 0);
        NeedClock.advance(pet, input(Presence.NEAR, false, false, true, true, minutes(30)));
        assertEquals(100.0, pet.need(Need.ENERGY), 0.001);
        assertEquals(62.0, pet.need(Need.HUNGER), 0.001);
    }

    @Test
    void dirtIsAChunkNotADrip() {
        Pet pet = pet();
        NeedClock.advance(pet, input(Presence.NEAR, false, false, false, true, minutes(20)));
        assertEquals(70.0, pet.need(Need.CLEANLINESS), 0.001);
        NeedClock.advance(pet, input(Presence.NEAR, false, false, false, true, minutes(20)));
        assertEquals(40.0, pet.need(Need.CLEANLINESS), 0.001);
    }

    @Test
    void unwellClearsWhenTheCauseIsFixedAndSickDoesNot() {
        Pet unwell = criticalPet();
        NeedClock.advance(unwell, input(Presence.NEAR, false, false, false, true, minutes(3)));
        assertEquals(Illness.UNWELL, unwell.illness());
        unwell.need(Need.HUNGER, 80);
        NeedClock.advance(unwell, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertEquals(Illness.NONE, unwell.illness());
        assertEquals(100.0, unwell.need(Need.HEALTH), 0.001);

        Pet sick = criticalPet();
        NeedClock.advance(sick, input(Presence.NEAR, false, false, false, true, minutes(6)));
        assertEquals(Illness.SICK, sick.illness());
        sick.need(Need.HUNGER, 80);
        NeedClock.advance(sick, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertEquals(Illness.SICK, sick.illness());
    }

    @Test
    void neglectCanWeakenOrKill() {
        Pet weakened = criticalPet();
        weakened.need(Need.HEALTH, 1);
        weakened.illness(Illness.UNWELL);
        NeedClock.advance(weakened, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertEquals(Illness.WEAKENED, weakened.illness());
        assertEquals(0.0, weakened.need(Need.HEALTH), 0.001);

        Pet dying = criticalPet();
        dying.need(Need.HEALTH, 1);
        dying.illness(Illness.UNWELL);
        CareSettings care = CareSettings.defaults();
        CareSettings lethal = new CareSettings(
                care.hungerMinutesToCritical(),
                care.moodMinutesToCritical(),
                care.energyMinutesToCritical(),
                care.dirtyEveryMinutes(),
                care.dirtyLoss(),
                care.minutesUntilUnwell(),
                care.minutesUntilSick(),
                care.decayWhileStored(),
                true,
                care.playSeconds(),
                care.playMoodGain(),
                care.playEnergyCost(),
                care.favoriteMoodMultiplier(),
                care.favoriteFoodMood(),
                care.sleepMinutesToFull(),
                care.sleepingHungerMultiplier(),
                care.healthLossPerMinute(),
                care.healthRegenPerMinute(),
                care.medicineHealthBump(),
                care.bondGainPerMinute(),
                care.bondLossPerMinute(),
                care.wakeMoodPenalty(),
                care.criticalSoundSeconds());
        List<CareNotice> notices = NeedClock.advance(dying, new CareInput(
                Presence.NEAR, false, false, false, true, minutes(1), lethal, 0.25));
        assertTrue(dying.dead());
        assertTrue(notices.stream().anyMatch(notice -> notice.kind() == CareNotice.Kind.DIED));
    }

    @Test
    void bondRisesWhenStableAndFallsOnlyAfterIllness() {
        Pet stable = pet();
        NeedClock.advance(stable, input(Presence.NEAR, false, false, false, true, minutes(10)));
        assertEquals(4.0, stable.bond(), 0.001);

        Pet brief = criticalPet();
        brief.bond(50);
        NeedClock.advance(brief, input(Presence.NEAR, false, false, false, true, minutes(2)));
        assertEquals(50.0, brief.bond(), 0.001);
        assertEquals(Illness.NONE, brief.illness());

        NeedClock.advance(brief, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertEquals(Illness.UNWELL, brief.illness());
        assertTrue(brief.bond() < 50.0);
    }

    @Test
    void lowWarningFiresOnceUntilTheNeedRecovers() {
        Pet pet = pet();
        pet.need(Need.HUNGER, 61);
        List<CareNotice> first = NeedClock.advance(pet, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertEquals(1, first.stream().filter(notice -> notice.need() == Need.HUNGER).count());
        List<CareNotice> second = NeedClock.advance(pet, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertEquals(0, second.stream().filter(notice -> notice.need() == Need.HUNGER).count());
        pet.need(Need.HUNGER, 80);
        NeedClock.advance(pet, input(Presence.FROZEN, false, false, false, false, minutes(1)));
        pet.need(Need.HUNGER, 61);
        List<CareNotice> third = NeedClock.advance(pet, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertEquals(1, third.stream().filter(notice -> notice.need() == Need.HUNGER).count());
    }

    @Test
    void treatedPetFinishesHealingOnlyWhenOtherNeedsAreClear() {
        Pet blocked = criticalPet();
        blocked.illness(Illness.SICK);
        blocked.treated(true);
        blocked.need(Need.HEALTH, 40);
        NeedClock.advance(blocked, input(Presence.NEAR, false, false, false, true, minutes(1)));
        assertTrue(blocked.need(Need.HEALTH) < 40.0);

        Pet healing = pet();
        healing.illness(Illness.SICK);
        healing.treated(true);
        healing.need(Need.HEALTH, 40);
        NeedClock.advance(healing, input(Presence.NEAR, false, false, false, true, minutes(3)));
        assertEquals(100.0, healing.need(Need.HEALTH), 0.001);
        assertEquals(Illness.NONE, healing.illness());
    }

    private static Pet criticalPet() {
        Pet pet = pet();
        pet.need(Need.HUNGER, 0);
        return pet;
    }

    private static Pet pet() {
        return new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Luna", PetSex.FEMALE);
    }

    private static CareInput input(
            Presence presence,
            boolean walking,
            boolean playing,
            boolean sleeping,
            boolean withOwner,
            long elapsed) {
        return new CareInput(presence, walking, playing, sleeping, withOwner, elapsed, CareSettings.defaults(), 0.25);
    }

    private static long minutes(double minutes) {
        return Math.round(minutes * 60_000.0);
    }
}
