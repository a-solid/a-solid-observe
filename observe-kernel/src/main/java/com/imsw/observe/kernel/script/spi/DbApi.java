package com.imsw.observe.kernel.script.spi;

import java.util.List;
import java.util.Map;

/**
 * Groovy 脚本的 DB 操作入口（{@code db} binding）。
 *
 * <p>命名参数（{@code :name}）防 SQL 注入；SQL 由可信规则作者写，平台只提供执行通道，
 * 不翻译/校验方言。所有方法跑在当前线程的 pipeline 事务内（Spring 事务上下文）。
 */
public interface DbApi {

    Map<String, Object> queryOne(String sql, Map<String, Object> params);

    List<Map<String, Object>> queryAll(String sql, Map<String, Object> params);

    int update(String sql, Map<String, Object> params);

    /** 调用存储过程，返回结果集。一期不支持 OUT 参数（见 roadmap）。 */
    List<Map<String, Object>> call(String spName, Map<String, Object> params);
}
