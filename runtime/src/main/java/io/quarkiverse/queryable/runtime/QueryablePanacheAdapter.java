package io.quarkiverse.queryable.runtime;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

public abstract class QueryablePanacheAdapter<T> {

    @Inject
    QueryableEngine engine;

    private final Class<T> entityType;

    protected QueryablePanacheAdapter(Class<T> entityType) {
        this.entityType = entityType;
    }

    public List<T> list(Page page, Sort sort) {
        throw new UnsupportedOperationException("Queryable replaces the REST Data list endpoint");
    }

    public List<T> list(Page page, Sort sort, String query, Map<String, Object> parameters) {
        throw new UnsupportedOperationException("Queryable replaces the REST Data list endpoint");
    }

    public int listPageCount(Page page, String query, Map<String, Object> parameters) {
        throw new UnsupportedOperationException("Queryable replaces the REST Data list endpoint");
    }

    public long count() {
        return engine.count(entityType);
    }

    public T get(Object id) {
        return engine.getEntity(entityType, id);
    }

    public T add(Object entity) {
        return engine.createEntity(entityType, entityType.cast(entity));
    }

    public T update(Object id, Object entity) {
        return engine.updateEntity(entityType, id, entityType.cast(entity));
    }

    public boolean delete(Object id) {
        return engine.deleteEntity(entityType, id);
    }
}
