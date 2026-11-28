package io.quarkiverse.queryable.example;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.queryable.example.model.Customer;
import io.quarkiverse.queryable.runtime.QueryableEngine;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class QueryableLifecycleCallbackTest {

    @Inject
    QueryableEngine engine;

    @BeforeEach
    void resetAudit() {
        given().delete("/test/audit").then().statusCode(204);
    }

    @Test
    void preListCallbackRuns() {
        authenticated().get("/api/v1/customers").then().statusCode(200);
        assertAuditEvent("ANNOTATED:PRE_LIST");
    }

    @Test
    void postListCallbackReceivesResults() {
        authenticated().get("/api/v1/customers").then().statusCode(200);
        assertAuditEvent("ANNOTATED:POST_LIST");
    }

    @Test
    void preFetchCallbackReceivesTheEntityId() {
        org.junit.jupiter.api.Assertions.assertEquals(200, engine.get(Customer.class, "1").getStatus());
        assertAuditEvent("ANNOTATED:PRE_FETCH:1");
    }

    @Test
    void postFetchCallbackReceivesTheEntity() {
        org.junit.jupiter.api.Assertions.assertEquals(200, engine.get(Customer.class, "1").getStatus());
        assertAuditEvent("ANNOTATED:POST_FETCH:1");
    }

    @Test
    void prePersistCallbackRunsBeforeCreation() {
        Long id = createCustomer();
        try {
            assertAuditEvent("ANNOTATED:PRE_PERSIST");
        } finally {
            deleteCustomer(id);
        }
    }

    @Test
    void postPersistCallbackReceivesTheCreatedEntity() {
        Long id = createCustomer();
        try {
            assertAuditEvent("ANNOTATED:POST_PERSIST:" + id);
        } finally {
            deleteCustomer(id);
        }
    }

    @Test
    void preUpdateCallbackReceivesTheEntity() {
        Long id = createCustomer();
        try {
            updateCustomer(id);
            assertAuditEvent("ANNOTATED:PRE_UPDATE:" + id);
        } finally {
            deleteCustomer(id);
        }
    }

    @Test
    void postUpdateCallbackRunsAfterTheUpdate() {
        Long id = createCustomer();
        try {
            updateCustomer(id);
            assertAuditEvent("ANNOTATED:POST_UPDATE:" + id);
        } finally {
            deleteCustomer(id);
        }
    }

    @Test
    void preDeleteCallbackReceivesTheEntityId() {
        Long id = createCustomer();
        given().delete("/test/audit").then().statusCode(204);

        authenticated().delete("/api/v1/customers/" + id).then().statusCode(204);
        assertAuditEvent("ANNOTATED:PRE_DELETE:" + id);
    }

    @Test
    void postDeleteCallbackReceivesTheEntityId() {
        Long id = createCustomer();
        given().delete("/test/audit").then().statusCode(204);

        authenticated().delete("/api/v1/customers/" + id).then().statusCode(204);
        assertAuditEvent("ANNOTATED:POST_DELETE:" + id);
    }

    @Test
    void listenerExtensionReceivesBeforeAndAfterEventsForEveryOperation() {
        Long id = createCustomer();
        org.junit.jupiter.api.Assertions.assertEquals(200, engine.get(Customer.class, id.toString()).getStatus());
        authenticated().get("/api/v1/customers").then().statusCode(200);
        updateCustomer(id);
        authenticated().delete("/api/v1/customers/" + id).then().statusCode(204);

        for (String operation : new String[] { "CREATE", "FETCH", "LIST", "UPDATE", "DELETE" }) {
            assertAuditEvent("LISTENER:BEFORE:" + operation);
            assertAuditEvent("LISTENER:AFTER:" + operation);
        }
    }

    private Long createCustomer() {
        String externalCode = "CALLBACK-" + UUID.randomUUID();
        return Long.valueOf(authenticated().contentType(ContentType.JSON)
                .body("""
                        {"name":"Callback customer","externalCode":"%s","country":"US",
                         "createdOn":"2026-03-01","active":true,"address":{"city":"Boston"}}
                        """.formatted(externalCode))
                .post("/api/v1/customers").then().statusCode(201).extract().path("id").toString());
    }

    private void updateCustomer(Long id) {
        authenticated().contentType(ContentType.JSON)
                .body("""
                        {"id":%d,"name":"Callback customer updated","externalCode":"CALLBACK-%d",
                         "country":"US","createdOn":"2026-03-01","active":true,
                         "address":{"city":"Boston"}}
                        """.formatted(id, id))
                .put("/api/v1/customers/" + id).then().statusCode(204);
    }

    private void deleteCustomer(Long id) {
        authenticated().delete("/api/v1/customers/" + id).then().statusCode(204);
    }

    private void assertAuditEvent(String event) {
        given().get("/test/audit").then().statusCode(200).body("$", hasItem(event));
    }

    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("alice", "secret");
    }
}
