package io.quarkiverse.queryable.runtime;

import io.quarkiverse.queryable.runtime.spi.QueryableFilterMarker;

public interface QueryableFilter<T> extends QueryableFilterMarker {

    void contribute(QueryableFilterContext<T> context);
}
