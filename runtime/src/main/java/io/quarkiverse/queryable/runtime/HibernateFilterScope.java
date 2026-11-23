package io.quarkiverse.queryable.runtime;

import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MultivaluedMap;

import org.hibernate.Session;
import org.hibernate.engine.spi.FilterDefinition;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.JdbcMapping;

final class HibernateFilterScope implements AutoCloseable {
    private final Session session;
    private final List<String> enabled = new ArrayList<>();

    private HibernateFilterScope(Session session) {
        this.session = session;
    }

    static HibernateFilterScope open(EntityManager entityManager, Class<?> entityType,
            MultivaluedMap<String, String> parameters) {
        QFilter[] mappings = entityType.getAnnotationsByType(QFilter.class);
        if (mappings.length == 0) {
            return new HibernateFilterScope(null);
        }

        Session session = entityManager.unwrap(Session.class);
        SessionFactoryImplementor factory = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactoryImplementor.class);
        Map<String, Map<String, Object>> values = new LinkedHashMap<>();
        Map<String, FilterDefinition> definitions = new LinkedHashMap<>();
        Map<String, String> configuredParameters = new LinkedHashMap<>();
        for (QFilter mapping : mappings) {
            if (mapping.name().isBlank() || mapping.queryParam().isBlank() || mapping.filterParam().isBlank()) {
                throw new IllegalStateException("@QFilter names must not be blank on " + entityType.getName());
            }
            if (!hasFilter(entityType, mapping.name())) {
                throw new IllegalStateException("No Hibernate @Filter named " + mapping.name()
                        + " on " + entityType.getName());
            }
            FilterDefinition definition = factory.getFilterDefinition(mapping.name());
            if (definition == null) {
                throw new IllegalStateException("No Hibernate @FilterDef named " + mapping.name());
            }
            JdbcMapping jdbcMapping = definition.getParameterJdbcMapping(mapping.filterParam());
            if (jdbcMapping == null) {
                throw new IllegalStateException("No parameter " + mapping.filterParam()
                        + " in Hibernate filter " + mapping.name());
            }
            String key = mapping.name() + "\0" + mapping.filterParam();
            if (configuredParameters.putIfAbsent(key, mapping.queryParam()) != null) {
                throw new IllegalStateException("Duplicate mapping for " + mapping.name() + "." + mapping.filterParam());
            }
            definitions.put(mapping.name(), definition);
            String raw = parameters.getFirst(mapping.queryParam());
            if (raw != null) {
                try {
                    if (jdbcMapping.getJavaTypeDescriptor().getJavaTypeClass() == Boolean.class
                            && !raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("Expected true or false");
                    }
                    Object value = jdbcMapping.getJavaTypeDescriptor().fromString(raw);
                    values.computeIfAbsent(mapping.name(), ignored -> new LinkedHashMap<>())
                            .put(mapping.filterParam(), value);
                } catch (IllegalArgumentException | DateTimeException exception) {
                    throw new BadRequestException("Invalid value for query parameter " + mapping.queryParam(), exception);
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : values.entrySet()) {
            FilterDefinition definition = definitions.get(entry.getKey());
            for (String required : definition.getParameterNames()) {
                if (!entry.getValue().containsKey(required) && definition.getParameterResolver(required) == null) {
                    String queryParam = configuredParameters.get(entry.getKey() + "\0" + required);
                    if (queryParam == null) {
                        throw new IllegalStateException("No @QFilter mapping for parameter "
                                + required + " in " + entry.getKey());
                    }
                    throw new BadRequestException("Missing query parameter " + queryParam);
                }
            }
            if (session.getEnabledFilter(entry.getKey()) != null) {
                throw new IllegalStateException("Hibernate filter " + entry.getKey()
                        + " is already enabled on this session");
            }
        }

        HibernateFilterScope scope = new HibernateFilterScope(session);
        try {
            for (Map.Entry<String, Map<String, Object>> entry : values.entrySet()) {
                org.hibernate.Filter filter = session.enableFilter(entry.getKey());
                scope.enabled.add(entry.getKey());
                entry.getValue().forEach(filter::setParameter);
                filter.validate();
            }
            return scope;
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
        }
    }

    private static boolean hasFilter(Class<?> entityType, String name) {
        for (Class<?> type = entityType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (org.hibernate.annotations.Filter filter : type
                    .getDeclaredAnnotationsByType(org.hibernate.annotations.Filter.class)) {
                if (filter.name().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (int i = enabled.size() - 1; i >= 0; i--) {
            session.disableFilter(enabled.get(i));
        }
    }
}
