package com.github.lechandonga.localversionedobjectstorejava.storage;

import java.util.ArrayList;
import java.util.List;

public class ObjectMeta {
    private String key;
    private List<VersionMeta> versions = new ArrayList<>();

    public ObjectMeta() {
    }

    public ObjectMeta(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<VersionMeta> getVersions() {
        return versions;
    }

    public void setVersions(List<VersionMeta> versions) {
        this.versions = versions;
    }
}
