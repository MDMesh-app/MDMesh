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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

@ApiModel(description = "A single setting for an application installed and used on mobile device")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApplicationSetting implements Serializable {

    private static final long serialVersionUID = -7840348027518868191L;

    @ApiModelProperty("An ID of a setting record")
    private Integer id;

    @ApiModelProperty(value = "An ID of application", required = true)
    private Integer applicationId;

    @ApiModelProperty(value = "A name of the setting", required = true)
    private String name;

    @ApiModelProperty(value = "A type of the application setting", required = true)
    private ApplicationSettingType type;

    @ApiModelProperty("A value of the setting")
    private String value;

    @ApiModelProperty("A comment on the setting")
    private String comment;

    @ApiModelProperty("A timestamp of the last update of the setting")
    private long lastUpdate;

    @ApiModelProperty(value = "A flag indicating if setting can not be modified on device", required = true)
    private boolean readonly;

    @ApiModelProperty(value = "An ID of the external object (device, configuration) which settings belong to", required = true)
    private Integer extRefId;

    @ApiModelProperty(value = "A flag indicating if the setting has the variable content", required = true)
    private boolean variable;

    @ApiModelProperty(hidden = true)
    private String applicationPkg;

    @ApiModelProperty(hidden = true)
    private String applicationName;

    // A name of the external object (device, configuration) which settings belong to
    @ApiModelProperty(hidden = true)
    private String extRefName;

    /**
     * <p>Constructs new <code>ApplicationSetting</code> instance. This implementation does nothing.</p>
     */
    public ApplicationSetting() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Integer applicationId) {
        this.applicationId = applicationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    /** The placeholders a {@code variable} value may hold, each replaced by the device's own field. */
    private static final String[] PLACEHOLDERS = {
            "%NUMBER%", "%IMEI%", "%PHONE%", "%DESCRIPTION%", "%CUSTOM1%", "%CUSTOM2%", "%CUSTOM3%"
    };

    /**
     * Whether the value holds at least one of the placeholders {@link #getValueForDevice} replaces. Such a
     * {@code variable} value gets its type only on the device's own fields, so its type is checked after the replace,
     * not at save. Not a bean property, so it is never serialized.
     */
    public boolean containsPlaceholder() {
        if (value == null) return false;
        for (String p : PLACEHOLDERS) {
            if (value.contains(p)) return true;
        }
        return false;
    }

    /**
     * <p>The value with its placeholders replaced by the device's fields (an absent field or device by nothing). The
     * replace is literal and in one pass: a device field holding {@code $}, {@code \} or a placeholder of its own is
     * put in as it is, never read as a pattern or replaced again.</p>
     *
     * @return null for a null value.
     */
    public String getValueForDevice(Device device) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            String placeholder = null;
            if (value.charAt(i) == '%') {
                for (String p : PLACEHOLDERS) {
                    if (value.startsWith(p, i)) {
                        placeholder = p;
                        break;
                    }
                }
            }
            if (placeholder == null) {
                out.append(value.charAt(i++));
            } else {
                String field = device == null ? null : deviceField(device, placeholder);
                out.append(field == null ? "" : field);
                i += placeholder.length();
            }
        }
        return out.toString();
    }

    private static String deviceField(Device device, String placeholder) {
        switch (placeholder) {
            case "%NUMBER%": return device.getNumber();
            case "%IMEI%": return device.getImei();
            case "%PHONE%": return device.getPhone();
            case "%DESCRIPTION%": return device.getDescription();
            case "%CUSTOM1%": return device.getCustom1();
            case "%CUSTOM2%": return device.getCustom2();
            default: return device.getCustom3();
        }
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public boolean isReadonly() {
        return readonly;
    }

    public void setReadonly(boolean readonly) {
        this.readonly = readonly;
    }

    public Integer getExtRefId() {
        return extRefId;
    }

    public void setExtRefId(Integer extRefId) {
        this.extRefId = extRefId;
    }

    public boolean isVariable() {
        return variable;
    }

    public void setVariable(boolean variable) {
        this.variable = variable;
    }

    public String getApplicationPkg() {
        return applicationPkg;
    }

    public void setApplicationPkg(String applicationPkg) {
        this.applicationPkg = applicationPkg;
    }

    public String getExtRefName() {
        return extRefName;
    }

    public void setExtRefName(String extRefName) {
        this.extRefName = extRefName;
    }

    public ApplicationSettingType getType() {
        return type;
    }

    public void setType(ApplicationSettingType type) {
        this.type = type;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(long lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    /** Leaves the value out: it can be a secret (a token), and a log line must never hold it. */
    @Override
    public String toString() {
        return "ApplicationSetting{" +
                "id=" + id +
                ", applicationId=" + applicationId +
                ", name='" + name + '\'' +
                ", comment='" + comment + '\'' +
                ", readonly=" + readonly +
                ", extRefId=" + extRefId +
                ", type=" + type +
                ", variable=" + variable +
                ", applicationPkg='" + applicationPkg + '\'' +
                ", extRefName='" + extRefName + '\'' +
                ", lastUpdate='" + lastUpdate + '\'' +
                '}';
    }
}
