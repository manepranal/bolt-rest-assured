package com.real.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Tag("arrakis")
@DisplayName("Arrakis - Core Business Logic & Transactions")
class ArrakisTest extends BaseTest {

    @Test
    @DisplayName("GET /api-docs - OpenAPI spec is accessible")
    void shouldReturnApiDocs() {
        given()
            .spec(baseSpec("arrakis"))
        .when()
            .get("/v3/api-docs/arrakis-public")
        .then()
            .statusCode(200)
            .body("openapi", notNullValue());
    }

    // Add your Arrakis API tests below
    // Example:
    // @Test
    // @DisplayName("GET /api/v1/transactions - List transactions")
    // void shouldListTransactions() {
    //     given()
    //         .spec(baseSpec("arrakis"))
    //     .when()
    //         .get("/api/v1/transactions")
    //     .then()
    //         .statusCode(200)
    //         .body("size()", greaterThanOrEqualTo(0));
    // }
}
