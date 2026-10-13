package net.tfminecraft.companionpets.ownership;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

class CompatibilityTest {
    @Test void absentNewApiClassesDoNotLinkIdentityOrEstateBridges() throws Exception {
        var url=CharacterAccess.class.getProtectionDomain().getCodeSource().getLocation();
        try(var loader=new URLClassLoader(new java.net.URL[]{url},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("net.tfminecraft.tlibs.character."))throw new ClassNotFoundException(name);
                if(name.startsWith("net.tfminecraft.companionpets.")) {
                    Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
                return super.loadClass(name,resolve);
            }
        }) {
            var access=loader.loadClass(CharacterAccess.class.getName());
            assertEquals(false,access.getMethod("available").invoke(null));
            assertNull(access.getMethod("capture",Player.class).invoke(null,new Object[]{null}));
            assertEquals(false,access.getMethod("heirReady").invoke(null));
            assertEquals(java.util.Optional.empty(),access.getMethod("heir",UUID.class).invoke(null,UUID.randomUUID()));
            assertEquals(false,loader.loadClass("net.tfminecraft.companionpets.estate.EstateProbe").getMethod("available").invoke(null));
        }
    }
}
