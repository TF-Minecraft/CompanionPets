package net.tfminecraft.companionpets.testutil;

import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

/**
 * MockBukkit throws on Paper's {@code getHolder(false)}. Its inventories hold no block snapshot,
 * so the patch answers with the plain holder, which is what Paper returns for plugin GUIs.
 */
public final class HolderLookupPatch implements LauncherSessionListener {
    private static boolean installed;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        install();
    }

    /** Patches {@link InventoryMock#getHolder(boolean)} once per test JVM. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .type(is(InventoryMock.class))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(PlainHolder.class)
                        .on(named("getHolder").and(takesArguments(boolean.class)))))
                .installOn(ByteBuddyAgent.install());
        installed = true;
    }

    /** Skips the throwing body and returns {@code getHolder()} instead. */
    static final class PlainHolder {
        private PlainHolder() {
        }

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean skip() {
            return true;
        }

        @Advice.OnMethodExit
        static void exit(@Advice.This Inventory inventory,
                @Advice.Return(readOnly = false) InventoryHolder holder) {
            holder = inventory.getHolder();
        }
    }
}
