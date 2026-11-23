package io.quarkiverse.queryable.example.service.filters;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.queryable.example.model.Customer;
import io.quarkiverse.queryable.runtime.QueryableFilter;
import io.quarkiverse.queryable.runtime.QueryableFilterContext;

@ApplicationScoped
public class CustomerFilter extends CustomerFilterBase<Customer> {

    @Override
    public void contribute(QueryableFilterContext<Customer> context) {
        String city = context.param("address.city");
        if (city != null) {
            context.add(context.criteriaBuilder().equal(
                    context.root().get("address").get("city"), city));
        }

    }
}

abstract class CustomerFilterBase<T> implements QueryableFilter<T> {
}
