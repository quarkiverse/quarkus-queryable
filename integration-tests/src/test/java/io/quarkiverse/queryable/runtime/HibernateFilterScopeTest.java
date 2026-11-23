package io.quarkiverse.queryable.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import org.hibernate.Session;
import org.junit.jupiter.api.Test;

import io.quarkiverse.queryable.example.model.Customer;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HibernateFilterScopeTest {
    @Inject
    EntityManager entityManager;

    @Inject
    QueryableEngine engine;

    @Test
    @Transactional
    void restoresSessionAfterSuccessAndQueryFailure() {
        Session session = entityManager.unwrap(Session.class);
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.add("obj.coordinator_id", "1");
        parameters.add("obj.coordinator_country", "IT");
        assertEquals("1", engine.list(Customer.class, uriInfo(parameters)).getHeaderString("listSize"));
        assertNull(session.getEnabledFilter("Customer.coordinator"));
        assertEquals(3L, entityManager.createQuery("select count(c) from Customer c", Long.class).getSingleResult());

        parameters.add("test.invalid_sql", "x");
        assertThrows(RuntimeException.class, () -> engine.list(Customer.class, uriInfo(parameters)));
        assertNull(session.getEnabledFilter("Customer.coordinator"));
        assertNull(session.getEnabledFilter("Customer.invalidSql"));
    }

    @Test
    @Transactional
    void doesNotModifyAnAlreadyEnabledFilter() {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("Customer.coordinator")
                .setParameter("coordinator_id", BigDecimal.ONE)
                .setParameter("country", "IT");
        try {
            MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
            parameters.add("obj.coordinator_id", "2");
            parameters.add("obj.coordinator_country", "FR");
            assertThrows(IllegalStateException.class, () -> engine.list(Customer.class, uriInfo(parameters)));
            assertEquals(1L, entityManager.createQuery("select count(c) from Customer c", Long.class).getSingleResult());
        } finally {
            session.disableFilter("Customer.coordinator");
        }
    }

    private static UriInfo uriInfo(MultivaluedMap<String, String> parameters) {
        return (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(), new Class<?>[] { UriInfo.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getQueryParameters")) {
                        return parameters;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
