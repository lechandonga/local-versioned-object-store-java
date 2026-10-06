package com.github.lechandonga.localversionedobjectstorejava.tests;

import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectStoreService;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：内容去重。
 * 覆盖：相同内容只存一份、跨对象共享 blob、删除/覆盖版本不影响其它引用、
 * 引用计数归零后物理删除、磁盘占用可解释。
 */
class ContentDedupTests {

    private static final Logger log = LoggerFactory.getLogger(ContentDedupTests.class);

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

    @Test
    void identicalContentStoredOnceAcrossObjectsAndVersions() throws IOException {
        log.info("[内容去重] 用例：两个对象 + 重复版本写入相同内容，磁盘只保留一份 blob");
        var r1 = store.put("a", bytes("same-content"), null);
        var r2 = store.put("b", bytes("same-content"), null);
        var r3 = store.put("a", bytes("same-content"), null); // new version, same content

        assertEquals(r1.sha256(), r2.sha256());
        assertTrue(r2.deduplicated() && r3.deduplicated());
        assertEquals(1, store.physicalBlobCount(), "one physical blob for identical content");

        StoreStats stats = store.stats();
        assertEquals(1, stats.blobCount());
        assertEquals(3, stats.versionCount());
        assertEquals(stats.totalBytes(), "same-content".length());
        assertEquals(3L * "same-content".length(), stats.logicalBytes());
        log.info("[内容去重] 通过：blobCount=1, versions=3, 物理 {} 字节 vs 逻辑 {} 字节",
                stats.totalBytes(), stats.logicalBytes());
    }

    @Test
    void deletingOneReferenceKeepsBlobForOthers() throws IOException {
        log.info("[内容去重] 用例：删除共享内容的一个版本，其它对象与版本不受影响");
        store.put("a", bytes("shared"), null);
        store.put("b", bytes("shared"), null);
        store.put("a", bytes("unique-a"), null);

        store.deleteVersion("a", 1L);
        assertEquals(2, store.physicalBlobCount(), "shared blob + unique-a blob remain");
        assertArrayEquals("shared".getBytes(), VersionChainTests.read(store.get("b", 1L)));

        store.deleteObject("b");
        assertEquals(1, store.physicalBlobCount(), "shared blob freed once refcount hits zero");
        assertArrayEquals("unique-a".getBytes(), VersionChainTests.read(store.get("a", 2L)));
        log.info("[内容去重] 通过：引用计数归零才物理删除，存活引用内容完整");
    }

    @Test
    void overwriteWithSameContentDoesNotCorruptChain() throws IOException {
        log.info("[内容去重] 用例：用相同内容覆盖后再删除旧版本，内容仍可读");
        store.put("k", bytes("x"), null);
        store.put("k", bytes("x"), null); // overwrite with identical content
        store.deleteVersion("k", 1L);

        assertEquals(1, store.physicalBlobCount());
        assertArrayEquals("x".getBytes(), VersionChainTests.read(store.get("k", 2L)));
        log.info("[内容去重] 通过：同内容覆盖 + 删除旧版本后 blob 仍被正确引用");
    }

    @Test
    void statsExplainStorageState() {
        log.info("[内容去重] 用例：对象数/版本数/占用空间与实际操作一一对应");
        store.put("o1", bytes("12345"), null);
        store.put("o1", bytes("1234567890"), null);
        store.put("o2", bytes("12345"), null);
        store.deleteVersion("o1", 1L);

        StoreStats s = store.stats();
        log.info("[内容去重] stats: {}", s);
        assertEquals(2, s.objectCount());
        assertEquals(2, s.versionCount());
        assertEquals(1, s.tombstoneCount());
        assertEquals(2, s.blobCount());
        assertEquals(5 + 10, s.totalBytes());
        assertEquals(5 + 10, s.logicalBytes());
        log.info("[内容去重] 通过：统计计数与预期完全一致");
    }
}
