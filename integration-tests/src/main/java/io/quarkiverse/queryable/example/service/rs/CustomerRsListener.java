package io.quarkiverse.queryable.example.service.rs;

import java.time.LocalDateTime;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import io.quarkiverse.queryable.example.model.Customer;
import io.quarkiverse.queryable.runtime.PostDelete;
import io.quarkiverse.queryable.runtime.PostFetch;
import io.quarkiverse.queryable.runtime.PostList;
import io.quarkiverse.queryable.runtime.PostPersist;
import io.quarkiverse.queryable.runtime.PostUpdate;
import io.quarkiverse.queryable.runtime.PreDelete;
import io.quarkiverse.queryable.runtime.PreFetch;
import io.quarkiverse.queryable.runtime.PreList;
import io.quarkiverse.queryable.runtime.PrePersist;
import io.quarkiverse.queryable.runtime.PreUpdate;
import io.quarkiverse.queryable.runtime.QueryableRsListener;
import io.quarkus.security.identity.SecurityIdentity;

@ApplicationScoped
public class CustomerRsListener extends CustomerListenerBase<Customer> {

    @Inject
    AuditSink auditSink;

    @Inject
    SecurityIdentity securityIdentity;

    @PrePersist
    void normalize(Customer customer) {
        if (customer.externalCode != null) {
            customer.externalCode = customer.externalCode.trim().toUpperCase(Locale.ROOT);
        }

        customer.creationDate = LocalDateTime.now();
        customer.creationUser = securityIdentity.getPrincipal().getName();
    }

    @PostPersist
    void auditPersist(Customer customer) {
        auditSink.add(io.quarkiverse.queryable.runtime.QueryableOperation.CREATE, customer.id);
    }

    @PostDelete
    void auditDelete(Long id) {
        auditSink.add(io.quarkiverse.queryable.runtime.QueryableOperation.DELETE, id);
    }

    @PostUpdate
    void afterUpdate(Customer customer) {
        auditSink.add(io.quarkiverse.queryable.runtime.QueryableOperation.UPDATE, customer.id);
        throw new IllegalStateException("simulated external system outage");
    }

    @PreFetch
    void preFetch(Long id) {
    }

    @PostFetch
    void postFetch(Customer customer) {
    }

    @PreList
    void preList() {
    }

    @PostList
    void postList(java.util.List<Customer> customers) {
        if (customers.isEmpty()) {
            throw new IllegalStateException("List callback did not receive results");
        }
    }

    @PreUpdate
    void preUpdate(Customer customer) {
        Customer persisted = Customer.findById(customer.id);
        if (persisted != null) {
            customer.creationDate = persisted.creationDate;
            customer.creationUser = persisted.creationUser;
        }
        customer.updateDate = LocalDateTime.now();
        customer.updateUser = securityIdentity.getPrincipal().getName();
    }

    @PrePersist
    @PreUpdate
    void validate(Customer customer) {
        if (customer.name == null || customer.name.isBlank()) {
            throw new BadRequestException("name is required");
        }
    }

    @PreDelete
    void preDelete(Long id) {
    }
}

abstract class CustomerListenerBase<T> implements QueryableRsListener<T> {
}
