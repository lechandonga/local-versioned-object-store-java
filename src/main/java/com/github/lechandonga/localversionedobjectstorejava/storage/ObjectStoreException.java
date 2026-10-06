package com.github.lechandonga.localversionedobjectstorejava.storage;

public abstract class ObjectStoreException extends RuntimeException {
    private final String code;

    protected ObjectStoreException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
