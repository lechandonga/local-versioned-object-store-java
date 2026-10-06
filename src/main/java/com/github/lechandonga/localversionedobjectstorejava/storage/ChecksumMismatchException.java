package com.github.lechandonga.localversionedobjectstorejava.storage;

public class ChecksumMismatchException extends ObjectStoreException {
    public ChecksumMismatchException(String key, String expected, String actual) {
        super("CHECKSUM_MISMATCH",
                "Upload checksum mismatch for object '" + key + "': expected sha256=" + expected + " but computed " + actual);
    }
}
