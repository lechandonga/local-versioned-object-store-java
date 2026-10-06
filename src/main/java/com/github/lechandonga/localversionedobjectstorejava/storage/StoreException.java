package com.github.lechandonga.localversionedobjectstorejava.storage;

/**
 * Base type for all storage-level failures. Subtypes map to distinct HTTP
 * statuses so callers can tell failure modes apart.
 */
public abstract class StoreException extends RuntimeException {

    protected StoreException(String message) {
        super(message);
    }

    /** Uploaded bytes do not match the client-supplied checksum. */
    public static class ChecksumMismatch extends StoreException {
        public ChecksumMismatch(String expected, String actual) {
            super("upload checksum mismatch: expected sha256=" + expected + " but computed " + actual);
        }
    }

    /** The object key does not exist (or has been fully removed). */
    public static class ObjectNotFound extends StoreException {
        public ObjectNotFound(String key) {
            super("object not found: " + key);
        }
    }

    /** The object exists but the requested version number does not. */
    public static class VersionNotFound extends StoreException {
        public VersionNotFound(String key, long version) {
            super("version " + version + " not found for object: " + key);
        }
    }

    /** The version existed but has been deleted (tombstone). */
    public static class VersionDeleted extends StoreException {
        public VersionDeleted(String key, long version) {
            super("version " + version + " of object " + key + " has been deleted");
        }
    }

    /** Stored bytes failed integrity verification on read; data is refused. */
    public static class IntegrityViolation extends StoreException {
        public IntegrityViolation(String key, long version) {
            super("integrity check failed for object " + key + " version " + version
                    + "; refusing to return corrupted data");
        }
    }
}
