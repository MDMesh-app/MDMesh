package com.hmdm.rest.json.agent;

import lombok.Getter;
import lombok.Setter;

/**
 * A typed managed configuration value: {@code type} is {@code bool}, {@code string} or {@code int} (32-bit), and
 * {@code value} the JSON boolean, string or number of that type.
 */
@Getter @Setter
public class DesiredAppRestrictionValue {
    public static final String BOOL = "bool";
    public static final String STRING = "string";
    public static final String INT = "int";

    private String type;
    /** A Boolean, String or Integer, as {@link #type} says. */
    private Object value;

    public DesiredAppRestrictionValue() {}

    public DesiredAppRestrictionValue(String type, Object value) {
        this.type = type;
        this.value = value;
    }
}
