/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.persistence.domain;

import java.io.Serializable;

/** A staged agent-APK rollout. One active row per customer; {@code stage} advances canary→fleet→done. */
public class AgentRollout implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private Integer customerId;
    private String targetVersion;
    private String packageName;
    private String apkUrl;
    private String apkSha256;
    private Integer apkVersionCode;
    private String stage;       // canary | fleet | done | cancelled
    private Long createdAt;
    private Long updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getApkUrl() { return apkUrl; }
    public void setApkUrl(String apkUrl) { this.apkUrl = apkUrl; }

    public String getApkSha256() { return apkSha256; }
    public void setApkSha256(String apkSha256) { this.apkSha256 = apkSha256; }

    public Integer getApkVersionCode() { return apkVersionCode; }
    public void setApkVersionCode(Integer apkVersionCode) { this.apkVersionCode = apkVersionCode; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
