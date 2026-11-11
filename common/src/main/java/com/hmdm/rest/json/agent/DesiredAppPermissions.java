package com.hmdm.rest.json.agent;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;

@Getter @Setter
public class DesiredAppPermissions {
    private String packageName;
    private Map<String, String> permissions;
}
