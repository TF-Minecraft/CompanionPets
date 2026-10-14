package net.tfminecraft.companionpets.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.*;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.body.OwnerFollowGoal;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.tlibs.character.*;
import com.destroystokyo.paper.entity.ai.*;

class CharacterOwnershipTest {
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    @Test void sameAccountSwitchRejectsOwnershipStalePromptsMenusAndNativeFollowerWithoutRemovingSafety() {
        var server = MockBukkit.mock(new GoalServerMock()); var plugin = MockBukkit.createMockPlugin(); var player = server.addPlayer();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var identity = mock(CharacterIdentity.class); var current = new java.util.concurrent.atomic.AtomicReference<>(new CharacterSession(new CharacterRef(player.getUniqueId(), a), 1));
        when(identity.currentSession(player)).thenAnswer(call -> Optional.ofNullable(current.get()));
        when(identity.isCurrent(any())).thenAnswer(call -> call.getArgument(0).equals(current.get()));
        when(identity.info(a)).thenReturn(Optional.of(new CharacterInfo(new CharacterRef(player.getUniqueId(), a), "A", CharacterStatus.ALIVE, null, Set.of(), 0, null)));
        when(identity.info(b)).thenReturn(Optional.of(new CharacterInfo(new CharacterRef(player.getUniqueId(), b), "B", CharacterStatus.ALIVE, null, Set.of(), 0, null)));
        Bukkit.getServicesManager().register(CharacterIdentity.class, identity, plugin, ServicePriority.Highest);
        Pet pet = new Pet(UUID.randomUUID(), player.getUniqueId(), "wolf", "Toby", PetSex.MALE); pet.ownership(player.getUniqueId(), a);
        OwnerSession captured = CharacterAccess.capture(player); assertTrue(CharacterAccess.owns(pet, player));
        var sessions = new Sessions(); sessions.enforceCharacters(); var hatch = new HatchPrompt("wolf", 99999); sessions.hatch(player.getUniqueId(), hatch);
        var rename = new RenamePrompt(pet.id(), 99999); sessions.rename(player.getUniqueId(), rename);
        sessions.release(player.getUniqueId(), new ReleasePrompt(pet.id(), 99999)); sessions.training(player.getUniqueId(), new TrainingSession(pet.id()));
        var menu = new MenuHolder(MenuHolder.Kind.CARE, pet.id(), null); menu.capture(player); assertTrue(menu.current(player));
        var world = server.addSimpleWorld("world"); var wolf = world.spawn(new org.bukkit.Location(world, 0, 64, 0), org.bukkit.entity.Wolf.class);
        @SuppressWarnings("unchecked") Goal<org.bukkit.entity.Tameable> nativeGoal = mock(Goal.class);
        when(nativeGoal.getKey()).thenReturn(VanillaGoal.FOLLOW_OWNER); when(nativeGoal.getTypes()).thenReturn(EnumSet.of(GoalType.MOVE, GoalType.JUMP));
        when(nativeGoal.shouldActivate()).thenReturn(true); when(nativeGoal.shouldStayActive()).thenReturn(true);
        server.getMobGoals().addGoal(wolf, 6, nativeGoal);
        var assists=new ArrayList<Goal<org.bukkit.entity.Tameable>>();
        for(var ownerKey:List.of(VanillaGoal.OWNER_HURT_BY,VanillaGoal.OWNER_HURT)) {
            @SuppressWarnings("unchecked") Goal<org.bukkit.entity.Tameable> assist=mock(Goal.class);
            when(assist.getKey()).thenReturn(ownerKey);when(assist.getTypes()).thenReturn(EnumSet.of(GoalType.TARGET));
            when(assist.shouldActivate()).thenReturn(true);when(assist.shouldStayActive()).thenReturn(true);assists.add(assist);
            server.getMobGoals().addGoal(wolf,1,assist);
        }
        GoalKey<org.bukkit.entity.Mob> safetyKey = GoalKey.of(org.bukkit.entity.Mob.class, new NamespacedKey(plugin, "swim-safety"));
        @SuppressWarnings("unchecked") Goal<org.bukkit.entity.Mob> safety = mock(Goal.class); when(safety.getKey()).thenReturn(safetyKey); server.getMobGoals().addGoal(wolf, 0, safety);
        OwnerFollowGoal.install(plugin, wolf, pet);
        GoalKey<org.bukkit.entity.Tameable> key = GoalKey.of(org.bukkit.entity.Tameable.class, new NamespacedKey(plugin, "owner_follow"));
        var wrapped = server.getMobGoals().getGoal(wolf, key); assertTrue(wrapped.shouldActivate()); assertTrue(wrapped.shouldStayActive()); wrapped.start(); wrapped.tick();
        assertEquals(nativeGoal.getTypes(), wrapped.getTypes()); assertEquals(key, wrapped.getKey());
        var wrappedAssists=List.of("owner_hurt_by","owner_hurt").stream().map(label->server.getMobGoals().getGoal(wolf,GoalKey.of(org.bukkit.entity.Tameable.class,new NamespacedKey(plugin,label)))).toList();
        wrappedAssists.forEach(assist->{assertTrue(assist.shouldActivate());assertTrue(assist.shouldStayActive());assist.start();assist.tick();});
        current.set(new CharacterSession(new CharacterRef(player.getUniqueId(), a), 99));
        assertTrue(CharacterAccess.owns(pet,player));assertFalse(wrapped.shouldStayActive());
        wrappedAssists.forEach(assist->assertFalse(assist.shouldStayActive()));
        wrapped.stop();wrapped.start();wrappedAssists.forEach(assist->{assist.stop();assist.start();});
        current.set(new CharacterSession(new CharacterRef(player.getUniqueId(), b), 2));
        assertFalse(CharacterAccess.current(captured)); assertFalse(CharacterAccess.owns(pet, player)); assertFalse(menu.current(player));
        assertNull(sessions.hatch(player.getUniqueId())); assertNull(sessions.rename(player.getUniqueId())); assertNull(sessions.release(player.getUniqueId())); assertNull(sessions.training(player.getUniqueId()));
        var nextRename=new RenamePrompt(pet.id(),999999);sessions.rename(player.getUniqueId(),nextRename);
        assertNull(sessions.hatch(player.getUniqueId()));assertNull(sessions.release(player.getUniqueId()));assertNull(sessions.training(player.getUniqueId()));
        assertSame(nextRename,sessions.privatePrompt(player.getUniqueId()));
        assertFalse(wrapped.shouldActivate()); assertFalse(wrapped.shouldStayActive()); wrapped.start(); wrapped.tick(); wrapped.stop();
        wrappedAssists.forEach(assist->{assertFalse(assist.shouldActivate());assertFalse(assist.shouldStayActive());assist.start();assist.tick();assist.stop();});
        assists.forEach(assist->verify(assist,times(2)).start());
        assertSame(safety, server.getMobGoals().getGoal(wolf, safetyKey)); assertNull(server.getMobGoals().getGoal(wolf, VanillaGoal.FOLLOW_OWNER));
        OwnerFollowGoal.install(plugin, wolf, pet); assertSame(wrapped, server.getMobGoals().getGoal(wolf, key));
        current.set(null); sessions.clearPlayer(player.getUniqueId()); sessions.refresh(player.getUniqueId());
        sessions.hatch(player.getUniqueId(), hatch); assertNull(sessions.hatch(player.getUniqueId())); assertNull(sessions.token(player.getUniqueId()));
        assertNull(CharacterAccess.capture(null)); assertNull(CharacterAccess.person(a));
    }
    @Test void anotherAccountsLiveProviderTokenCannotAuthorizeThisConnectionOrCapturePrompts() {
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        var ref=new CharacterRef(UUID.randomUUID(),UUID.randomUUID());var token=new CharacterSession(ref,1);var identity=mock(CharacterIdentity.class);
        when(identity.currentSession(player)).thenReturn(Optional.of(token));when(identity.isCurrent(token)).thenReturn(true);
        when(identity.info(ref.characterId())).thenReturn(Optional.of(new CharacterInfo(ref,"Wrong account",CharacterStatus.ALIVE,null,Set.of(),0,null)));
        Bukkit.getServicesManager().register(CharacterIdentity.class,identity,plugin,ServicePriority.Highest);
        var pet=new Pet(UUID.randomUUID(),ref.ownerId(),"wolf","Other account pet",PetSex.MALE);pet.ownership(ref.ownerId(),ref.characterId());
        assertNull(CharacterAccess.capture(player));assertFalse(CharacterAccess.owns(pet,player));
        var sessions=new Sessions();sessions.enforceCharacters();sessions.hatch(player.getUniqueId(),new HatchPrompt("wolf",Long.MAX_VALUE));
        assertNull(sessions.hatch(player.getUniqueId()));assertNull(sessions.privatePrompt(player.getUniqueId()));assertNull(sessions.token(player.getUniqueId()));
        var menu=new MenuHolder(MenuHolder.Kind.CARE,pet.id(),null);menu.capture(player);assertFalse(menu.current(player));
    }
    @Test void staffCanResolveDeadIdentityForInspectionWhileCreationRequiresLivingAndReviewedHeirMayBeDead() {
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        UUID dead=UUID.randomUUID();var ref=new CharacterRef(player.getUniqueId(),dead);var identity=mock(CharacterIdentity.class);
        var info=new CharacterInfo(ref,"Old Owner",CharacterStatus.DEAD,null,Set.of(),0,null);
        when(identity.info(dead)).thenReturn(Optional.of(info));when(identity.findByName("Old Owner")).thenReturn(List.of(info));
        when(identity.legacyHeir(player.getUniqueId())).thenReturn(Optional.of(ref));
        Bukkit.getServicesManager().register(CharacterIdentity.class,identity,plugin,ServicePriority.Highest);
        assertEquals(dead,CharacterAccess.resolve(dead.toString()));assertEquals(dead,CharacterAccess.resolve("Old Owner"));
        assertFalse(CharacterAccess.alive(dead));assertFalse(CharacterAccess.alive(null));assertEquals(player.getUniqueId(),CharacterAccess.account(dead));
        assertEquals(Optional.of(dead),CharacterAccess.heir(player.getUniqueId()));
        assertNull(CharacterAccess.resolve(UUID.randomUUID().toString()));assertNull(CharacterAccess.resolve("missing"));
    }
    @Test void lifecycleUsesOutgoingRefAfterItIsNoncurrentAndRejectsMissingProvider() {
        var server=MockBukkit.mock();var plugin=MockBukkit.createMockPlugin();var player=server.addPlayer();
        var identity=mock(CharacterIdentity.class);var ref=new CharacterRef(player.getUniqueId(),UUID.randomUUID());var session=new CharacterSession(ref,3);
        when(identity.currentSession(player)).thenReturn(Optional.of(session));when(identity.isCurrent(session)).thenReturn(true);
        when(identity.info(ref.characterId())).thenReturn(Optional.of(new CharacterInfo(ref,"A",CharacterStatus.ALIVE,null,Set.of(),0,null)));
        when(identity.legacyHeirsReady()).thenReturn(true);when(identity.legacyHeir(player.getUniqueId())).thenReturn(Optional.of(ref));
        Bukkit.getServicesManager().register(CharacterIdentity.class,identity,plugin,ServicePriority.Highest);
        var sessions=new Sessions();sessions.enforceCharacters();sessions.hatch(player.getUniqueId(),new HatchPrompt("wolf",99999));
        var runtime=mock(net.tfminecraft.companionpets.runtime.PetRuntime.class);when(runtime.sessions()).thenReturn(sessions);
        var actions=mock(net.tfminecraft.companionpets.runtime.PetActions.class);var lifecycle=new CharacterLifecycle(runtime,actions);
        var holder=new MenuHolder(MenuHolder.Kind.CARE,UUID.randomUUID(),null);holder.capture(player);var inventory=Bukkit.createInventory(holder,9);holder.inventory(inventory);player.openInventory(inventory);
        when(identity.currentSession(player)).thenReturn(Optional.empty());when(identity.isCurrent(session)).thenReturn(false);
        lifecycle.end(new CharacterSessionEndEvent(player,session,CharacterSessionEndEvent.Reason.SWITCH));
        verify(actions).ownerSessionChanged(player);assertNull(sessions.privatePrompt(player.getUniqueId()));assertNotSame(inventory,player.getOpenInventory().getTopInventory());
        when(identity.currentSession(player)).thenReturn(Optional.of(session));when(identity.isCurrent(session)).thenReturn(true);
        lifecycle.start(new CharacterSessionStartEvent(player,session));assertNotNull(sessions.token(player.getUniqueId()));
        assertTrue(CharacterAccess.heirReady());assertEquals(Optional.of(ref.characterId()),CharacterAccess.heir(player.getUniqueId()));
        player.disconnect();lifecycle.end(new CharacterSessionEndEvent(player,session,CharacterSessionEndEvent.Reason.QUIT));lifecycle.start(new CharacterSessionStartEvent(player,session));
        Bukkit.getServicesManager().unregisterAll(plugin);assertNull(CharacterAccess.capture(player));assertFalse(CharacterAccess.heirReady());assertEquals(Optional.empty(),CharacterAccess.heir(player.getUniqueId()));
    }
}
