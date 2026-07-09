package com.eazycount.handler;

import com.eazycount.entity.Tenant.TenantType;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(TenantType.class)
public class TenantTypeHandler extends BaseTypeHandler<TenantType> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, TenantType parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.name());
    }

    @Override
    public TenantType getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public TenantType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public TenantType getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private static TenantType parse(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TenantType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unknown tenant type: " + value, e);
        }
    }
}
