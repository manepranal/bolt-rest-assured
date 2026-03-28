package com.real.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Tag("yenta")
@DisplayName("Yenta - Image & Media Services")
class YentaTest extends BaseTest {

    @Test
    @DisplayName("GET /api-docs - OpenAPI spec is accessible")
    void shouldReturnApiDocs() {
        given()
            .spec(baseSpec("yenta"))
        .when()
            .get("/v3/api-docs/yenta-public")
        .then()
            .statusCode(200)
            .body("openapi", notNullValue());
    }

    // Add your Yenta API tests below
}
