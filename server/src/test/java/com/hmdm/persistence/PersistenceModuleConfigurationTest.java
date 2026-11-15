/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.persistence.mapper.RemoteViewSessionMapper;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the same MyBatis module that Tomcat creates at application startup.
 *
 * <p>This intentionally needs no database connection: mapper parsing and global
 * type-handler registration must succeed before a server can expose any route.
 * It prevents a new mapper parameter type from being discovered only after a
 * native deployment has started Tomcat.</p>
 */
public class PersistenceModuleConfigurationTest {

    @Test
    public void productionModuleRegistersUuidHandlerAndParsesRemoteViewMapper() {
        Map<String, String> jdbc = new HashMap<>();
        jdbc.put("JDBC.driver", "org.postgresql.Driver");
        jdbc.put("JDBC.url", "jdbc:postgresql://127.0.0.1:1/mdmesh_test");
        jdbc.put("JDBC.username", "unused");
        jdbc.put("JDBC.password", "unused");

        Injector injector = Guice.createInjector(new PersistenceModule(servletContext(jdbc)));
        Configuration configuration = injector.getInstance(SqlSessionFactory.class).getConfiguration();

        TypeHandler<?> uuidHandler = configuration.getTypeHandlerRegistry().getTypeHandler(UUID.class);
        assertEquals(PostgresUuidTypeHandler.class, uuidHandler.getClass());
        assertTrue(configuration.hasMapper(RemoteViewSessionMapper.class));
        assertTrue(configuration.hasStatement(RemoteViewSessionMapper.class.getName() + ".find"));
        assertTrue(configuration.hasStatement(RemoteViewSessionMapper.class.getName() + ".insertAudit"));

        String expirySql = configuration
                .getMappedStatement(RemoteViewSessionMapper.class.getName() + ".findExpired")
                .getBoundSql(Collections.singletonMap("now", 1L))
                .getSql();
        assertTrue(expirySql.contains("state IN ('requested','awaiting_consent')"));
        assertTrue(expirySql.contains("expiresAt < ?"));

        List<ParameterMapping> findParameters = configuration
                .getMappedStatement(RemoteViewSessionMapper.class.getName() + ".find")
                .getBoundSql(Collections.singletonMap("id", UUID.randomUUID()))
                .getParameterMappings();
        assertEquals(PostgresUuidTypeHandler.class, findParameters.get(0).getTypeHandler().getClass());
    }

    private static ServletContext servletContext(Map<String, String> parameters) {
        return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getInitParameterNames":
                            return Collections.enumeration(parameters.keySet());
                        case "getInitParameter":
                            return parameters.get((String) arguments[0]);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
