package com.mdmesh.policies.persistence.domain;

/** A pair from a query: a configuration's id with its policy JSON or one allowed package, a phone with a payload. */
public class KeyValue {
    private String key;
    private String value;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
