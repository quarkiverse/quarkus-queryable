package io.quarkiverse.queryable.runtime.spi;

import io.quarkiverse.queryable.runtime.QueryableFilter;

public interface QueryableFilterMarker {
    default Class<?> entityType() {
        return QueryableEntityType.resolve(this, QueryableFilter.class);
    }
}
