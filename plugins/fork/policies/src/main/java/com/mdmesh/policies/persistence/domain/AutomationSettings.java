package com.mdmesh.policies.persistence.domain;

/** A customer's automation settings and state ({@code plugin_policies_settings}). */
public class AutomationSettings {
    private Integer customerId;
    private boolean nightlyScan;
    private int windowStart = 1;
    private int windowEnd = 5;
    private String timeZone = "UTC";
    /** The night the sweep is on (the date the window started, in the time zone), or null before the first. */
    private String sweepNight;
    /** The last phone id the sweep reached tonight (it walks the phones in id order). */
    private int sweepCursor;
    private Integer sweepJobId;
    /** Finished scans up to this time were checked for auto-remove; null until auto-remove first runs. */
    private Long autoRemoveSince;

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }
    public boolean isNightlyScan() { return nightlyScan; }
    public void setNightlyScan(boolean nightlyScan) { this.nightlyScan = nightlyScan; }
    public int getWindowStart() { return windowStart; }
    public void setWindowStart(int windowStart) { this.windowStart = windowStart; }
    public int getWindowEnd() { return windowEnd; }
    public void setWindowEnd(int windowEnd) { this.windowEnd = windowEnd; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public String getSweepNight() { return sweepNight; }
    public void setSweepNight(String sweepNight) { this.sweepNight = sweepNight; }
    public int getSweepCursor() { return sweepCursor; }
    public void setSweepCursor(int sweepCursor) { this.sweepCursor = sweepCursor; }
    public Integer getSweepJobId() { return sweepJobId; }
    public void setSweepJobId(Integer sweepJobId) { this.sweepJobId = sweepJobId; }
    public Long getAutoRemoveSince() { return autoRemoveSince; }
    public void setAutoRemoveSince(Long autoRemoveSince) { this.autoRemoveSince = autoRemoveSince; }
}
