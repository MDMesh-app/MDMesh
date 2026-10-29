package com.hmdm.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads and writes an {@link ObjectNode} property as JSON text (a jsonb column such as configurations.extensions).
 * The statement casts the parameter itself ({@code CAST(#{x} AS jsonb)}): PostgreSQL won't implicitly cast a bound
 * varchar to jsonb. Registered for every mapper by {@link com.hmdm.guice.module.AbstractPersistenceModule}.
 */
public class JsonObjectTypeHandler extends BaseTypeHandler<ObjectNode> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, ObjectNode parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, JSON.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("cannot serialize a JSON object", e);
        }
    }

    @Override
    public ObjectNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public ObjectNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public ObjectNode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    static ObjectNode parse(String json) throws SQLException {
        if (json == null) return null;
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isObject()) throw new SQLException("not a JSON object: " + node.getNodeType());
            return (ObjectNode) node;
        } catch (JsonProcessingException e) {
            throw new SQLException("cannot parse a JSON object", e);
        }
    }
}
