import java.net.URLClassLoader;
import java.nio.file.Path;

/** Run with current Paper API dependencies on the parent classpath; load actual old TLibs separately. */
public final class LegacyApiCheck {
    public static void main(String[] args) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL(),Path.of(args[1]).toUri().toURL()},LegacyApiCheck.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.tfminecraft.companionpets.") || name.startsWith("net.tfminecraft.tlibs.")) {
                    Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
                return super.loadClass(name,resolve);
            }
        }) {
            Class<?> identity=loader.loadClass("net.tfminecraft.companionpets.ownership.CharacterAccess");
            if(!Boolean.FALSE.equals(identity.getMethod("available").invoke(null)))throw new AssertionError("Old TLibs incorrectly accepts character ownership migration");
            if(!Boolean.FALSE.equals(identity.getMethod("heirReady").invoke(null)))throw new AssertionError("Old provider readiness must fail closed");
            Class<?> estates=loader.loadClass("net.tfminecraft.companionpets.estate.EstateProbe");
            if(!Boolean.FALSE.equals(estates.getMethod("available").invoke(null)))throw new AssertionError("Old TLibs incorrectly accepts estate API");
            System.out.println("{\"passed\":true,\"checks\":3,\"legacyJar\":\""+args[1]+"\"}");
        }
    }
}
