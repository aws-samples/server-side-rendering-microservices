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

public class CatalogHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(CatalogHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> HEADERS = Map.of(
        "Content-Type", "application/json",
        "Access-Control-Allow-Origin", "*",
        "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS",
        "Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
    );

    private final DynamoDbClient dynamoDb = DynamoDbClient.builder().build();
    private final String tableName = System.getenv("CATALOG_TABLE_NAME");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent req, Context ctx) {
        String path = req.getPath();
        String method = req.getHttpMethod();

        try {
            if ("OPTIONS".equalsIgnoreCase(method)) {
                return respond(200, "");
            }
            if ("GET".equalsIgnoreCase(method) && ("/health".equals(path) || "/catalog/health".equals(path))) {
                return respond(200, "{\"status\":\"healthy\"}");
            }
            if ("GET".equalsIgnoreCase(method) && path.matches("/catalog/\\w+")) {
                return getProduct(path.substring(path.lastIndexOf("/") + 1));
            }
            if ("GET".equalsIgnoreCase(method)) {
                return listProducts();
            }
            if ("POST".equalsIgnoreCase(method)) {
                return createProduct(req.getBody());
            }
            if ("PUT".equalsIgnoreCase(method) && path.matches("/catalog/\\w+")) {
                return updateProduct(path.substring(path.lastIndexOf("/") + 1), req.getBody());
            }
            if ("DELETE".equalsIgnoreCase(method) && path.matches("/catalog/\\w+")) {
                return deleteProduct(path.substring(path.lastIndexOf("/") + 1));
            }
            return respond(400, "{\"error\":\"Invalid method or path\"}");
        } catch (Exception e) {
            logger.error("Error handling request", e);
            return respond(500, "{\"error\":\"Internal server error\"}");
        }
    }

    private APIGatewayProxyResponseEvent getProduct(String productId) {
        try {
            GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build());
            if (resp.hasItem()) {
                return respond(200, gson.toJson(resp.item()));
            } else {
                return respond(404, "{\"error\":\"Product not found\"}");
            }
        } catch (Exception e) {
            logger.error("getProduct failed", e);
            return respond(500, "{\"error\":\"Error retrieving product\"}");
        }
    }

    private APIGatewayProxyResponseEvent listProducts() {
        try {
            List<Map<String, AttributeValue>> items = new ArrayList<>();
            ScanRequest scanReq = ScanRequest.builder().tableName(tableName).build();
            dynamoDb.scanPaginator(scanReq).stream().flatMap(r -> r.items().stream()).forEach(items::add);
            return respond(200, gson.toJson(items));
        } catch (Exception e) {
            logger.error("listProducts failed", e);
            return respond(500, "{\"error\":\"Error listing products\"}");
        }
    }

    private APIGatewayProxyResponseEvent createProduct(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("productId") || !json.has("name") || !json.has("price")) {
                return respond(400, "{\"error\":\"Missing required fields\"}");
            }
            Map<String, AttributeValue> item = Map.of(
                "productId", AttributeValue.builder().s(Encode.forHtml(json.get("productId").getAsString())).build(),
                "name", AttributeValue.builder().s(Encode.forHtml(json.get("name").getAsString())).build(),
                "price", AttributeValue.builder().n(json.get("price").getAsString()).build()
            );
            dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
            return respond(201, "{\"message\":\"Product created\"}");
        } catch (Exception e) {
            logger.error("createProduct failed", e);
            return respond(500, "{\"error\":\"Error creating product\"}");
        }
    }

    private APIGatewayProxyResponseEvent updateProduct(String productId, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("productId", AttributeValue.builder().s(productId).build());
            if (json.has("name")) item.put("name", AttributeValue.builder().s(Encode.forHtml(json.get("name").getAsString())).build());
            if (json.has("price")) item.put("price", AttributeValue.builder().n(json.get("price").getAsString()).build());
            dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
            return respond(200, "{\"message\":\"Product updated\"}");
        } catch (Exception e) {
            logger.error("updateProduct failed", e);
            return respond(500, "{\"error\":\"Error updating product\"}");
        }
    }

    private APIGatewayProxyResponseEvent deleteProduct(String productId) {
        try {
            DeleteItemResponse resp = dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .returnValues(ReturnValue.ALL_OLD)
                .build());
            if (resp.attributes().isEmpty()) {
                return respond(404, "{\"error\":\"Product not found\"}");
            }
            return respond(200, "{\"message\":\"Product deleted\"}");
        } catch (Exception e) {
            logger.error("deleteProduct failed", e);
            return respond(500, "{\"error\":\"Error deleting product\"}");
        }
    }

    private APIGatewayProxyResponseEvent respond(int status, String body) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(status)
            .withHeaders(HEADERS)
            .withBody(body);
    }
}
