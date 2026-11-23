package io.quarkiverse.queryable.runtime;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

public abstract class QueryableGeneratedResource<T> {

    @Inject
    QueryableEngine engine;

    private final Class<T> entityType;

    protected QueryableGeneratedResource(Class<T> entityType) {
        this.entityType = entityType;
    }

    protected Response listInternal(UriInfo uriInfo) {
        return engine.list(entityType, uriInfo);
    }

    protected Response getInternal(String id) {
        return engine.get(entityType, id);
    }

    protected Response createInternal(T entity) {
        return engine.create(entityType, entity);
    }

    protected Response updateInternal(String id, T entity) {
        return engine.update(entityType, id, entity);
    }

    protected Response deleteInternal(String id) {
        return engine.delete(entityType, id);
    }
}
