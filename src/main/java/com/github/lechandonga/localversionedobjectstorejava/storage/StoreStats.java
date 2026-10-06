package com.github.lechandonga.localversionedobjectstorejava.storage;

/**
 * Snapshot of store-wide counters used to explain current storage state.
 *
 * @param objectCount      objects with at least one live version
 * @param versionCount     live (non-deleted) versions across all objects
 * @param tombstoneCount   deleted versions retained as tombstones
 * @param blobCount        distinct content blobs on disk (deduplicated)
 * @param totalBytes       sum of blob sizes actually occupying disk
 * @param logicalBytes     sum of sizes of all live versions (before dedup)
 */
public record StoreStats(
        long objectCount,
        long versionCount,
        long tombstoneCount,
        long blobCount,
        long totalBytes,
        long logicalBytes) {
}
