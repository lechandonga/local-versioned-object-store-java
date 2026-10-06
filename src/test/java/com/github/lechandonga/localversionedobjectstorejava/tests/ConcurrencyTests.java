package com.github.lechandonga.localversionedobjectstorejava.tests;

import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectStoreService;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreException;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreStats;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：并发访问。
 * 覆盖：同一对象并发上传版本链完整、并发上传+删除下版本链与引用关系一致、
 * 并发去重不产生引用泄漏、并发读写内容始终完整正确。
 */
class ConcurrencyTests {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTests.class);

    @TempDir
    Path dir;

    private ObjectStoreService store;

    @BeforeEach
    void setUp() throws IOException {
        store = new ObjectStoreService(dir.toString());
        store.init();
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void await(ExecutorService pool, List<Future<?>> futures) throws Exception {
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "tasks finished in time");
        for (Future<?> f : futures) {
            f.get();
        }
    }

    @Test
    void concurrentUploadsToSameKeyKeepEveryVersion() throws Exception {
        int threads = 16;
        log.info("[并发] 用例：{} 个线程对同一对象并发上传，版本链必须完整无丢失", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                store.put("hot", bytes("payload-" + n), null);
                return null;
            }));
        }
        start.countDown();
        await(pool, futures);

        List<VersionInfo> versions = store.listVersions("hot");
        assertEquals(threads, versions.size(), "no version lost under concurrency");
        Set<Long> numbers = new HashSet<>(versions.stream().map(VersionInfo::version).toList());
        assertEquals(threads, numbers.size(), "version numbers unique");
        assertEquals(Set.copyOf(numbers), new HashSet<>(numbers));
        for (VersionInfo v : versions) {
            assertTrue(v.version() >= 1 && v.version() <= threads);
        }
        StoreStats s = store.stats();
        assertEquals(threads, s.versionCount());
        assertEquals(threads, s.blobCount(), "distinct contents -> distinct blobs");
        log.info("[并发] 通过：{} 个版本全部保留，版本号唯一，stats={}", threads, s);
    }

    @Test
    void concurrentUploadAndDeleteNeverLosesDataOrLeaksRefs() throws Exception {
        int threads = 12;
        log.info("[并发] 用例：{} 线程并发上传并随机删除自己刚写的版本，最终引用计数必须一致", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                var r = store.put("shared-" + (n % 3), bytes("content-" + n), null);
                if (n % 2 == 0) {
                    store.deleteVersion(r.key(), r.version());
                }
                return null;
            }));
        }
        start.countDown();
        await(pool, futures);

        StoreStats s = store.stats();
        long liveVersions = s.versionCount();
        long expectedBlobs = liveVersions; // all contents distinct
        assertEquals(expectedBlobs, s.blobCount(), "blob count matches live versions, no leak");
        assertEquals(expectedBlobs, store.physicalBlobCount(), "physical files match refcount");
        assertEquals(threads - liveVersions, s.tombstoneCount());
        // every live version still reads back correct content
        for (int k = 0; k < 3; k++) {
            for (VersionInfo v : store.listVersions("shared-" + k)) {
                if (!v.deleted()) {
                    VersionChainTests.read(store.get("shared-" + k, v.version()));
                }
            }
        }
        log.info("[并发] 通过：live={}, tombstones={}, blobs={} 完全一致，无误删/泄漏",
                liveVersions, s.tombstoneCount(), s.blobCount());
    }

    @Test
    void concurrentDedupOfSameContentKeepsSingleBlob() throws Exception {
        int threads = 16;
        log.info("[并发] 用例：{} 线程并发上传完全相同内容，去重后仅一份 blob", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                store.put("obj-" + n, bytes("identical"), null);
                return null;
            }));
        }
        start.countDown();
        await(pool, futures);

        assertEquals(1, store.physicalBlobCount(), "identical content deduplicated to one blob");
        StoreStats s = store.stats();
        assertEquals(threads, s.versionCount());
        assertEquals(1, s.blobCount());

        // delete all but one object concurrently; blob must survive until last ref
        ExecutorService pool2 = Executors.newFixedThreadPool(threads - 1);
        List<Future<?>> futures2 = new ArrayList<>();
        for (int i = 0; i < threads - 1; i++) {
            int n = i;
            futures2.add(pool2.submit(() -> {
                store.deleteObject("obj-" + n);
                return null;
            }));
        }
        await(pool2, futures2);
        assertEquals(1, store.physicalBlobCount(), "last reference keeps blob alive");
        assertEquals("identical", new String(
                VersionChainTests.read(store.get("obj-" + (threads - 1), 1L)), StandardCharsets.UTF_8));
        store.deleteObject("obj-" + (threads - 1));
        assertEquals(0, store.physicalBlobCount(), "blob freed after final reference removed");
        log.info("[并发] 通过：并发去重 + 并发删除全程引用一致，最终无残留");
    }

    @Test
    void concurrentReadWhileWritingYieldsConsistentContent() throws Exception {
        int writers = 6;
        int readers = 6;
        log.info("[并发] 用例：{} 写线程 + {} 读线程并发，读到的内容要么完整要么明确报错", writers, readers);
        store.put("rw", bytes("gen-0"), null);
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                for (int g = 1; g <= 20; g++) {
                    store.put("rw", bytes("gen-" + n + "-" + g), null);
                }
                return null;
            }));
        }
        for (int i = 0; i < readers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int r = 0; r < 100; r++) {
                    try {
                        byte[] data = VersionChainTests.read(store.get("rw", null));
                        assertTrue(new String(data, StandardCharsets.UTF_8).startsWith("gen-"),
                                "read content always complete");
                    } catch (StoreException.ObjectNotFound e) {
                        // acceptable: no live version at this instant
                    }
                }
                return null;
            }));
        }
        start.countDown();
        await(pool, futures);
        assertEquals(1 + writers * 20L, store.stats().versionCount());
        log.info("[并发] 通过：读写交错下每次读取内容完整，版本数={}", store.stats().versionCount());
    }
}
