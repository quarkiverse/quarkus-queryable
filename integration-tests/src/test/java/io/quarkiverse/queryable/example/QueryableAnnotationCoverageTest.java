package io.quarkiverse.queryable.example;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class QueryableAnnotationCoverageTest {

    @Test
    void qSupportsTypeAndFieldScopeAndAliasedNames() {
        int id = createClassFilterSample("annotation-class", "annotation-label");
        try {
            given().queryParam("obj.name", "annotation-class")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(1));
            given().queryParam("exact.label", "annotation-label")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(1));
            authenticated().queryParam("obj.externalCode", "IT-001")
                    .get("/api/v1/customers").then().statusCode(200)
                    .body("$", hasSize(1))
                    .body("[0].externalCode", equalTo("IT-001"));
        } finally {
            given().delete("/api/class-filters/" + id).then().statusCode(204);
        }
    }

    @Test
    void qExecuteAlwaysUsesConfiguredConditions() {
        given().queryParam("obj.country", "FR")
                .queryParam("obj.rating", "20")
                .queryParam("obj.enabled", "false")
                .queryParam("obj.state", "CLOSED")
                .queryParam("like.name", "Adam")
                .get("/api/options").then().statusCode(200)
                .body("$", hasSize(2))
                .body("name", hasItem("Alice"))
                .body("name", hasItem("Alana"));
    }

    @Test
    void qFilterSupportsRepeatableMappingsAndFilterParameterNames() {
        authenticated().queryParam("obj.coordinator_id", "1")
                .queryParam("obj.coordinator_country", "IT")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("IT-001"))
                .header("listSize", "1");
    }

    @Test
    void qFilterWithoutParametersSupportsFlagsAndResolvers() {
        given().get("/api/options?only.current")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Alice"));
        given().get("/api/options?only.resolved")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Alice"));
    }

    @Test
    void qLogicalDeleteMapsTheConfiguredBooleanField() {
        authenticated().queryParam("obj.active", "false")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("DE-003"));
    }

    @Test
    void qLikeMatchesCaseInsensitively() {
        authenticated().queryParam("like.name", "mArIo rOsSi")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("IT-001"));
    }

    @Test
    void qRLikeMatchesTheConfiguredDirectionalPattern() {
        authenticated().queryParam("rlike.name", "mArIo r")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("IT-001"));
    }

    @Test
    void qLLikeMatchesTheConfiguredDirectionalPattern() {
        authenticated().queryParam("llike.name", "BIANCHI")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("FR-002"));
    }

    @Test
    void qListMatchesCommaSeparatedValues() {
        authenticated().queryParam("obj.countries", "IT,FR")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(2))
                .body("externalCode", hasItem("IT-001"))
                .body("externalCode", hasItem("FR-002"))
                .header("listSize", "2");
    }

    @Test
    void qLikeListMatchesAnyCommaSeparatedPattern() {
        String first = createTeam("coverage-alpha", "coverage-alpha");
        String second = createTeam("coverage-beta", "coverage-beta");
        try {
            given().queryParam("like.tags", "missing,coverage-alpha")
                    .get("/api/teams").then().statusCode(200)
                    .body("$", hasSize(1))
                    .body("[0].name", equalTo("coverage-alpha"));
            given().queryParam("or.like.tags", "missing,coverage-beta")
                    .get("/api/teams").then().statusCode(200)
                    .body("$", hasSize(1))
                    .body("[0].name", equalTo("coverage-beta"));
        } finally {
            given().delete("/api/teams/" + first).then().statusCode(204);
            given().delete("/api/teams/" + second).then().statusCode(204);
        }
    }

    @Test
    void qNilMatchesNonNullFields() {
        authenticated().queryParam("nil.notes", "")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("FR-002"));
    }

    @Test
    void qNotNilMatchesNullFields() {
        authenticated().queryParam("notNil.notes", "")
                .queryParam("obj.active", "false")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("externalCode", hasItem("DE-003"));
    }

    @Test
    void qOrderByProvidesTheDefaultSort() {
        String zulu = createDeveloper("coverage-order-z", "Zulu");
        String alpha = createDeveloper("coverage-order-a", "Alpha");
        try {
            given().queryParam("like.name", "coverage-order")
                    .get("/api/developers").then().statusCode(200)
                    .body("$", hasSize(2))
                    .body("[0].surname", equalTo("Alpha"))
                    .body("[1].surname", equalTo("Zulu"));
        } finally {
            given().delete("/api/developers/" + alpha).then().statusCode(204);
            given().delete("/api/developers/" + zulu).then().statusCode(204);
        }
    }

    @Test
    void qRsRegistersTheConfiguredResourcePath() {
        given().get("/api/teams").then().statusCode(200);
        authenticated().get("/api/v1/customers/count").then().statusCode(200);
    }

    @Test
    void customFilterExtensionSupportsRepeatedAndOrParameters() {
        authenticated().queryParam("address.city", "Milano", "Parigi")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(2));
        authenticated().queryParam("or.address.city", "Milano", "Parigi")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(2));
    }

    private int createClassFilterSample(String name, String title) {
        return given().contentType(ContentType.JSON)
                .body("""
                        {"name":"%s","title":"%s","createdAt":"2026-03-10T00:00:00Z","active":true}
                        """.formatted(name, title))
                .post("/api/class-filters").then().statusCode(201).extract().path("id");
    }

    private String createTeam(String name, String tags) {
        return given().contentType(ContentType.JSON)
                .body("""
                        {"name":"%s","tags":"%s"}
                        """.formatted(name, tags))
                .post("/api/teams").then().statusCode(201).extract().path("uuid");
    }

    private String createDeveloper(String name, String surname) {
        return given().contentType(ContentType.JSON)
                .body("""
                        {"name":"%s","surname":"%s","team_uuid":"coverage"}
                        """.formatted(name, surname))
                .post("/api/developers").then().statusCode(201).extract().path("uuid");
    }

    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("alice", "secret");
    }
}
