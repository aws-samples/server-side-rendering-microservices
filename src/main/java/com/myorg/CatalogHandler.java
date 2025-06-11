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
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonParser;

public class CatalogHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(CatalogHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final DynamoDbClient dynamoDbClient;
    private final String catalogTableName;

    public CatalogHandler() {
        this(DynamoDbClient.builder().build(), System.getenv("CATALOG_TABLE_NAME"));
    }

    public CatalogHandler(DynamoDbClient dynamoDbClient, String catalogTableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.catalogTableName = catalogTableName;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Received request: method={}, path={}, body={}", 
            request.getHttpMethod(), request.getPath(), request.getBody());
        
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
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument", e);
            return buildResponse(400, "Bad request: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.error("Runtime error", e);
            return buildResponse(500, "Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent getProduct(String productId) {
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(catalogTableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build());

            if (response.hasItem() && !response.item().isEmpty()) {
                Map<String, String> result = new HashMap<>();
                response.item().forEach((key, value) -> {
                    if (value.s() != null) {
                        result.put(key, value.s());
                    } else if (value.n() != null) {
                        result.put(key, value.n());
                    }
                });
                return buildResponse(200, gson.toJson(result));
            } else {
                return buildResponse(404, "Product not found");
            }
        } catch (ResourceNotFoundException e) {
            logger.error("Table not found", e);
            return buildResponse(404, "Product not found");
        } catch (DynamoDbException e) {
            logger.error("Error getting product from DynamoDB", e);
            return buildResponse(500, "Error retrieving product from database");
        } catch (Exception e) {
            logger.error("Unexpected error getting product", e);
            return buildResponse(500, "Unexpected error retrieving product: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent listProducts() {
        try {
            List<Map<String, String>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;

            do {
                ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                    .tableName(catalogTableName);

                if (lastEvaluatedKey != null) {
                    scanRequestBuilder.exclusiveStartKey(lastEvaluatedKey);
                }

                ScanResponse response = dynamoDbClient.scan(scanRequestBuilder.build());
                
                for (Map<String, AttributeValue> item : response.items()) {
                    Map<String, String> convertedItem = new HashMap<>();
                    item.forEach((key, value) -> {
                        if (value.s() != null) {
                            convertedItem.put(key, value.s());
                        } else if (value.n() != null) {
                            convertedItem.put(key, value.n());
                        }
                    });
                    allItems.add(convertedItem);
                }

                lastEvaluatedKey = response.lastEvaluatedKey();
            } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

            return buildResponse(200, gson.toJson(allItems));
        } catch (ResourceNotFoundException e) {
            logger.error("Table not found while listing products", e);
            return buildResponse(404, "Products table not found");
        } catch (ProvisionedThroughputExceededException e) {
            logger.error("Throughput exceeded while listing products", e);
            return buildResponse(503, "Service temporarily unavailable");
        } catch (DynamoDbException e) {
            logger.error("Error listing products: DynamoDB operation failed", e);
            return buildResponse(500, "Error listing products: Database operation failed");
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while listing products", e);
            return buildResponse(400, "Invalid request parameters");
        } catch (RuntimeException e) {
            logger.error("Unexpected error listing products", e);
            return buildResponse(500, "Unexpected error listing products");
        }
    }

    private APIGatewayProxyResponseEvent createProduct(String productJson) {
        try {
            if (productJson == null || productJson.isEmpty()) {
                return buildResponse(400, "Invalid product data: JSON is null or empty");
            }
            
            // Import com.amazonaws.services.dynamodbv2.model.AttributeValue
            Map<String, AttributeValue> productAttributes = new HashMap<>();
            JsonObject jsonObject = JsonParser.parseString(productJson).getAsJsonObject();
            
            if (!jsonObject.has("name") || !jsonObject.has("price")) {
                return buildResponse(400, "Invalid product data: Missing required fields");
            }
            
            String productId = jsonObject.has("productId") ? 
                jsonObject.get("productId").getAsString() : 
                java.util.UUID.randomUUID().toString();
            productAttributes.put("productId", AttributeValue.builder().s(productId).build());
            // Sanitize the "name" field to prevent XSS
            String sanitizedName = sanitizeName(jsonObject.get("name").getAsString());
            productAttributes.put("name", AttributeValue.builder().s(sanitizedName).build());
            productAttributes.put("price", AttributeValue.builder().n(jsonObject.get("price").getAsString()).build());
            
            dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(catalogTableName)
                .item(productAttributes)
                .build());

            logger.info("Product created successfully with productId: {}", jsonObject.get("productId").getAsString());
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

    // import java.util.concurrent.ExecutionException;
    // import java.util.concurrent.TimeoutException;
    // Importing specific exceptions for more granular error handling

    private APIGatewayProxyResponseEvent updateProduct(String productId, String productJson) {
        try {
            if (productJson == null || productJson.isEmpty()) {
                return buildResponse(400, "Invalid product data: JSON is null or empty");
            }

            JsonObject jsonObject;
            try {
                jsonObject = JsonParser.parseString(productJson).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.error("Error parsing JSON", e);
                return buildResponse(400, "Invalid JSON format");
            }

            if (!jsonObject.has("name") || !jsonObject.has("price")) {
                return buildResponse(400, "Invalid product data: Missing required fields");
            }

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("productId", AttributeValue.builder().s(productId).build());
            // Sanitize the "name" field to prevent XSS
            String sanitizedName = sanitizeName(jsonObject.get("name").getAsString());
            item.put("name", AttributeValue.builder().s(sanitizedName).build());

            return buildResponse(200, "Product updated successfully");
        } catch (DynamoDbException e) {
            logger.error("Error updating product in DynamoDB", e);
            return buildResponse(500, "Error updating product in database");
        } catch (IllegalArgumentException e) {
            logger.error("Unexpected error updating product", e);
            return buildResponse(500, "Unexpected error updating product");
        }
    }

    private APIGatewayProxyResponseEvent deleteProduct(String productId) {
        try {
            // Check if the item exists before deletion
            GetItemResponse getItemResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(catalogTableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build());

            if (!getItemResponse.hasItem()) {
                return buildResponse(404, "Product not found");
            }

            dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(catalogTableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build());

            return buildResponse(200, "Product deleted successfully");
        } catch (ResourceNotFoundException e) {
            logger.error("Table not found while deleting product with ID: {}", productId, e);
            return buildResponse(500, "Error deleting product: Table not found");
        } catch (ProvisionedThroughputExceededException e) {
            logger.error("Throughput exceeded while deleting product with ID: {}", productId, e);
            return buildResponse(503, "Error deleting product: Service temporarily unavailable");
        } catch (DynamoDbException e) {
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

    /**
     * Sanitizes a product name to prevent XSS attacks.
     */
    private String sanitizeName(String name) {
        return name
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;");
    }
}