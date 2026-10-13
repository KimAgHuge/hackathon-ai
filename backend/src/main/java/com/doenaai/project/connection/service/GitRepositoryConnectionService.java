package com.doenaai.project.connection.service;

import com.doenaai.project.connection.dto.*;
import com.doenaai.project.connection.entity.*;
import com.doenaai.project.connection.repository.GitHostPolicyRepository;
import com.doenaai.project.connection.repository.ProjectRepositoryConnectionRepository;
import com.doenaai.project.connection.security.CredentialCipher;
import com.doenaai.project.connection.security.RepositoryCredentialPayload;
import com.doenaai.project.dto.ProjectResponse;
import com.doenaai.project.entity.ProjectSourceType;
import com.doenaai.project.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.net.IDN;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class GitRepositoryConnectionService {
    static final long MAX_PACK_BYTES = 128L * 1024 * 1024;
    static final long MAX_CLONE_DISK_BYTES = 256L * 1024 * 1024;
    static final long MAX_RECEIVED_OBJECT_BYTES = 16L * 1024 * 1024;
    static final int MAX_RECEIVED_OBJECTS = 100_000;
    private static final Set<String> SNAPSHOT_EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".venv", "venv", "node_modules",
            "vendor", "build", "dist", "target", "out", "coverage", "generated", "generated-sources",
            "__pycache__", ".next", ".nuxt", ".cache", ".pytest_cache", "pods", "bin", "obj",
            "artifacts", "datasets", "checkpoints", "secrets", ".ssh", ".aws");
    private static final Set<String> SNAPSHOT_BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tiff", "psd", "mp3", "mp4", "mov",
            "wav", "ogg", "webm", "avi", "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
            "zip", "gz", "bz2", "xz", "7z", "tar", "rar", "jar", "war", "class", "exe", "dll",
            "so", "dylib", "a", "o", "pyc", "pyo", "woff", "woff2", "ttf", "otf", "eot",
            "db", "sqlite", "sqlite3", "parquet", "npy", "npz", "pt", "pth", "onnx", "bin");

    private final ProjectService projectService;
    private final ProjectRepositoryConnectionRepository
            repositoryConnectionRepository;
    private final GitHostPolicyRepository hostPolicyRepository;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;
    private final int connectionTimeoutSeconds;

    public GitRepositoryConnectionService(
            ProjectService projectService,
            ProjectRepositoryConnectionRepository
                    repositoryConnectionRepository,
            GitHostPolicyRepository hostPolicyRepository,
            CredentialCipher credentialCipher,
            ObjectMapper objectMapper,
            @Value(
                    "${doenaai.git.connection-timeout-seconds:10}"
            ) int connectionTimeoutSeconds
    ) {
        this.projectService = projectService;
        this.repositoryConnectionRepository =
                repositoryConnectionRepository;
        this.hostPolicyRepository = hostPolicyRepository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
        this.connectionTimeoutSeconds =
                connectionTimeoutSeconds;
    }

    /** projectId·userId 권한과 request의 Git 주소/인증을 시험해 커밋 정보를 반환한다. */
    @Transactional(readOnly = true)
    public GitRepositoryConnectionResponse testConnection(
            UUID projectId,
            GitRepositoryConnectionRequest request,
            UUID userId
    ) {
        projectService.assertCanManage(
                projectId,
                userId
        );
        assertRequestMatchesProject(
                projectId,
                userId,
                request
        );

        return inspect(
                request,
                credentialsFromRequest(request)
        );
    }

    /** 시험에 성공한 Git 연결을 저장하고 인증정보는 암호화된 형태로 보관한다. */
    @Transactional
    public ProjectRepositoryConnectionResponse saveConnection(
            UUID projectId,
            GitRepositoryConnectionRequest request,
            UUID userId
    ) {
        projectService.assertCanManage(
                projectId,
                userId
        );
        assertRequestMatchesProject(
                projectId,
                userId,
                request
        );

        RepositoryCredentialPayload credentials =
                credentialsFromRequest(request);
        GitRepositoryConnectionResponse testResult =
                inspect(
                        request,
                        credentials
                );

        ProjectRepositoryConnection connection =
                repositoryConnectionRepository
                        .findByProjectId(projectId)
                        .orElseGet(() ->
                                new ProjectRepositoryConnection(
                                        projectId,
                                        userId
                                )
                        );

        if (!testResult.success()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    testResult.message()
            );
        }

        connection.markConnected(
                testResult.providerType(),
                request.authType(),
                normalizedUsername(request),
                encryptCredentials(
                        request.authType(),
                        credentials
                ),
                normalizedKnownHostsEntry(request),
                testResult.commitSha()
        );

        return ProjectRepositoryConnectionResponse.from(
                repositoryConnectionRepository.save(
                        connection
                )
        );
    }

    @Transactional(readOnly = true)
    public ProjectRepositoryConnectionResponse getConnection(
            UUID projectId,
            UUID userId
    ) {
        projectService.getProject(
                projectId,
                userId
        );

        ProjectRepositoryConnection connection =
                repositoryConnectionRepository
                        .findByProjectId(projectId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "저장된 Git 연결정보를 찾을 수 없습니다."
                                )
                        );

        return ProjectRepositoryConnectionResponse.from(
                connection
        );
    }

    /** 저장된 프로젝트 Git 설정을 다시 확인해 분석에 사용할 커밋 SHA를 반환한다. */
    @Transactional(readOnly = true)
    public GitRepositoryConnectionResponse verifySavedProject(
            UUID projectId,
            UUID userId
    ) {
        projectService.assertCanManage(
                projectId,
                userId
        );

        ProjectResponse project =
                projectService.getProject(
                        projectId,
                        userId
                );

        if (project.sourceType() != ProjectSourceType.GIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Git 소스 프로젝트만 저장소 연결을 확인할 수 있습니다."
            );
        }

        ProjectRepositoryConnection connection =
                repositoryConnectionRepository
                        .findByProjectId(projectId)
                        .orElse(null);

        RepositoryProviderType providerType =
                connection == null
                        ? RepositoryProviderType.AUTO
                        : connection.getProviderType();
        RepositoryAuthType authType =
                connection == null
                        ? RepositoryAuthType.NONE
                        : connection.getAuthType();

        RepositoryCredentialPayload credentials =
                connection == null
                        ? emptyCredentials()
                        : decryptCredentials(connection);

        GitRepositoryConnectionRequest request =
                new GitRepositoryConnectionRequest(
                        project.repositoryUrl(),
                        project.defaultBranch(),
                        providerType,
                        authType,
                        connection == null
                                ? null
                                : connection.getUsername(),
                        credentials.secret(),
                        credentials.privateKey(),
                        credentials.passphrase(),
                        connection == null
                                ? null
                                : connection.getKnownHostsEntry()
                );

        return inspect(
                request,
                credentials
        );
    }

    private GitRepositoryConnectionResponse inspect(
            GitRepositoryConnectionRequest request,
            RepositoryCredentialPayload credentials
    ) {
        RepositoryTarget target =
                validateTarget(request);
        validateAuthentication(
                request,
                target,
                credentials
        );

        String targetRefName =
                "refs/heads/" + target.branch();

        try (
                GitAuthenticationContext authentication =
                        createAuthenticationContext(
                                request,
                                target,
                                credentials
                        )
        ) {
            LsRemoteCommand command =
                    Git.lsRemoteRepository()
                            .setRemote(
                                    target.repositoryUrl()
                            )
                            .setHeads(true)
                            .setTimeout(
                                    connectionTimeoutSeconds
                            );

            authentication.configure(command);

            Collection<Ref> refs = command.call();

            Ref branchRef = refs.stream()
                    .filter(ref ->
                            targetRefName.equals(
                                    ref.getName()
                            )
                    )
                    .findFirst()
                    .orElse(null);

            if (
                    branchRef == null ||
                    branchRef.getObjectId() == null
            ) {
                return GitRepositoryConnectionResponse.failure(
                        target.repositoryUrl(),
                        target.branch(),
                        "원격 저장소에서 '%s' 브랜치를 찾을 수 없습니다."
                                .formatted(target.branch()),
                        target.providerType(),
                        request.authType()
                );
            }

            return GitRepositoryConnectionResponse.success(
                    target.repositoryUrl(),
                    target.branch(),
                    branchRef.getObjectId().name(),
                    target.providerType(),
                    request.authType()
            );
        } catch (TransportException exception) {
            log.warn(
                    "Git repository connection failed: host={}, protocol={}, authType={}, exception={}",
                    target.host(),
                    target.protocol(),
                    request.authType(),
                    exception.getClass().getSimpleName()
            );

            return GitRepositoryConnectionResponse.failure(
                    target.repositoryUrl(),
                    target.branch(),
                    "Git 저장소에 접근할 수 없습니다. URL, 인증정보와 저장소 권한을 확인하세요.",
                    target.providerType(),
                    request.authType()
            );
        } catch (GitAPIException | IOException exception) {
            log.warn(
                    "Git repository inspection failed: host={}, protocol={}, authType={}, exception={}",
                    target.host(),
                    target.protocol(),
                    request.authType(),
                    exception.getClass().getSimpleName()
            );

            return GitRepositoryConnectionResponse.failure(
                    target.repositoryUrl(),
                    target.branch(),
                    "Git 저장소 정보를 확인하지 못했습니다. 연결 설정을 확인하고 다시 시도하세요.",
                    target.providerType(),
                    request.authType()
            );
        }
    }

    /**
     * @param projectId 저장소가 속한 프로젝트
     * @param userId 접근 권한을 확인할 사용자
     * @param repositoryUrl 검증된 저장소 주소
     * @param branch 분석할 기본 브랜치
     * @param commitSha 분석 시점에 고정한 커밋
     * @return 훅·필터·저장소 스크립트를 실행하지 않고 받은 임시 스냅샷; 사용 후 닫아 정리한다.
     */
    public GitSnapshot checkoutSnapshot(UUID projectId, UUID userId, String repositoryUrl,
                                        String branch, String commitSha) throws IOException, GitAPIException {
        projectService.assertCanManage(projectId, userId);
        ProjectResponse project = projectService.getProject(projectId, userId);
        if (!java.util.Objects.equals(project.repositoryUrl(), repositoryUrl)
                || !java.util.Objects.equals(project.defaultBranch(), branch)
                || commitSha == null || !commitSha.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalStateException("Project repository changed or snapshot is invalid.");
        }
        var connection = repositoryConnectionRepository.findByProjectId(projectId).orElse(null);
        var credentials = connection == null ? emptyCredentials() : decryptCredentials(connection);
        var request = new GitRepositoryConnectionRequest(repositoryUrl, branch,
                connection == null ? RepositoryProviderType.AUTO : connection.getProviderType(),
                connection == null ? RepositoryAuthType.NONE : connection.getAuthType(),
                connection == null ? null : connection.getUsername(), credentials.secret(),
                credentials.privateKey(), credentials.passphrase(),
                connection == null ? null : connection.getKnownHostsEntry());
        var target = validateTarget(request);
        validateAuthentication(request, target, credentials);
        Path root = Files.createTempDirectory("doenaai-analysis-");
        boolean succeeded = false;
        try {
          Path checkout;
          try (var authentication = createAuthenticationContext(request, target, credentials)) {
            var monitor = new SnapshotProgressMonitor(root);
            // The inserter hook bounds decoded pack bytes for both HTTPS and SSH transports.
            try (var repository = new SnapshotRepository(root.resolve("objects.git"), monitor)) {
                repository.create(true);
                var command = new Git(repository).fetch().setRemote(target.repositoryUrl())
                        .setRefSpecs(new RefSpec("+refs/heads/" + target.branch() + ":refs/heads/snapshot"))
                        .setTagOpt(TagOpt.NO_TAGS).setDepth(100)
                        .setTimeout(Math.min(180, Math.max(connectionTimeoutSeconds, 60)))
                        .setProgressMonitor(monitor);
                authentication.configure(command);
                command.call();
                monitor.checkDiskNow();
                monitor.check();
                checkout = Files.createDirectory(root.resolve("source"));
                exportTree(repository, commitSha, checkout);
            }
          }
          succeeded = true;
          return new GitSnapshot(checkout, root);
        } finally {
            if (!succeeded) GitAuthenticationContext.deleteDirectory(root);
        }
    }

    static void exportTree(Repository repository, String commitSha, Path target) throws IOException {
        try (RevWalk commits = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
            var commit = commits.parseCommit(ObjectId.fromString(commitSha));
            if (!commit.name().equalsIgnoreCase(commitSha)) throw new IOException("Snapshot mismatch.");
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            int files = 0;
            long totalBytes = 0;
            while (tree.next()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Snapshot extraction cancelled.");
                var mode = tree.getFileMode(0);
                if (!mode.equals(FileMode.REGULAR_FILE) && !mode.equals(FileMode.EXECUTABLE_FILE)) continue;
                String relative = tree.getPathString();
                if (relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                        || java.util.Arrays.stream(relative.split("/")).anyMatch(part ->
                        part.equals("..") || part.equalsIgnoreCase(".git"))) {
                    throw new IOException("Unsafe snapshot path.");
                }
                Path file = target.resolve(relative).normalize();
                if (!file.startsWith(target)) throw new IOException("Unsafe snapshot path.");
                if (excludedFromSnapshot(relative)) continue;
                var object = repository.open(tree.getObjectId(0));
                if (++files > 10000 || object.getSize() > 2 * 1024 * 1024
                        || (totalBytes += object.getSize()) > 64L * 1024 * 1024) {
                    throw new IOException("Snapshot exceeds analysis limits.");
                }
                Files.createDirectories(file.getParent());
                // Git object bytes only. No executable permission, hooks, LFS or smudge filters.
                try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    object.copyTo(output);
                }
            }
        }
    }

    public record GitSnapshot(Path path, Path ownedRoot) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            GitAuthenticationContext.deleteDirectory(ownedRoot);
        }
    }

    private static boolean excludedFromSnapshot(String relative) {
        String[] parts = relative.toLowerCase(Locale.ROOT).split("/");
        for (int i = 0; i < parts.length - 1; i++) if (SNAPSHOT_EXCLUDED_DIRECTORIES.contains(parts[i])) return true;
        String name = parts[parts.length - 1];
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return SNAPSHOT_BINARY_EXTENSIONS.contains(extension) || name.startsWith(".env")
                || name.startsWith("application-local") || name.startsWith("application-secret")
                || name.startsWith("application-prod") || name.contains("credential") || name.contains("secret")
                || name.equals("id_rsa") || name.equals("id_ed25519") || name.equals(".npmrc") || name.equals(".pypirc")
                || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".jks")
                || name.endsWith(".lock") || name.equals("package-lock.json") || name.equals("npm-shrinkwrap.json")
                || name.endsWith(".min.js") || name.endsWith(".min.css") || name.endsWith(".generated.java")
                || name.endsWith(".g.cs") || name.endsWith(".g.dart") || name.endsWith(".freezed.dart");
    }

    /** JGit's fetch path obtains its PackParser from this hook, after HTTP/SSH protocol decoding. */
    static final class SnapshotRepository extends FileRepository {
        private final SnapshotProgressMonitor monitor;
        SnapshotRepository(Path directory, SnapshotProgressMonitor monitor) throws IOException {
            super(directory.toFile());
            this.monitor = monitor;
        }
        @Override public ObjectInserter newObjectInserter() {
            ObjectInserter original = super.newObjectInserter();
            return new ObjectInserter.Filter() {
                @Override protected ObjectInserter delegate() { return original; }
                @Override public PackParser newPackParser(InputStream input) throws IOException {
                    PackParser parser = original.newPackParser(boundedPackInput(input, monitor));
                    parser.setMaxObjectSizeLimit(MAX_RECEIVED_OBJECT_BYTES);
                    return parser;
                }
            };
        }
    }

    static InputStream boundedPackInput(InputStream input, SnapshotProgressMonitor monitor) throws IOException {
        InputStream bounded = new FilterInputStream(input) {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                monitor.check();
                int read = in.read(bytes, offset, Math.min(length, 8192));
                if (read > 0) monitor.received(read);
                return read;
            }
            @Override public long skip(long count) throws IOException {
                byte[] buffer = new byte[(int) Math.min(Math.max(count, 0), 8192)];
                long skipped = 0;
                while (skipped < count) {
                    int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                    if (read == -1) break;
                    skipped += read;
                }
                return skipped;
            }
        };
        byte[] header = bounded.readNBytes(12);
        if (header.length != 12 || header[0] != 'P' || header[1] != 'A' || header[2] != 'C' || header[3] != 'K') {
            throw new IOException("Invalid snapshot pack header.");
        }
        long objects = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(header, 8, 4).getInt());
        if (objects > monitor.maxObjects) throw new IOException("Snapshot object count exceeds analysis limits.");
        // Reject the advertised count before JGit allocates its object array, then replay the checked header.
        return new BufferedInputStream(new SequenceInputStream(new ByteArrayInputStream(header), bounded));
    }

    static final class SnapshotProgressMonitor implements ProgressMonitor {
        private final Path ownedRoot;
        private final long deadline;
        private final long maxPackBytes;
        private final long maxDiskBytes;
        private final int maxObjects;
        private final AtomicLong packBytes = new AtomicLong();
        private volatile boolean cancelled;
        private long lastDiskCheck;
        SnapshotProgressMonitor(Path ownedRoot) {
            this(ownedRoot, MAX_PACK_BYTES, MAX_CLONE_DISK_BYTES, MAX_RECEIVED_OBJECTS,
                    java.time.Duration.ofMinutes(3).toNanos());
        }
        SnapshotProgressMonitor(Path ownedRoot, long maxPackBytes, long maxDiskBytes, int maxObjects, long timeoutNanos) {
            this.ownedRoot = ownedRoot;
            this.maxPackBytes = maxPackBytes;
            this.maxDiskBytes = maxDiskBytes;
            this.maxObjects = maxObjects;
            this.deadline = System.nanoTime() + timeoutNanos;
        }
        public void start(int totalTasks) { }
        public void beginTask(String title, int totalWork) { if (totalWork > maxObjects) cancelled = true; }
        public void update(int completed) { }
        public void endTask() { }
        public boolean isCancelled() {
            if (cancelled || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) return true;
            if (System.nanoTime() - lastDiskCheck > java.time.Duration.ofMillis(250).toNanos()) checkDiskNow();
            return cancelled;
        }
        void received(int count) throws IOException {
            if (packBytes.addAndGet(count) > maxPackBytes) cancelled = true;
            check();
        }
        void check() throws IOException {
            if (isCancelled()) throw new IOException("Snapshot transfer exceeds analysis limits or was cancelled.");
        }
        void checkDiskNow() {
            lastDiskCheck = System.nanoTime();
            try {
                Files.walkFileTree(ownedRoot, new SimpleFileVisitor<>() {
                    private long bytes;
                    private int entries;
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        bytes += attributes.size();
                        if (++entries > MAX_RECEIVED_OBJECTS * 2 || bytes > maxDiskBytes) cancelled = true;
                        return cancelled ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | SecurityException exception) {
                cancelled = true;
            }
        }
        public void showDuration(boolean enabled) { }
    }

    private void assertRequestMatchesProject(
            UUID projectId,
            UUID userId,
            GitRepositoryConnectionRequest request
    ) {
        ProjectResponse project =
                projectService.getProject(
                        projectId,
                        userId
                );

        if (
                project.sourceType() != ProjectSourceType.GIT ||
                !StringUtils.hasText(project.repositoryUrl()) ||
                !StringUtils.hasText(project.defaultBranch()) ||
                !project.repositoryUrl().trim().equals(
                        request.repositoryUrl().trim()
                ) ||
                !project.defaultBranch().trim().equals(
                        request.branch().trim()
                )
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "요청한 Git 저장소와 브랜치가 프로젝트 설정과 일치하지 않습니다."
            );
        }
    }

    /** request의 저장소 URL·브랜치·호스트 정책을 검사해 안전한 접속 대상을 반환한다. */
    private RepositoryTarget validateTarget(
            GitRepositoryConnectionRequest request
    ) {
        String repositoryUrl =
                request.repositoryUrl().trim();
        String branch = request.branch().trim();

        if (
                !Repository.isValidRefName(
                        "refs/heads/" + branch
                )
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Git 브랜치 이름 형식이 올바르지 않습니다."
            );
        }

        if (
                repositoryUrl.contains("?") ||
                repositoryUrl.contains("#")
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Git 저장소 URL에는 Query 또는 Fragment를 사용할 수 없습니다."
            );
        }

        URIish uri;

        try {
            uri = new URIish(repositoryUrl);
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Git 저장소 URL 형식이 올바르지 않습니다."
            );
        }

        String host = uri.getHost();

        if (!StringUtils.hasText(host)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Git 저장소 호스트를 확인할 수 없습니다."
            );
        }

        String normalizedHost = IDN.toASCII(host)
                .toLowerCase(Locale.ROOT);
        RepositoryProtocol protocol =
                resolveProtocol(uri);

        if (
                protocol == RepositoryProtocol.HTTPS &&
                (
                        StringUtils.hasText(uri.getUser()) ||
                        StringUtils.hasText(uri.getPass())
                )
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Git 인증정보를 URL에 포함하지 마세요."
            );
        }

        if (
                !StringUtils.hasText(uri.getPath()) ||
                "/".equals(uri.getPath())
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Git 저장소 경로가 포함된 URL을 입력하세요."
            );
        }

        GitHostPolicy policy = hostPolicyRepository
                .findByHostIgnoreCaseAndEnabledTrue(
                        normalizedHost
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "허용되지 않은 Git 호스트입니다. 관리자에게 호스트 등록을 요청하세요: " +
                                        normalizedHost
                        )
                );

        int port = resolvePort(
                uri,
                protocol
        );
        validateHostPolicy(
                policy,
                protocol,
                port
        );

        RepositoryProviderType providerType =
                resolveProviderType(
                        request.providerType(),
                        policy
                );

        return new RepositoryTarget(
                repositoryUrl,
                branch,
                normalizedHost,
                port,
                protocol,
                providerType
        );
    }

    private RepositoryProtocol resolveProtocol(
            URIish uri
    ) {
        String scheme = uri.getScheme();

        if ("https".equalsIgnoreCase(scheme)) {
            return RepositoryProtocol.HTTPS;
        }

        if (
                "ssh".equalsIgnoreCase(scheme) ||
                (
                        scheme == null &&
                        StringUtils.hasText(uri.getUser())
                )
        ) {
            return RepositoryProtocol.SSH;
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "HTTPS 또는 SSH Git 저장소 URL만 사용할 수 있습니다."
        );
    }

    private int resolvePort(
            URIish uri,
            RepositoryProtocol protocol
    ) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }

        return protocol == RepositoryProtocol.HTTPS
                ? 443
                : 22;
    }

    private void validateHostPolicy(
            GitHostPolicy policy,
            RepositoryProtocol protocol,
            int port
    ) {
        boolean allowed =
                protocol == RepositoryProtocol.HTTPS
                        ? (
                                policy.isHttpsEnabled() &&
                                policy.getHttpsPort() == port
                        )
                        : (
                                policy.isSshEnabled() &&
                                policy.getSshPort() == port
                        );

        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "해당 Git 호스트에서 허용되지 않은 프로토콜 또는 포트입니다."
            );
        }
    }

    private RepositoryProviderType resolveProviderType(
            RepositoryProviderType requestedProvider,
            GitHostPolicy policy
    ) {
        if (requestedProvider == RepositoryProviderType.AUTO) {
            return policy.getProviderType();
        }

        if (
                policy.getProviderType() !=
                        RepositoryProviderType.CUSTOM &&
                requestedProvider != policy.getProviderType()
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "선택한 Git 공급자가 호스트 정책과 일치하지 않습니다."
            );
        }

        return requestedProvider;
    }

    /** Git 프로토콜과 인증 방식이 맞고 필수 자격증명이 있는지 확인한다. */
    private void validateAuthentication(
            GitRepositoryConnectionRequest request,
            RepositoryTarget target,
            RepositoryCredentialPayload credentials
    ) {
        RepositoryAuthType authType = request.authType();

        if (
                target.protocol() == RepositoryProtocol.SSH &&
                authType != RepositoryAuthType.SSH_KEY
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "SSH 저장소 URL은 SSH 키 인증을 선택해야 합니다."
            );
        }

        if (
                target.protocol() == RepositoryProtocol.HTTPS &&
                authType == RepositoryAuthType.SSH_KEY
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "SSH 키 인증에는 SSH 저장소 URL을 사용하세요."
            );
        }

        switch (authType) {
            case NONE -> {
                if (target.protocol() != RepositoryProtocol.HTTPS) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "인증 없음은 공개 HTTPS 저장소에서만 사용할 수 있습니다."
                    );
                }
            }
            case HTTPS_TOKEN, HTTPS_BASIC -> {
                if (!StringUtils.hasText(request.username())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "HTTPS 인증 사용자명을 입력하세요."
                    );
                }

                if (!StringUtils.hasText(credentials.secret())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "HTTPS Token 또는 비밀번호를 입력하세요."
                    );
                }
            }
            case SSH_KEY -> {
                if (!StringUtils.hasText(credentials.privateKey())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "SSH 개인키를 입력하세요."
                    );
                }

                if (!StringUtils.hasText(request.knownHostsEntry())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "SSH 서버 검증을 위한 known_hosts 값을 입력하세요."
                    );
                }
            }
            case PROVIDER_APP ->
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "공급자 App 인증은 외부 App 등록정보가 필요합니다. 현재는 HTTPS Token 또는 SSH 키를 사용하세요."
                    );
        }
    }

    private GitAuthenticationContext
    createAuthenticationContext(
            GitRepositoryConnectionRequest request,
            RepositoryTarget target,
            RepositoryCredentialPayload credentials
    ) throws IOException {
        return switch (request.authType()) {
            case NONE -> GitAuthenticationContext.none();
            case HTTPS_TOKEN, HTTPS_BASIC ->
                    GitAuthenticationContext.https(
                            normalizedUsername(request),
                            credentials.secret()
                    );
            case SSH_KEY ->
                    GitAuthenticationContext.ssh(
                            credentials.privateKey(),
                            credentials.passphrase(),
                            request.knownHostsEntry()
                    );
            case PROVIDER_APP ->
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "공급자 App 인증 설정이 완료되지 않았습니다."
                    );
        };
    }

    private RepositoryCredentialPayload credentialsFromRequest(
            GitRepositoryConnectionRequest request
    ) {
        return switch (request.authType()) {
            case NONE ->
                    emptyCredentials();
            case HTTPS_TOKEN, HTTPS_BASIC ->
                    new RepositoryCredentialPayload(
                            valueToNull(
                                    request.secret()
                            ),
                            null,
                            null
                    );
            case SSH_KEY ->
                    new RepositoryCredentialPayload(
                            null,
                            valueToNull(
                                    request.sshPrivateKey()
                            ),
                            valueToNull(
                                    request.sshPassphrase()
                            )
                    );
            case PROVIDER_APP ->
                    emptyCredentials();
        };
    }

    private RepositoryCredentialPayload decryptCredentials(
            ProjectRepositoryConnection connection
    ) {
        if (
                connection.getAuthType() ==
                        RepositoryAuthType.NONE
        ) {
            return emptyCredentials();
        }

        if (
                !StringUtils.hasText(
                        connection.getCredentialCiphertext()
                )
        ) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장소 인증정보가 저장되어 있지 않습니다."
            );
        }

        try {
            return objectMapper.readValue(
                    credentialCipher.decrypt(
                            connection.getCredentialCiphertext()
                    ),
                    RepositoryCredentialPayload.class
            );
        } catch (JacksonException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "저장된 Git 인증정보를 읽지 못했습니다.",
                    exception
            );
        }
    }

    private String encryptCredentials(
            RepositoryAuthType authType,
            RepositoryCredentialPayload credentials
    ) {
        if (authType == RepositoryAuthType.NONE) {
            return null;
        }

        try {
            return credentialCipher.encrypt(
                    objectMapper.writeValueAsString(
                            credentials
                    )
            );
        } catch (JacksonException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Git 인증정보를 직렬화하지 못했습니다.",
                    exception
            );
        }
    }

    private RepositoryCredentialPayload emptyCredentials() {
        return new RepositoryCredentialPayload(
                null,
                null,
                null
        );
    }

    private String normalizedUsername(
            GitRepositoryConnectionRequest request
    ) {
        return trimToNull(request.username());
    }

    private String normalizedKnownHostsEntry(
            GitRepositoryConnectionRequest request
    ) {
        return request.authType() ==
                RepositoryAuthType.SSH_KEY
                ? trimToNull(
                        request.knownHostsEntry()
                )
                : null;
    }

    private String trimToNull(
            String value
    ) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

    private String valueToNull(
            String value
    ) {
        return value != null && !value.isBlank()
                ? value
                : null;
    }

    private enum RepositoryProtocol {
        HTTPS,
        SSH
    }

    private record RepositoryTarget(
            String repositoryUrl,
            String branch,
            String host,
            int port,
            RepositoryProtocol protocol,
            RepositoryProviderType providerType
    ) {
    }

    private static final class GitAuthenticationContext
            implements AutoCloseable {

        private final CredentialsProvider credentialsProvider;
        private final SshdSessionFactory sshSessionFactory;
        private final JGitKeyCache keyCache;
        private final Path temporaryDirectory;

        private GitAuthenticationContext(
                CredentialsProvider credentialsProvider,
                SshdSessionFactory sshSessionFactory,
                JGitKeyCache keyCache,
                Path temporaryDirectory
        ) {
            this.credentialsProvider = credentialsProvider;
            this.sshSessionFactory = sshSessionFactory;
            this.keyCache = keyCache;
            this.temporaryDirectory = temporaryDirectory;
        }

        static GitAuthenticationContext none() {
            return new GitAuthenticationContext(
                    null,
                    null,
                    null,
                    null
            );
        }

        static GitAuthenticationContext https(
                String username,
                String secret
        ) {
            return new GitAuthenticationContext(
                    new UsernamePasswordCredentialsProvider(
                            username,
                            secret
                    ),
                    null,
                    null,
                    null
            );
        }

        static GitAuthenticationContext ssh(
                String privateKey,
                String passphrase,
                String knownHostsEntry
        ) throws IOException {
            Path temporaryDirectory =
                    Files.createTempDirectory(
                            "doenaai-git-ssh-"
                    );
            Path homeDirectory =
                    Files.createDirectories(
                            temporaryDirectory.resolve("home")
                    );
            Path sshDirectory =
                    Files.createDirectories(
                            homeDirectory.resolve(".ssh")
                    );
            Path identityFile =
                    sshDirectory.resolve("id_repository");
            Path knownHostsFile =
                    sshDirectory.resolve("known_hosts");
            Path configFile =
                    sshDirectory.resolve("config");

            try {
                Files.writeString(
                        identityFile,
                        ensureTrailingNewline(privateKey),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                );
                restrictPrivateKeyPermissions(
                        identityFile
                );

                Files.writeString(
                        knownHostsFile,
                        ensureTrailingNewline(
                                knownHostsEntry
                        ),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                );

                Files.writeString(
                        configFile,
                        sshConfig(
                                identityFile,
                                knownHostsFile
                        ),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                );

                JGitKeyCache keyCache =
                        new JGitKeyCache();
                SshdSessionFactory sessionFactory =
                        new SshdSessionFactoryBuilder()
                                .setPreferredAuthentications(
                                        "publickey"
                                )
                                .setHomeDirectory(
                                        homeDirectory.toFile()
                                )
                                .setSshDirectory(
                                        sshDirectory.toFile()
                                )
                                .build(keyCache);
                CredentialsProvider credentialsProvider =
                        StringUtils.hasText(passphrase)
                                ? new UsernamePasswordCredentialsProvider(
                                        "",
                                        passphrase
                                )
                                : null;

                return new GitAuthenticationContext(
                        credentialsProvider,
                        sessionFactory,
                        keyCache,
                        temporaryDirectory
                );
            } catch (IOException exception) {
                deleteDirectory(
                        temporaryDirectory
                );

                throw exception;
            } catch (RuntimeException exception) {
                try {
                    deleteDirectory(
                            temporaryDirectory
                    );
                } catch (IOException cleanupException) {
                    exception.addSuppressed(
                            cleanupException
                    );
                }

                throw exception;
            }
        }

        void configure(
                org.eclipse.jgit.api.TransportCommand<?, ?> command
        ) {
            if (credentialsProvider != null) {
                command.setCredentialsProvider(
                        credentialsProvider
                );
            }

            if (sshSessionFactory != null) {
                command.setTransportConfigCallback(
                        transport -> {
                            if (
                                    transport instanceof
                                            SshTransport sshTransport
                            ) {
                                sshTransport.setSshSessionFactory(
                                        sshSessionFactory
                                );
                            }
                        }
                );
            }
        }

        @Override
        public void close() throws IOException {
            if (sshSessionFactory != null) {
                sshSessionFactory.close();
            }

            if (keyCache != null) {
                keyCache.close();
            }

            deleteDirectory(
                    temporaryDirectory
            );
        }

        private static String sshConfig(
                Path identityFile,
                Path knownHostsFile
        ) {
            return """
                    Host *
                        IdentitiesOnly yes
                        IdentityFile "%s"
                        StrictHostKeyChecking yes
                        UserKnownHostsFile "%s"
                        PreferredAuthentications publickey
                    """.formatted(
                    normalizeSshPath(identityFile),
                    normalizeSshPath(knownHostsFile)
            );
        }

        private static String normalizeSshPath(
                Path path
        ) {
            return path.toAbsolutePath()
                    .toString()
                    .replace('\\', '/');
        }

        private static String ensureTrailingNewline(
                String value
        ) {
            String normalized = value.replace(
                    "\r\n",
                    "\n"
            );

            return normalized.endsWith("\n")
                    ? normalized
                    : normalized + "\n";
        }

        private static void restrictPrivateKeyPermissions(
                Path identityFile
        ) throws IOException {
            try {
                Files.setPosixFilePermissions(
                        identityFile,
                        PosixFilePermissions.fromString(
                                "rw-------"
                        )
                );
            } catch (UnsupportedOperationException ignored) {
                // Windows에서는 POSIX 권한 API를 지원하지 않는다.
            }
        }

        private static void deleteDirectory(
                Path directory
        ) throws IOException {
            if (
                    directory == null ||
                    !Files.exists(directory)
            ) {
                return;
            }

            try (var paths = Files.walk(directory)) {
                for (
                        Path path :
                        paths.sorted(
                                Comparator.reverseOrder()
                        ).toList()
                ) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
