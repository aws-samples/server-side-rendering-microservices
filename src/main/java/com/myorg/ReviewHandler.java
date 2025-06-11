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
import com.google.gson.JsonSyntaxException;

public class ReviewHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ReviewHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public ReviewHandler() {
        this(DynamoDbClient.builder().build(), System.getenv("REVIEW_TABLE_NAME"));
    }

    public ReviewHandler(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Received request: {}", request);
        
        try {
            String httpMethod = request.getHttpMethod();
            String path = request.getPath();
            
            return switch (httpMethod) {
                case "GET" -> handleGetRequest(path);
                case "POST" -> createReview(request.getBody());
                case "PUT" -> handlePutRequest(path, request.getBody());
                case "DELETE" -> handleDeleteRequest(path);
                default -> buildResponse(400, "Invalid HTTP method");
            };
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error processing request", e);
            return buildResponse(500, "A database error occurred while processing your request.");
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument in request", e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        } catch (JsonSyntaxException e) {
            logger.error("Malformed JSON in request", e);
            return buildResponse(400, "Malformed JSON: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error processing request", e);
            return buildResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent handleGetRequest(String path) {
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
    }

    private APIGatewayProxyResponseEvent handlePutRequest(String path, String body) {
        if (path.matches("/reviews/\\w+")) {
            return updateReview(path.substring(path.lastIndexOf("/") + 1), body);
        }
        return buildResponse(400, "Invalid request");
    }

    private APIGatewayProxyResponseEvent handleDeleteRequest(String path) {
        if (path.matches("/reviews/\\w+")) {
            return deleteReview(path.substring(path.lastIndexOf("/") + 1));
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
            return buildResponse(500, "Error retrieving review");
        } catch (Exception e) {
            logger.error("Unexpected error getting review for reviewId {}: {}", reviewId, e.toString(), e);
            // Do not expose internal exception details to the client
            return buildResponse(500, "An unexpected error occurred while retrieving the review.");
        }
    }

    private APIGatewayProxyResponseEvent getProductReviews(String productId) {
        try {
            String exclusiveStartKey = null;
            List<Map<String, AttributeValue>> items = new ArrayList<>();
            QueryRequest.Builder queryBuilder = QueryRequest.builder()
                .tableName(tableName)
                .indexName("productId-index")
                .keyConditionExpression("productId = :pid")
                .expressionAttributeValues(Map.of(":pid", AttributeValue.builder().s(productId).build()))
                .limit(100); // Set a reasonable page size

            QueryResponse response = dynamoDb.query(queryBuilder.build());
            items.addAll(response.items());
            exclusiveStartKey = response.lastEvaluatedKey() != null ? gson.toJson(response.lastEvaluatedKey()) : null;

            Map<String, Object> result = new HashMap<>();
            result.put("items", items);
            result.put("nextPageToken", exclusiveStartKey);

            return buildResponse(200, gson.toJson(result));
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error getting product reviews", e);
            return buildResponse(500, "DynamoDB error retrieving product reviews: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while getting product reviews", e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error getting product reviews", e);
            return buildResponse(500, "Unexpected error retrieving product reviews");
        }
    }

    private APIGatewayProxyResponseEvent listReviews() {
        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;
            int pageSize = 100; // Set a reasonable page size

            ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                .tableName(tableName)
                .limit(pageSize);

            do {
                if (lastEvaluatedKey != null) {
                    scanRequestBuilder.exclusiveStartKey(lastEvaluatedKey);
                }

                ScanResponse response = dynamoDb.scan(scanRequestBuilder.build());
                allItems.addAll(response.items());
                lastEvaluatedKey = response.lastEvaluatedKey();

                // Break the loop if we've reached the desired number of items or there are no more pages
                if (allItems.size() >= pageSize || !response.hasLastEvaluatedKey()) {
                    break;
                }
            } while (true);

            Map<String, Object> result = new HashMap<>();
            result.put("items", allItems);
            result.put("nextPageToken", lastEvaluatedKey != null ? gson.toJson(lastEvaluatedKey) : null);

            return buildResponse(200, gson.toJson(result));
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error while listing reviews", e);
            return buildResponse(500, "DynamoDB error listing reviews: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while listing reviews", e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error listing reviews", e);
            return buildResponse(500, "Unexpected error listing reviews");
        }
    }

private APIGatewayProxyResponseEvent createReview(String reviewJson) {
    try {
        validateInput(reviewJson);
        Map<String, AttributeValue> sanitizedItem = parseAndSanitizeReview(reviewJson);
        String reviewId = UUID.randomUUID().toString();
        sanitizedItem.put("reviewId", AttributeValue.builder().s(reviewId).build());
        
        saveReviewToDynamoDB(sanitizedItem);
        
        return buildResponse(201, String.format("Review created successfully with ID: %s", reviewId));
    } catch (ReviewException e) {
        logger.error(e.getMessage(), e);
        return buildResponse(e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
        logger.error("Unexpected error creating review", e);
        return buildResponse(500, "Unexpected error creating review: " + e.getMessage());
    }
}

// Helper methods (to be implemented separately)
private void validateInput(String reviewJson) throws ReviewException {
    // Implement input validation logic
}

private Map<String, AttributeValue> parseAndSanitizeReview(String reviewJson) throws ReviewException {
    try {
        // Parse JSON to a Map
        Map<String, Object> rawMap = gson.fromJson(reviewJson, Map.class);
        if (rawMap == null) {
            throw new ReviewException();
        }

        // Validate required fields
        if (!rawMap.containsKey("productId") || !rawMap.containsKey("rating") || !rawMap.containsKey("comment")) {
            throw new ReviewException();
        }

        String productId = rawMap.get("productId").toString().trim();
        String comment = rawMap.get("comment").toString().trim();
        Object ratingObj = rawMap.get("rating");

        // Basic sanitization
        if (productId.isEmpty() || comment.isEmpty()) {
            throw new ReviewException();
        }

        int rating;
        try {
            rating = Integer.parseInt(ratingObj.toString());
        } catch (NumberFormatException e) {
            throw new ReviewException();
        }
        if (rating < 1 || rating > 5) {
            throw new ReviewException();
        }

        // Optionally sanitize comment (strip HTML, limit length, etc.)
        if (comment.length() > 1000) {
            throw new ReviewException();
        }

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("productId", AttributeValue.builder().s(productId).build());
        item.put("rating", AttributeValue.builder().n(String.valueOf(rating)).build());
        item.put("comment", AttributeValue.builder().s(comment).build());

        // Optionally add other fields (e.g., reviewer, timestamp)
        if (rawMap.containsKey("reviewer")) {
            String reviewer = rawMap.get("reviewer").toString().trim();
            if (!reviewer.isEmpty()) {
                item.put("reviewer", AttributeValue.builder().s(reviewer).build());
            }
        }

        return item;
    } catch (JsonSyntaxException e) {
        throw new ReviewException();
    }
}

private void saveReviewToDynamoDB(Map<String, AttributeValue> item) throws ReviewException {
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
        } catch (ResourceNotFoundException e) {
            logger.error("DynamoDB table not found", e);
            throw new ReviewException("Database table not found", 500);
        } catch (ProvisionedThroughputExceededException e) {
            logger.error("Throughput exceeded while saving review", e);
            throw new ReviewException("Service temporarily unavailable", 503);
        } catch (DynamoDbException e) {
            logger.error("Error saving review to DynamoDB", e);
            throw new ReviewException("Error saving review to database", 500);
        }
    }

    // Custom exception class
    private static class ReviewException extends Exception {
        private final int statusCode;
        
        public ReviewException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public ReviewException() {
            this("Invalid review data", 400);
        }
        
        public int getStatusCode() {
            return statusCode;
        }
    }

    private APIGatewayProxyResponseEvent updateReview(String reviewId, String reviewJson) {
        try {
            // Validate input
            if (reviewJson == null || reviewJson.isEmpty()) {
                return buildResponse(400, "Invalid review data: JSON is empty or null");
            }

            // Check if the review exists
            GetItemResponse getItemResponse = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());

            if (!getItemResponse.hasItem()) {
                return buildResponse(404, "Review not found");
            }
            
            Map<String, AttributeValue> item;
            try {
                item = gson.fromJson(reviewJson, Map.class);
            } catch (JsonSyntaxException e) {
                logger.error("Error parsing review JSON", e);
                return buildResponse(400, "Invalid review JSON format");
            }

            if (item == null || !item.containsKey("productId") || !item.containsKey("rating") || !item.containsKey("comment")) {
                return buildResponse(400, "Invalid review data: Missing required fields");
            }

            item.put("reviewId", AttributeValue.builder().s(reviewId).build());

            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            return buildResponse(200, "Review updated successfully");
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
            // Check if the review exists before deleting
            GetItemResponse getItemResponse = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());

            if (!getItemResponse.hasItem()) {
                return buildResponse(404, "Review not found");
            }

            dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());

            logger.info("Review deleted successfully: {}", reviewId);
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

    private Map<String, String> getCorsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
        headers.put("Access-Control-Allow-Methods", CORS_ALLOW_METHODS);
        headers.put("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS);
        return headers;
    }

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
            .withStatusCode(statusCode)
            .withHeaders(getCorsHeaders())
            .withBody(body);

        logger.info("Returning response with status code {} and body: {}", statusCode, body != null && body.length() > 200 ? body.substring(0, 200) + "..." : body);
        return response;
    }
}