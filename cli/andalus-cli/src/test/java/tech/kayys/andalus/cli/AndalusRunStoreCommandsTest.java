package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tech.kayys.andalus.cli.AndalusAqliCli;
import tech.kayys.andalus.client.AndalusAqliSdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AndalusRunStoreCommandsTest {

    @Test
    void runStoreCommandIsRegisteredFromDedicatedModule() {
        CommandLine root = commandLine();
        CommandLine run = root.getSubcommands().get("run");
        Map<String, CommandLine> subcommands = run.getSubcommands();

        assertThat((Object) subcommands.get("store").getCommand())
                .isInstanceOf(AndalusRunStoreCommands.StoreCommand.class);
    }

    @Test
    void storeCommandAcceptsStandardHelpOption() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "run",
                "store",
                "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Usage: andalus run store")
                .contains("Inspect configured run-store diagnostics")
                .contains("--apply")
                .contains("--compact")
                .contains("--dry-run")
                .contains("--verification-policy");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandRendersMemoryDiagnosticsAsText() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "run",
                "store");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Andalus run store")
                .contains("backend: memory")
                .contains("persistent: no")
                .contains("retention: unlimited maxRuns=0 maxEventsPerRun=0")
                .contains("runs: 0")
                .contains("events: 0");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandRendersFileDiagnosticsAsJson(@TempDir Path directory) {
        Path storePath = directory.resolve("runs.properties");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "run",
                "store",
                "--json");

        assertThat(exitCode).describedAs(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-store\"}")
                .contains("\"backend\":\"file\"")
                .contains("\"persistent\":true")
                .contains("\"path\":\"" + storePath.toAbsolutePath().normalize() + "\"")
                .contains("\"lockPath\":\"" + directory
                        .resolve("runs.properties.lock")
                        .toAbsolutePath()
                        .normalize() + "\"")
                .contains("\"snapshotPresent\":false")
                .contains("\"lockPresent\":true")
                .contains("\"runCount\":0")
                .contains("\"retentionAssessment\"")
                .contains("\"backupRetentionPolicy\":{\"mode\":\"bounded\",\"maxBackups\":5")
                .contains("\"backupInventory\":{\"backupCount\":0");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandVerifiesMemoryStoreAsText() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                AndalusAqliSdk.local(),
                stream(out),
                stream(err),
                "run",
                "store",
                "--verify");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Andalus run store verification")
                .contains("passed: yes")
                .contains("policy: lenient")
                .contains("errors: 0")
                .contains("Andalus run store")
                .contains("backend: memory");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandVerifiesCorruptFileAsJsonWithoutQuarantine(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("runs.properties");
        Files.writeString(storePath, "count=1\nrun.0.runId=\\u00ZZ\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "run",
                "store",
                "--verify",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-store-verification\"}")
                .contains("\"passed\":false")
                .contains("\"exitCode\":1")
                .contains("\"policy\":{\"mode\":\"lenient\",\"failOnWarnings\":false}")
                .contains("\"code\":\"snapshot.unreadable\"")
                .contains("\"snapshotPresent\":true");
        assertThat(Files.exists(storePath)).isTrue();
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandStrictVerificationFailsWarningOnlyFileAsJson(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("runs.properties");
        writeRetentionWarningSnapshot(storePath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "--run-store-max-events-per-run",
                "1",
                "run",
                "store",
                "--verify",
                "--strict",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"passed\":false")
                .contains("\"exitCode\":1")
                .contains("\"errorCount\":0")
                .contains("\"warningCount\":1")
                .contains("\"policy\":{\"mode\":\"strict\",\"failOnWarnings\":true}")
                .contains("\"code\":\"retention.would-prune\"");
        assertThat(Files.exists(storePath)).isTrue();
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandVerificationPolicyFailsWarningOnlyFileAsJson(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("runs.properties");
        writeRetentionWarningSnapshot(storePath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "--run-store-max-events-per-run",
                "1",
                "run",
                "store",
                "--verify",
                "--verification-policy",
                "warnings-as-errors",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"passed\":false")
                .contains("\"policy\":{\"mode\":\"strict\",\"failOnWarnings\":true}")
                .contains("\"code\":\"retention.would-prune\"");
        assertThat(Files.exists(storePath)).isTrue();
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandStrictVerificationFailsBackupRetentionWarningAsJson(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("runs.properties");
        Path firstBackup = writeCompactionBackup(storePath, "first");
        Path secondBackup = writeCompactionBackup(storePath, "second");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "--run-store-max-backups",
                "1",
                "run",
                "store",
                "--verify",
                "--strict",
                "--json");

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"passed\":false")
                .contains("\"errorCount\":0")
                .contains("\"warningCount\":1")
                .contains("\"code\":\"backup-retention.would-prune\"")
                .contains("\"backupInventory\":{\"backupCount\":2")
                .contains("\"policy\":{\"mode\":\"strict\",\"failOnWarnings\":true}");
        assertThat(backupStoreFiles(directory)).containsExactlyInAnyOrder(firstBackup, secondBackup);
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandCompactionDryRunRendersWarningFileAsJson(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("runs.properties");
        writeRetentionWarningSnapshot(storePath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "--run-store-max-events-per-run",
                "1",
                "run",
                "store",
                "--compact",
                "--dry-run",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-store-compaction-preview\"}")
                .contains("\"dryRun\":true")
                .contains("\"previewable\":true")
                .contains("\"compactionNeeded\":true")
                .contains("\"backupRetention\":{\"policy\":{\"mode\":\"bounded\",\"maxBackups\":5")
                .contains("\"prunedBackupCount\":0")
                .contains("\"prunedEvents\":1")
                .contains("\"code\":\"retention.would-prune\"");
        assertThat(Files.readString(storePath)).contains("event.count=2");
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandCompactionDryRunReportsBackupRetentionWithoutMutation(@TempDir Path directory)
            throws Exception {
        Path storePath = directory.resolve("runs.properties");
        writeRetentionWarningSnapshot(storePath);
        Path firstBackup = writeCompactionBackup(storePath, "first");
        Path secondBackup = writeCompactionBackup(storePath, "second");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "--run-store-max-events-per-run",
                "1",
                "--run-store-max-backups",
                "1",
                "run",
                "store",
                "--compact",
                "--dry-run",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"compactionNeeded\":true")
                .contains("\"warningCount\":2")
                .contains("\"backupRetention\":{\"policy\":{\"mode\":\"bounded\",\"maxBackups\":1")
                .contains("\"retainedBackupCount\":1")
                .contains("\"prunedBackupCount\":1")
                .contains("\"code\":\"backup-retention.would-prune\"");
        assertThat(backupStoreFiles(directory)).containsExactlyInAnyOrder(firstBackup, secondBackup);
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandCompactionApplyMutatesWarningFileAsJson(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("runs.properties");
        writeRetentionWarningSnapshot(storePath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "--run-store-max-events-per-run",
                "1",
                "--run-store-max-backups",
                "1",
                "run",
                "store",
                "--compact",
                "--apply",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"contract\":{\"schema\":\"andalus.run.lifecycle\",\"version\":1,\"envelope\":\"run-store-compaction\"}")
                .contains("\"applied\":true")
                .contains("\"compacted\":true")
                .contains("\"backupCreated\":true")
                .contains("\"backupPath\":\"")
                .contains("\"backupRetention\":{\"policy\":{\"mode\":\"bounded\",\"maxBackups\":1")
                .contains("\"retainedBackupCount\":1")
                .contains("\"prunedBackupCount\":0")
                .contains("\"successful\":true")
                .contains("\"prunedEvents\":1")
                .contains("\"afterDiagnostics\":{\"backend\":\"file\"")
                .contains("\"eventCount\":1");
        assertThat(Files.readString(storePath))
                .contains("event.count=1")
                .doesNotContain("event.1.runId=");
        List<Path> backups = backupStoreFiles(directory);
        assertThat(backups).hasSize(1);
        assertThat(Files.readString(backups.get(0))).contains("event.count=2");
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandCompactionApplyReportsIncompleteBackupPruningAsJson(@TempDir Path directory)
            throws Exception {
        Path storePath = directory.resolve("runs.properties");
        Path stuckBackup = writeStuckCompactionBackup(storePath, "stuck");
        writeRetentionWarningSnapshot(storePath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "--run-store-max-events-per-run",
                "1",
                "--run-store-max-backups",
                "1",
                "run",
                "store",
                "--compact",
                "--apply",
                "--json");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("\"applied\":true")
                .contains("\"successful\":true")
                .contains("\"failedBackupPruneCount\":1")
                .contains("\"failedBackupPrunePaths\":[\"" + stuckBackup + "\"]")
                .contains("\"code\":\"backup-retention.prune-incomplete\"");
        assertThat(stuckBackup).exists();
        assertThat(corruptStoreFiles(directory)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void storeCommandCompactionRequiresDryRunOrApply(@TempDir Path directory) throws Exception {
        Path storePath = directory.resolve("runs.properties");
        writeRetentionWarningSnapshot(storePath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = AndalusAqliCli.execute(
                null,
                stream(out),
                stream(err),
                "--run-store",
                storePath.toString(),
                "run",
                "store",
                "--compact",
                "--json");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("Run-store compaction requires --dry-run or --apply");
        assertThat(Files.readString(storePath)).contains("event.count=2");
    }

    private static CommandLine commandLine() {
        return new CommandLine(new AndalusAqliCli(
                AndalusAqliSdk.local(),
                stream(new ByteArrayOutputStream()),
                stream(new ByteArrayOutputStream())));
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private static void writeRetentionWarningSnapshot(Path storePath) throws IOException {
        Files.writeString(storePath, String.join(System.lineSeparator(),
                "version=1",
                "count=1",
                "run.0.runId=run-retention-warning-1",
                "run.0.state=RUNNING",
                "run.0.strategy=strategy-a",
                "run.0.known=true",
                "run.0.message=running",
                "run.0.metadata.count=0",
                "event.count=2",
                "event.0.runId=run-retention-warning-1",
                "event.0.sequence=1",
                "event.0.type=run.audit",
                "event.0.state=RUNNING",
                "event.0.message=first",
                "event.0.metadata.count=0",
                "event.1.runId=run-retention-warning-1",
                "event.1.sequence=2",
                "event.1.type=run.audit",
                "event.1.state=RUNNING",
                "event.1.message=second",
                "event.1.metadata.count=0"));
    }

    private static Path writeCompactionBackup(Path storePath, String suffix) throws IOException {
        Path backupPath = storePath.resolveSibling(
                storePath.getFileName().toString() + ".compaction-" + suffix + ".properties");
        Files.writeString(backupPath, String.join(System.lineSeparator(),
                "version=1",
                "count=0",
                "event.count=0"));
        return backupPath;
    }

    private static Path writeStuckCompactionBackup(Path storePath, String suffix) throws IOException {
        Path backupPath = storePath.resolveSibling(
                storePath.getFileName().toString() + ".compaction-" + suffix + ".properties");
        Files.createDirectories(backupPath);
        Files.writeString(backupPath.resolve("held.txt"), "held");
        Files.setLastModifiedTime(backupPath, FileTime.fromMillis(0));
        return backupPath;
    }

    private static List<Path> corruptStoreFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().contains(".corrupt-"))
                    .toList();
        }
    }

    private static List<Path> backupStoreFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().contains(".compaction-"))
                    .toList();
        }
    }
}
