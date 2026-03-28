package com.real.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Tag("keymaker")
@DisplayName("Keymaker - Authentication & Authorization")
class KeymakerTest extends BaseTest {

    @Test
    @DisplayName("GET /api-docs - OpenAPI spec is accessible")
    void shouldReturnApiDocs() {
        given()
            .spec(baseSpec("keymaker"))
        .when()
            .get("/v3/api-docs/keymaker-public")
        .then()
            .statusCode(200)
            .body("openapi", notNullValue());
    }

    // Add your Keymaker API tests below
    // Example:
    // @Test
    // @DisplayName("POST /api/v1/auth/login - Login")
    // void shouldLogin() {
    //     given()
    //         .spec(baseSpec("keymaker"))
    //         .body("{\"email\": \"test@example.com\", \"password\": \"secret\"}")
    //     .when()
    //         .post("/api/v1/auth/login")
    //     .then()
    //         .statusCode(200)
    //         .body("token", notNullValue());
    // }
}
