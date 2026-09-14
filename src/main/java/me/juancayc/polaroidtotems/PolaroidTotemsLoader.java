package me.juancayc.polaroidtotems;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Pulls the database libraries at load time.
 *
 * <p>Paper plugins have no {@code libraries:} key — that belongs to Spigot's {@code plugin.yml} and
 * is silently ignored in a {@code paper-plugin.yml}. A loader class declared through the
 * {@code loader:} key is the only route, which is why this class exists rather than a list of
 * coordinates in the manifest.
 *
 * <p>Keeping the jar free of these also avoids shipping sqlite-jdbc's per-platform native binaries,
 * and avoids the classloader fight that two plugins shading different HikariCP versions produce.
 *
 * <p>The three coordinates below must stay identical to the {@code compileOnly} ones in
 * build.gradle.kts. Compiling against one version and resolving another at runtime is how a plugin
 * gets a {@code NoSuchMethodError} that only appears on someone else's server.
 *
 * <p>MySQL is fetched unconditionally, even though {@code data.yml} defaults to sqlite. Resolving it
 * lazily would mean an operator who flips {@code type: mysql} gets a {@code ClassNotFoundException}
 * on the next boot instead of a working pool, and the download is a one-time cost paid at install.
 */
public final class PolaroidTotemsLoader implements PluginLoader {

    private static final String HIKARI = "com.zaxxer:HikariCP:7.0.2";
    private static final String SQLITE = "org.xerial:sqlite-jdbc:3.50.3.0";
    private static final String MYSQL = "com.mysql:mysql-connector-j:9.3.0";

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpath) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        resolver.addDependency(new Dependency(new DefaultArtifact(HIKARI), null));
        resolver.addDependency(new Dependency(new DefaultArtifact(SQLITE), null));
        resolver.addDependency(new Dependency(new DefaultArtifact(MYSQL), null));

        classpath.addLibrary(resolver);
    }
}
