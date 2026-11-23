package io.quarkiverse.queryable.example.service.filters;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.queryable.example.model.Customer;
import io.quarkiverse.queryable.runtime.QueryableFilter;
import io.quarkiverse.queryable.runtime.QueryableFilterContext;

@ApplicationScoped
public class CustomerFilter implements QueryableFilter<Customer> {

    @Override
    public Class<Customer> entityType() {
        return Customer.class;
    }

    @Override
    public void contribute(QueryableFilterContext<Customer> context) {
        String city = context.param("address.city");
        if (city != null) {
            context.add(context.criteriaBuilder().equal(
                    context.root().get("address").get("city"), city));
        }
    }
}
