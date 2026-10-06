package com.github.lechandonga.localversionedobjectstorejava.tests;

import com.github.lechandonga.localversionedobjectstorejava.storage.ChecksumMismatchException;
import com.github.lechandonga.localversionedobjectstorejava.storage.DataIntegrityException;
import com.github.lechandonga.localversionedobjectstorejava.storage.LocalObjectStore;
import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectNotFoundException;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreStats;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoredObject;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionDeletedException;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionMeta;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("本地版本化对象存储 - 核心行为")
class LocalObjectStoreTest {
    private static final Logger log = LoggerFactory.getLogger(LocalObjectStoreTest.class);

    @TempDir
    Path tempDir;

    private LocalObjectStore store;

    @BeforeEach
    void setUp() {
        store = new LocalObjectStore(tempDir.toString());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("[版本链] 多次上传产生递增版本, 历史版本可取回, 删除版本成墓碑")
    void versionChain() {
        log.info("场景: 版本链 - 上传3个版本并逐一验证");
        VersionMeta v1 = store.put("doc/a.txt", bytes("v1-content"), null);
        VersionMeta v2 = store.put("doc/a.txt", bytes("v2-content"), null);
        VersionMeta v3 = store.put("doc/a.txt", bytes("v3-content"), null);
        assertEquals(1, v1.getVersion());
        assertEquals(2, v2.getVersion());
        assertEquals(3, v3.getVersion());

        assertArrayEquals(bytes("v1-content"), store.get("doc/a.txt", 1L).content());
        assertArrayEquals(bytes("v2-content"), store.get("doc/a.txt", 2L).content());
        assertArrayEquals(bytes("v3-content"), store.get("doc/a.txt", null).content());
        log.info("历史版本读取正确, 默认读取最新版本正确");

        store.deleteVersion("doc/a.txt", 2);
        assertThrows(VersionDeletedException.class, () -> store.get("doc/a.txt", 2L));
        assertThrows(VersionDeletedException.class, () -> store.deleteVersion("doc/a.txt", 2));
        assertArrayEquals(bytes("v3-content"), store.get("doc/a.txt", null).content());
        assertEquals(3, store.listVersions("doc/a.txt").size());
        log.info("版本2删除后为墓碑(410语义), 其余版本不受影响");

        store.deleteVersion("doc/a.txt", 3);
        assertArrayEquals(bytes("v1-content"), store.get("doc/a.txt", null).content());
        log.info("最新版本删除后回退到最近存活版本, 版本链行为验证通过");
    }

    @Test
    @DisplayName("[内容去重] 相同内容只存一份, 删除/覆盖不影响其它引用")
    void contentDedup() {
        log.info("场景: 内容去重 - 两个对象写入相同内容");
        store.put("obj-x", bytes("same-payload"), null);
        store.put("obj-y", bytes("same-payload"), null);
        store.put("obj-x", bytes("x-v2"), null);

        StoreStats stats = store.stats();
        assertEquals(2, stats.objectCount());
        assertEquals(3, stats.liveVersionCount());
        assertEquals(2, stats.blobCount());
        log.info("3个版本仅产生2个blob, 去重生效: {}", stats);

        store.deleteVersion("obj-x", 1);
        assertArrayEquals(bytes("same-payload"), store.get("obj-y", null).content());
        assertEquals(2, store.stats().blobCount());
        log.info("删除obj-x v1后, obj-y引用的blob保留");

        store.deleteObject("obj-y");
        assertEquals(1, store.stats().blobCount());
        assertArrayEquals(bytes("x-v2"), store.get("obj-x", null).content());
        log.info("obj-y整体删除后共享blob被回收, obj-x不受影响, 无引用泄漏");
    }

    @Test
    @DisplayName("[错误区分] 校验失败/对象不存在/版本不存在/版本已删除 各不相同")
    void distinctErrors() {
        log.info("场景: 错误类型区分");
        byte[] data = bytes("hello");
        assertThrows(ChecksumMismatchException.class,
                () -> store.put("k", data, "0000deadbeef"));
        log.info("上传校验失败 -> ChecksumMismatchException");

        assertThrows(ObjectNotFoundException.class, () -> store.get("missing", null));
        assertThrows(ObjectNotFoundException.class, () -> store.deleteObject("missing"));
        log.info("对象不存在 -> ObjectNotFoundException");

        store.put("k", data, null);
        assertThrows(VersionNotFoundException.class, () -> store.get("k", 99L));
        assertThrows(VersionNotFoundException.class, () -> store.deleteVersion("k", 99));
        log.info("版本不存在 -> VersionNotFoundException");

        store.deleteVersion("k", 1);
        assertThrows(VersionDeletedException.class, () -> store.get("k", 1L));
        log.info("版本已删除 -> VersionDeletedException, 四类错误互不混淆");
    }

    @Test
    @DisplayName("[完整性] 磁盘数据被篡改后读取被拒绝而非返回坏数据")
    void integrityProtection() throws Exception {
        log.info("场景: 读取校验 - 篡改blob文件");
        VersionMeta vm = store.put("asset", bytes("original-bytes"), null);
        Path blob = tempDir.resolve("blobs").resolve(vm.getSha256());
        Files.write(blob, bytes("tampered-bytes!"));
        log.info("已篡改blob {}, 尝试读取", blob.getFileName());

        DataIntegrityException ex = assertThrows(DataIntegrityException.class,
                () -> store.get("asset", null));
        assertTrue(ex.getMessage().contains("asset"));
        log.info("读取被校验拦截: {}", ex.getMessage());
    }

    @Test
    @DisplayName("[并发] 同一对象并发上传+删除, 版本链与引用关系保持一致")
    void concurrentPutAndDelete() throws Exception {
        log.info("场景: 并发读写 - 8线程并发上传, 交错删除");
        int threads = 8;
        int putsPerThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        store.put("hot-key", bytes("t" + id + "-i" + i), null);
                        if (i % 3 == 0) {
                            store.get("hot-key", null);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (long v = 1; v <= 50; v++) {
                    try {
                        store.deleteVersion("hot-key", v);
                    } catch (VersionNotFoundException | VersionDeletedException ignored) {
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<VersionMeta> chain = store.listVersions("hot-key");
        assertEquals(threads * putsPerThread, chain.size());
        Set<Long> versions = new HashSet<>();
        for (VersionMeta vm : chain) {
            assertTrue(versions.add(vm.getVersion()), "duplicate version number " + vm.getVersion());
            if (!vm.isDeleted()) {
                StoredObject obj = store.get("hot-key", vm.getVersion());
                assertEquals(vm.getSha256(), obj.sha256());
            }
        }
        StoreStats stats = store.stats();
        long referenced = chain.stream().filter(v -> !v.isDeleted())
                .map(VersionMeta::getSha256).distinct().count();
        assertEquals(referenced, stats.blobCount(), "blob数必须等于存活版本引用的去重内容数, 无泄漏无误删");
        log.info("并发后版本链完整(共{}版, 无重号), blob数={}与引用一致, stats={}",
                chain.size(), stats.blobCount(), stats);
    }

    @Test
    @DisplayName("[统计] 对象数/版本数/占用空间能解释存储状态")
    void statsConsistency() {
        log.info("场景: 统计可解释性");
        store.put("s1", bytes("12345"), null);
        store.put("s1", bytes("12345"), null);
        store.put("s2", bytes("abcdefghij"), null);
        store.deleteVersion("s2", 1);

        StoreStats stats = store.stats();
        log.info("stats: {}", stats);
        assertEquals(2, stats.objectCount());
        assertEquals(2, stats.liveVersionCount());
        assertEquals(1, stats.deletedVersionCount());
        assertEquals(1, stats.blobCount());
        assertEquals(5, stats.totalBlobBytes());
    }
}
