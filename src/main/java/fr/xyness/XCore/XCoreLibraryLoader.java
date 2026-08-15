package fr.xyness.XCore;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Paper {@link PluginLoader} that downloads runtime dependencies from Maven Central
 * before the plugin is loaded. This avoids bundling large drivers (MySQL, PostgreSQL, Jedis)
 * inside the plugin JAR.
 * <p>
 * Referenced in {@code paper-plugin.yml} via the {@code loader} field.
 * </p>
 */
public class XCoreLibraryLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addRepository(new RemoteRepository.Builder(
    	    "central-mirror", "default", "https://repo.papermc.io/repository/maven-public/"
    	).build());

        // Always needed: the pool and the cache are the two things every code path uses.
        resolver.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:5.1.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("com.github.ben-manes.caffeine:caffeine:3.1.8"), null));

        // Jedis is not optional, however much we would like it to be. XCore names JedisPoolConfig
        // in its own class body, so the JVM resolves it while verifying XCore itself — before a
        // single line has run, and regardless of what the configuration says. Leaving it out on a
        // server with Redis switched off is how the plugin came to fail at load with
        // NoClassDefFoundError: org/apache/commons/pool2/impl/GenericObjectPoolConfig, which is
        // JedisPoolConfig's superclass. It comes along with Jedis.
        resolver.addDependency(new Dependency(new DefaultArtifact("redis.clients:jedis:5.2.0"), null));

        // The JDBC drivers are another matter, and they are the bulk of it: nothing references
        // their classes by name, they are found through the connection URL. Downloading MySQL and
        // PostgreSQL on a server that stores everything in SQLite costs a first-start download and
        // two sets of classes resolved for nothing. What the configuration asks for is loaded;
        // an unreadable file falls back to loading both, because refusing to start over a
        // configuration we could not parse would be far worse.
        Wanted wanted = readConfiguration();

        if (wanted.mysql) {
            resolver.addDependency(new Dependency(new DefaultArtifact("com.mysql:mysql-connector-j:9.1.0"), null));
        }
        if (wanted.postgresql) {
            resolver.addDependency(new Dependency(new DefaultArtifact("org.postgresql:postgresql:42.7.4"), null));
        }

        classpathBuilder.addLibrary(resolver);
    }

    /** Which JDBC driver the configuration calls for. */
    private record Wanted(boolean mysql, boolean postgresql) {}

    /**
     * Reads {@code plugins/XCore/config.yml} to find out which driver is actually needed.
     *
     * <p>This runs before the plugin exists, so there is no configuration API to lean on — the file
     * is read as text and one question is asked of it. On a fresh install there is no file yet, and
     * the default (SQLite) is exactly right.</p>
     *
     * @return What to download; both drivers, if the file cannot be understood.
     */
    private Wanted readConfiguration() {
        java.nio.file.Path path = java.nio.file.Path.of("plugins", "XCore", "config.yml");
        if (!java.nio.file.Files.isReadable(path)) {
            // First start: the bundled default is sqlite.
            return new Wanted(false, false);
        }
        try {
            String content = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);

            java.util.regex.Matcher type = java.util.regex.Pattern
                    .compile("(?m)^\\s*database-type\\s*:\\s*[\"']?(\\w+)").matcher(content);
            String database = type.find() ? type.group(1).toLowerCase(java.util.Locale.ROOT) : "sqlite";

            return new Wanted("mysql".equals(database), "postgresql".equals(database));
        } catch (Throwable t) {
            return new Wanted(true, true);
        }
    }

}
