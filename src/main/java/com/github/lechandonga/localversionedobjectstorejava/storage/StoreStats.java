package com.github.lechandonga.localversionedobjectstorejava.storage;

public record StoreStats(int objectCount, int liveVersionCount, int deletedVersionCount,
                         int blobCount, long totalBlobBytes) {
}
