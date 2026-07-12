package com.imsw.observe.bootstrap.worker.db;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;

import com.imsw.observe.kernel.error.DataSourceException;
import com.imsw.observe.kernel.script.spi.DbApi;

public final class JdbcDbApi implements DbApi {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDbApi(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> queryOne(final String sql, final Map<String, Object> params) {
        try {
            return jdbc.queryForMap(sql, toParams(params));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        } catch (RuntimeException e) {
            throw new DataSourceException("db.queryOne failed: " + sql, e);
        }
    }

    @Override
    public List<Map<String, Object>> queryAll(final String sql, final Map<String, Object> params) {
        try {
            return jdbc.queryForList(sql, toParams(params));
        } catch (RuntimeException e) {
            throw new DataSourceException("db.queryAll failed: " + sql, e);
        }
    }

    @Override
    public int update(final String sql, final Map<String, Object> params) {
        try {
            return jdbc.update(sql, toParams(params));
        } catch (RuntimeException e) {
            throw new DataSourceException("db.update failed: " + sql, e);
        }
    }

    @Override
    public List<Map<String, Object>> call(final String spName, final Map<String, Object> params) {
        try {
            SimpleJdbcCall call = new SimpleJdbcCall(jdbc.getJdbcTemplate()).withProcedureName(spName);
            Map<String, Object> result = call.execute(new MapSqlParameterSource(params));
            return extractResultSet(result);
        } catch (RuntimeException e) {
            throw new DataSourceException("db.call failed: " + spName, e);
        }
    }

    private static MapSqlParameterSource toParams(final Map<String, Object> params) {
        return params == null ? new MapSqlParameterSource() : new MapSqlParameterSource(params);
    }

    /** SimpleJdbcCall 返回的 Map 里,结果集通常在 "#result-set-1" key 下。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractResultSet(final Map<String, Object> result) {
        for (Object value : result.values()) {
            if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
                return (List<Map<String, Object>>) list;
            }
        }
        return List.of();
    }
}
