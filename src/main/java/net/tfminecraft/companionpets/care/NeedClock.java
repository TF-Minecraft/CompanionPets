package net.tfminecraft.companionpets.care;

import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Presence;

public final class NeedClock {
    private NeedClock() {
    }

    public static List<CareNotice> advance(Pet pet, CareInput input) {
        List<CareNotice> notices = new ArrayList<>();
        if (pet.dead() || input.elapsedMillis() <= 0) {
            return notices;
        }
        CareSettings care = input.care();
        boolean frozen = input.presence() == Presence.FROZEN;
        double rate = switch (input.presence()) {
            case FROZEN -> 0.0;
            case NEAR -> 1.0;
            case AWAY -> input.awayRate();
        };
        if (!frozen && rate > 0.0) {
            double minutes = input.elapsedMillis() / 60000.0 * rate;
            double hungerRate = input.sleeping() ? care.sleepingHungerMultiplier() : 1.0;
            pet.need(Need.HUNGER, pet.need(Need.HUNGER) - dropPerMinute(care.hungerMinutesToCritical()) * minutes * hungerRate);
            if (!input.playing()) {
                pet.need(Need.MOOD, pet.need(Need.MOOD) - dropPerMinute(care.moodMinutesToCritical()) * minutes);
            }
            if (input.walking() || (input.presence() == Presence.NEAR && !input.sleeping())) {
                double energyRate = input.walking() ? 1.0 : 0.35;
                pet.need(Need.ENERGY, pet.need(Need.ENERGY) - dropPerMinute(care.energyMinutesToCritical()) * minutes * energyRate);
            }
            if (input.sleeping()) {
                double fill = care.sleepMinutesToFull() <= 0 ? 100.0 : 100.0 / care.sleepMinutesToFull();
                pet.need(Need.ENERGY, pet.need(Need.ENERGY) + fill * minutes);
            }
            pet.addDirtyMillis(Math.round(input.elapsedMillis() * rate));
            long interval = Math.round(care.dirtyEveryMinutes() * 60000.0);
            if (interval > 0) {
                while (pet.dirtyMillis() >= interval) {
                    pet.addDirtyMillis(-interval);
                    pet.need(Need.CLEANLINESS, pet.need(Need.CLEANLINESS) - care.dirtyLoss());
                }
            }
        }

        boolean causeCritical = pet.causeCritical();
        if (causeCritical && !frozen) {
            pet.addCriticalMillis(input.elapsedMillis());
        } else if (!causeCritical) {
            pet.criticalMillis(0);
            if (pet.illness() == Illness.UNWELL) {
                pet.illness(Illness.NONE);
                pet.need(Need.HEALTH, 100);
            }
        }

        long unwellAt = Math.round(care.minutesUntilUnwell() * 60000.0);
        long sickAt = unwellAt + Math.round(care.minutesUntilSick() * 60000.0);
        if (!frozen && pet.illness() == Illness.NONE && pet.criticalMillis() >= unwellAt && unwellAt > 0) {
            pet.illness(Illness.UNWELL);
            notices.add(CareNotice.of(CareNotice.Kind.UNWELL));
        }
        if (!frozen && pet.illness() == Illness.UNWELL && pet.criticalMillis() >= sickAt) {
            pet.illness(Illness.SICK);
            notices.add(CareNotice.of(CareNotice.Kind.SICK));
        }

        if (!frozen && causeCritical && (pet.illness() == Illness.UNWELL || pet.illness() == Illness.SICK)) {
            double minutes = input.elapsedMillis() / 60000.0;
            pet.need(Need.HEALTH, pet.need(Need.HEALTH) - care.healthLossPerMinute() * minutes);
        }

        if (pet.need(Need.HEALTH) <= 0.0 && pet.illness() != Illness.NONE) {
            pet.need(Need.HEALTH, 0);
            if (care.deathOnNeglect()) {
                if (!pet.dead()) {
                    pet.dead(true);
                    notices.add(CareNotice.of(CareNotice.Kind.DIED));
                }
            } else if (pet.illness() != Illness.WEAKENED) {
                pet.illness(Illness.WEAKENED);
                notices.add(CareNotice.of(CareNotice.Kind.WEAKENED));
            }
        }

        if (pet.treated() && !pet.dead()) {
            boolean othersClear = !pet.causeCritical();
            boolean rested = input.sleeping() || pet.need(Need.ENERGY) >= 25.0;
            boolean fed = pet.need(Need.HUNGER) >= 25.0;
            boolean canRegen = switch (pet.illness()) {
                case WEAKENED -> othersClear && rested && fed;
                case SICK, UNWELL -> othersClear;
                case NONE -> false;
            };
            if (canRegen) {
                double minutes = input.elapsedMillis() / 60000.0;
                pet.need(Need.HEALTH, pet.need(Need.HEALTH) + care.healthRegenPerMinute() * minutes);
                if (pet.need(Need.HEALTH) >= 100.0) {
                    pet.need(Need.HEALTH, 100);
                    pet.illness(Illness.NONE);
                    pet.treated(false);
                }
            }
        }

        if (!frozen && input.withOwner() && input.presence() == Presence.NEAR && pet.needsStable()) {
            double minutes = input.elapsedMillis() / 60000.0;
            pet.bond(pet.bond() + care.bondGainPerMinute() * minutes);
        }
        if (!frozen && pet.illness() != Illness.NONE) {
            double minutes = input.elapsedMillis() / 60000.0;
            pet.bond(pet.bond() - care.bondLossPerMinute() * minutes);
        }

        for (Need need : List.of(Need.HUNGER, Need.MOOD, Need.ENERGY, Need.CLEANLINESS)) {
            double value = pet.need(need);
            if (value >= 60.0) {
                pet.clearAnnouncedLow(need);
            } else if (pet.markAnnouncedLow(need)) {
                notices.add(CareNotice.low(need));
            }
        }
        return notices;
    }

    static double dropPerMinute(double minutesToCritical) {
        if (minutesToCritical <= 0.0) {
            return 0.0;
        }
        return 76.0 / minutesToCritical;
    }
}
