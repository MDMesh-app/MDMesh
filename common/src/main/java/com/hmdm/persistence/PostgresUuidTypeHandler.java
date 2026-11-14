package com.hmdm.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** PostgreSQL binding for the server's native UUID columns. */
public class PostgresUuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override public void setNonNullParameter(PreparedStatement ps, int i, UUID value, JdbcType jdbcType) throws SQLException { ps.setObject(i, value); }
    @Override public UUID getNullableResult(ResultSet rs, String column) throws SQLException { return uuid(rs.getObject(column)); }
    @Override public UUID getNullableResult(ResultSet rs, int column) throws SQLException { return uuid(rs.getObject(column)); }
    @Override public UUID getNullableResult(CallableStatement cs, int column) throws SQLException { return uuid(cs.getObject(column)); }
    private static UUID uuid(Object value) { return value == null ? null : value instanceof UUID ? (UUID) value : UUID.fromString(value.toString()); }
}
