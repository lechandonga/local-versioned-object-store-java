package com.github.lechandonga.localversionedobjectstorejava.api;

import com.github.lechandonga.localversionedobjectstorejava.storage.StoreException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps each distinct storage failure to its own HTTP status so clients can
 * tell them apart:
 * <ul>
 *   <li>400 upload checksum mismatch</li>
 *   <li>404 object / version not found</li>
 *   <li>410 version deleted (tombstone)</li>
 *   <li>500 integrity violation on read (corrupted data refused)</li>
 * </ul>
 */
@RestControllerAdvice
public class StoreExceptionHandler {

    @ExceptionHandler(StoreException.ChecksumMismatch.class)
    public ResponseEntity<Map<String, String>> checksum(StoreException.ChecksumMismatch e) {
        return error(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH", e.getMessage());
    }

    @ExceptionHandler(StoreException.ObjectNotFound.class)
    public ResponseEntity<Map<String, String>> objectNotFound(StoreException.ObjectNotFound e) {
        return error(HttpStatus.NOT_FOUND, "OBJECT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(StoreException.VersionNotFound.class)
    public ResponseEntity<Map<String, String>> versionNotFound(StoreException.VersionNotFound e) {
        return error(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(StoreException.VersionDeleted.class)
    public ResponseEntity<Map<String, String>> versionDeleted(StoreException.VersionDeleted e) {
        return error(HttpStatus.GONE, "VERSION_DELETED", e.getMessage());
    }

    @ExceptionHandler(StoreException.IntegrityViolation.class)
    public ResponseEntity<Map<String, String>> integrity(StoreException.IntegrityViolation e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTEGRITY_VIOLATION", e.getMessage());
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", code, "message", message));
    }
}
