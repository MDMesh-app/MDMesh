package com.hmdm.persistence.domain;

import java.io.Serializable;

/**
 * One agent-reported location fix in a device's breadcrumb trail (table {@code device_location}).
 * {@code capturedAt} is the fix's own timestamp; {@code recordedAt} is when the server stored it.
 */
public class DeviceLocation implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String deviceNumber;
    private double lat;
    private double lon;
    private Float accuracy;
    private String provider;
    private long capturedAt;
    private long recordedAt;

    public DeviceLocation() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceNumber() {
        return deviceNumber;
    }

    public void setDeviceNumber(String deviceNumber) {
        this.deviceNumber = deviceNumber;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public Float getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(Float accuracy) {
        this.accuracy = accuracy;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public long getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(long recordedAt) {
        this.recordedAt = recordedAt;
    }
}
