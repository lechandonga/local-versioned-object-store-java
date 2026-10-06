package com.github.lechandonga.localversionedobjectstorejava.storage;

public class VersionDeletedException extends ObjectStoreException {
    public VersionDeletedException(String key, long version) {
        super("VERSION_DELETED", "Version " + version + " of object '" + key + "' has been deleted");
    }
}
