package com.myorg.resources;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.*;
import java.util.*;

public class ReviewHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ReviewHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> HEADERS = Map.of(
        "Content-Type", "application/json",
        "Access-Control-Allow-Origin", "*",
        "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS",
        "Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
    );

    private final DynamoDbClient dynamoDb = DynamoDbClient.builder().build();
    private final String tableName = System.getenv("REVIEW_TABLE_NAME");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent req, Context ctx) {
        String path = req.getPath();
        String method = req.getHttpMethod();

        try {
            if ("OPTIONS".equalsIgnoreCase(method)) {
                return respond(200, "");
            }
            if ("GET".equalsIgnoreCase(method) && path.matches("/reviews/product/\\w+")) {
                return getProductReviews(path.substring(path.lastIndexOf("/") + 1));
            }
            if ("GET".equalsIgnoreCase(method) && path.matches("/reviews/\\w+")) {
                return getReview(path.substring(path.lastIndexOf("/") + 1));
            }
            if ("GET".equalsIgnoreCase(method)) {
                return listReviews();
            }
            if ("POST".equalsIgnoreCase(method)) {
                return createReview(req.getBody());
            }
            if ("PUT".equalsIgnoreCase(method) && path.matches("/reviews/\\w+")) {
                return updateReview(path.substring(path.lastIndexOf("/") + 1), req.getBody());
            }
            if ("DELETE".equalsIgnoreCase(method) && path.matches("/reviews/\\w+")) {
                return deleteReview(path.substring(path.lastIndexOf("/") + 1));
            }
            return respond(400, "{\"error\":\"Invalid method or path\"}");
        } catch (Exception e) {
            logger.error("Error handling request", e);
            return respond(500, "{\"error\":\"Internal server error\"}");
        }
    }

    private APIGatewayProxyResponseEvent getReview(String reviewId) {
        try {
            GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());
            if (resp.hasItem()) {
                return respond(200, gson.toJson(resp.item()));
            } else {
                return respond(404, "{\"error\":\"Review not found\"}");
            }
        } catch (Exception e) {
            logger.error("getReview failed", e);
            return respond(500, "{\"error\":\"Error retrieving review\"}");
        }
    }

    private APIGatewayProxyResponseEvent getProductReviews(String productId) {
        try {
            QueryRequest queryReq = QueryRequest.builder()
                .tableName(tableName)
                .indexName("productId-index")
                .keyConditionExpression("productId = :pid")
                .expressionAttributeValues(Map.of(":pid", AttributeValue.builder().s(productId).build()))
                .build();
            QueryResponse resp = dynamoDb.query(queryReq);
            return respond(200, gson.toJson(resp.items()));
        } catch (Exception e) {
            logger.error("getProductReviews failed", e);
            return respond(500, "{\"error\":\"Error retrieving product reviews\"}");
        }
    }

    private APIGatewayProxyResponseEvent listReviews() {
        try {
            List<Map<String, AttributeValue>> items = new ArrayList<>();
            ScanRequest scanReq = ScanRequest.builder().tableName(tableName).build();
            dynamoDb.scanPaginator(scanReq).stream().flatMap(r -> r.items().stream()).forEach(items::add);
            return respond(200, gson.toJson(items));
        } catch (Exception e) {
            logger.error("listReviews failed", e);
            return respond(500, "{\"error\":\"Error listing reviews\"}");
        }
    }

    private APIGatewayProxyResponseEvent createReview(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("productId") || !json.has("rating") || !json.has("comment")) {
                return respond(400, "{\"error\":\"Missing required fields\"}");
            }
            String reviewId = UUID.randomUUID().toString();
            Map<String, AttributeValue> item = Map.of(
                "reviewId", AttributeValue.builder().s(Encode.forHtml(reviewId)).build(),
                "productId", AttributeValue.builder().s(Encode.forHtml(json.get("productId").getAsString())).build(),
                "rating", AttributeValue.builder().n(json.get("rating").getAsString()).build(),
                "comment", AttributeValue.builder().s(Encode.forHtml(json.get("comment").getAsString())).build()
            );
            dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
            return respond(201, "{\"message\":\"Review created\"}");
        } catch (Exception e) {
            logger.error("createReview failed", e);
            return respond(500, "{\"error\":\"Error creating review\"}");
        }
    }

    private APIGatewayProxyResponseEvent updateReview(String reviewId, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("reviewId", AttributeValue.builder().s(reviewId).build());
            if (json.has("productId")) item.put("productId", AttributeValue.builder().s(Encode.forHtml(json.get("productId").getAsString())).build());
            if (json.has("rating")) item.put("rating", AttributeValue.builder().n(json.get("rating").getAsString()).build());
            if (json.has("comment")) item.put("comment", AttributeValue.builder().s(Encode.forHtml(json.get("comment").getAsString())).build());
            dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
            return respond(200, "{\"message\":\"Review updated\"}");
        } catch (Exception e) {
            logger.error("updateReview failed", e);
            return respond(500, "{\"error\":\"Error updating review\"}");
        }
    }

    private APIGatewayProxyResponseEvent deleteReview(String reviewId) {
        try {
            DeleteItemResponse resp = dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .returnValues(ReturnValue.ALL_OLD)
                .build());
            if (resp.attributes().isEmpty()) {
                return respond(404, "{\"error\":\"Review not found\"}");
            }
            return respond(200, "{\"message\":\"Review deleted\"}");
        } catch (Exception e) {
            logger.error("deleteReview failed", e);
            return respond(500, "{\"error\":\"Error deleting review\"}");
        }
    }

    private APIGatewayProxyResponseEvent respond(int status, String body) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(status)
            .withHeaders(HEADERS)
            .withBody(body);
    }
}
