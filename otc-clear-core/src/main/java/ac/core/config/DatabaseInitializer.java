package ac.core.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Wipes the data of one tenant before the application starts.
 *
 * <p>Driven by {@code --init-database}. The database files and the tenant file areas are removed, so
 * the next start migrates an empty schema and seeds the factory definitions again. Only the resolved
 * tenant directory is touched, which keeps the other tenants of a shared data root intact.
 *
 * <p>Nothing is deleted before the operator confirms, and a database still locked by a running
 * instance blocks the whole operation: deleting the files underneath a live process would leave that
 * process writing to a file nobody can see any more.
 */
public final class DatabaseInitializer {

    /** Tenant subdirectories holding data; anything else inside the tenant directory is left alone. */
    static final List<String> DATA_DIRECTORIES = List.of("db", "uploads", "batches", "exports", "jobs");

    /** File of the embedded database inside the {@code db} directory. */
    static final String DATABASE_FILE = "otc.mv.db";

    private DatabaseInitializer() {
    }

    /** Asks the operator to confirm the destructive step. */
    @FunctionalInterface
    public interface Confirmation {

        /** @return true only when the operator explicitly confirmed */
        boolean confirmed();
    }

    /** How one initialization run ended. */
    public enum Status {

        /** The data is gone (or was already empty), the application may start. */
        DONE,

        /** The operator did not type {@code YES}; nothing was touched. */
        DECLINED,

        /** Another instance is using the database of this tenant, so nothing can be deleted. */
        BLOCKED,

        /** Some paths could not be deleted; the startup must not continue. */
        FAILED
    }

    /**
     * Outcome of one initialization run.
     *
     * @param status   how the run ended
     * @param removed  tenant subdirectories that were deleted
     * @param files    number of deleted files
     * @param bytes    freed bytes
     * @param problems paths that blocked or failed the run
     */
    public record Report(Status status, List<String> removed, long files, long bytes, List<String> problems) {

        public Report {
            removed = List.copyOf(removed);
            problems = List.copyOf(problems);
        }

        public boolean ok() {
            return status == Status.DONE;
        }
    }

    /**
     * Lists the data of one tenant, asks for confirmation and deletes it.
     *
     * @param tenantDirectory {@code <dataRoot>/tenants/<id>}
     * @param confirmation    operator prompt
     * @param out             console used for the listing and the result
     */
    public static Report initialize(Path tenantDirectory, Confirmation confirmation, PrintStream out) {
        Path root = tenantDirectory.toAbsolutePath().normalize();
        List<Path> targets = new ArrayList<>();
        for (String name : DATA_DIRECTORIES) {
            Path candidate = root.resolve(name).normalize();
            if (!candidate.startsWith(root)) {
                // defensive: the tenant id is validated, a traversal would still be refused
                throw new IllegalStateException("Refusing to delete outside " + root + ": " + candidate);
            }
            if (Files.exists(candidate)) {
                targets.add(candidate);
            }
        }

        out.println("--init-database: tenant data to delete");
        out.println("  tenant directory: " + root);
        long files = 0;
        long bytes = 0;
        if (targets.isEmpty()) {
            out.println("  (already empty, a fresh database will be created)");
        } else {
            for (Path target : targets) {
                out.println("  " + target);
                files += countFiles(target);
                bytes += countBytes(target);
            }
            out.println("  " + files + " file(s), " + bytes + " byte(s)");
        }

        String locked = databaseInUse(root.resolve("db"));
        if (locked != null) {
            out.println("Initialization refused: " + locked + " is locked by a running instance.");
            out.println("Stop every instance of this tenant, then start again with --init-database.");
            return new Report(Status.BLOCKED, List.of(), 0, 0, List.of(locked));
        }

        out.println("Only the tenant directory above is affected. Type YES to continue:");
        if (!confirmation.confirmed()) {
            out.println("Initialization cancelled: nothing was deleted.");
            return new Report(Status.DECLINED, List.of(), 0, 0, List.of());
        }

        List<String> removed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long deletedFiles = 0;
        long freedBytes = 0;
        for (Path target : targets) {
            long targetFiles = countFiles(target);
            long targetBytes = countBytes(target);
            if (deleteRecursively(target)) {
                removed.add(target.toString());
                deletedFiles += targetFiles;
                freedBytes += targetBytes;
            } else {
                failed.add(target.toString());
            }
        }
        if (!failed.isEmpty()) {
            failed.forEach(path -> out.println("  cannot delete " + path));
            return new Report(Status.FAILED, removed, deletedFiles, freedBytes, failed);
        }
        out.println("Deleted " + removed.size() + " entr(ies), " + deletedFiles + " file(s), "
                + freedBytes + " byte(s). A fresh database will be created.");
        return new Report(Status.DONE, removed, deletedFiles, freedBytes, List.of());
    }

    /**
     * Confirmation that reads one line from the given stream.
     *
     * @param in console input; an end of stream counts as a refusal
     */
    public static Confirmation consoleConfirmation(InputStream in) {
        return () -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line = reader.readLine();
                return line != null && "YES".equalsIgnoreCase(line.trim());
            } catch (IOException e) {
                return false;
            }
        };
    }

    /**
     * Probe for a live instance through the file lock the embedded database holds.
     *
     * @return the locked database file, or {@code null} when it is free
     */
    private static String databaseInUse(Path dbDir) {
        Path database = dbDir.resolve(DATABASE_FILE);
        if (!Files.exists(database)) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return database.toString();
            }
            lock.release();
            return null;
        } catch (OverlappingFileLockException | IOException e) {
            return database.toString();
        }
    }

    private static long countFiles(Path path) {
        if (!Files.isDirectory(path)) {
            return Files.exists(path) ? 1 : 0;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long countBytes(Path path) {
        if (!Files.isDirectory(path)) {
            return sizeOf(path);
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(DatabaseInitializer::sizeOf).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Deletes a file or a directory tree; returns false when something could not be removed. */
    private static boolean deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> entries = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
