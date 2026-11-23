package io.quarkiverse.queryable.runtime.spi;

import io.quarkiverse.queryable.runtime.QueryableRsListener;

public interface QueryableRsListenerMarker {
    default Class<?> entityType() {
        return QueryableEntityType.resolve(this, QueryableRsListener.class);
    }
}
