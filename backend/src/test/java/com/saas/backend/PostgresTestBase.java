package com.saas.backend;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A disposable database: tests never connect to the developer's saasdb. */
public abstract class PostgresTestBase {
    protected static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16-alpine");
    protected static final org.testcontainers.containers.GenericContainer<?> REDIS=new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static { POSTGRES.start(); REDIS.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("app.redis-url",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        registry.add("app.outbox.initial-delay-ms",()->"3600000");
    }
}
