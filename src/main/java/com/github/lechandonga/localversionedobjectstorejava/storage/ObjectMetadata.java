package com.github.lechandonga.localversionedobjectstorejava.storage;

import java.util.ArrayList;
import java.util.List;

/** Mutable per-object state: the ordered version chain. */
public class ObjectMetadata {

    private final String key;
    private final List<VersionInfo> versions = new ArrayList<>();

    public ObjectMetadata(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public synchronized List<VersionInfo> versions() {
        return List.copyOf(versions);
    }

    public synchronized long nextVersion() {
        return versions.isEmpty() ? 1 : versions.get(versions.size() - 1).version() + 1;
    }

    public synchronized void addVersion(VersionInfo info) {
        versions.add(info);
    }

    public synchronized void restoreVersions(List<VersionInfo> restored) {
        versions.addAll(restored);
    }

    public synchronized void markDeleted(long version) {
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).version() == version) {
                versions.set(i, versions.get(i).markDeleted());
                return;
            }
        }
        throw new StoreException.VersionNotFound(key, version);
    }

    public synchronized boolean hasLiveVersions() {
        return versions.stream().anyMatch(v -> !v.deleted());
    }
}
