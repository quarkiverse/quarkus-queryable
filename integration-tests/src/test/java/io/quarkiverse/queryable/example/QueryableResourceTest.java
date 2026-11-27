package io.quarkiverse.queryable.example;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

@QuarkusTest
class QueryableResourceTest {

    @BeforeEach
    void resetAudit() {
        given().delete("/test/audit").then().statusCode(204);
    }

    @Test
    void protectsGeneratedRoutesWithQuarkusConfiguration() {
        given().get("/api/v1/customers").then().statusCode(401);
        authenticated().get("/api/v1/customers").then().statusCode(200);
    }

    @Test
    void supportsQueryableFilterMatrixAndHeaders() {
        authenticated()
                .queryParam("like.name", "mario")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .header("listSize", "2");

        authenticated()
                .queryParam("obj.countries", "IT,DE")
                .queryParam("obj.active", "false")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("externalCode", hasItem("DE-003"));

        authenticated()
                .queryParam("from.createdOn", "2026-01-01")
                .queryParam("to.createdOn", "2026-12-31")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));

        authenticated()
                .queryParam("nil.notes", "1")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("FR-002"));

        authenticated()
                .queryParam("notNil.notes", "1")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("IT-001"));

        authenticated()
                .queryParam("obj.active", "false")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("$", hasSize(1));
    }

    @Test
    void appliesEqualityRangesAndDefaultLogicalDelete() {
        authenticated().queryParam("obj.externalCode", "IT-001")
                .queryParam("obj.createdOn", "2026-01-10")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Mario Rossi"));

        authenticated().queryParam("efrom.createdOn", "2026-02-12")
                .queryParam("eto.createdOn", "2026-02-12")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("FR-002"));

        authenticated().queryParam("obj.externalCode", "DE-003")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void combinesCaseInsensitiveLikeAndTrimmedInValues() {
        authenticated().queryParam("like.name", "mArIo")
                .queryParam("obj.countries", " IT , DE ")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("IT-001"))
                .header("listSize", "1");
    }

    @Test
    void combinesRepeatedAndDifferentOrParametersWithMandatoryAndFilters() {
        authenticated().queryParam("or.obj.name", "Mario Rossi", "Mario Bianchi", "Luigi Verdi")
                .queryParam("pageSize", "1")
                .queryParam("orderBy", "name asc")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Mario Bianchi"))
                .header("listSize", "2");

        authenticated().queryParam("or.obj.name", "Mario")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(0));
        authenticated().queryParam("or.like.name", "rossi")
                .queryParam("or.obj.externalCode", "FR-002")
                .queryParam("obj.countries", "FR")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("FR-002"))
                .header("listSize", "1");
    }

    @Test
    void combinesCustomRangeListAndNullAlternatives() {
        authenticated().queryParam("or.address.city", "Milano", "Roma")
                .queryParam("or.from.createdOn", "2026-02-01")
                .queryParam("or.obj.countries", "DE")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(2))
                .header("listSize", "2");
        authenticated().queryParam("or.eto.createdOn", "2026-01-10")
                .queryParam("or.nil.notes", "")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(2));
        authenticated().queryParam("or.notNil.notes", "")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("IT-001"));
        authenticated().queryParam("or.address.city", "Milano")
                .queryParam("obj.coordinator_id", "1")
                .queryParam("obj.coordinator_country", "IT")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1)).header("listSize", "1");
    }

    @Test
    void rejectsUnsupportedProtectedAndInvalidOrParameters() {
        authenticated().queryParam("or.obj.active", "false")
                .get("/api/v1/customers").then().statusCode(400);
        authenticated().queryParam("or.obj.coordinator_id", "1")
                .get("/api/v1/customers").then().statusCode(400);
        authenticated().queryParam("or.unknown", "x")
                .get("/api/v1/customers").then().statusCode(400);
        authenticated().queryParam("or.from.createdOn", "invalid")
                .get("/api/v1/customers").then().statusCode(400);
        authenticated().queryParam("or.obj.countries", ",")
                .get("/api/v1/customers").then().statusCode(400);
        given().queryParam("or.obj.country", "FR")
                .get("/api/options").then().statusCode(400);
        given().queryParam("or.from.rating", "1")
                .get("/api/options").then().statusCode(400);
        given().queryParam("or.only.current", "")
                .get("/api/options").then().statusCode(400);
        given().queryParam("or.obj.archived", "invalid")
                .get("/api/options").then().statusCode(400);
    }

    @Test
    void supportsClassLevelFiltersCollectionMembershipAndDateRanges() {
        int first = createClassFilterSample("early", "first", "2026-03-10T00:00:00Z", true);
        int last = createClassFilterSample("late", "last", "2026-03-10T23:59:59.999Z", true);
        int next = createClassFilterSample("next", "other", "2026-03-11T00:00:00Z", true);
        int inactive = createClassFilterSample("hidden", "hidden", "2026-03-10T12:00:00Z", false);
        try {
            given().queryParam("efrom.createdAt", "2026-03-10")
                    .queryParam("eto.createdAt", "2026-03-10")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(2)).header("listSize", "2");
            given().queryParam("efrom.createdAt", "2026-03-10T23:59:59.999Z")
                    .queryParam("eto.createdAt", "2026-03-11T01:00:00+01:00")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(2));
            given().queryParam("obj.createdAt", "10/03/2026 00:00")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(1)).body("[0].name", equalTo("early"));
            given().queryParam("or.efrom.createdAt", "2026-03-11")
                    .queryParam("or.obj.name", "early")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(2));
            given().queryParam("from.createdAt", "2026-02-30")
                    .get("/api/class-filters").then().statusCode(400);
            given().queryParam("or.to.createdAt", "bad-date")
                    .get("/api/class-filters").then().statusCode(400);

            given().queryParam("obj.name", "early")
                    .queryParam("exact.label", "first")
                    .queryParam("obj.tags", "java")
                    .queryParam("obj.ratings", "7")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(1)).body("[0].name", equalTo("early"))
                    .header("listSize", "1");
            given().queryParam("obj.id", first)
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(1)).body("[0].name", equalTo("early"));
            given().queryParam("or.obj.tags", "missing", "java")
                    .queryParam("or.obj.name", "next")
                    .queryParam("pageSize", "1")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(1)).header("listSize", "3");
            given().queryParam("or.obj.active", "false")
                    .get("/api/class-filters").then().statusCode(400);
            given().queryParam("or.obj.ignored", "value")
                    .get("/api/class-filters").then().statusCode(400);
            given().queryParam("or.obj.title", "first")
                    .get("/api/class-filters").then().statusCode(400);
            given().queryParam("from.tags", "java")
                    .get("/api/class-filters").then().statusCode(400);
            given().queryParam("obj.ratings", "invalid")
                    .get("/api/class-filters").then().statusCode(400);
        } finally {
            for (int id : new int[] { first, last, next, inactive }) {
                given().delete("/api/class-filters/" + id).then().statusCode(204);
            }
        }
    }

    @Test
    void supportsComparisonAliasesNegationAndDirectionalLike() {
        for (String prefix : new String[] { "from", "gt" }) {
            authenticated().queryParam(prefix + ".createdOn", "2026-01-10")
                    .get("/api/v1/customers").then().statusCode(200)
                    .body("$", hasSize(1)).body("[0].externalCode", equalTo("FR-002"));
        }
        for (String prefix : new String[] { "efrom", "gte" }) {
            authenticated().queryParam(prefix + ".createdOn", "2026-01-10")
                    .get("/api/v1/customers").then().statusCode(200).body("$", hasSize(2));
        }
        for (String prefix : new String[] { "to", "lt" }) {
            authenticated().queryParam(prefix + ".createdOn", "2026-02-12")
                    .get("/api/v1/customers").then().statusCode(200)
                    .body("$", hasSize(1)).body("[0].externalCode", equalTo("IT-001"));
        }
        for (String prefix : new String[] { "eto", "lte" }) {
            authenticated().queryParam(prefix + ".createdOn", "2026-02-12")
                    .get("/api/v1/customers").then().statusCode(200).body("$", hasSize(2));
        }
        authenticated().queryParam("not.externalCode", "IT-001")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1)).body("[0].externalCode", equalTo("FR-002"));
        authenticated().queryParam("rlike.name", "mArIo r")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1)).body("[0].externalCode", equalTo("IT-001"));
        authenticated().queryParam("llike.name", "BIANCHI")
                .get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(1)).body("[0].externalCode", equalTo("FR-002"));
        authenticated().queryParam("or.rlike.name", "mario r")
                .queryParam("or.lt.createdOn", "2026-01-10")
                .get("/api/v1/customers").then().statusCode(200).body("$", hasSize(1));
        authenticated().queryParam("from.createdOn", "2026-01-10")
                .queryParam("gt.createdOn", "2026-01-10")
                .get("/api/v1/customers").then().statusCode(400);
        authenticated().queryParam("or.efrom.createdOn", "2026-01-10")
                .queryParam("gte.createdOn", "2026-01-10")
                .get("/api/v1/customers").then().statusCode(400);
        authenticated().queryParam("or.eto.createdOn", "2026-01-10")
                .queryParam("or.lte.createdOn", "2026-02-12")
                .get("/api/v1/customers").then().statusCode(400);
    }

    @Test
    void supportsNegatedLikeAndEmptyStringChecks() {
        int alpha = createClassFilterSample("alpha", "Alpha", "2026-03-10T00:00:00Z", true);
        int beta = createClassFilterSample("beta", "bEtA", "2026-03-10T00:00:00Z", true);
        int empty = createClassFilterSample("empty", "", "2026-03-10T00:00:00Z", true);
        int nullTitle = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"null","title":null,"createdAt":"2026-03-10T00:00:00Z","active":true}
                        """)
                .post("/api/class-filters").then().statusCode(201).extract().path("id");
        try {
            given().queryParam("find.label", "ALP").get("/api/class-filters")
                    .then().statusCode(200).body("$", hasSize(1)).body("[0].name", equalTo("alpha"));
            given().queryParam("notlike.label", "aLp").get("/api/class-filters")
                    .then().statusCode(200).body("$", hasSize(2))
                    .body("name", hasItem("beta")).body("name", hasItem("empty"));
            given().get("/api/class-filters?ie.label").then().statusCode(200)
                    .body("$", hasSize(1)).body("[0].name", equalTo("empty"));
            given().get("/api/class-filters?nie.label").then().statusCode(200)
                    .body("$", hasSize(2)).body("name", hasItem("alpha")).body("name", hasItem("beta"));
            given().queryParam("or.ie.label", "").queryParam("or.notlike.label", "ALP")
                    .get("/api/class-filters").then().statusCode(200).body("$", hasSize(2));
            given().queryParam("or.nie.label", "").queryParam("obj.name", "beta")
                    .get("/api/class-filters").then().statusCode(200).body("$", hasSize(1));
            given().queryParam("or.notlike.name", "a")
                    .get("/api/options").then().statusCode(400);
        } finally {
            for (int id : new int[] { alpha, beta, empty, nullTitle }) {
                given().delete("/api/class-filters/" + id).then().statusCode(204);
            }
        }
    }

    @Test
    void appliesWholeDayBoundariesAndNegatedCollectionMembership() {
        int first = createClassFilterSample("early", "first", "2026-03-10T00:00:00Z", true);
        int last = createClassFilterSample("late", "last", "2026-03-10T23:59:59.999Z", true);
        int next = createClassFilterSample("next", "other", "2026-03-11T00:00:00Z", true);
        try {
            given().queryParam("from.createdAt", "2026-03-10").get("/api/class-filters")
                    .then().statusCode(200).body("$", hasSize(1)).body("[0].name", equalTo("next"));
            given().queryParam("to.createdAt", "2026-03-10").get("/api/class-filters")
                    .then().statusCode(200).body("$", hasSize(0));
            given().queryParam("gte.createdAt", "2026-03-10").queryParam("lte.createdAt", "2026-03-10")
                    .get("/api/class-filters").then().statusCode(200).body("$", hasSize(2));
            given().queryParam("not.tags", "missing").get("/api/class-filters")
                    .then().statusCode(200).body("$", hasSize(3));
            given().queryParam("starts.label", "FiR").queryParam("not.label", "last")
                    .get("/api/class-filters").then().statusCode(200)
                    .body("$", hasSize(1)).body("[0].name", equalTo("early"));
            given().queryParam("gt.id", "not-a-number").get("/api/class-filters")
                    .then().statusCode(400);
            given().queryParam("obj.extra9", "indexed").get("/api/class-filters")
                    .then().statusCode(200).body("$", hasSize(3));
            given().queryParam("or.not.tags", "java").queryParam("or.obj.name", "next")
                    .get("/api/class-filters").then().statusCode(200).body("$", hasSize(1));
        } finally {
            for (int id : new int[] { first, last, next }) {
                given().delete("/api/class-filters/" + id).then().statusCode(204);
            }
        }
    }

    private int createClassFilterSample(String name, String title, String date, boolean active) {
        return given().contentType(ContentType.JSON)
                .body("""
                        {"name":"%s","title":"%s","createdAt":"%s","extra9":"indexed",
                         "tags":["java","quarkus"],"ratings":[7,9],"active":%s}
                        """.formatted(name, title, date, active))
                .post("/api/class-filters").then().statusCode(201).extract().path("id");
    }

    @Test
    void appliesClassLevelFiltersOnMappedSuperclasses() {
        int id = given().contentType(ContentType.JSON)
                .body("""
                        {"inheritedValue":"base","childValue":"child"}
                        """)
                .post("/api/inherited-filters").then().statusCode(201).extract().path("id");
        try {
            given().queryParam("obj.inheritedValue", "base")
                    .get("/api/inherited-filters").then().statusCode(200)
                    .body("$", hasSize(1)).header("listSize", "1");
            given().queryParam("or.obj.childValue", "child")
                    .get("/api/inherited-filters").then().statusCode(400);
        } finally {
            given().delete("/api/inherited-filters/" + id).then().statusCode(204);
        }
    }

    @Test
    void supportsCustomContributorOrderingAndPagination() {
        authenticated()
                .queryParam("address.city", "Milano")
                .queryParam("orderBy", "name desc")
                .queryParam("pageSize", "1")
                .get("/api/v1/customers")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Mario Rossi"))
                .header("pageSize", "1")
                .header("listSize", "1");
    }

    @Test
    void appliesHibernateSubqueryToResultsAndCount() {
        authenticated()
                .queryParam("obj.coordinator_id", "1")
                .queryParam("obj.coordinator_country", "IT")
                .queryParam("pageSize", "1")
                .get("/api/v1/customers")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].externalCode", equalTo("IT-001"))
                .header("listSize", "1");

        authenticated()
                .queryParam("obj.coordinator_id", "1")
                .queryParam("obj.coordinator_country", "FR")
                .get("/api/v1/customers")
                .then().statusCode(200)
                .body("$", hasSize(0))
                .header("listSize", "0");

        authenticated().get("/api/v1/customers").then().statusCode(200)
                .body("$", hasSize(2))
                .header("listSize", "2");
    }

    @Test
    void rejectsInvalidOrIncompleteHibernateFilterParameters() {
        authenticated().queryParam("obj.coordinator_id", "invalid")
                .queryParam("obj.coordinator_country", "IT")
                .get("/api/v1/customers").then().statusCode(400);
        authenticated().queryParam("obj.coordinator_id", "1")
                .get("/api/v1/customers").then().statusCode(400);
    }

    @Test
    void appliesFixedConditionsRegardlessOfRequestParameters() {
        given().get("/api/options").then().statusCode(200)
                .body("$", hasSize(2))
                .body("name", hasItem("Alice"))
                .body("name", hasItem("Alana"))
                .header("listSize", "2");

        given().queryParam("obj.country", "FR")
                .queryParam("like.name", "Adam")
                .queryParam("from.rating", "20")
                .queryParam("obj.enabled", "false")
                .queryParam("obj.state", "CLOSED")
                .get("/api/options").then().statusCode(200)
                .body("$", hasSize(2))
                .header("listSize", "2");
    }

    @Test
    void activatesParameterlessHibernateFilterWhenFlagIsPresent() {
        given().get("/api/options?only.current").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Alice"))
                .header("listSize", "1");
        given().queryParam("only.current", "")
                .get("/api/options").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Alice"))
                .header("listSize", "1");
        given().queryParam("only.current", "false")
                .get("/api/options").then().statusCode(200)
                .body("$", hasSize(1))
                .header("listSize", "1");
        given().queryParam("only.resolved", "")
                .get("/api/options").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Alice"))
                .header("listSize", "1");
        given().get("/api/options").then().statusCode(200)
                .body("$", hasSize(2))
                .header("listSize", "2");
    }

    @Test
    void rejectsInvalidCreateBeforePersistence() {
        authenticated().contentType(ContentType.JSON)
                .body("""
                        {"externalCode":"INVALID-CREATE","country":"US","active":true}
                        """)
                .post("/api/v1/customers").then().statusCode(400);

        authenticated().get("/api/v1/customers/count").then().statusCode(200)
                .body(is("3"));
        given().get("/test/audit").then().body("$", hasSize(0));
    }

    @Test
    void rejectsInvalidUpdateBeforePersistence() {
        authenticated().contentType(ContentType.JSON)
                .body("""
                        {"id":1,"name":null,"externalCode":"CHANGED","country":"US","active":true}
                        """)
                .put("/api/v1/customers/1").then().statusCode(400);

        authenticated().get("/api/v1/customers/1").then().statusCode(200)
                .body("name", equalTo("Mario Rossi"))
                .body("externalCode", equalTo("IT-001"));
        given().get("/test/audit").then().body("$", hasSize(0));
    }

    @Test
    void invokesRestLifecycleHooksOutsideTheWriteTransaction() {
        String created = authenticated()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "New Customer",
                          "externalCode": " new-004 ",
                          "country": "US",
                          "createdOn": "2026-03-01",
                          "active": true,
                          "address": {"city": "Boston"}
                        }
                        """)
                .post("/api/v1/customers")
                .then()
                .statusCode(201)
                .body("externalCode", equalTo("NEW-004"))
                .body("creationUser", equalTo("alice"))
                .body("creationDate", notNullValue())
                .extract()
                .path("id")
                .toString();

        given().get("/test/audit")
                .then()
                .body("$", hasItem("CREATE:" + created));

        authenticated()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "id": %s,
                          "name": "New Customer Updated",
                          "externalCode": "NEW-004",
                          "country": "US",
                          "createdOn": "2026-03-01",
                          "active": true,
                          "address": {"city": "Boston"}
                        }
                        """.formatted(created))
                .put("/api/v1/customers/" + created)
                .then()
                .statusCode(204);

        authenticated()
                .get("/api/v1/customers/" + created)
                .then()
                .statusCode(200)
                .body("name", equalTo("New Customer Updated"))
                .body("updateUser", equalTo("alice"))
                .body("updateDate", notNullValue())
                .body("creationUser", equalTo("alice"))
                .body("creationDate", notNullValue());

        authenticated()
                .get("/api/v1/customers/" + created)
                .then()
                .statusCode(200)
                .body("name", equalTo("New Customer Updated"));

        authenticated()
                .delete("/api/v1/customers/" + created)
                .then()
                .statusCode(204);

        authenticated()
                .get("/api/v1/customers/" + created)
                .then()
                .statusCode(404);

        given().get("/test/audit")
                .then()
                .body("$", hasItem("DELETE:" + created));
    }

    @Test
    void publishesGeneratedOpenApiOperations() {
        String documentText = given()
                .accept("application/json")
                .get("/q/openapi?format=JSON")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonPath document = JsonPath.from(documentText);
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("like.name"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("or.like.name"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("or.obj.name"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("or.from.createdOn"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("or.gte.createdOn"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("not.name"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("rlike.name"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("or.llike.name"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), not(hasItem("or.obj.active")));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"),
                not(hasItem("or.obj.coordinator_id")));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("obj.coordinator_id"));
        assertThat(document.getList("paths['/api/v1/customers'].get.parameters.name"), hasItem("obj.coordinator_country"));
        assertThat(document.getList("paths['/api/options'].get.parameters.name"), hasItem("only.current"));
        assertThat(document.getList("paths['/api/options'].get.parameters.name"), not(hasItem("obj.country")));
        assertThat(document.getList("paths['/api/options'].get.parameters.name"), not(hasItem("like.name")));
        assertThat(document.getList("paths['/api/options'].get.parameters.name"), not(hasItem("from.rating")));
        assertThat(document.getList("paths['/api/options'].get.parameters.name"), not(hasItem("or.obj.country")));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("obj.name"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("obj.id"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("exact.label"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("starts.label"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("or.lte.extra9"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("notlike.label"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("or.ie.label"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("nie.label"));
        assertThat(document.getList("paths['/api/options'].get.parameters.name"), not(hasItem("notlike.name")));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("from.createdAt"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), hasItem("or.obj.tags"));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), not(hasItem("from.tags")));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), not(hasItem("obj.ignored")));
        assertThat(document.getList("paths['/api/class-filters'].get.parameters.name"), not(hasItem("or.obj.active")));
        assertThat(document.getList("paths['/api/inherited-filters'].get.parameters.name"), hasItem("obj.inheritedValue"));
        assertThat(document.getList("paths['/api/inherited-filters'].get.parameters.name"), not(hasItem("obj.childValue")));
        assertThat(document.getMap("paths['/api/v1/customers'].post"), notNullValue());
        assertThat(document.getMap("paths['/api/v1/customers/{id}'].put"), notNullValue());
        assertThat(document.getMap("paths['/api/v1/customers/count'].get"), notNullValue());
    }

    @Test
    void usesOfficialRestDataCountEndpoint() {
        authenticated()
                .get("/api/v1/customers/count")
                .then()
                .statusCode(200)
                .body(is("3"));
    }

    @Test
    void supportsOriginalQueryableTutorialEntities() {
        String teamId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"secondo","tags":"typescript,react"}
                        """)
                .post("/api/teams")
                .then()
                .statusCode(201)
                .extract().path("uuid");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"primo","tags":"java,angular"}
                        """)
                .post("/api/teams")
                .then().statusCode(201);

        given().queryParam("like.tags", "react,java")
                .get("/api/teams")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].name", equalTo("primo"))
                .body("[1].name", equalTo("secondo"));

        given().queryParam("or.like.tags", "react,java")
                .get("/api/teams").then().statusCode(200)
                .body("$", hasSize(2));
        given().queryParam("or.like.tags", ",")
                .get("/api/teams").then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "name":"fiorenzo",
                          "surname":"pizza",
                          "team_uuid":"%s",
                          "active":true
                        }
                        """.formatted(teamId))
                .post("/api/developers")
                .then().statusCode(201);

        given().queryParam("like.surname", "pi")
                .queryParam("obj.team_uuid", teamId)
                .get("/api/developers")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("fiorenzo"));
    }

    private io.restassured.specification.RequestSpecification authenticated() {
        return given().auth().preemptive().basic("alice", "secret");
    }
}
