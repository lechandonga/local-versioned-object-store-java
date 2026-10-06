package com.github.lechandonga.localversionedobjectstorejava.web;

import com.github.lechandonga.localversionedobjectstorejava.storage.LocalObjectStore;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreStats;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoredObject;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ObjectController {
    private final LocalObjectStore store;

    public ObjectController(LocalObjectStore store) {
        this.store = store;
    }

    public record PutResponse(String key, long version, String sha256, long size) {
    }

    @PutMapping("/objects/**")
    public ResponseEntity<PutResponse> put(HttpServletRequest request,
                                           @RequestBody byte[] content,
                                           @RequestHeader(value = "X-Content-Sha256", required = false) String expectedSha256) {
        String key = extractKey(request);
        VersionMeta vm = store.put(key, content, expectedSha256);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PutResponse(key, vm.getVersion(), vm.getSha256(), vm.getSize()));
    }

    @GetMapping("/objects/**")
    public ResponseEntity<byte[]> get(HttpServletRequest request,
                                      @RequestParam(value = "version", required = false) Long version) {
        String key = extractKey(request);
        StoredObject obj = store.get(key, version);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Object-Version", String.valueOf(obj.version()))
                .header("X-Content-Sha256", obj.sha256())
                .body(obj.content());
    }

    @GetMapping(value = "/objects/**", params = "list=versions")
    public List<VersionMeta> listVersions(HttpServletRequest request) {
        return store.listVersions(extractKey(request));
    }

    @DeleteMapping("/objects/**")
    public ResponseEntity<Void> delete(HttpServletRequest request,
                                       @RequestParam(value = "version", required = false) Long version) {
        String key = extractKey(request);
        if (version == null) {
            store.deleteObject(key);
        } else {
            store.deleteVersion(key, version);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public StoreStats stats() {
        return store.stats();
    }

    private String extractKey(HttpServletRequest request) {
        String path = request.getRequestURI();
        String prefix = "/objects/";
        int idx = path.indexOf(prefix);
        if (idx < 0 || idx + prefix.length() >= path.length()) {
            throw new IllegalArgumentException("Object key is missing in path: " + path);
        }
        return path.substring(idx + prefix.length());
    }
}
