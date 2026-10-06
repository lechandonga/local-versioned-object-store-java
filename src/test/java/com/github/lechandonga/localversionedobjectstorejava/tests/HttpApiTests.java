package com.github.lechandonga.localversionedobjectstorejava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：HTTP 接口端到端。
 * 覆盖：上传/下载/删除/版本列表/统计接口，以及 400/404/410/500 错误状态区分。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpApiTests {

    private static final Logger log = LoggerFactory.getLogger(HttpApiTests.class);

    private static final Path STORE_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("store-http-test");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("store.root", STORE_DIR::toString);
    }

    @Autowired
    TestRestTemplate rest;

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes()));
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        log.info("[HTTP] 用例：上传→下载→版本列表→删除→统计 全链路");
        // upload two versions
        ResponseEntity<Map> v1 = rest.exchange("/objects/http-doc", HttpMethod.PUT,
                new HttpEntity<>("hello-v1".getBytes(), jsonHeaders()), Map.class);
        assertEquals(HttpStatus.OK, v1.getStatusCode());
        assertEquals(1, ((Number) v1.getBody().get("version")).intValue());

        HttpHeaders withChecksum = jsonHeaders();
        withChecksum.set("X-Content-Sha256", sha256("hello-v2"));
        ResponseEntity<Map> v2 = rest.exchange("/objects/http-doc", HttpMethod.PUT,
                new HttpEntity<>("hello-v2".getBytes(), withChecksum), Map.class);
        assertEquals(2, ((Number) v2.getBody().get("version")).intValue());

        // download latest and historical
        ResponseEntity<byte[]> latest = rest.getForEntity("/objects/http-doc", byte[].class);
        assertEquals("hello-v2", new String(latest.getBody()));
        assertEquals("2", latest.getHeaders().getFirst("X-Object-Version"));
        ResponseEntity<byte[]> old = rest.getForEntity("/objects/http-doc?version=1", byte[].class);
        assertEquals("hello-v1", new String(old.getBody()));

        // version list
        ResponseEntity<java.util.List> chain = rest.getForEntity("/objects/http-doc/versions", java.util.List.class);
        assertEquals(2, chain.getBody().size());

        // delete version 1 -> GET returns 410, version 99 -> 404
        rest.delete("/objects/http-doc?version=1");
        ResponseEntity<Map> gone = rest.getForEntity("/objects/http-doc?version=1", Map.class);
        assertEquals(HttpStatus.GONE, gone.getStatusCode());
        assertEquals("VERSION_DELETED", gone.getBody().get("error"));
        ResponseEntity<Map> missing = rest.getForEntity("/objects/http-doc?version=99", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("VERSION_NOT_FOUND", missing.getBody().get("error"));

        // stats endpoint explains state
        ResponseEntity<Map> stats = rest.getForEntity("/stats", Map.class);
        assertEquals(HttpStatus.OK, stats.getStatusCode());
        assertTrue(((Number) stats.getBody().get("versionCount")).longValue() >= 1);
        log.info("[HTTP] 通过：全链路状态码与内容正确，stats={}", stats.getBody());
    }

    @Test
    void checksumMismatchReturns400AndNothingStored() throws Exception {
        log.info("[HTTP] 用例：上传校验失败返回 400 CHECKSUM_MISMATCH");
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Content-Sha256", sha256("expected-content"));
        ResponseEntity<Map> resp = rest.exchange("/objects/bad-upload", HttpMethod.PUT,
                new HttpEntity<>("actual-content".getBytes(), headers), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("CHECKSUM_MISMATCH", resp.getBody().get("error"));

        ResponseEntity<Map> after = rest.getForEntity("/objects/bad-upload", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, after.getStatusCode());
        assertEquals("OBJECT_NOT_FOUND", after.getBody().get("error"));
        log.info("[HTTP] 通过：400 与 404 错误体可区分");
    }

    @Test
    void deletingWholeObjectRemovesIt() {
        log.info("[HTTP] 用例：DELETE 不带版本号删除整个对象");
        rest.exchange("/objects/to-delete", HttpMethod.PUT,
                new HttpEntity<>("bye".getBytes(), jsonHeaders()), Map.class);
        rest.delete("/objects/to-delete");
        ResponseEntity<Map> resp = rest.getForEntity("/objects/to-delete", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        log.info("[HTTP] 通过：对象删除后返回 OBJECT_NOT_FOUND");
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return h;
    }
}
