package ac.core.config;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the configured data root into an absolute directory.
 *
 * <p>The value may use several notations, all resolved before the Spring context starts because the
 * H2 URL and every tenant directory are derived from it:
 * <ul>
 *   <li>absolute Windows path, e.g. {@code C:\otc-data} or {@code E:/otc-data};</li>
 *   <li>absolute Unix path, e.g. {@code /srv/otc-data}; on Windows the MSYS form {@code /c/otc-data}
 *       is accepted as well and mapped to {@code C:\otc-data};</li>
 *   <li>path relative to the working directory, e.g. {@code ./data} or {@code mydata/otc};</li>
 *   <li>{@code classpath:data} - the classpath root holding {@code application.yml}, which is how a
 *       source checkout runs from an IDE; not writable inside a packaged jar;</li>
 *   <li>{@code projectdir:data} - the project directory when running from an IDE, or the directory
 *       holding the jar when running the packaged application.</li>
 * </ul>
 */
public final class DataRootResolver {

    /** Prefix for a path below the classpath root, used when running from sources. */
    public static final String CLASSPATH_PREFIX = "classpath:";

    /** Prefix for a path below the project directory (sources) or the jar directory (packaged). */
    public static final String PROJECT_DIR_PREFIX = "projectdir:";

    /** Resource used to locate the classpath root: it always ships with the application. */
    private static final String CLASSPATH_ANCHOR = "application.yml";

    /** Marks the project root of a source checkout. */
    private static final List<String> PROJECT_MARKERS = List.of("settings.gradle", "settings.gradle.kts", ".git");

    private DataRootResolver() {
    }

    /** Resolves the value of {@code -Dapp.data-root} or {@code APP_DATA_ROOT}. */
    public static Path resolve(String raw) {
        return resolve(raw, Path.of("").toAbsolutePath(), codeSource(), null);
    }

    /**
     * Resolves one data root notation.
     *
     * @param raw              configured value, may be blank
     * @param workingDirectory directory relative values are resolved against
     * @param codeSource       location the running classes come from (directory or jar)
     * @param classpathRoot    explicit classpath root, {@code null} to detect it from the classpath
     * @return absolute normalized directory
     */
    static Path resolve(String raw, Path workingDirectory, Path codeSource, Path classpathRoot) {
        String value = clean(raw);
        if (value.isEmpty()) {
            return workingDirectory.resolve("data").toAbsolutePath().normalize();
        }
        if (value.regionMatches(true, 0, CLASSPATH_PREFIX, 0, CLASSPATH_PREFIX.length())) {
            String rest = value.substring(CLASSPATH_PREFIX.length());
            return classpathBase(classpathRoot).resolve(rest).toAbsolutePath().normalize();
        }
        if (value.regionMatches(true, 0, PROJECT_DIR_PREFIX, 0, PROJECT_DIR_PREFIX.length())) {
            String rest = value.substring(PROJECT_DIR_PREFIX.length());
            return projectDirectory(codeSource, workingDirectory, System.getProperty("java.class.path", ""))
                    .resolve(rest).toAbsolutePath().normalize();
        }
        String mapped = msysToWindows(value);
        try {
            Path path = Path.of(mapped);
            return (path.isAbsolute() ? path : workingDirectory.resolve(path)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Data root '" + raw + "' is not a valid path: " + e.getMessage(), e);
        }
    }

    /**
     * Root used by the {@code classpath:} notation.
     *
     * @throws IllegalStateException when the classes are packaged in a jar, where the data could not
     *                               be written
     */
    private static Path classpathBase(Path explicit) {
        if (explicit != null) {
            return explicit;
        }
        return classpathBaseFrom(resourcesOf(CLASSPATH_ANCHOR));
    }

    /**
     * Classpath root holding the anchor resource.
     *
     * @param anchors locations of {@link #CLASSPATH_ANCHOR}, as returned by the class loader
     * @throws IllegalStateException when no location is a plain directory (the classes sit in a jar)
     */
    static Path classpathBaseFrom(List<URL> anchors) {
        for (URL url : anchors) {
            if (!"file".equals(url.getProtocol())) {
                continue;
            }
            try {
                return Path.of(url.toURI()).toAbsolutePath().normalize().getParent();
            } catch (URISyntaxException | IllegalArgumentException e) {
                throw new IllegalStateException("Cannot read the classpath root " + url + ": "
                        + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("The '" + CLASSPATH_PREFIX + "' data root needs a classpath directory, "
                + "but the application runs from a jar. Use '" + PROJECT_DIR_PREFIX
                + "' or an absolute path instead.");
    }

    /** Locations of one resource, in class loader order. */
    private static List<URL> resourcesOf(String name) {
        List<URL> urls = new java.util.ArrayList<>();
        try {
            Enumeration<URL> resources = classLoader().getResources(name);
            while (resources.hasMoreElements()) {
                urls.add(resources.nextElement());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate the classpath root: " + e.getMessage(), e);
        }
        return urls;
    }

    /**
     * Directory behind the {@code projectdir:} notation: the jar directory for a packaged run, the
     * project root for a source run.
     *
     * <p>A Spring Boot jar reports a {@code jar:nested:} code source, which is not a file path, so the
     * packaged case falls back to the jar named on the command line.
     */
    static Path projectDirectory(Path codeSource, Path workingDirectory, String classPath) {
        if (codeSource != null && Files.isRegularFile(codeSource)) {
            Path parent = codeSource.toAbsolutePath().normalize().getParent();
            return parent == null ? workingDirectory : parent;
        }
        if (codeSource != null) {
            // running from classes: the project root is the nearest ancestor carrying a build marker
            for (Path current = codeSource.toAbsolutePath().normalize(); current != null;
                    current = current.getParent()) {
                for (String marker : PROJECT_MARKERS) {
                    if (Files.exists(current.resolve(marker))) {
                        return current;
                    }
                }
            }
            return codeSource.toAbsolutePath().normalize();
        }
        Path jar = launcherJar(classPath, workingDirectory);
        if (jar != null) {
            // packaged: the jar sits in the directory the operator launched from
            Path parent = jar.getParent();
            return parent == null ? workingDirectory : parent;
        }
        return workingDirectory.toAbsolutePath().normalize();
    }

    /** Jar the application was launched from, derived from the class path; {@code null} if none. */
    private static Path launcherJar(String classPath, Path workingDirectory) {
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (entry.isEmpty() || !entry.endsWith(".jar")) {
                continue;
            }
            try {
                Path candidate = workingDirectory.resolve(entry).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException e) {
                // ignore a malformed entry and keep looking
            }
        }
        return null;
    }

    /** Maps the MSYS/Git-Bash drive notation {@code /c/foo} onto {@code C:\foo}; other values pass through. */
    private static String msysToWindows(String value) {
        if (!isWindows() || value.length() < 2 || value.charAt(0) != '/') {
            return value;
        }
        char drive = value.charAt(1);
        if (!Character.isLetter(drive)) {
            return value;
        }
        if (value.length() > 2 && value.charAt(2) != '/') {
            return value;
        }
        String rest = value.length() > 2 ? value.substring(3).replace('/', '\\') : "";
        String mapped = Character.toUpperCase(drive) + ":\\" + rest;
        return mapped;
    }

    /** Trims the value and drops the quotes a shell may keep. */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? DataRootResolver.class.getClassLoader() : loader;
    }

    /** Location of the running classes, only when it is a plain file path; {@code null} otherwise. */
    private static Path codeSource() {
        CodeSource source = DataRootResolver.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        URL location = source.getLocation();
        if (!"file".equals(location.getProtocol())) {
            // a packaged Spring Boot jar reports a jar:nested: location, not a usable file path
            return null;
        }
        try {
            return Path.of(location.toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }
}
