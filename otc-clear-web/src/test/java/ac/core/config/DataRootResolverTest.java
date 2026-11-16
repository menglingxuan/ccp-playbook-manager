package ac.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The data root accepts absolute, relative, classpath and project directory notations. */
class DataRootResolverTest {

    @TempDir
    Path workingDirectory;

    @Test
    void blankValueFallsBackToDataUnderTheWorkingDirectory() {
        assertThat(DataRootResolver.resolve(null, workingDirectory, null, null))
                .isEqualTo(workingDirectory.resolve("data").toAbsolutePath().normalize());
        assertThat(DataRootResolver.resolve("   ", workingDirectory, null, null))
                .isEqualTo(workingDirectory.resolve("data").toAbsolutePath().normalize());
    }

    @Test
    void relativeValuesResolveAgainstTheWorkingDirectory() {
        assertThat(DataRootResolver.resolve("./data", workingDirectory, null, null))
                .isEqualTo(workingDirectory.resolve("data").toAbsolutePath().normalize());
        assertThat(DataRootResolver.resolve("mydata/otc", workingDirectory, null, null))
                .isEqualTo(workingDirectory.resolve("mydata/otc").toAbsolutePath().normalize());
    }

    @Test
    void quotesAroundTheValueAreDropped() {
        assertThat(DataRootResolver.resolve("\"mydata\"", workingDirectory, null, null))
                .isEqualTo(workingDirectory.resolve("mydata").toAbsolutePath().normalize());
        assertThat(DataRootResolver.resolve("'mydata'", workingDirectory, null, null))
                .isEqualTo(workingDirectory.resolve("mydata").toAbsolutePath().normalize());
    }

    @Test
    void windowsAbsolutePathIsKept() {
        assumeTrue(windows());
        assertThat(DataRootResolver.resolve("C:\\otc\\data", workingDirectory, null, null).toString())
                .isEqualTo(Path.of("C:\\otc\\data").toAbsolutePath().normalize().toString());
        assertThat(DataRootResolver.resolve("C:/otc/data", workingDirectory, null, null).toString())
                .isEqualTo(Path.of("C:/otc/data").toAbsolutePath().normalize().toString());
    }

    @Test
    void msysStyleUnixPathMapsToTheWindowsDrive() {
        assumeTrue(windows());
        assertThat(DataRootResolver.resolve("/c/otc/data", workingDirectory, null, null).toString())
                .isEqualTo(Path.of("C:\\otc\\data").toAbsolutePath().normalize().toString());
        // a bare drive root has no trailing segment
        assertThat(DataRootResolver.resolve("/d", workingDirectory, null, null).toString())
                .isEqualTo(Path.of("D:").toAbsolutePath().normalize().toString());
    }

    @Test
    void unixAbsolutePathIsKept() {
        assumeTrue(!windows());
        assertThat(DataRootResolver.resolve("/srv/otc", workingDirectory, null, null).toString())
                .isEqualTo(Path.of("/srv/otc").toAbsolutePath().normalize().toString());
    }

    @Test
    void classpathNotationResolvesAgainstTheClasspathRoot() throws Exception {
        Path classpathRoot = workingDirectory.resolve("classes");
        Files.createDirectories(classpathRoot);
        assertThat(DataRootResolver.resolve("classpath:data", workingDirectory, null, classpathRoot))
                .isEqualTo(classpathRoot.resolve("data").toAbsolutePath().normalize());
    }

    @Test
    void classpathNotationWithoutADirectoryExplainsTheAlternative() throws Exception {
        // inside a jar every anchor is a jar: URL, so nothing writable can be derived
        java.net.URL jarAnchor = new java.net.URL("jar:file:/app/otc-clear-testdata.jar!/application.yml");
        assertThatThrownBy(() -> DataRootResolver.classpathBaseFrom(java.util.List.of(jarAnchor)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runs from a jar")
                .hasMessageContaining("projectdir:");
    }

    @Test
    void projectDirNotationUsesTheJarDirectoryWhenPackaged() throws Exception {
        Path libs = workingDirectory.resolve("build/libs");
        Files.createDirectories(libs);
        Path jar = Files.createFile(libs.resolve("otc-clear-testdata.jar"));
        assertThat(DataRootResolver.resolve("projectdir:data", workingDirectory, jar, null))
                .isEqualTo(libs.resolve("data").toAbsolutePath().normalize());
    }

    @Test
    void projectDirNotationFallsBackToTheLaunchedJar() throws Exception {
        // a Spring Boot jar reports a jar:nested: code source, so the class path decides
        Path libs = workingDirectory.resolve("libs");
        Files.createDirectories(libs);
        Files.createFile(libs.resolve("otc-clear-testdata.jar"));
        assertThat(DataRootResolver.projectDirectory(null, workingDirectory, "libs/otc-clear-testdata.jar"
                .replace('/', java.io.File.separatorChar)))
                .isEqualTo(libs.toAbsolutePath().normalize());
        // without any jar the working directory is the best guess
        assertThat(DataRootResolver.projectDirectory(null, workingDirectory, ""))
                .isEqualTo(workingDirectory.toAbsolutePath().normalize());
    }

    @Test
    void projectDirNotationUsesTheProjectRootWhenRunningFromSources() throws Exception {
        Path project = workingDirectory.resolve("project");
        Path classes = project.resolve("otc-clear-web/build/classes/java/main");
        Files.createDirectories(classes);
        Files.createFile(project.resolve("settings.gradle"));
        assertThat(DataRootResolver.resolve("projectdir:data", workingDirectory, classes, null))
                .isEqualTo(project.resolve("data").toAbsolutePath().normalize());
    }

    @Test
    void notationsAreDocumented() {
        String help = StartupHelp.text();
        assertThat(help).contains("classpath:data", "projectdir:data", "./data", "APP_DATA_ROOT");
        assertThat(DataRootResolver.CLASSPATH_PREFIX).isEqualTo("classpath:");
        assertThat(DataRootResolver.PROJECT_DIR_PREFIX).isEqualTo("projectdir:");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
