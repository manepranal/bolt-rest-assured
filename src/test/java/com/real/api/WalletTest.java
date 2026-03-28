package com.real.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Tag("wallet")
@DisplayName("Wallet - Payment Processing & Wallet Management")
class WalletTest extends BaseTest {

    @Test
    @DisplayName("GET /api-docs - OpenAPI spec is accessible")
    void shouldReturnApiDocs() {
        given()
            .spec(baseSpec("wallet"))
        .when()
            .get("/v3/api-docs/wallet-public")
        .then()
            .statusCode(200)
            .body("openapi", notNullValue());
    }

    // Add your Wallet API tests below
}
