package com.github.lechandonga.localversionedobjectstorejava.api;

import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectStoreService;
import com.github.lechandonga.localversionedobjectstorejava.storage.StoreStats;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionInfo;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;

/**
 * HTTP API for the versioned object store.
 *
 * <ul>
 *   <li>{@code PUT /objects/{key}} upload a new version (optional
 *       {@code X-Content-Sha256} header enables upload verification)</li>
 *   <li>{@code GET /objects/{key}[?version=N]} download latest or a version</li>
 *   <li>{@code DELETE /objects/{key}[?version=N]} delete object or one version</li>
 *   <li>{@code GET /objects/{key}/versions} list the version chain</li>
 *   <li>{@code GET /stats} store-wide counters</li>
 * </ul>
 */
@RestController
public class ObjectController {

    private final ObjectStoreService store;

    public ObjectController(ObjectStoreService store) {
        this.store = store;
    }

    @PutMapping("/objects/{key}")
    public ObjectStoreService.PutResult put(@PathVariable String key,
                                            @RequestHeader(value = "X-Content-Sha256", required = false)
                                            String expectedSha256,
                                            HttpServletRequest request) throws IOException {
        return store.put(key, request.getInputStream(), expectedSha256);
    }

    @GetMapping("/objects/{key}")
    public ResponseEntity<FileSystemResource> get(@PathVariable String key,
                                                  @RequestParam(required = false) Long version) {
        ObjectStoreService.StoredObject obj = store.get(key, version);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(obj.info().size())
                .header("X-Object-Version", String.valueOf(obj.info().version()))
                .header("X-Content-Sha256", obj.info().sha256())
                .body(new FileSystemResource(obj.blobPath()));
    }

    @GetMapping("/objects/{key}/versions")
    public List<VersionInfo> versions(@PathVariable String key) {
        return store.listVersions(key);
    }

    @DeleteMapping("/objects/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key,
                                       @RequestParam(required = false) Long version) {
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
}
