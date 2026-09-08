package com.saas.backend.operations;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
@Configuration @EnableScheduling @EnableSchedulerLock(defaultLockAtMostFor="PT2M")
public class OutboxConfiguration {
    @Bean LockProvider lockProvider(DataSource source) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(new JdbcTemplate(source)).usingDbTime().build());
    }
}
