package com.real.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * RV2-62364: Make Yenta→Sherlock answer proxying atomic.
 *
 * Tests that when Yenta processing fails after Sherlock commits,
 * Sherlock is rolled back (compensating transaction).
 *
 * Target env: team1
 * Test application ID: 63afe33d-6f79-46e9-91b4-62e1fecb5684
 * Run: mvn test -Pteam1 -Dtest=YentaSherlockAtomicityTest
 */
@Tag("yenta")
@Tag("sherlock")
@Tag("RV2-62364")
@DisplayName("RV2-62364: Yenta→Sherlock Atomic Answer Proxying")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class YentaSherlockAtomicityTest extends BaseTest {

    // Test application from PR — update if using a different app
    private static final String APPLICATION_ID = "63afe33d-6f79-46e9-91b4-62e1fecb5684";

    // Populated during setup from the application's checklist
    private static String checklistId;
    private static String testQuestionId;
    private static String testQuestionAlias;
    private static String testFileItemId;
    private static String originalItemStatus;

    @BeforeAll
    static void loadChecklist() {
        // Fetch the application's onboarding checklist via Yenta
        var response = given()
            .spec(baseSpec("yenta"))
        .when()
            .get("/api/v1/applications/{id}", APPLICATION_ID)
        .then()
            .statusCode(200)
            .extract().response();

        // Get the onboarding ID to fetch the checklist
        List<Map<String, Object>> onboardings = response.jsonPath().getList("onboardings");
        if (onboardings == null || onboardings.isEmpty()) {
            throw new RuntimeException(
                "No onboardings found for application " + APPLICATION_ID +
                ". Ensure the application exists on team1 and has started onboarding.");
        }
        String onboardingId = onboardings.get(0).get("id").toString();

        // Fetch checklist from Yenta (which proxies to Sherlock)
        var checklistResponse = given()
            .spec(baseSpec("yenta"))
        .when()
            .get("/api/v1/applications/onboardings/{onboardingId}/checklist", onboardingId)
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("items", not(empty()))
            .extract().response();

        checklistId = checklistResponse.jsonPath().getString("id");
        List<Map<String, Object>> items = checklistResponse.jsonPath().getList("items");

        // Find a NOT_STARTED question item suitable for answer testing
        for (Map<String, Object> item : items) {
            String status = (String) item.get("status");
            String alias = (String) item.get("alias");
            String itemId = (String) item.get("id");

            if ("NOT_STARTED".equals(status) && alias != null && testQuestionId == null) {
                testQuestionId = itemId;
                testQuestionAlias = alias;
                originalItemStatus = status;
            }
        }

        // Find a suitable item for file-reference testing (any non-completed item)
        for (Map<String, Object> item : items) {
            String status = (String) item.get("status");
            if (!"DONE".equals(status) && !"ACCEPTED".equals(status)) {
                testFileItemId = (String) item.get("id");
                break;
            }
        }

        System.out.println("[Setup] checklistId=" + checklistId);
        System.out.println("[Setup] testQuestionId=" + testQuestionId + " alias=" + testQuestionAlias);
        System.out.println("[Setup] testFileItemId=" + testFileItemId);
    }

    // -------------------------------------------------------------------------
    // 1. Happy Path — Answer Submission
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Submit answer: happy path returns 204 and Sherlock item is updated")
    void submitAnswer_happyPath_sherlockItemUpdated() {
        if (testQuestionId == null) {
            throw new RuntimeException("No suitable NOT_STARTED question found in checklist. " +
                "Check the test application's onboarding state.");
        }

        // Submit answer via Yenta (no simulateYentaFailure)
        given()
            .spec(baseSpec("yenta"))
            .body("\"Test Answer - RV2-62364\"")
        .when()
            .post("/api/v1/applications/{id}/questions/{questionId}/answer",
                  APPLICATION_ID, testQuestionId)
        .then()
            .statusCode(204);

        // Verify Sherlock checklist item was updated (status should no longer be NOT_STARTED)
        given()
            .spec(baseSpec("sherlock"))
        .when()
            .get("/api/v1/checklists/checklist-items/{itemId}", testQuestionId)
        .then()
            .statusCode(200)
            .body("id", equalTo(testQuestionId))
            .body("status", not(equalTo("NOT_STARTED")));
    }

    // -------------------------------------------------------------------------
    // 2. Rollback — Answer Submission with Simulated Yenta Failure
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("Submit answer with Yenta failure: Sherlock item is rolled back to NOT_STARTED")
    void submitAnswer_yentaFailure_sherlockRolledBack() {
        if (testQuestionId == null) {
            throw new RuntimeException("No suitable question found in checklist.");
        }

        // First, reset the item to NOT_STARTED so we have a clean state
        given()
            .spec(baseSpec("sherlock"))
            .body("{\"itemIds\": [\"" + testQuestionId + "\"]}")
        .when()
            .post("/api/v1/checklists/checklist-items/reset")
        .then()
            .statusCode(204);

        // Confirm item is NOT_STARTED before test
        given()
            .spec(baseSpec("sherlock"))
        .when()
            .get("/api/v1/checklists/checklist-items/{itemId}", testQuestionId)
        .then()
            .statusCode(200)
            .body("status", equalTo("NOT_STARTED"));

        // Submit answer with simulateYentaFailure=true
        // Yenta should forward to Sherlock, then fail locally, then compensate by resetting Sherlock
        given()
            .spec(baseSpec("yenta"))
            .queryParam("simulateYentaFailure", true)
            .body("\"Simulated Failure Answer - RV2-62364\"")
        .when()
            .post("/api/v1/applications/{id}/questions/{questionId}/answer",
                  APPLICATION_ID, testQuestionId)
        .then()
            .statusCode(anyOf(equalTo(500), equalTo(400), equalTo(422)));

        // Verify Sherlock item was rolled back to NOT_STARTED (compensation worked)
        given()
            .spec(baseSpec("sherlock"))
        .when()
            .get("/api/v1/checklists/checklist-items/{itemId}", testQuestionId)
        .then()
            .statusCode(200)
            .body("status", equalTo("NOT_STARTED"));
    }

    // -------------------------------------------------------------------------
    // 3. Happy Path — File References
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("Add file references: happy path returns 204 and Sherlock has file refs")
    void addFileReferences_happyPath_sherlockHasFileRefs() {
        if (testFileItemId == null) {
            throw new RuntimeException("No suitable checklist item found for file reference testing.");
        }

        String testFileId = UUID.randomUUID().toString();
        String requestBody = "{\"references\": [{\"fileId\": \"" + testFileId +
                             "\", \"filename\": \"test-rv2-62364.pdf\"}]}";

        // Add file references via Yenta
        given()
            .spec(baseSpec("yenta"))
            .body(requestBody)
        .when()
            .post("/api/v1/applications/{id}/checklists/checklist-items/{itemId}/file-references",
                  APPLICATION_ID, testFileItemId)
        .then()
            .statusCode(204);

        // Verify Sherlock has the file reference
        given()
            .spec(baseSpec("sherlock"))
        .when()
            .get("/api/v1/checklists/checklist-items/{itemId}", testFileItemId)
        .then()
            .statusCode(200)
            .body("fileReferences.references", notNullValue())
            .body("fileReferences.references.fileId", hasItem(testFileId));

        // Cleanup — remove the test file reference
        given()
            .spec(baseSpec("sherlock"))
            .body("{\"references\": [{\"fileId\": \"" + testFileId +
                  "\", \"filename\": \"test-rv2-62364.pdf\"}]}")
        .when()
            .delete("/api/v1/checklists/checklist-items/{itemId}/file-references", testFileItemId)
        .then()
            .statusCode(204);
    }

    // -------------------------------------------------------------------------
    // 4. Rollback — File References with Simulated Yenta Failure
    // -------------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("Add file references with Yenta failure: Sherlock file refs are removed")
    void addFileReferences_yentaFailure_sherlockFileRefsRolledBack() {
        if (testFileItemId == null) {
            throw new RuntimeException("No suitable checklist item found for file reference testing.");
        }

        // Capture current file references on the Sherlock item
        var beforeResponse = given()
            .spec(baseSpec("sherlock"))
        .when()
            .get("/api/v1/checklists/checklist-items/{itemId}", testFileItemId)
        .then()
            .statusCode(200)
            .extract().response();

        List<Map<String, Object>> refsBefore = beforeResponse.jsonPath()
            .getList("fileReferences.references");
        int refCountBefore = (refsBefore != null) ? refsBefore.size() : 0;

        String testFileId = UUID.randomUUID().toString();
        String requestBody = "{\"references\": [{\"fileId\": \"" + testFileId +
                             "\", \"filename\": \"rollback-test-rv2-62364.pdf\"}]}";

        // Add file references with simulateYentaFailure=true
        given()
            .spec(baseSpec("yenta"))
            .queryParam("simulateYentaFailure", true)
            .body(requestBody)
        .when()
            .post("/api/v1/applications/{id}/checklists/checklist-items/{itemId}/file-references",
                  APPLICATION_ID, testFileItemId)
        .then()
            .statusCode(anyOf(equalTo(500), equalTo(400), equalTo(422)));

        // Verify Sherlock does NOT have the new file reference (compensation removed it)
        var afterResponse = given()
            .spec(baseSpec("sherlock"))
        .when()
            .get("/api/v1/checklists/checklist-items/{itemId}", testFileItemId)
        .then()
            .statusCode(200)
            .extract().response();

        List<Map<String, Object>> refsAfter = afterResponse.jsonPath()
            .getList("fileReferences.references");
        int refCountAfter = (refsAfter != null) ? refsAfter.size() : 0;

        // File ref count should be unchanged (rollback removed the added ref)
        org.junit.jupiter.api.Assertions.assertEquals(refCountBefore, refCountAfter,
            "File reference count should be unchanged after rollback. " +
            "Before=" + refCountBefore + ", After=" + refCountAfter);

        // The specific test file ID should not be present
        if (refsAfter != null) {
            boolean found = refsAfter.stream()
                .anyMatch(ref -> testFileId.equals(ref.get("fileId")));
            org.junit.jupiter.api.Assertions.assertFalse(found,
                "Test file reference " + testFileId + " should not exist after rollback");
        }
    }

    // -------------------------------------------------------------------------
    // 5. Sherlock Reset Endpoint — Verify it exists (new endpoint from PR)
    // -------------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("Sherlock resetChecklistItems endpoint exists and accepts valid request")
    void sherlockResetEndpoint_exists() {
        // Use a random UUID that likely doesn't exist — should still return 204
        // (reset is idempotent) or a 4xx if item not found
        String randomId = UUID.randomUUID().toString();

        given()
            .spec(baseSpec("sherlock"))
            .body("{\"itemIds\": [\"" + randomId + "\"]}")
        .when()
            .post("/api/v1/checklists/checklist-items/reset")
        .then()
            .statusCode(anyOf(equalTo(204), equalTo(200), equalTo(404)));
    }

    // -------------------------------------------------------------------------
    // 6. Cross-System Consistency — Yenta and Sherlock agree
    // -------------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("Yenta and Sherlock checklist state should be consistent")
    void yentaAndSherlock_checklistConsistency() {
        if (checklistId == null) {
            throw new RuntimeException("Checklist ID not available from setup.");
        }

        // Get checklist from Sherlock directly
        var sherlockResponse = given()
            .spec(baseSpec("sherlock"))
        .when()
            .get("/api/v1/checklists/{checklistId}", checklistId)
        .then()
            .statusCode(200)
            .extract().response();

        int sherlockItemCount = sherlockResponse.jsonPath().getList("items").size();

        // Get checklist from Yenta (which proxies to Sherlock)
        // First need the onboarding ID
        var appResponse = given()
            .spec(baseSpec("yenta"))
        .when()
            .get("/api/v1/applications/{id}", APPLICATION_ID)
        .then()
            .statusCode(200)
            .extract().response();

        String onboardingId = appResponse.jsonPath().getString("onboardings[0].id");

        var yentaResponse = given()
            .spec(baseSpec("yenta"))
        .when()
            .get("/api/v1/applications/onboardings/{onboardingId}/checklist", onboardingId)
        .then()
            .statusCode(200)
            .extract().response();

        int yentaItemCount = yentaResponse.jsonPath().getList("items").size();

        // Both should report the same number of items
        org.junit.jupiter.api.Assertions.assertEquals(sherlockItemCount, yentaItemCount,
            "Yenta and Sherlock should have the same number of checklist items. " +
            "Sherlock=" + sherlockItemCount + ", Yenta=" + yentaItemCount);
    }
}
