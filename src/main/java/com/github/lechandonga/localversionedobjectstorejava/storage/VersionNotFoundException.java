package com.github.lechandonga.localversionedobjectstorejava.storage;

public class VersionNotFoundException extends ObjectStoreException {
    public VersionNotFoundException(String key, long version) {
        super("VERSION_NOT_FOUND", "Object '" + key + "' has no version " + version);
    }
}
