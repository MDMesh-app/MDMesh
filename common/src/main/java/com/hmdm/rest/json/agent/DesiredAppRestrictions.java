package com.hmdm.rest.json.agent;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;

/** One app's managed configuration (protocol 1.8): the whole bundle the agent sets for {@link #packageName}. */
@Getter @Setter
public class DesiredAppRestrictions {
    private String packageName;
    /** Key to typed value, sorted by key. */
    private Map<String, DesiredAppRestrictionValue> values;
}
