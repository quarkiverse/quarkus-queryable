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
            context.add(context.root().get("address").get("city").in(context.params("address.city")));
        }
        for (String alternative : context.orParams("address.city")) {
            context.addOr(context.criteriaBuilder().equal(
                    context.root().get("address").get("city"), alternative));
        }
    }
}

abstract class CustomerFilterBase<T> implements QueryableFilter<T> {
}
