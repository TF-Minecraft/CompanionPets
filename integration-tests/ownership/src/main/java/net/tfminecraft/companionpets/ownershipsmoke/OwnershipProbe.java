package net.tfminecraft.companionpets.ownershipsmoke;

import java.nio.file.Files;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.companionpets.body.*;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.ownership.*;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.*;
import net.tfminecraft.companionpets.session.*;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;
import net.tfminecraft.tlibs.character.*;
import com.destroystokyo.paper.entity.ai.*;

/** Synthetic loopback-only fixture: no RPCharacters/player records or external services. */
public final class OwnershipProbe extends JavaPlugin implements Listener, CharacterIdentity {
    private final UUID a=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), b=UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private CharacterSession session; private Player player; private Wolf wolf; private Zombie target; private Pet pet; private PetRuntime runtime; private PetStore store;
    private PetActions actions; private Goal<Mob> trainingGoal; private Location origin; private double aDistance; private final List<String> checks=new ArrayList<>();
    @Override public void onEnable() {
        if (!"127.0.0.1".equals(Bukkit.getIp()) || Bukkit.getPort()!=25583) throw new IllegalStateException("Loopback-only ownership fixture requires port 25583");
        Bukkit.getServicesManager().register(CharacterIdentity.class,this,this,ServicePriority.Highest);
        Bukkit.getPluginManager().registerEvents(this,this);
    }
    @EventHandler public void joined(PlayerJoinEvent event) {
        if (!event.getPlayer().getName().equals("S6PetProbe")) return;
        player=event.getPlayer();session=new CharacterSession(new CharacterRef(player.getUniqueId(),a),1);
        Bukkit.getScheduler().runTaskLater(this,()->phase(this::start),20);
    }
    private void phase(Runnable run) { try { run.run(); } catch(Throwable ex) { finish(false,ex.toString()); } }
    private void check(boolean value,String text) { if(!value)throw new AssertionError(text);checks.add(text); }
    private Goal<Tameable> follower() { return Bukkit.getMobGoals().getGoal(wolf,GoalKey.of(Tameable.class,new NamespacedKey(this,"owner_follow"))); }
    private void start() {
        try {
            var world=player.getWorld();origin=new Location(world,0.5,130,0.5);
            for(int x=-18;x<=35;x++)for(int z=-18;z<=18;z++){world.getBlockAt(x,129,z).setType(Material.STONE);world.getBlockAt(x,130,z).setType(Material.AIR);world.getBlockAt(x,131,z).setType(Material.AIR);}
            world.setGameRule(GameRule.DO_MOB_SPAWNING,false);player.setGameMode(GameMode.SURVIVAL);player.teleport(origin.clone().add(10,0,0));
            var config=new org.bukkit.configuration.file.YamlConfiguration();config.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, native-combat: true}}\n");
            store=new PetStore(this);check(store.load(),"synthetic store opened");var visual=new IdleVisual();var key=new NamespacedKey(this,"pet");
            runtime=new PetRuntime(this,CompanionConfig.load(this,config),store,new Sessions(),new Bodies(this,key,visual),visual,key,new NamespacedKey(this,"toy"));actions=new PetActions(runtime);
            Bukkit.getPluginManager().registerEvents(new CharacterLifecycle(runtime,actions),this);
            pet=new Pet(UUID.randomUUID(),player.getUniqueId(),"wolf","Ownership fixture",PetSex.MALE);pet.ownership(player.getUniqueId(),a);
            wolf=(Wolf)runtime.bodies().spawn(pet,runtime.config().type("wolf"),origin,player);
            check(wolf!=null&&wolf.isTamed()&&wolf.getOwner()!=null&&wolf.getOwner().getUniqueId().equals(player.getUniqueId()),"production spawn tames to the routing account");
            pet.entityId(wolf.getUniqueId());store.add(pet);
            OwnerFollowGoal.install(this,wolf,pet);check(follower()!=null&&follower().shouldActivate(),"actual native follower activates for A");
            aDistance=wolf.getLocation().distanceSquared(player.getLocation());
            Bukkit.getScheduler().runTaskLater(this,()->phase(this::switched),35);
        } catch(Exception ex){throw new RuntimeException(ex);}
    }
    private void switched() {
        check(wolf.getLocation().distanceSquared(player.getLocation())<aDistance-1,"actual native selector walks towards A");
        proveOwnerAssists();
        runtime.sessions().training(player.getUniqueId(),new TrainingSession(pet.id()));runtime.sessions().hatch(player.getUniqueId(),new HatchPrompt("wolf",Long.MAX_VALUE));
        try {
            var begin=Class.forName("net.tfminecraft.companionpets.runtime.TrainingNavigationGoal").getDeclaredMethod("begin",PetRuntime.class,Pet.class,Mob.class,Player.class);
            begin.setAccessible(true);begin.invoke(null,runtime,pet,wolf,player);
            trainingGoal=Bukkit.getMobGoals().getGoal(wolf,GoalKey.of(Mob.class,new NamespacedKey(this,"training_navigation")));
            check(trainingGoal!=null&&trainingGoal.shouldActivate(),"actual training posture activates under A");
        } catch(Exception ex){throw new RuntimeException(ex);}

        CharacterSession outgoing=session;session=null;Bukkit.getPluginManager().callEvent(new CharacterSessionEndEvent(player,outgoing,CharacterSessionEndEvent.Reason.SWITCH));
        session=new CharacterSession(new CharacterRef(player.getUniqueId(),b),2);Bukkit.getPluginManager().callEvent(new CharacterSessionStartEvent(player,session));
        wolf.teleport(origin);player.teleport(origin.clone().add(10,0,0));
        check(!follower().shouldActivate()&&!follower().shouldStayActive(),"same account B cannot activate or continue A follower");
        check(!trainingGoal.shouldActivate()&&!trainingGoal.shouldStayActive(),"outgoing training posture cannot survive A to B");
        check(runtime.sessions().training(player.getUniqueId())==null&&runtime.sessions().hatch(player.getUniqueId())==null,"outgoing training/hatch cleared before B");
        check(!CharacterAccess.owns(pet,player),"B has no pet management authority");
        wolf.setTarget(null);
        for(String label:List.of("owner_hurt_by","owner_hurt")) {
            var assist=Bukkit.getMobGoals().getGoal(wolf,GoalKey.of(Tameable.class,new NamespacedKey(this,label)));
            check(assist!=null&&!assist.shouldActivate()&&!assist.shouldStayActive(),"actual "+label+" denies same-account B");
            assist.start();check(wolf.getTarget()==null,"denied "+label+" cannot assign B's target");
        }
        Bukkit.getScheduler().runTaskLater(this,()->phase(this::water),35);
    }
    private void proveOwnerAssists() {
        try {
            target=wolf.getWorld().spawn(origin.clone().add(2,0,2),Zombie.class);target.setAI(false);
            markCombatMemory();
            for(String label:List.of("owner_hurt_by","owner_hurt")) {
                var assist=Bukkit.getMobGoals().getGoal(wolf,GoalKey.of(Tameable.class,new NamespacedKey(this,label)));
                check(assist!=null&&assist.shouldActivate(),"actual "+label+" allows active A");
                assist.start();check(wolf.getTarget()==target,"actual "+label+" delegates A's native target");assist.stop();wolf.setTarget(null);
            }
            check(Bukkit.getMobGoals().hasGoal(wolf,VanillaGoal.HURT_BY),"native self-defense target goal retained");
        } catch(Exception ex){throw new RuntimeException(ex);}
    }
    private void markCombatMemory() throws Exception {
        Object owner=player.getClass().getMethod("getHandle").invoke(player),victim=target.getClass().getMethod("getHandle").invoke(target);
        for(String setter:List.of("setLastHurtByMob","setLastHurtMob")) {
            var method=Arrays.stream(owner.getClass().getMethods()).filter(candidate->candidate.getName().equals(setter)&&candidate.getParameterCount()==1&&candidate.getParameterTypes()[0].isInstance(victim)).findFirst().orElseThrow();
            method.invoke(owner,victim);
        }
    }
    private void water() {
        try {
            markCombatMemory();
            for(String label:List.of("owner_hurt_by","owner_hurt")) {
                var assist=Bukkit.getMobGoals().getGoal(wolf,GoalKey.of(Tameable.class,new NamespacedKey(this,label)));
                var field=assist.getClass().getDeclaredField("nativeGoal");field.setAccessible(true);
                check(((Goal<?>)field.get(assist)).shouldActivate(),"ungated native "+label+" sees B's fresh account combat memory");
                check(!assist.shouldActivate()&&!assist.shouldStayActive(),"character gate denies fresh B combat memory for "+label);
            }
            target.remove();
        } catch(Exception ex){throw new RuntimeException(ex);}
        check(Bukkit.getMobGoals().getRunningGoals(wolf).stream().noneMatch(goal->goal.getKey().equals(follower().getKey())),"actual selector excludes follower while B is active");
        check(wolf.getLocation().distanceSquared(player.getLocation())>16,"A body remains separated from B");
        var world=wolf.getWorld();for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){world.getBlockAt(x,130,z).setType(Material.WATER);world.getBlockAt(x,131,z).setType(Material.WATER);}
        wolf.teleport(origin);wolf.setAware(true);
        Bukkit.getScheduler().runTaskLater(this,()->phase(this::wet),15);
    }
    private void wet() {
        check(wolf.isInWater(),"actual body is in water");
        check(Bukkit.getMobGoals().hasGoal(wolf,VanillaGoal.FLOAT),"native floating goal is retained");
        check(!follower().shouldActivate()&&!follower().shouldStayActive(),"water/safety posture cannot bypass follower character gate");
        runtime.bodies().reattach(wolf,pet,runtime.config().type("wolf"));
        check(!follower().shouldActivate(),"reattach retains exact character gate");
        check(wolf.getOwner()!=null&&wolf.getOwner().getUniqueId().equals(player.getUniqueId()),"production reattach retains vanilla routing account");
        pet.ownershipHeld(true);check(!follower().shouldActivate(),"held ownership cannot activate follower");pet.ownershipHeld(false);
        session=null;check(!follower().shouldActivate(),"unknown/inactive owner cannot activate follower");
        finish(true,"native follow, switch, training cleanup, water and reattach proved");
    }
    private void finish(boolean passed,String message) {
        try{getDataFolder().mkdirs();Files.writeString(getDataFolder().toPath().resolve("result.json"),"{\"passed\":"+passed+",\"checks\":"+checks.size()+",\"message\":\""+message.replace("\"","'")+"\"}\n");}
        catch(Exception ex){getLogger().severe(ex.toString());}
        checks.forEach(value->getLogger().info("CHECK "+value));getLogger().info("OWNERSHIP_PROOF "+(passed?"PASS ":"FAIL ")+message);
        if(target!=null)target.remove();if(wolf!=null)wolf.remove();if(store!=null)store.close();Bukkit.getScheduler().runTaskLater(this,()->Bukkit.shutdown(),5);
    }
    @Override public Optional<CharacterSession> currentSession(Player target){return target==player?Optional.ofNullable(session):Optional.empty();}
    @Override public boolean isCurrent(CharacterSession token){return token!=null&&token.equals(session);}
    @Override public Optional<CharacterInfo> info(UUID id){return player!=null&&(id.equals(a)||id.equals(b))?Optional.of(new CharacterInfo(new CharacterRef(player.getUniqueId(),id),id.equals(a)?"A":"B",CharacterStatus.ALIVE,null,Set.of(),0,null)):Optional.empty();}
    @Override public Optional<List<CharacterInfo>> characters(UUID account){return player!=null&&account.equals(player.getUniqueId())?Optional.of(List.of(info(a).orElseThrow(),info(b).orElseThrow())):Optional.empty();}
    @Override public Optional<CharacterInfo> lastPlayed(UUID account){return session==null?Optional.empty():info(session.ref().characterId());}
    @Override public Optional<CharacterRef> legacyHeir(UUID account){return player!=null&&account.equals(player.getUniqueId())?Optional.of(new CharacterRef(account,a)):Optional.empty();}
    @Override public boolean legacyHeirsReady(){return true;}
    @Override public String displayName(Player target){return target.getName();}
}
