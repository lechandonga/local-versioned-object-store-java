package com.github.lechandonga.localversionedobjectstorejava.storage;

public class DataIntegrityException extends ObjectStoreException {
    public DataIntegrityException(String key, long version) {
        super("DATA_INTEGRITY_ERROR",
                "Stored data for object '" + key + "' version " + version + " failed checksum verification; refusing to serve corrupt data");
    }
}
