package io.quarkiverse.queryable.runtime;

import io.quarkiverse.queryable.runtime.spi.QueryableRsListenerMarker;

public interface QueryableRsListener<T> extends QueryableRsListenerMarker {

    default void onBefore(QueryableEvent<T> event) {
    }

    default void onAfter(QueryableEvent<T> event) {
    }
}
