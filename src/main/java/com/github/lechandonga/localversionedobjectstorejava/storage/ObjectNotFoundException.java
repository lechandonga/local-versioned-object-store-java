package com.github.lechandonga.localversionedobjectstorejava.storage;

public class ObjectNotFoundException extends ObjectStoreException {
    public ObjectNotFoundException(String key) {
        super("OBJECT_NOT_FOUND", "Object '" + key + "' does not exist");
    }
}
