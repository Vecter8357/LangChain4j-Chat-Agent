package com.melon.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * MySQL 连接与数据源路由的只读诊断入口，不接收或执行外部 SQL。
 * 业务仓库可同样注入 JdbcTemplate，并在 Spring Bean 的公开方法上使用 @DS 切库。
 */
@Service
public class    MySqlConnectionService {

    private final JdbcTemplate jdbcTemplate;

    /** 使用 Spring Boot 自动配置的 JdbcTemplate，默认连接 application.yml 指定的 primary 数据源。 */
    public MySqlConnectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 读取当前实际连接的数据库名称，用于确认默认数据源。 */
    public String currentDatabase() {
        return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    }

    /**
     * 在指定数据源执行只读连接检查；数据源名必须在配置中存在。
     * 通过注入的 Spring Bean 调用，以使 @DS 代理生效；切换发生在事务创建前。
     */
    @DS("#dataSourceName")
    public String database(String dataSourceName) {
        Assert.hasText(dataSourceName, "数据源名称不能为空");
        return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    }
}
