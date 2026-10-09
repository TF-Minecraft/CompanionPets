package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.visual.IdleVisual;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.WolfMock;

class NavigationContractCoverageTest {
    GoalServerMock server; PetRuntime runtime; PetActions actions; Pet pet; WolfMock body; PetStore store;
    Pathfinder path;
    boolean inWater;
    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock(new GoalServerMock()); var world=server.addSimpleWorld("world");
        var plugin=MockBukkit.createMockPlugin(); var yaml=new YamlConfiguration();
        yaml.loadFromString("pets:\n  dog: {species: dog, egg: EGG}\n");
        var config=CompanionConfig.load(plugin,yaml); var key=new NamespacedKey(plugin,"pet");
        store=new PetStore(plugin); assertTrue(store.load()); var visual=new IdleVisual();
        runtime=new PetRuntime(plugin,config,store,new Sessions(),new Bodies(plugin,key,visual),visual,key,new NamespacedKey(plugin,"toy"));
        actions=new PetActions(runtime); var owner=server.addPlayer();
        pet=new Pet(UUID.randomUUID(),owner.getUniqueId(),"dog","Toby",PetSex.MALE); pet.stored(false);
        path=mock(Pathfinder.class);
        body=new WolfMock(server,UUID.randomUUID()) {
            @Override public Pathfinder getPathfinder() { return path; }
            @Override public boolean isInWater() { return inWater; }
        };
        body.teleport(new Location(world,0,64,0));
    }
    @AfterEach void teardown() { try { if(store!=null) store.close(); } finally { MockBukkit.unmock(); } }
    Goal<Mob> goal(String name) { return server.getMobGoals().getGoal(body,GoalKey.of(Mob.class,new NamespacedKey(runtime.plugin(),name))); }
    void inactive(String name,EnumSet<GoalType> types) {
        var goal=goal(name); assertNotNull(goal); assertFalse(goal.shouldActivate()); assertFalse(goal.shouldStayActive());
        assertEquals(types,goal.getTypes()); goal.tick(); assertFalse(goal.shouldActivate());
    }
    @Test void nameCallGoalRunsOnlyDuringAnActiveFollowCallAndRegistersOnce() {
        int[] steps={0}; Runnable step=()->steps[0]++;
        CallNavigationGoal.ensure(runtime,pet,body,step); var registered=goal("call_navigation");
        CallNavigationGoal.ensure(runtime,pet,body,step); assertSame(registered,goal("call_navigation"));
        inactive("call_navigation",EnumSet.of(GoalType.MOVE)); assertEquals(0,steps[0]);
        pet.activity(Activity.ATTENDING); pet.order(PetOrder.FOLLOW);
        assertTrue(registered.shouldStayActive()); registered.tick(); assertEquals(1,steps[0]);
        pet.stored(true); registered.tick(); assertFalse(registered.shouldStayActive()); assertEquals(1,steps[0]);
    }
    @Test void idleGreetingToyAndSocialGoalsYieldMovementWithoutStartingAnInteraction() {
        var greetings=new PetGreetings(runtime,actions);
        GreetingNavigationGoal.ensure(runtime,pet,body,greetings);
        var greeting=goal("greeting_navigation"); GreetingNavigationGoal.ensure(runtime,pet,body,greetings);
        assertSame(greeting,goal("greeting_navigation"));
        inactive("greeting_navigation",EnumSet.of(GoalType.MOVE,GoalType.JUMP)); assertFalse(greetings.active(pet));
        var toy=new PetToyAnticipation(runtime,actions);
        ToyNavigationGoal.ensure(runtime,pet,body,toy); ToyNavigationGoal.ensure(runtime,pet,body,toy);
        inactive("toy_navigation",EnumSet.of(GoalType.MOVE,GoalType.JUMP)); assertFalse(toy.active(pet));
        var social=new PetSocial(runtime,new PetRoaming(runtime,(entity,sleep)->{}));
        SocialNavigationGoal.ensure(runtime,pet,body,social); SocialNavigationGoal.ensure(runtime,pet,body,social);
        inactive("social_navigation",EnumSet.of(GoalType.MOVE,GoalType.JUMP)); assertFalse(social.engaged(pet));
        verifyNoInteractions(path);
    }
    @Test void inactiveFetchAndWaterGoalsDoNotMoveStoredPets() {
        int[] steps={0}; FetchNavigationGoal.ensure(runtime,pet,body,()->steps[0]++);
        inactive("fetch_navigation",EnumSet.of(GoalType.MOVE)); assertEquals(0,steps[0]);
        pet.stored(true); WaterNavigationGoal.ensure(runtime,pet,body);
        inactive("water_navigation",EnumSet.of(GoalType.MOVE));
        goal("water_navigation").start(); verifyNoInteractions(path);
        goal("water_navigation").stop(); verifyNoInteractions(path);
    }

}
