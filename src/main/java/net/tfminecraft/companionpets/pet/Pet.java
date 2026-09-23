package net.tfminecraft.companionpets.pet;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.companionpets.play.FetchJob;

public final class Pet {
    private final UUID id;
    private final UUID ownerId;
    private final String typeId;
    private String name;
    private PetSex sex;
    private PetOrder order = PetOrder.FOLLOW;
    private Activity activity = Activity.NONE;
    private boolean staying;
    private boolean stored;
    private String worldName = "";
    private double x;
    private double y;
    private double z;
    private float yaw;
    private UUID entityId;
    private final EnumMap<Need, Double> needs = new EnumMap<>(Need.class);
    private double bond;
    private long criticalMillis;
    private long dirtyMillis;
    private final EnumSet<Need> announcedLow = EnumSet.noneOf(Need.class);
    private Illness illness = Illness.NONE;
    private boolean treated;
    private boolean dead;
    private String favoriteToy;
    private String carriedToy;
    private final Map<String, Trick> words = new LinkedHashMap<>();
    private final Map<Trick, Double> progress = new EnumMap<>(Trick.class);
    private long playUntilMillis;
    private long forcedSitUntilMillis;
    private FetchJob fetch;
    private long nextCryAtMillis;
    private long nextCriticalSoundAtMillis;
    private long pauseUntilMillis;

    public Pet(UUID id, UUID ownerId, String typeId, String name, PetSex sex) {
        this.id = id;
        this.ownerId = ownerId;
        this.typeId = typeId;
        this.name = name;
        this.sex = sex;
        for (Need need : Need.values()) {
            needs.put(need, 100.0);
        }
        needs.put(Need.HEALTH, 100.0);
        bond = 0.0;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String typeId() {
        return typeId;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public PetSex sex() {
        return sex;
    }

    public void sex(PetSex sex) {
        this.sex = sex;
    }

    public PetOrder order() {
        return order;
    }

    public void order(PetOrder order) {
        this.order = order;
    }

    public Activity activity() {
        return activity;
    }

    public void activity(Activity activity) {
        this.activity = activity;
    }

    public boolean staying() {
        return staying;
    }

    public void staying(boolean staying) {
        this.staying = staying;
    }

    public boolean stored() {
        return stored;
    }

    public void stored(boolean stored) {
        this.stored = stored;
    }

    public String worldName() {
        return worldName;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public void place(String worldName, double x, double y, double z, float yaw) {
        this.worldName = worldName == null ? "" : worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    public UUID entityId() {
        return entityId;
    }

    public void entityId(UUID entityId) {
        this.entityId = entityId;
    }

    public double need(Need need) {
        return needs.getOrDefault(need, 0.0);
    }

    public void need(Need need, double value) {
        needs.put(need, clamp(value));
    }

    public double bond() {
        return bond;
    }

    public void bond(double bond) {
        this.bond = clamp(bond);
    }

    public long criticalMillis() {
        return criticalMillis;
    }

    public void criticalMillis(long criticalMillis) {
        this.criticalMillis = Math.max(0L, criticalMillis);
    }

    public void addCriticalMillis(long elapsed) {
        this.criticalMillis = Math.max(0L, this.criticalMillis + elapsed);
    }

    public long dirtyMillis() {
        return dirtyMillis;
    }

    public void dirtyMillis(long dirtyMillis) {
        this.dirtyMillis = Math.max(0L, dirtyMillis);
    }

    public void addDirtyMillis(long elapsed) {
        this.dirtyMillis = Math.max(0L, this.dirtyMillis + elapsed);
    }

    public boolean announcedLow(Need need) {
        return announcedLow.contains(need);
    }

    public boolean markAnnouncedLow(Need need) {
        return announcedLow.add(need);
    }

    public void clearAnnouncedLow(Need need) {
        announcedLow.remove(need);
    }

    public EnumSet<Need> announcedLowView() {
        return EnumSet.copyOf(announcedLow);
    }

    public void announcedLow(EnumSet<Need> needs) {
        announcedLow.clear();
        if (needs != null) {
            announcedLow.addAll(needs);
        }
    }

    public Illness illness() {
        return illness;
    }

    public void illness(Illness illness) {
        this.illness = illness == null ? Illness.NONE : illness;
    }

    public boolean treated() {
        return treated;
    }

    public void treated(boolean treated) {
        this.treated = treated;
    }

    public boolean dead() {
        return dead;
    }

    public void dead(boolean dead) {
        this.dead = dead;
    }

    public String favoriteToy() {
        return favoriteToy;
    }

    public void favoriteToy(String favoriteToy) {
        this.favoriteToy = favoriteToy;
    }

    public String carriedToy() {
        return carriedToy;
    }

    public void carriedToy(String carriedToy) {
        this.carriedToy = carriedToy == null || carriedToy.isBlank() ? null : carriedToy;
    }

    public Map<String, Trick> words() {
        return Collections.unmodifiableMap(words);
    }

    public void bindWord(String word, Trick trick) {
        words.put(word.toLowerCase(Locale.ROOT), trick);
    }

    public Trick trickFor(String word) {
        if (word == null) {
            return null;
        }
        return words.get(word.toLowerCase(Locale.ROOT));
    }

    public boolean knowsWord(String word) {
        return trickFor(word) != null;
    }

    public double progress(Trick trick) {
        return progress.getOrDefault(trick, 0.0);
    }

    public void progress(Trick trick, double value) {
        progress.put(trick, Math.max(0.0, Math.min(100.0, value)));
    }

    public Map<Trick, Double> progressView() {
        return Collections.unmodifiableMap(progress);
    }

    public long playUntilMillis() {
        return playUntilMillis;
    }

    public void playUntilMillis(long playUntilMillis) {
        this.playUntilMillis = playUntilMillis;
    }

    public long forcedSitUntilMillis() {
        return forcedSitUntilMillis;
    }

    public void forcedSitUntilMillis(long forcedSitUntilMillis) {
        this.forcedSitUntilMillis = forcedSitUntilMillis;
    }

    public FetchJob fetch() {
        return fetch;
    }

    public void fetch(FetchJob fetch) {
        this.fetch = fetch;
    }

    public long nextCryAtMillis() {
        return nextCryAtMillis;
    }

    public void nextCryAtMillis(long nextCryAtMillis) {
        this.nextCryAtMillis = nextCryAtMillis;
    }

    public long nextCriticalSoundAtMillis() {
        return nextCriticalSoundAtMillis;
    }

    public void nextCriticalSoundAtMillis(long nextCriticalSoundAtMillis) {
        this.nextCriticalSoundAtMillis = nextCriticalSoundAtMillis;
    }

    public long pauseUntilMillis() {
        return pauseUntilMillis;
    }

    public void pauseUntilMillis(long pauseUntilMillis) {
        this.pauseUntilMillis = pauseUntilMillis;
    }

    public boolean causeCritical() {
        return need(Need.HUNGER) < 25.0
                || need(Need.MOOD) < 25.0
                || need(Need.ENERGY) < 25.0
                || need(Need.CLEANLINESS) < 25.0;
    }

    public boolean needsStable() {
        for (Need need : Need.values()) {
            if (need(need) < 60.0) {
                return false;
            }
        }
        return illness == Illness.NONE;
    }

    public void clearRuntimeMotion() {
        activity = Activity.NONE;
        fetch = null;
        playUntilMillis = 0L;
        forcedSitUntilMillis = 0L;
        pauseUntilMillis = 0L;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
