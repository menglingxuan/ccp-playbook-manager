package ac.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code --init-database}: the tenant data is wiped only when the operator confirms. */
class DatabaseInitializerTest {

    @TempDir
    Path dataRoot;

    private ByteArrayOutputStream console;

    @BeforeEach
    void setUp() {
        console = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        console.reset();
    }

    @Test
    void confirmedRunDeletesTheTenantDataOnly() throws Exception {
        Path tenant = dataRoot.resolve("tenants").resolve("hkex-otc");
        Path other = dataRoot.resolve("tenants").resolve("other-tenant");
        write(tenant.resolve("db").resolve("otc.mv.db"), "database");
        write(tenant.resolve("uploads").resolve("20260101").resolve("u1.csv"), "upload");
        write(tenant.resolve("batches").resolve("b1").resolve("source.xlsx"), "baseline");
        write(tenant.resolve("exports").resolve("20260101").resolve("e1.csv"), "export");
        write(tenant.resolve("jobs").resolve("job.txt"), "job");
        write(tenant.resolve("keep-me.txt"), "not a data directory");
        write(other.resolve("db").resolve("otc.mv.db"), "other tenant database");

        DatabaseInitializer.Report report =
                DatabaseInitializer.initialize(tenant, () -> true, new PrintStream(console));

        assertThat(report.status()).isEqualTo(DatabaseInitializer.Status.DONE);
        assertThat(report.ok()).isTrue();
        assertThat(report.removed()).hasSize(5);
        assertThat(report.files()).isEqualTo(5);
        assertThat(report.bytes()).isEqualTo(8 + 6 + 8 + 6 + 3);
        for (String name : DatabaseInitializer.DATA_DIRECTORIES) {
            assertThat(Files.exists(tenant.resolve(name))).as(name).isFalse();
        }
        // anything outside the known data directories, and every other tenant, is untouched
        assertThat(Files.exists(tenant.resolve("keep-me.txt"))).isTrue();
        assertThat(Files.exists(other.resolve("db").resolve("otc.mv.db"))).isTrue();
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("5 file(s)").contains("YES");
    }

    @Test
    void declinedRunKeepsEverything() throws Exception {
        Path tenant = dataRoot.resolve("tenants").resolve("hkex-otc");
        Path database = tenant.resolve("db").resolve("otc.mv.db");
        write(database, "database");

        DatabaseInitializer.Report report =
                DatabaseInitializer.initialize(tenant, () -> false, new PrintStream(console));

        assertThat(report.status()).isEqualTo(DatabaseInitializer.Status.DECLINED);
        assertThat(report.ok()).isFalse();
        assertThat(report.removed()).isEmpty();
        assertThat(Files.exists(database)).isTrue();
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("cancelled");
    }

    @Test
    void lockedDatabaseBlocksTheRunEvenWhenConfirmed() throws Exception {
        Path tenant = dataRoot.resolve("tenants").resolve("hkex-otc");
        Path database = tenant.resolve("db").resolve("otc.mv.db");
        write(database, "database");
        // a second open channel with an exclusive lock stands in for a running instance
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.WRITE)) {
            FileLock lock = channel.lock();
            try {
                DatabaseInitializer.Report report =
                        DatabaseInitializer.initialize(tenant, () -> true, new PrintStream(console));
                assertThat(report.status()).isEqualTo(DatabaseInitializer.Status.BLOCKED);
                assertThat(report.problems()).hasSize(1);
                assertThat(Files.exists(database)).isTrue();
                assertThat(console.toString(StandardCharsets.UTF_8)).contains("locked by a running instance");
            } finally {
                lock.release();
            }
        }
    }

    @Test
    void missingTenantDirectoryStartsFromAnEmptyDatabase() {
        Path tenant = dataRoot.resolve("tenants").resolve("fresh");
        DatabaseInitializer.Report report =
                DatabaseInitializer.initialize(tenant, () -> true, new PrintStream(console));

        assertThat(report.ok()).isTrue();
        assertThat(report.removed()).isEmpty();
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("already empty");
    }

    @Test
    void consoleConfirmationOnlyAcceptsYes() {
        assertThat(confirm("YES\n")).isTrue();
        assertThat(confirm("yes\n")).isTrue();
        assertThat(confirm("  YES  \n")).isTrue();
        assertThat(confirm("no\n")).isFalse();
        assertThat(confirm("")).isFalse();
    }

    private static boolean confirm(String input) {
        return DatabaseInitializer
                .consoleConfirmation(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))
                .confirmed();
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
