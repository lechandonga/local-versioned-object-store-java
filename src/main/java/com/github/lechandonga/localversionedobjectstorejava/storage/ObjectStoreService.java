package com.github.lechandonga.localversionedobjectstorejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Local-disk versioned object store.
 *
 * <p>Layout under the storage root:
 * <pre>
 *   blobs/&lt;sha256&gt;            deduplicated content, addressed by digest
 *   objects/&lt;base64url(key)&gt;.json   version-chain metadata per object
 * </pre>
 *
 * <p>Consistency model: a single fair read/write lock guards both the in-memory
 * metadata/refcount maps and the metadata files + blob lifecycle, so any
 * interleaving of concurrent uploads, reads and deletes leaves the version
 * chain and blob reference counts consistent. A blob is physically removed
 * only when its reference count (live versions pointing at it) reaches zero.
 */
@Service
public class ObjectStoreService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreService.class);

    private final Path root;
    private final Path blobDir;
    private final Path metaDir;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Map<String, ObjectMetadata> objects = new HashMap<>();
    /** sha256 -> number of live versions referencing it. */
    private final Map<String, Long> refCounts = new HashMap<>();
    /** sha256 -> blob size in bytes. */
    private final Map<String, Long> blobSizes = new HashMap<>();

    public ObjectStoreService(@Value("${store.root:./data}") String rootDir) {
        this.root = Path.of(rootDir);
        this.blobDir = root.resolve("blobs");
        this.metaDir = root.resolve("objects");
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(blobDir);
        Files.createDirectories(metaDir);
        lock.writeLock().lock();
        try {
            loadMetadata();
            sweepOrphanBlobs();
        } finally {
            lock.writeLock().unlock();
        }
        log.info("object store initialised at {} (objects={}, blobs={})",
                root.toAbsolutePath(), objects.size(), refCounts.size());
    }

    // ---------------------------------------------------------------- put

    /** Result of a successful upload. */
    public record PutResult(String key, long version, String sha256, long size, boolean deduplicated) {
    }

    /**
     * Uploads content as a new version of {@code key}.
     *
     * @param expectedSha256 optional client-supplied digest; mismatch aborts
     *                       the upload with {@link StoreException.ChecksumMismatch}
     *                       and nothing is persisted.
     */
    public PutResult put(String key, InputStream content, String expectedSha256) {
        lock.writeLock().lock();
        try {
            Path tmp = Files.createTempFile(root, "upload-", ".tmp");
            String sha256;
            long size;
            try (InputStream in = content) {
                MessageDigest digest = newSha256();
                size = copy(in, tmp, digest);
                sha256 = HexFormat.of().formatHex(digest.digest());
            }
            if (expectedSha256 != null && !expectedSha256.isBlank()
                    && !expectedSha256.equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(tmp);
                throw new StoreException.ChecksumMismatch(expectedSha256, sha256);
            }

            boolean deduplicated = refCounts.getOrDefault(sha256, 0L) > 0 || Files.exists(blobDir.resolve(sha256));
            Path blob = blobDir.resolve(sha256);
            if (!Files.exists(blob)) {
                Files.move(tmp, blob, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.deleteIfExists(tmp); // identical content already stored
            }

            ObjectMetadata meta = objects.computeIfAbsent(key, ObjectMetadata::new);
            long version = meta.nextVersion();
            meta.addVersion(new VersionInfo(version, sha256, size, System.currentTimeMillis(), false));
            refCounts.merge(sha256, 1L, Long::sum);
            blobSizes.putIfAbsent(sha256, size);
            persist(meta);
            log.info("put key={} version={} sha256={} size={} deduplicated={}", key, version, sha256, size, deduplicated);
            return new PutResult(key, version, sha256, size, deduplicated);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- get

    /** A resolved, integrity-verified object version ready for streaming. */
    public record StoredObject(String key, VersionInfo info, Path blobPath) {
    }

    /**
     * Resolves a version (latest when {@code version} is null) and verifies
     * the blob's checksum before returning. Corrupted blobs are refused with
     * {@link StoreException.IntegrityViolation}.
     */
    public StoredObject get(String key, Long version) {
        lock.readLock().lock();
        try {
            ObjectMetadata meta = objects.get(key);
            if (meta == null) {
                throw new StoreException.ObjectNotFound(key);
            }
            VersionInfo info = resolve(meta, version);
            Path blob = blobDir.resolve(info.sha256());
            if (!Files.exists(blob)) {
                throw new StoreException.IntegrityViolation(key, info.version());
            }
            String actual = hashFile(blob);
            if (!actual.equals(info.sha256())) {
                log.error("integrity violation key={} version={} expected={} actual={}",
                        key, info.version(), info.sha256(), actual);
                throw new StoreException.IntegrityViolation(key, info.version());
            }
            return new StoredObject(key, info, blob);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<VersionInfo> listVersions(String key) {
        lock.readLock().lock();
        try {
            ObjectMetadata meta = objects.get(key);
            if (meta == null) {
                throw new StoreException.ObjectNotFound(key);
            }
            return meta.versions();
        } finally {
            lock.readLock().unlock();
        }
    }

    private VersionInfo resolve(ObjectMetadata meta, Long version) {
        List<VersionInfo> versions = meta.versions();
        if (version == null) {
            for (int i = versions.size() - 1; i >= 0; i--) {
                if (!versions.get(i).deleted()) {
                    return versions.get(i);
                }
            }
            throw new StoreException.ObjectNotFound(meta.key());
        }
        for (VersionInfo v : versions) {
            if (v.version() == version) {
                if (v.deleted()) {
                    throw new StoreException.VersionDeleted(meta.key(), version);
                }
                return v;
            }
        }
        throw new StoreException.VersionNotFound(meta.key(), version);
    }

    // ------------------------------------------------------------- delete

    /** Deletes one version; keeps a tombstone so it stays distinguishable. */
    public void deleteVersion(String key, long version) {
        lock.writeLock().lock();
        try {
            ObjectMetadata meta = objects.get(key);
            if (meta == null) {
                throw new StoreException.ObjectNotFound(key);
            }
            VersionInfo target = null;
            for (VersionInfo v : meta.versions()) {
                if (v.version() == version) {
                    target = v;
                    break;
                }
            }
            if (target == null) {
                throw new StoreException.VersionNotFound(key, version);
            }
            if (target.deleted()) {
                throw new StoreException.VersionDeleted(key, version);
            }
            meta.markDeleted(version);
            release(target.sha256());
            persist(meta);
            log.info("deleted version key={} version={}", key, version);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Deletes all live versions of an object and removes its metadata. */
    public void deleteObject(String key) {
        lock.writeLock().lock();
        try {
            ObjectMetadata meta = objects.get(key);
            if (meta == null) {
                throw new StoreException.ObjectNotFound(key);
            }
            for (VersionInfo v : meta.versions()) {
                if (!v.deleted()) {
                    meta.markDeleted(v.version());
                    release(v.sha256());
                }
            }
            objects.remove(key);
            Files.deleteIfExists(metaFile(key));
            log.info("deleted object key={}", key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Decrements a blob refcount and physically removes it at zero. */
    private void release(String sha256) throws IOException {
        long remaining = refCounts.getOrDefault(sha256, 0L) - 1;
        if (remaining <= 0) {
            refCounts.remove(sha256);
            blobSizes.remove(sha256);
            Files.deleteIfExists(blobDir.resolve(sha256));
        } else {
            refCounts.put(sha256, remaining);
        }
    }

    // -------------------------------------------------------------- stats

    public StoreStats stats() {
        lock.readLock().lock();
        try {
            long objectsLive = 0;
            long versionsLive = 0;
            long tombstones = 0;
            long logical = 0;
            for (ObjectMetadata meta : objects.values()) {
                boolean live = false;
                for (VersionInfo v : meta.versions()) {
                    if (v.deleted()) {
                        tombstones++;
                    } else {
                        versionsLive++;
                        logical += v.size();
                        live = true;
                    }
                }
                if (live) {
                    objectsLive++;
                }
            }
            long totalBytes = blobSizes.values().stream().mapToLong(Long::longValue).sum();
            return new StoreStats(objectsLive, versionsLive, tombstones,
                    refCounts.size(), totalBytes, logical);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------- internals

    private void loadMetadata() throws IOException {
        try (Stream<Path> files = Files.list(metaDir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                PersistedObject persisted = mapper.readValue(file.toFile(), PersistedObject.class);
                ObjectMetadata meta = new ObjectMetadata(persisted.key());
                meta.restoreVersions(persisted.versions());
                objects.put(meta.key(), meta);
                for (VersionInfo v : meta.versions()) {
                    if (!v.deleted()) {
                        refCounts.merge(v.sha256(), 1L, Long::sum);
                        blobSizes.putIfAbsent(v.sha256(), v.size());
                    }
                }
            }
        }
    }

    /** Removes blob files no live version references (crash leftovers). */
    private void sweepOrphanBlobs() throws IOException {
        try (Stream<Path> files = Files.list(blobDir)) {
            for (Path blob : files.toList()) {
                if (!refCounts.containsKey(blob.getFileName().toString())) {
                    Files.deleteIfExists(blob);
                    log.warn("swept orphan blob {}", blob.getFileName());
                }
            }
        }
    }

    private Path metaFile(String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return metaDir.resolve(encoded + ".json");
    }

    private void persist(ObjectMetadata meta) throws IOException {
        Path tmp = Files.createTempFile(metaDir, "meta-", ".tmp");
        mapper.writeValue(tmp.toFile(), new PersistedObject(meta.key(), meta.versions()));
        Files.move(tmp, metaFile(meta.key()), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** JSON-serialisable form of an object's version chain. */
    record PersistedObject(String key, List<VersionInfo> versions) {
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long copy(InputStream in, Path target, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (var out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                total += n;
            }
        }
        return total;
    }

    private static String hashFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = newSha256();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Test hook: expose blob directory for corruption/orphan checks. */
    public Path blobDir() {
        return blobDir;
    }

    /** Test hook: number of blob files physically on disk. */
    public long physicalBlobCount() throws IOException {
        try (Stream<Path> files = Files.list(blobDir)) {
            return files.count();
        }
    }

    /** Test hook: all versions of a key including tombstones. */
    public List<VersionInfo> debugVersions(String key) {
        ObjectMetadata meta = objects.get(key);
        return meta == null ? new ArrayList<>() : meta.versions();
    }
}
