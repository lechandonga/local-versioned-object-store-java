package com.github.lechandonga.localversionedobjectstorejava.tests;

import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectStoreService;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 业务场景：校验与错误区分。
 * 覆盖：上传校验失败拒绝写入、读取时校验失败拒绝返回坏数据、
 * 各类异常类型互不混淆。
 */
class ChecksumTests {

    private static final Logger log = LoggerFactory.getLogger(ChecksumTests.class);

    @TempDir
    Path dir;

    private ObjectStoreService store;

    @BeforeEach
    void setUp() throws IOException {
        store = new ObjectStoreService(dir.toString());
        store.init();
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void uploadWithMatchingChecksumSucceeds() throws Exception {
        log.info("[校验] 用例：上传携带正确 sha256，正常入库");
        var result = store.put("k",
                new ByteArrayInputStream("good".getBytes()), sha256("good"));
        assertEquals(sha256("good"), result.sha256());
        log.info("[校验] 通过：校验匹配上传成功");
    }

    @Test
    void uploadChecksumMismatchRejectsAndPersistsNothing() throws Exception {
        log.info("[校验] 用例：上传校验失败，拒绝写入且不产生版本/blob");
        assertThrows(StoreException.ChecksumMismatch.class, () ->
                store.put("k", new ByteArrayInputStream("tampered".getBytes()),
                        sha256("something-else")));

        assertThrows(StoreException.ObjectNotFound.class, () -> store.get("k", null));
        assertEquals(0, store.physicalBlobCount());
        assertEquals(0, store.stats().versionCount());
        log.info("[校验] 通过：失败上传零副作用");
    }

    @Test
    void corruptedBlobIsRefusedOnRead() throws Exception {
        log.info("[校验] 用例：磁盘上 blob 被损坏，读取时校验失败拒绝返回");
        var put = store.put("k", new ByteArrayInputStream("integrity".getBytes()), null);
        Path blob = store.blobDir().resolve(put.sha256());
        Files.write(blob, "corrupted-by-disk-failure".getBytes());

        StoreException.IntegrityViolation ex = assertThrows(
                StoreException.IntegrityViolation.class, () -> store.get("k", null));
        log.info("[校验] 通过：读取被拒绝，错误信息：{}", ex.getMessage());
    }

    @Test
    void errorTypesAreDistinct() {
        log.info("[校验] 用例：四种错误类型层级独立，不会混成同一种失败");
        assertThrows(StoreException.ObjectNotFound.class, () -> store.get("nope", null));
        assertThrows(StoreException.ObjectNotFound.class, () -> store.deleteObject("nope"));

        store.put("k", new ByteArrayInputStream("v".getBytes()), null);
        assertThrows(StoreException.VersionNotFound.class, () -> store.get("k", 7L));
        store.deleteVersion("k", 1L);
        assertThrows(StoreException.VersionDeleted.class, () -> store.get("k", 1L));

        log.info("[校验] 通过：ChecksumMismatch/ObjectNotFound/VersionNotFound/VersionDeleted/IntegrityViolation 各自独立");
    }
}
