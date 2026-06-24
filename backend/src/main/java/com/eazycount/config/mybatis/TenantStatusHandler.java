package com.eazycount.config.mybatis;

import com.eazycount.entity.Tenant.TenantStatus;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(TenantStatus.class)
public class TenantStatusHandler extends BaseTypeHandler<TenantStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, TenantStatus parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.getValue());
    }

    @Override
    public TenantStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return TenantStatus.fromValue(rs.getString(columnName));
    }

    @Override
    public TenantStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return TenantStatus.fromValue(rs.getString(columnIndex));
    }

    @Override
    public TenantStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return TenantStatus.fromValue(cs.getString(columnIndex));
    }
}
