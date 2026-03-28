package com.real.api;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public abstract class BaseTest {

    protected static Properties config = new Properties();
    protected static String authToken;

    @BeforeAll
    static void setup() throws IOException {
        String env = System.getProperty("env", "staging");
        String configFile = "config-" + env + ".properties";

        try (InputStream is = BaseTest.class.getClassLoader().getResourceAsStream(configFile)) {
            if (is == null) {
                throw new RuntimeException("Config file not found: " + configFile);
            }
            config.load(is);
        }

        authToken = config.getProperty("auth.token");

        RestAssured.filters(new io.restassured.filter.log.RequestLoggingFilter(),
                            new io.restassured.filter.log.ResponseLoggingFilter());
    }

    protected static RequestSpecification baseSpec(String service) {
        String baseUrl = config.getProperty("base.url." + service);
        if (baseUrl == null) {
            throw new RuntimeException("No base URL configured for service: " + service);
        }

        return new RequestSpecBuilder()
                .setBaseUri(baseUrl)
                .setContentType(ContentType.JSON)
                .addHeader("Authorization", "Bearer " + authToken)
                .log(LogDetail.URI)
                .build();
    }
}
