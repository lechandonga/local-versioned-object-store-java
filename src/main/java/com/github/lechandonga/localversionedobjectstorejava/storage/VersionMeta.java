package com.github.lechandonga.localversionedobjectstorejava.storage;

import java.time.Instant;

public class VersionMeta {
    private long version;
    private String sha256;
    private long size;
    private boolean deleted;
    private Instant createdAt;

    public VersionMeta() {
    }

    public VersionMeta(long version, String sha256, long size, boolean deleted, Instant createdAt) {
        this.version = version;
        this.sha256 = sha256;
        this.size = size;
        this.deleted = deleted;
        this.createdAt = createdAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
