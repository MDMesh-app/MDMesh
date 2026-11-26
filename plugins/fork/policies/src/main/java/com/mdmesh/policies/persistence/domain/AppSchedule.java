package com.mdmesh.policies.persistence.domain;

/**
 * Opens an app on a policy's phones at set times: the whole policy, one group of it, or phones picked by hand. A slot
 * fires once (lastSlot); a phone may get it up to lateMinutes after its time.
 */
public class AppSchedule {
    private Integer id;
    private int customerId;
    private int configurationId;
    private String pkg;
    private String appName;
    /** policy, group or phones. */
    private String target;
    /** For target group; null when that group was deleted. */
    private Integer groupId;
    /** For target phones: device ids, comma-separated. */
    private String devices;
    /** Days of the week, Monday = 1 ... Sunday = 64. */
    private int days;
    /** "HH:MM" times, comma-separated. */
    private String times;
    private String timeZone;
    private int lateMinutes;
    private boolean enabled;
    private String lastSlot;
    private long createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public int getCustomerId() { return customerId; }
    public void setCustomerId(int customerId) { this.customerId = customerId; }
    public int getConfigurationId() { return configurationId; }
    public void setConfigurationId(int configurationId) { this.configurationId = configurationId; }
    public String getPkg() { return pkg; }
    public void setPkg(String pkg) { this.pkg = pkg; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public Integer getGroupId() { return groupId; }
    public void setGroupId(Integer groupId) { this.groupId = groupId; }
    public String getDevices() { return devices; }
    public void setDevices(String devices) { this.devices = devices; }
    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }
    public String getTimes() { return times; }
    public void setTimes(String times) { this.times = times; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public int getLateMinutes() { return lateMinutes; }
    public void setLateMinutes(int lateMinutes) { this.lateMinutes = lateMinutes; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLastSlot() { return lastSlot; }
    public void setLastSlot(String lastSlot) { this.lastSlot = lastSlot; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
