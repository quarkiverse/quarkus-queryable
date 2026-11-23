package io.quarkiverse.queryable.runtime;

import java.util.List;
import java.util.Optional;

public record QueryableEvent<T>(
        Class<?> entityType,
        QueryableOperation operation,
        boolean after,
        Optional<T> entity,
        Optional<Object> id,
        Optional<List<T>> results) {

    public static <T> QueryableEvent<T> before(Class<?> entityType, QueryableOperation operation,
            T entity, Object id) {
        return new QueryableEvent<>(entityType, operation, false,
                Optional.ofNullable(entity), Optional.ofNullable(id), Optional.empty());
    }

    public static <T> QueryableEvent<T> after(Class<?> entityType, QueryableOperation operation,
            T entity, Object id) {
        return new QueryableEvent<>(entityType, operation, true,
                Optional.ofNullable(entity), Optional.ofNullable(id), Optional.empty());
    }

    public static <T> QueryableEvent<T> after(Class<?> entityType, QueryableOperation operation,
            List<T> results) {
        return new QueryableEvent<>(entityType, operation, true,
                Optional.empty(), Optional.empty(), Optional.of(List.copyOf(results)));
    }
}
