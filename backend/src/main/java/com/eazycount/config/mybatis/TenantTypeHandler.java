package com.eazycount.config.mybatis;

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
        ps.setString(i, parameter.getValue());
    }

    @Override
    public TenantType getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return TenantType.fromValue(rs.getString(columnName));
    }

    @Override
    public TenantType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return TenantType.fromValue(rs.getString(columnIndex));
    }

    @Override
    public TenantType getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return TenantType.fromValue(cs.getString(columnIndex));
    }
}
