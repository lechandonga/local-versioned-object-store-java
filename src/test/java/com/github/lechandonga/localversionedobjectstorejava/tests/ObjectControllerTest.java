package com.github.lechandonga.localversionedobjectstorejava.tests;

import com.github.lechandonga.localversionedobjectstorejava.storage.LocalObjectStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@DisplayName("HTTP 接口 - 端到端行为与错误码区分")
class ObjectControllerTest {
    private static final Logger log = LoggerFactory.getLogger(ObjectControllerTest.class);

    @TempDir
    static Path tempDir;

    @TestConfiguration
    static class Config {
        @Bean
        LocalObjectStore localObjectStore() {
            return new LocalObjectStore(tempDir.toString());
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("[HTTP] 上传/下载/删除/版本列表/统计 全链路")
    void httpEndToEnd() throws Exception {
        log.info("场景: HTTP 全链路");
        MvcResult r1 = mvc.perform(put("/objects/docs/readme.txt")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("hello-v1".getBytes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String sha = com.jayway.jsonpath.JsonPath.read(r1.getResponse().getContentAsString(), "$.sha256");
        log.info("上传v1成功 sha256={}", sha);

        mvc.perform(put("/objects/docs/readme.txt").content("hello-v2".getBytes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/objects/docs/readme.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Version", "2"))
                .andExpect(content().bytes("hello-v2".getBytes()));
        mvc.perform(get("/objects/docs/readme.txt?version=1"))
                .andExpect(content().bytes("hello-v1".getBytes()));
        log.info("下载最新版与历史版均正确");

        mvc.perform(get("/objects/docs/readme.txt?list=versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(delete("/objects/docs/readme.txt?version=1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/objects/docs/readme.txt?version=1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERSION_DELETED"));
        log.info("删除版本返回204, 再读该版本返回410 VERSION_DELETED");

        MvcResult stats = mvc.perform(get("/stats")).andExpect(status().isOk()).andReturn();
        log.info("GET /stats -> {}", stats.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("[HTTP] 各类错误映射为不同状态码与错误码")
    void httpErrorDistinction() throws Exception {
        log.info("场景: HTTP 错误码区分");
        mvc.perform(put("/objects/err-key")
                        .header("X-Content-Sha256", "badchecksum")
                        .content("payload".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
        log.info("上传校验失败 -> 400 CHECKSUM_MISMATCH");

        mvc.perform(get("/objects/never-existed"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"));
        log.info("对象不存在 -> 404 OBJECT_NOT_FOUND");

        mvc.perform(put("/objects/err-key").content("ok".getBytes()))
                .andExpect(status().isCreated());
        mvc.perform(get("/objects/err-key?version=42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
        log.info("版本不存在 -> 404 VERSION_NOT_FOUND");

        mvc.perform(delete("/objects/err-key?version=1")).andExpect(status().isNoContent());
        mvc.perform(get("/objects/err-key?version=1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERSION_DELETED"));
        log.info("版本已删除 -> 410 VERSION_DELETED, 四类错误HTTP语义清晰区分");
    }
}
