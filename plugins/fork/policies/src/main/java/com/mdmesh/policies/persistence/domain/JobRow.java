package com.mdmesh.policies.persistence.domain;

/** A job ({@code plugin_policies_job}). */
public class JobRow {
    private Integer id;
    private Integer customerId;
    private Integer createdBy;
    private String action;
    private String label;
    private Long createdAt;
    private boolean cancelled;

    /** For a scheduled job: commands still undelivered after this are expired (null: no deadline). */
    private Long deadline;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public Long getDeadline() { return deadline; }
    public void setDeadline(Long deadline) { this.deadline = deadline; }
}
