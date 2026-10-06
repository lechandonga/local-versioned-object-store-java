package com.github.lechandonga.localversionedobjectstorejava.tests;

import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectStoreService;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreException;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：版本链。
 * 覆盖：多次上传产生递增版本、历史版本可取回、最新版本解析、
 * 删除版本留下墓碑、对象整体删除、各类“不存在/已删除”错误区分。
 */
class VersionChainTests {

    private static final Logger log = LoggerFactory.getLogger(VersionChainTests.class);

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
    void uploadsCreateIncreasingVersionsAndHistoryIsRetrievable() {
        log.info("[版本链] 用例：连续上传 3 个版本，校验版本号递增且每个历史版本可取回");
        store.put("doc", bytes("v1-content"), null);
        store.put("doc", bytes("v2-content"), null);
        store.put("doc", bytes("v3-content"), null);

        List<VersionInfo> versions = store.listVersions("doc");
        assertEquals(List.of(1L, 2L, 3L), versions.stream().map(VersionInfo::version).toList());

        assertArrayEquals("v1-content".getBytes(), read(store.get("doc", 1L)));
        assertArrayEquals("v2-content".getBytes(), read(store.get("doc", 2L)));
        assertArrayEquals("v3-content".getBytes(), read(store.get("doc", 3L)));
        log.info("[版本链] 通过：3 个历史版本内容均完整正确");
    }

    @Test
    void latestVersionResolvesNewestLiveVersion() {
        log.info("[版本链] 用例：不带版本号读取解析为最新存活版本");
        store.put("doc", bytes("old"), null);
        store.put("doc", bytes("new"), null);
        assertEquals(2L, store.get("doc", null).info().version());
        assertArrayEquals("new".getBytes(), read(store.get("doc", null)));

        store.deleteVersion("doc", 2L);
        assertEquals(1L, store.get("doc", null).info().version());
        log.info("[版本链] 通过：删除最新版本后回退到上一个存活版本");
    }

    @Test
    void deletedVersionIsDistinguishableFromMissing() {
        log.info("[版本链] 用例：已删除版本(410 语义)与从未存在版本(404 语义)可区分");
        store.put("doc", bytes("a"), null);
        store.put("doc", bytes("b"), null);
        store.deleteVersion("doc", 1L);

        assertThrows(StoreException.VersionDeleted.class, () -> store.get("doc", 1L));
        assertThrows(StoreException.VersionNotFound.class, () -> store.get("doc", 99L));
        assertThrows(StoreException.ObjectNotFound.class, () -> store.get("ghost", null));
        assertThrows(StoreException.VersionDeleted.class, () -> store.deleteVersion("doc", 1L));

        List<VersionInfo> chain = store.listVersions("doc");
        assertTrue(chain.get(0).deleted(), "tombstone retained for deleted version");
        log.info("[版本链] 通过：墓碑保留，VersionDeleted / VersionNotFound / ObjectNotFound 各自独立");
    }

    @Test
    void deleteObjectRemovesAllVersionsAndMetadata() {
        log.info("[版本链] 用例：整体删除对象后对象不可读且版本链清除");
        store.put("doc", bytes("a"), null);
        store.put("doc", bytes("b"), null);
        store.deleteObject("doc");

        assertThrows(StoreException.ObjectNotFound.class, () -> store.get("doc", null));
        assertThrows(StoreException.ObjectNotFound.class, () -> store.listVersions("doc"));
        assertThrows(StoreException.ObjectNotFound.class, () -> store.deleteObject("doc"));
        assertEquals(0, store.stats().objectCount());
        log.info("[版本链] 通过：对象删除后统计中对象数归零");
    }

    @Test
    void metadataSurvivesRestart() throws IOException {
        log.info("[版本链] 用例：元数据持久化，重启后版本链与内容仍可取回");
        store.put("doc", bytes("persist-me"), null);
        store.put("doc", bytes("persist-me-2"), null);

        ObjectStoreService reopened = new ObjectStoreService(dir.toString());
        reopened.init();
        assertEquals(2, reopened.listVersions("doc").size());
        assertArrayEquals("persist-me".getBytes(), read(reopened.get("doc", 1L)));
        log.info("[版本链] 通过：重新加载后版本链完整");
    }

    static byte[] read(ObjectStoreService.StoredObject obj) {
        try {
            return java.nio.file.Files.readAllBytes(obj.blobPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
