/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
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
