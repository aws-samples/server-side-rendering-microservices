package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;

public class ReviewHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ReviewHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final DynamoDbClient dynamoDb = DynamoDbClient.builder().build();
    private final String tableName = System.getenv("REVIEW_TABLE_NAME");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Received request: {}", request);
        
        try {
            String httpMethod = request.getHttpMethod();
            String path = request.getPath();
            
            switch (httpMethod) {
                case "GET":
                    if (path.matches("/reviews/product/\\w+")) {
                        String[] pathParts = path.split("/");
                        if (pathParts.length >= 4) {
                            return getProductReviews(pathParts[3]);
                        }
                        return buildResponse(400, "Invalid path");
                    } else if (path.matches("/reviews/\\w+")) {
                        return getReview(path.substring(path.lastIndexOf("/") + 1));
                    } else {
                        return listReviews();
                    }
                case "POST":
                    return createReview(request.getBody());
                case "PUT":
                    if (path.matches("/reviews/\\w+")) {
                        return updateReview(path.substring(path.lastIndexOf("/") + 1), request.getBody());
                    }
                    break;
                case "DELETE":
                    if (path.matches("/reviews/\\w+")) {
                        return deleteReview(path.substring(path.lastIndexOf("/") + 1));
                    }
                    break;
                default:
                    return buildResponse(400, "Invalid HTTP method");
            }
        } catch (Exception e) {
            logger.error("Error processing request", e);
            return buildResponse(500, "Internal server error: " + e.getMessage());
        }
        return buildResponse(400, "Invalid request");
    }

    private APIGatewayProxyResponseEvent getReview(String reviewId) {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());

            if (response.hasItem()) {
                return buildResponse(200, gson.toJson(response.item()));
            } else {
                return buildResponse(404, "Review not found");
            }
        } catch (DynamoDbException e) {
            logger.error("Error getting review from DynamoDB", e);
            return buildResponse(500, "Error retrieving review: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error getting review", e);
            return buildResponse(500, "Unexpected error retrieving review");
        }
    }

    private APIGatewayProxyResponseEvent getProductReviews(String productId) {
        try {
            String exclusiveStartKey = null;
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            do {
                QueryRequest.Builder queryBuilder = QueryRequest.builder()
                    .tableName(tableName)
                    .indexName("productId-index")
                    .keyConditionExpression("productId = :pid")
                    .expressionAttributeValues(Map.of(":pid", AttributeValue.builder().s(productId).build()))
                    .limit(100); // Set a reasonable page size

                if (exclusiveStartKey != null) {
                    queryBuilder.exclusiveStartKey(gson.fromJson(exclusiveStartKey, Map.class));
                }

                QueryResponse response = dynamoDb.query(queryBuilder.build());
                allItems.addAll(response.items());
                exclusiveStartKey = response.lastEvaluatedKey() != null ? gson.toJson(response.lastEvaluatedKey()) : null;
            } while (exclusiveStartKey != null);

            return buildResponse(200, gson.toJson(allItems));
        } catch (Exception e) {
            logger.error("Error getting product reviews", e);
            return buildResponse(500, "Error retrieving product reviews");
        }
    }

    private APIGatewayProxyResponseEvent listReviews() {
        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;

            do {
                ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                    .tableName(tableName);

                if (lastEvaluatedKey != null) {
                    scanRequestBuilder.exclusiveStartKey(lastEvaluatedKey);
                }

                ScanResponse response = dynamoDb.scan(scanRequestBuilder.build());
                allItems.addAll(response.items());
                lastEvaluatedKey = response.lastEvaluatedKey();
            } while (lastEvaluatedKey != null);

            return buildResponse(200, gson.toJson(allItems));
        } catch (Exception e) {
            logger.error("Error listing reviews", e);
            return buildResponse(500, "Error listing reviews");
        }
    }

    private APIGatewayProxyResponseEvent createReview(String reviewJson) {
        try {
            // Validate input JSON
            if (reviewJson == null || reviewJson.isEmpty()) {
                return buildResponse(400, "Invalid review data: JSON is empty or null");
            }
            
            Map<String, AttributeValue> item = new HashMap<>();
            try {
                JsonObject jsonObject = JsonParser.parseString(reviewJson).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                    item.put(entry.getKey(), AttributeValue.builder().s(entry.getValue().getAsString()).build());
                }
            } catch (JsonSyntaxException e) {
                return buildResponse(400, "Invalid review data: Malformed JSON");
            }
            
            if (!item.containsKey("productId") || !item.containsKey("rating") || !item.containsKey("comment")) {
                return buildResponse(400, "Invalid review data: Missing required fields");
            }

            String reviewId = UUID.randomUUID().toString();
            Map<String, AttributeValue> sanitizedItem = new HashMap<>();
            sanitizedItem.put("reviewId", AttributeValue.builder().s(reviewId).build());
            sanitizedItem.put("productId", AttributeValue.builder().s(item.get("productId").toString()).build());
            sanitizedItem.put("rating", AttributeValue.builder().n(item.get("rating").toString()).build());
            sanitizedItem.put("comment", AttributeValue.builder().s(item.get("comment").toString()).build());

            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(sanitizedItem)
                .build());

            return buildResponse(201, String.format("Review created successfully with ID: %s", reviewId));
        } catch (DynamoDbException e) {
            logger.error("Error creating review in DynamoDB", e);
            return buildResponse(500, "Error creating review in database");
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while creating review", e);
            return buildResponse(400, "Invalid review data");
        } catch (Exception e) {
            logger.error("Unexpected error creating review", e);
            return buildResponse(500, "Unexpected error creating review");
        }
    }

    private APIGatewayProxyResponseEvent updateReview(String reviewId, String reviewJson) {
        try {
            // Check if the review exists
            GetItemResponse getItemResponse = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());

            if (!getItemResponse.hasItem()) {
                return buildResponse(404, "Review not found");
            }
            
            Map<String, AttributeValue> item = gson.fromJson(reviewJson, Map.class);
            item.put("reviewId", AttributeValue.builder().s(reviewId).build());

            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            return buildResponse(200, "Review updated successfully");
        } catch (JsonSyntaxException e) {
            logger.error("Error parsing review JSON", e);
            return buildResponse(400, "Invalid review JSON format");
        } catch (ResourceNotFoundException e) {
            logger.error("DynamoDB table not found", e);
            return buildResponse(500, "Database error");
        } catch (DynamoDbException e) {
            logger.error("Error updating review in DynamoDB", e);
            return buildResponse(500, "Database error");
        }
    }

    private APIGatewayProxyResponseEvent deleteReview(String reviewId) {
        try {
            dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());

            return buildResponse(200, "Review deleted successfully");
        } catch (ResourceNotFoundException e) {
            logger.error("DynamoDB table not found", e);
            return buildResponse(500, "Database error");
        } catch (DynamoDbException e) {
            logger.error("Error deleting review from DynamoDB", e);
            return buildResponse(500, "Database error");
        }
    }

    // Constants for CORS headers
    private static final String CORS_ALLOW_ORIGIN = "*";
    private static final String CORS_ALLOW_METHODS = "GET,POST,PUT,DELETE,OPTIONS";
    private static final String CORS_ALLOW_HEADERS = "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token";

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
        headers.put("Access-Control-Allow-Methods", CORS_ALLOW_METHODS);
        headers.put("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS);

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
            .withStatusCode(statusCode)
            .withHeaders(headers)
            .withBody(body);

        logger.info("Returning response: {}", response);
        return response;
    }
}