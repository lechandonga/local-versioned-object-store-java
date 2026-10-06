package com.github.lechandonga.localversionedobjectstorejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Local-disk versioned object store.
 *
 * Layout under the storage root:
 *   blobs/{sha256}   - content-addressed data, one file per distinct content (dedup)
 *   meta/{base64url(key)}.json - version chain metadata per object key
 *
 * All mutations are serialized by a write lock; reads take a read lock, so the
 * version chain and blob reference relations stay consistent under concurrency.
 */
@Component
public class LocalObjectStore {
    private static final Logger log = LoggerFactory.getLogger(LocalObjectStore.class);

    private final Path root;
    private final Path blobDir;
    private final Path metaDir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public LocalObjectStore(@Value("${object-store.root:./data}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        this.blobDir = root.resolve("blobs");
        this.metaDir = root.resolve("meta");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(blobDir);
            Files.createDirectories(metaDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize storage at " + root, e);
        }
        log.info("LocalObjectStore initialized at {}", root);
    }

    public Path getRoot() {
        return root;
    }

    /** Upload a new version of {@code key}. If expectedSha256 is given it is verified first. */
    public VersionMeta put(String key, byte[] content, String expectedSha256) {
        String sha256 = sha256Hex(content);
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.equalsIgnoreCase(sha256)) {
            throw new ChecksumMismatchException(key, expectedSha256, sha256);
        }
        lock.writeLock().lock();
        try {
            writeBlobIfAbsent(sha256, content);
            ObjectMeta meta = readMetaOrNew(key);
            long nextVersion = meta.getVersions().stream()
                    .mapToLong(VersionMeta::getVersion).max().orElse(0) + 1;
            VersionMeta vm = new VersionMeta(nextVersion, sha256, content.length, false, Instant.now());
            meta.getVersions().add(vm);
            writeMeta(meta);
            log.info("PUT key='{}' version={} sha256={} size={}", key, nextVersion, sha256, content.length);
            return vm;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Fetch the latest live version, or a specific version when {@code version} is non-null. */
    public StoredObject get(String key, Long version) {
        lock.readLock().lock();
        try {
            ObjectMeta meta = readMeta(key);
            VersionMeta vm = selectVersion(meta, version);
            byte[] content = readBlobVerified(key, vm);
            return new StoredObject(key, vm.getVersion(), vm.getSha256(), content);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** List the full version chain (including tombstones) of an object. */
    public List<VersionMeta> listVersions(String key) {
        lock.readLock().lock();
        try {
            return new ArrayList<>(readMeta(key).getVersions());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Tombstone a single version; garbage-collects its blob when no other version references it. */
    public void deleteVersion(String key, long version) {
        lock.writeLock().lock();
        try {
            ObjectMeta meta = readMeta(key);
            VersionMeta vm = meta.getVersions().stream()
                    .filter(v -> v.getVersion() == version).findFirst()
                    .orElseThrow(() -> new VersionNotFoundException(key, version));
            if (vm.isDeleted()) {
                throw new VersionDeletedException(key, version);
            }
            vm.setDeleted(true);
            writeMeta(meta);
            log.info("DELETE key='{}' version={} (tombstoned)", key, version);
            gcIfUnreferenced(vm.getSha256());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Delete the whole object: tombstones every version and collects unreferenced blobs. */
    public void deleteObject(String key) {
        lock.writeLock().lock();
        try {
            ObjectMeta meta = readMeta(key);
            Set<String> candidates = new HashSet<>();
            for (VersionMeta vm : meta.getVersions()) {
                if (!vm.isDeleted()) {
                    vm.setDeleted(true);
                    candidates.add(vm.getSha256());
                }
            }
            writeMeta(meta);
            log.info("DELETE key='{}' (all {} versions tombstoned)", key, meta.getVersions().size());
            candidates.forEach(this::gcIfUnreferenced);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Snapshot of object/version/blob counts and actual disk usage. */
    public StoreStats stats() {
        lock.readLock().lock();
        try {
            int objects = 0;
            int live = 0;
            int deleted = 0;
            for (Path metaFile : listFiles(metaDir)) {
                ObjectMeta meta = readMetaFile(metaFile);
                objects++;
                for (VersionMeta vm : meta.getVersions()) {
                    if (vm.isDeleted()) {
                        deleted++;
                    } else {
                        live++;
                    }
                }
            }
            int blobs = 0;
            long bytes = 0;
            for (Path blob : listFiles(blobDir)) {
                blobs++;
                bytes += Files.size(blob);
            }
            return new StoreStats(objects, live, deleted, blobs, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---- internals ----

    private VersionMeta selectVersion(ObjectMeta meta, Long version) {
        String key = meta.getKey();
        if (version == null) {
            return meta.getVersions().stream()
                    .filter(v -> !v.isDeleted())
                    .max(Comparator.comparingLong(VersionMeta::getVersion))
                    .orElseThrow(() -> new ObjectNotFoundException(key));
        }
        VersionMeta vm = meta.getVersions().stream()
                .filter(v -> v.getVersion() == version).findFirst()
                .orElseThrow(() -> new VersionNotFoundException(key, version));
        if (vm.isDeleted()) {
            throw new VersionDeletedException(key, version);
        }
        return vm;
    }

    private byte[] readBlobVerified(String key, VersionMeta vm) {
        Path blob = blobDir.resolve(vm.getSha256());
        byte[] content;
        try {
            content = Files.readAllBytes(blob);
        } catch (IOException e) {
            throw new DataIntegrityException(key, vm.getVersion());
        }
        if (!sha256Hex(content).equals(vm.getSha256())) {
            log.error("Integrity check FAILED key='{}' version={} sha256={}", key, vm.getVersion(), vm.getSha256());
            throw new DataIntegrityException(key, vm.getVersion());
        }
        return content;
    }

    private void writeBlobIfAbsent(String sha256, byte[] content) {
        Path blob = blobDir.resolve(sha256);
        try {
            if (Files.exists(blob)) {
                log.debug("Dedup hit: blob {} already exists, reusing", sha256);
                return;
            }
            Path tmp = Files.createTempFile(blobDir, ".tmp-", ".part");
            Files.write(tmp, content);
            try {
                Files.move(tmp, blob, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, blob);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write blob " + sha256, e);
        }
    }

    /** Delete the blob only when no live version in any object still references it. Caller holds write lock. */
    private void gcIfUnreferenced(String sha256) {
        for (Path metaFile : listFiles(metaDir)) {
            ObjectMeta meta = readMetaFile(metaFile);
            for (VersionMeta vm : meta.getVersions()) {
                if (!vm.isDeleted() && vm.getSha256().equals(sha256)) {
                    return;
                }
            }
        }
        try {
            if (Files.deleteIfExists(blobDir.resolve(sha256))) {
                log.info("GC removed unreferenced blob {}", sha256);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to gc blob " + sha256, e);
        }
    }

    private ObjectMeta readMeta(String key) {
        Path metaFile = metaFile(key);
        if (!Files.exists(metaFile)) {
            throw new ObjectNotFoundException(key);
        }
        return readMetaFile(metaFile);
    }

    private ObjectMeta readMetaOrNew(String key) {
        Path metaFile = metaFile(key);
        return Files.exists(metaFile) ? readMetaFile(metaFile) : new ObjectMeta(key);
    }

    private ObjectMeta readMetaFile(Path metaFile) {
        try {
            return mapper.readValue(metaFile.toFile(), ObjectMeta.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read metadata " + metaFile, e);
        }
    }

    private void writeMeta(ObjectMeta meta) {
        Path metaFile = metaFile(meta.getKey());
        try {
            Path tmp = Files.createTempFile(metaDir, ".tmp-", ".json");
            mapper.writeValue(tmp.toFile(), meta);
            try {
                Files.move(tmp, metaFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, metaFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write metadata for key " + meta.getKey(), e);
        }
    }

    private Path metaFile(String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return metaDir.resolve(encoded + ".json");
    }

    private List<Path> listFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith(".tmp-"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
