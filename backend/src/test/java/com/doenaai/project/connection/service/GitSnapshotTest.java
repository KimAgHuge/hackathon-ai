package com.doenaai.project.connection.service;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.transport.RefSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitSnapshotTest {
    @TempDir Path root;

    @Test void exportsRequestedCommitNotLatest() throws Exception {
        Path repository = Files.createDirectory(root.resolve("repo"));
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("query.sql"), "select id from public.users;");
            git.add().addFilepattern(".").call();
            var snapshot = git.commit().setMessage("first").setAuthor("Test", "test@example.test").call();
            Files.writeString(repository.resolve("query.sql"), "select id from public.other;");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second").setAuthor("Test", "test@example.test").call();
            Path output = Files.createDirectory(root.resolve("output"));
            GitRepositoryConnectionService.exportTree(git.getRepository(), snapshot.name(), output);
            assertThat(Files.readString(output.resolve("query.sql"))).contains("public.users");
            assertThat(output.resolve(".git")).doesNotExist();
        }
    }

    @Test void doesNotExportSymlinksOnSupportedFileSystems() throws Exception {
        Path repository = Files.createDirectory(root.resolve("repo"));
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("query.sql"), "select id from public.users;");
            try {
                Files.createSymbolicLink(repository.resolve("linked.sql"), Path.of("query.sql"));
            } catch (UnsupportedOperationException | FileSystemException | SecurityException exception) {
                assumeTrue(false, "Symbolic links are unavailable on this filesystem.");
            }
            git.add().addFilepattern(".").call();
            var commit = git.commit().setMessage("source").setAuthor("Test", "test@example.test").call();
            Path output = Files.createDirectory(root.resolve("output"));
            GitRepositoryConnectionService.exportTree(git.getRepository(), commit.name(), output);
            assertThat(output.resolve("linked.sql")).doesNotExist();
            assertThat(output.resolve("query.sql")).exists();
        }
    }

    @Test void skipsOversizedBinaryGeneratedAndSecretFilesBeforeSourceLimits() throws Exception {
        Path repository = Files.createDirectory(root.resolve("repo"));
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.writeString(repository.resolve("query.sql"), "select id from public.users;");
            byte[] large = new byte[2 * 1024 * 1024 + 1];
            for (String relative : new String[]{"assets/large.png", "node_modules/dependency.js", ".env.sql", "config/application-local.xml"}) {
                Path ignored = repository.resolve(relative);
                Files.createDirectories(ignored.getParent());
                Files.write(ignored, large);
            }
            git.add().addFilepattern(".").call();
            var commit = git.commit().setMessage("source and ignored files").setAuthor("Test", "test@example.test").call();
            Path output = Files.createDirectory(root.resolve("output"));

            GitRepositoryConnectionService.exportTree(git.getRepository(), commit.name(), output);

            try (var entries = Files.list(output)) {
                assertThat(entries.map(path -> path.getFileName().toString()).toList()).containsExactly("query.sql");
            }
        }
    }

    @Test void rejectsOversizedRelevantSourceRatherThanSilentlyOmittingIt() throws Exception {
        Path repository = Files.createDirectory(root.resolve("repo"));
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            Files.write(repository.resolve("large.sql"), new byte[2 * 1024 * 1024 + 1]);
            git.add().addFilepattern(".").call();
            var commit = git.commit().setMessage("large source").setAuthor("Test", "test@example.test").call();
            Path output = Files.createDirectory(root.resolve("output"));
            assertThatThrownBy(() -> GitRepositoryConnectionService.exportTree(git.getRepository(), commit.name(), output))
                    .isInstanceOf(IOException.class).hasMessageContaining("limits");
        }
    }

    @Test void capsPackBytesAndAdvertisedObjectCountBeforeParsing() throws Exception {
        var byteLimit = monitor(24, 1024, 100, Duration.ofSeconds(10).toNanos());
        try (var input = GitRepositoryConnectionService.boundedPackInput(new ByteArrayInputStream(pack(1, 40)), byteLimit)) {
            assertThatThrownBy(input::readAllBytes).isInstanceOf(IOException.class).hasMessageContaining("limits");
        }
        var countLimit = monitor(1024, 1024, 2, Duration.ofSeconds(10).toNanos());
        assertThatThrownBy(() -> GitRepositoryConnectionService.boundedPackInput(new ByteArrayInputStream(pack(3, 0)), countLimit))
                .isInstanceOf(IOException.class).hasMessageContaining("object count");
        assertThatThrownBy(() -> GitRepositoryConnectionService.boundedPackInput(new ByteArrayInputStream(new byte[12]), countLimit))
                .isInstanceOf(IOException.class).hasMessageContaining("header");
    }

    @Test void progressMonitorCancelsForDiskDeadlineAdvertisedWorkAndInterruption() throws Exception {
        Files.write(root.resolve("pack.tmp"), new byte[100]);
        var disk = monitor(1024, 50, 100, Duration.ofSeconds(10).toNanos());
        disk.checkDiskNow();
        assertThat(disk.isCancelled()).isTrue();
        assertThat(monitor(1024, 1024, 100, -1).isCancelled()).isTrue();
        var count = monitor(1024, 1024, 2, Duration.ofSeconds(10).toNanos());
        count.beginTask("Receiving objects", 3);
        assertThat(count.isCancelled()).isTrue();
        var interrupted = monitor(1024, 1024, 100, Duration.ofSeconds(10).toNanos());
        Thread.currentThread().interrupt();
        try { assertThat(interrupted.isCancelled()).isTrue(); }
        finally { Thread.interrupted(); }
    }

    @Test void actualFetchUsesPackReceiveLimitAndClosesOwnedRepository() throws Exception {
        Path source = Files.createDirectory(root.resolve("origin"));
        try (Git origin = Git.init().setDirectory(source.toFile()).call()) {
            Files.writeString(source.resolve("query.sql"), "select * from public.users;");
            origin.add().addFilepattern(".").call();
            origin.commit().setMessage("source").setAuthor("Test", "test@example.test").call();
            Path owned = Files.createDirectory(root.resolve("owned"));
            var limit = new GitRepositoryConnectionService.SnapshotProgressMonitor(owned, 24, 1024 * 1024, 100,
                    Duration.ofSeconds(10).toNanos());
            try (var target = new GitRepositoryConnectionService.SnapshotRepository(owned.resolve("objects.git"), limit)) {
                target.create(true);
                var fetch = new Git(target).fetch().setRemote(source.toUri().toString())
                        .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*")).setProgressMonitor(limit);
                assertThatThrownBy(fetch::call).isInstanceOf(org.eclipse.jgit.api.errors.TransportException.class);
            }
            new GitRepositoryConnectionService.GitSnapshot(owned.resolve("source"), owned).close();
            assertThat(owned).doesNotExist();
        }
    }

    private GitRepositoryConnectionService.SnapshotProgressMonitor monitor(long bytes, long disk, int objects, long duration) {
        return new GitRepositoryConnectionService.SnapshotProgressMonitor(root, bytes, disk, objects, duration);
    }

    private static byte[] pack(int objects, int payloadBytes) {
        return ByteBuffer.allocate(12 + payloadBytes).put(new byte[]{'P', 'A', 'C', 'K'})
                .putInt(2).putInt(objects).array();
    }

    @Test void unknownCommitFailsRatherThanAnalyzingDifferentHead() throws Exception {
        try (Git git = Git.init().setDirectory(root.resolve("repo").toFile()).call()) {
            Path output = Files.createDirectory(root.resolve("output"));
            assertThatThrownBy(() -> GitRepositoryConnectionService.exportTree(git.getRepository(), "a".repeat(40), output))
                    .isInstanceOf(java.io.IOException.class);
        }
    }
}
