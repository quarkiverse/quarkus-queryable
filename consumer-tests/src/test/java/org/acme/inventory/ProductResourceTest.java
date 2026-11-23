package org.acme.inventory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class ProductResourceTest {

    @Test
    void extensionWorksAsAnExternalDependency() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"Quarkus mug","price":19.90,"active":true}
                        """)
                .post("/api/products")
                .then()
                .statusCode(201);

        given()
                .queryParam("like.name", "mug")
                .get("/api/products")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Quarkus mug"));

        given().get("/api/products/count")
                .then()
                .statusCode(200)
                .body(equalTo("1"));
    }
}
