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
import io.quarkiverse.queryable.example.model.InvalidFilterSample;
import io.quarkiverse.queryable.example.model.InvalidOptionSamples;
import io.quarkiverse.queryable.example.model.OptionSample;
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

    @Test
    @Transactional
    void restoresParameterlessFilterAndPreservesExistingSessionState() {
        Session session = entityManager.unwrap(Session.class);
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.add("only.current", "");
        assertEquals("1", engine.list(OptionSample.class, uriInfo(parameters)).getHeaderString("listSize"));
        assertNull(session.getEnabledFilter("OptionSample.current"));
        assertEquals(7L, entityManager.createQuery("select count(o) from OptionSample o", Long.class).getSingleResult());

        session.enableFilter("OptionSample.current");
        try {
            assertThrows(IllegalStateException.class, () -> engine.list(OptionSample.class, uriInfo(parameters)));
            assertEquals(6L, entityManager.createQuery("select count(o) from OptionSample o", Long.class).getSingleResult());
        } finally {
            session.disableFilter("OptionSample.current");
        }
    }

    @Test
    @Transactional
    void rejectsParameterlessMappingForFilterThatNeedsValues() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.list(InvalidFilterSample.class, uriInfo(new MultivaluedHashMap<>())));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("cannot use WITHOUT_PARAMETERS"));
    }

    @Test
    @Transactional
    void cleansUpParameterlessFiltersAfterQueryFailure() {
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.add("only.current", "");
        parameters.add("only.invalid", "");
        assertThrows(RuntimeException.class, () -> engine.list(OptionSample.class, uriInfo(parameters)));
        Session session = entityManager.unwrap(Session.class);
        assertNull(session.getEnabledFilter("OptionSample.current"));
        assertNull(session.getEnabledFilter("OptionSample.invalid"));
    }

    @Test
    @Transactional
    void rejectsMissingOrInvalidFixedConditions() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> engine.list(InvalidOptionSamples.MissingCondition.class, uriInfo(new MultivaluedHashMap<>())));
        org.junit.jupiter.api.Assertions.assertTrue(missing.getMessage().contains("requires a non-empty condition"));
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> engine.list(InvalidOptionSamples.InvalidBoolean.class, uriInfo(new MultivaluedHashMap<>())));
        org.junit.jupiter.api.Assertions.assertTrue(invalid.getMessage().contains("Invalid fixed condition"));
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
