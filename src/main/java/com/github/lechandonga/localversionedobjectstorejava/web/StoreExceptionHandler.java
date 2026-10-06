package com.github.lechandonga.localversionedobjectstorejava.web;

import com.github.lechandonga.localversionedobjectstorejava.storage.ChecksumMismatchException;
import com.github.lechandonga.localversionedobjectstorejava.storage.DataIntegrityException;
import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectNotFoundException;
import com.github.lechandonga.localversionedobjectstorejava.storage.ObjectStoreException;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionDeletedException;
import com.github.lechandonga.localversionedobjectstorejava.storage.VersionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class StoreExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(StoreExceptionHandler.class);

    public record ErrorBody(String code, String message, Instant timestamp) {
    }

    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<ErrorBody> checksum(ChecksumMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(ObjectNotFoundException.class)
    public ResponseEntity<ErrorBody> objectNotFound(ObjectNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<ErrorBody> versionNotFound(VersionNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(VersionDeletedException.class)
    public ResponseEntity<ErrorBody> versionDeleted(VersionDeletedException e) {
        return build(HttpStatus.GONE, e);
    }

    @ExceptionHandler(DataIntegrityException.class)
    public ResponseEntity<ErrorBody> integrity(DataIntegrityException e) {
        log.error("Data integrity failure: {}", e.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e);
    }

    private ResponseEntity<ErrorBody> build(HttpStatus status, ObjectStoreException e) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(e.getCode(), e.getMessage(), Instant.now()));
    }
}
