package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.util.CommandPayloads;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One row of a device's command history as the console sees it. Deliberately has NO payload: payloads of
 * {@code config.apply} / {@code kiosk.enter} embed the configuration admin password hash, and the history is
 * readable by every user of the customer. {@link #subject} is a safe, server-derived label instead.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandHistoryView {
    private Integer id;
    private String type;
    private String status;
    private String detail;
    private Long createdAt;
    private Long deliveredAt;
    private Long completedAt;
    /** The package an {@code app.install}/{@code app.uninstall} targets; null for every other type. */
    private String subject;

    public static CommandHistoryView from(AgentCommand c) {
        CommandHistoryView v = new CommandHistoryView();
        v.setId(c.getId());
        v.setType(c.getType());
        v.setStatus(c.getStatus());
        v.setDetail(c.getDetail());
        v.setCreatedAt(c.getCreatedAt());
        v.setDeliveredAt(c.getDeliveredAt());
        v.setCompletedAt(c.getCompletedAt());
        v.setSubject(subjectOf(c.getType(), c.getPayload()));
        return v;
    }

    public static List<CommandHistoryView> fromAll(List<AgentCommand> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<CommandHistoryView> out = new ArrayList<CommandHistoryView>(rows.size());
        for (AgentCommand c : rows) out.add(from(c));
        return out;
    }

    /**
     * The history label for a command: its target package for {@code app.install}/{@code app.uninstall}
     * (exact type match — command types are exact strings across the protocol), null for every other type.
     * Never throws; the payload parsing rules live in {@link CommandPayloads#packageNameOf}.
     */
    public static String subjectOf(String type, String payloadJson) {
        if (!"app.install".equals(type) && !"app.uninstall".equals(type)) return null;
        return CommandPayloads.packageNameOf(payloadJson);
    }
}
