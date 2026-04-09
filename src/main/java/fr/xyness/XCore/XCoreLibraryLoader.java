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

        resolver.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:5.1.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("com.github.ben-manes.caffeine:caffeine:3.1.8"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("com.mysql:mysql-connector-j:9.1.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("org.postgresql:postgresql:42.7.4"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("redis.clients:jedis:5.2.0"), null));

        classpathBuilder.addLibrary(resolver);
    }

}
