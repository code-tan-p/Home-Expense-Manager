package com.homeexpense;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests that need the full application on a real SQLite file.
 *
 * <p>The database lives in a temporary folder that is created once per test run and
 * deleted when the JVM exits. The data directory itself is a nested path that does not
 * exist yet, so tests also exercise the application's directory creation.
 */
@SpringBootTest
public abstract class SqliteIntegrationTest {

    protected static final Path TEMP_ROOT = createTempRoot();
    protected static final Path DATA_DIR = TEMP_ROOT.resolve("nested").resolve("data");

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("home-expense.data-dir", DATA_DIR::toString);
        registry.add("home-expense.fixed-household", () -> "1");
    }

    private static Path createTempRoot() {
        try {
            Path root = Files.createTempDirectory("home-expense-test-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(root)));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // Best effort cleanup of the system temp folder
        }
    }
}
