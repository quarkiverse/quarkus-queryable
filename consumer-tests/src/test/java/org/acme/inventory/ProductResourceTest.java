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
                        {"name":"Quarkus mug","price":19.90,"active":true,"visibility":"PUBLIC"}
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

        given().queryParam("min.price", "20")
                .get("/api/products")
                .then().statusCode(200)
                .body("$", hasSize(0))
                .header("listSize", "0");

        given().queryParam("min.price", "19")
                .get("/api/products")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .header("listSize", "1");

        given().queryParam("obj.visibility", "PRIVATE")
                .get("/api/products")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .header("listSize", "1");

        given().queryParam("or.like.name", "missing", "mug")
                .queryParam("or.obj.price", "0")
                .get("/api/products")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .header("listSize", "1");

        given().queryParam("only.premium", "")
                .get("/api/products")
                .then().statusCode(200)
                .body("$", hasSize(0))
                .header("listSize", "0");

        given().get("/api/products/count")
                .then()
                .statusCode(200)
                .body(equalTo("1"));
    }
}
