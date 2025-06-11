import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CatalogHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(CatalogHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private DynamoDbClient dynamoDb;
    private final String tableName = System.getenv("CATALOG_TABLE_NAME");

    public CatalogHandler() {
        this.dynamoDb = DynamoDbClient.builder().build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Received request: {}", request);
        
        try {
            String httpMethod = request.getHttpMethod();
            String path = request.getPath();
            
            switch (httpMethod) {
                case "GET":
                    if (path.equals("/health")) {
                        return buildResponse(200, "{\"status\":\"healthy\"}");
                    } else if (path.matches("/catalog/\\w+")) {
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
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error processing request", e);
            return buildResponse(503, "Database service unavailable: " + e.getMessage());
        } catch (JsonSyntaxException e) {
            logger.error("JSON parsing error processing request", e);
            return buildResponse(400, "Invalid JSON format: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument error processing request", e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error processing request", e);
            return buildResponse(500, "Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent getProduct(String productId) {
        try {
            if (productId == null || productId.isEmpty()) {
                logger.error("Invalid productId: null or empty");
                return buildResponse(400, "Invalid productId: null or empty");
            }

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
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while getting product", e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        }
    }


    // Import ArrayList and List to handle paginated results

    private APIGatewayProxyResponseEvent listProducts() {
        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();

            // Use the paginator for auto-pagination
            ScanRequest scanRequest = ScanRequest.builder()
                .tableName(tableName)
                .build();

            dynamoDb.scanPaginator(scanRequest).stream()
                .flatMap(scanResponse -> scanResponse.items().stream())
                .forEach(allItems::add);

            return buildResponse(200, gson.toJson(allItems));
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error listing products", e);
            return buildResponse(500, "Database error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while listing products", e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        } catch (NullPointerException e) {
            logger.error("Null pointer exception while listing products", e);
            return buildResponse(500, "Internal server error: Null value encountered");
        }
    }

    private APIGatewayProxyResponseEvent createProduct(String productJson) {
        try {
            if (productJson == null || productJson.isEmpty()) {
                return buildResponse(400, "Invalid product data: JSON is null or empty");
            }
            
            // Import com.amazonaws.services.dynamodbv2.model.AttributeValue
            // Import java.util.HashMap
            // Import org.owasp.encoder.Encode
            // These imports are needed to create a properly typed Map for DynamoDB items and to sanitize user input
            Map<String, AttributeValue> item = new HashMap<>();
            JsonObject jsonObject = JsonParser.parseString(productJson).getAsJsonObject();
            
            if (!jsonObject.has("productId") || !jsonObject.has("name") || !jsonObject.has("price")) {
                return buildResponse(400, "Invalid product data: Missing required fields");
            }
            
            item.put("productId", AttributeValue.builder().s(Encode.forHtml(jsonObject.get("productId").getAsString())).build());
            item.put("name", AttributeValue.builder().s(Encode.forHtml(jsonObject.get("name").getAsString())).build());
            item.put("price", AttributeValue.builder().n(jsonObject.get("price").getAsString()).build());
            
            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            return buildResponse(201, "Product created successfully");
        } catch (JsonSyntaxException jsonException) {
            logger.error("Error parsing JSON", jsonException);
            return buildResponse(400, "Invalid JSON format");
        } catch (ResourceNotFoundException resourceException) {
            logger.error("DynamoDB table not found", resourceException);
            return buildResponse(500, "Database error");
        } catch (DynamoDbException dbException) {
            logger.error("Error interacting with DynamoDB", dbException);
            return buildResponse(500, "Database error");
        }
    }


    // These imports are needed to catch specific DynamoDB-related exceptions.

    private APIGatewayProxyResponseEvent updateProduct(String productId, String productJson) {
        try {
            Map<String, AttributeValue> item = gson.fromJson(productJson, Map.class);
            item.put("productId", AttributeValue.builder().s(productId).build());

            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            return buildResponse(200, "Product updated successfully");
        } catch (DynamoDbException dbException) {
            logger.error("Error updating product in DynamoDB", dbException);
            return buildResponse(500, "Error updating product in database");
        } catch (JsonSyntaxException e) {
            logger.error("JSON parsing error updating product", e);
            return buildResponse(400, "Invalid JSON format: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument updating product", e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        } catch (NullPointerException e) {
            logger.error("Null pointer exception while updating product", e);
            return buildResponse(500, "Internal server error: Null value encountered");
        }
    }

    private APIGatewayProxyResponseEvent deleteProduct(String productId) {
        try {
            DeleteItemResponse deleteItemResponse = dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .returnValues(ReturnValue.ALL_OLD)
                .build());

            if (deleteItemResponse.attributes().isEmpty()) {
                return buildResponse(404, "Product not found");
            }

            return buildResponse(200, "Product deleted successfully");
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error deleting product with ID: {}", productId, e);
            return buildResponse(500, "Database error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument while deleting product with ID: {}", productId, e);
            return buildResponse(400, "Invalid argument: " + e.getMessage());
        } catch (NullPointerException e) {
            logger.error("Null pointer exception while deleting product with ID: {}", productId, e);
            return buildResponse(500, "Internal server error: Null value encountered");
        }
    }

    private static final Map<String, String> RESPONSE_HEADERS = new HashMap<>();
    static {
        RESPONSE_HEADERS.put("Content-Type", "application/json");
        RESPONSE_HEADERS.put("Access-Control-Allow-Origin", "*");
        RESPONSE_HEADERS.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        RESPONSE_HEADERS.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");
    }

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
            .withStatusCode(statusCode)
            .withHeaders(RESPONSE_HEADERS)
            .withBody(body);

        logger.info("Returning response: {}", response);
        return response;
    }
}
