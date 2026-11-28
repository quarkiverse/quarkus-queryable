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
import io.quarkiverse.queryable.runtime.QueryableEvent;
import io.quarkiverse.queryable.runtime.QueryableRsListener;
import io.quarkus.security.identity.SecurityIdentity;

@ApplicationScoped
public class CustomerRsListener extends CustomerListenerBase<Customer> {

    @Inject
    AuditSink auditSink;

    @Inject
    SecurityIdentity securityIdentity;

    @Override
    public void onBefore(QueryableEvent<Customer> event) {
        auditSink.add("LISTENER:BEFORE:" + event.operation());
    }

    @Override
    public void onAfter(QueryableEvent<Customer> event) {
        auditSink.add("LISTENER:AFTER:" + event.operation());
    }

    @PrePersist
    void normalize(Customer customer) {
        auditSink.add("ANNOTATED:PRE_PERSIST");
        if (customer.externalCode != null) {
            customer.externalCode = customer.externalCode.trim().toUpperCase(Locale.ROOT);
        }

        customer.creationDate = LocalDateTime.now();
        customer.creationUser = securityIdentity.getPrincipal().getName();
    }

    @PostPersist
    void auditPersist(Customer customer) {
        auditSink.add("ANNOTATED:POST_PERSIST:" + customer.id);
        auditSink.add(io.quarkiverse.queryable.runtime.QueryableOperation.CREATE, customer.id);
    }

    @PostDelete
    void auditDelete(Long id) {
        auditSink.add("ANNOTATED:POST_DELETE:" + id);
        auditSink.add(io.quarkiverse.queryable.runtime.QueryableOperation.DELETE, id);
    }

    @PostUpdate
    void afterUpdate(Customer customer) {
        auditSink.add("ANNOTATED:POST_UPDATE:" + customer.id);
        auditSink.add(io.quarkiverse.queryable.runtime.QueryableOperation.UPDATE, customer.id);
        throw new IllegalStateException("simulated external system outage");
    }

    @PreFetch
    void preFetch(Long id) {
        auditSink.add("ANNOTATED:PRE_FETCH:" + id);
    }

    @PostFetch
    void postFetch(Customer customer) {
        auditSink.add("ANNOTATED:POST_FETCH:" + customer.id);
    }

    @PreList
    void preList() {
        auditSink.add("ANNOTATED:PRE_LIST");
    }

    @PostList
    void postList(java.util.List<Customer> customers) {
        auditSink.add("ANNOTATED:POST_LIST");
        if (customers.isEmpty()) {
            throw new IllegalStateException("List callback did not receive results");
        }
    }

    @PreUpdate
    void preUpdate(Customer customer) {
        auditSink.add("ANNOTATED:PRE_UPDATE:" + customer.id);
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
        auditSink.add("ANNOTATED:PRE_DELETE:" + id);
    }
}

abstract class CustomerListenerBase<T> implements QueryableRsListener<T> {
}
