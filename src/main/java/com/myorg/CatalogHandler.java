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
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

public class CatalogHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(CatalogHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final DynamoDbClient dynamoDb = DynamoDbClient.builder().build();
    private final String tableName = System.getenv("CATALOG_TABLE_NAME");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Received request: {}", request);
        
        try {
            String httpMethod = request.getHttpMethod();
            String path = request.getPath();
            
            switch (httpMethod) {
                case "GET":
                    if (path.matches("/catalog/\\w+")) {
                        return getProduct(path.substring(path.lastIndexOf("/") + 1));
                    } else {
                        return listProducts();
                    }
                case "POST":
                    return createProduct(request.getBody());
                case "PUT":
                    if (path.matches("/catalog/\\w+")) {
                        return updateProduct(path.substring(path.lastIndexOf("/") + 1), request.getBody());
                    } else {
                        return buildResponse(400, "Invalid path for PUT request");
                    }
                case "DELETE":
                    if (path.matches("/catalog/\\w+")) {
                        return deleteProduct(path.substring(path.lastIndexOf("/") + 1));
                    } else {
                        return buildResponse(400, "Invalid path for DELETE request");
                    }
                default:
                    return buildResponse(400, "Invalid HTTP method");
            }
        } catch (Exception e) {
            logger.error("Error processing request", e);
            return buildResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent getProduct(String productId) {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build());

            if (response.hasItem()) {
                return buildResponse(200, gson.toJson(response.item()));
            } else {
                return buildResponse(404, "Product not found");
            }
        } catch (DynamoDbException e) {
            logger.error("Error getting product from DynamoDB", e);
            return buildResponse(500, "Error retrieving product from database");
        } catch (Exception e) {
            logger.error("Unexpected error getting product", e);
            return buildResponse(500, "Unexpected error retrieving product");
        }
    }

    private APIGatewayProxyResponseEvent listProducts() {
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
            logger.error("Error listing products", e);
            return buildResponse(500, "Error listing products");
        }
    }

    private APIGatewayProxyResponseEvent createProduct(String productJson) {
        try {
            if (productJson == null || productJson.isEmpty()) {
                return buildResponse(400, "Invalid product data: JSON is null or empty");
            }
            
            Map<String, AttributeValue> item = gson.fromJson(productJson, Map.class);
            
            if (!item.containsKey("productId") || !item.containsKey("name") || !item.containsKey("price")) {
                return buildResponse(400, "Invalid product data: Missing required fields");
            }
            
            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            return buildResponse(201, "Product created successfully");
        } catch (JsonSyntaxException e) {
            logger.error("Error parsing JSON", e);
            return buildResponse(400, "Invalid JSON format");
        } catch (ResourceNotFoundException e) {
            logger.error("DynamoDB table not found", e);
            return buildResponse(500, "Database error");
        } catch (DynamoDbException e) {
            logger.error("Error interacting with DynamoDB", e);
            return buildResponse(500, "Database error");
        }
    }

    private APIGatewayProxyResponseEvent updateProduct(String productId, String productJson) {
        try {
            Map<String, AttributeValue> item = gson.fromJson(productJson, Map.class);
            item.put("productId", AttributeValue.builder().s(productId).build());

            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            return buildResponse(200, "Product updated successfully");
        } catch (DynamoDbException e) {
            logger.error("Error updating product in DynamoDB", e);
            return buildResponse(500, "Error updating product in database");
        } catch (Exception e) {
            logger.error("Unexpected error updating product", e);
            return buildResponse(500, "Unexpected error updating product");
        }
    }

    private APIGatewayProxyResponseEvent deleteProduct(String productId) {
        try {
            // Check if the item exists before deletion
            GetItemResponse getItemResponse = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build());

            if (!getItemResponse.hasItem()) {
                return buildResponse(404, "Product not found");
            }

            dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build());

            return buildResponse(200, "Product deleted successfully");
        } catch (Exception e) {
            logger.error("Error deleting product with ID: {}", productId, e);
            return buildResponse(500, "Error deleting product");
        }
    }

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
            .withStatusCode(statusCode)
            .withHeaders(headers)
            .withBody(body);

        logger.info("Returning response: {}", response);
        return response;
    }
}