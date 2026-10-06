package com.github.lechandonga.localversionedobjectstorejava.storage;

public record StoredObject(String key, long version, String sha256, byte[] content) {
}
