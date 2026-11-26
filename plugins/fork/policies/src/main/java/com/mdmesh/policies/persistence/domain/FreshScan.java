package com.mdmesh.policies.persistence.domain;

/** A phone on an auto-remove policy whose latest app scan finished in the window being checked. */
public class FreshScan extends JobPhone {
    private String scanDetail;
    private Long scannedAt;

    public String getScanDetail() { return scanDetail; }
    public void setScanDetail(String scanDetail) { this.scanDetail = scanDetail; }
    public Long getScannedAt() { return scannedAt; }
    public void setScannedAt(Long scannedAt) { this.scannedAt = scannedAt; }
}
