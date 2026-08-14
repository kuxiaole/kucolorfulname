package cc.chlchun.kucolorfulname;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/** Resolves runtime libraries before the plugin main class is loaded. */
public final class KuColorfulNamePluginLoader implements PluginLoader {
    private static final String LIBRARY_RESOURCE = "/paper-libraries.txt";
    private static final List<Repository> REPOSITORIES = List.of(
            new Repository("aliyun-central", "https://maven.aliyun.com/repository/central/"),
            new Repository("huawei-central", "https://repo.huaweicloud.com/repository/maven/")
    );

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        REPOSITORIES.forEach(repository -> resolver.addRepository(new RemoteRepository.Builder(
                repository.id(), "default", repository.url()).build()));

        try (var stream = KuColorfulNamePluginLoader.class.getResourceAsStream(LIBRARY_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing runtime library list: " + LIBRARY_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(coordinate -> resolver.addDependency(
                                new Dependency(new DefaultArtifact(coordinate), null)));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read runtime library list", exception);
        }

        classpathBuilder.addLibrary(resolver);
    }

    private record Repository(String id, String url) { }
}
