package com.github.lechandonga.localversionedobjectstorejava.storage;

/**
 * Metadata for one version of an object. Deleted versions are kept as
 * tombstones ({@code deleted=true}) so "version deleted" is distinguishable
 * from "version never existed".
 */
public record VersionInfo(
        long version,
        String sha256,
        long size,
        long createdAtEpochMs,
        boolean deleted) {

    public VersionInfo markDeleted() {
        return new VersionInfo(version, sha256, size, createdAtEpochMs, true);
    }
}
