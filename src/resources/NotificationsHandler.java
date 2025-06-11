import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SnsException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

public class NotificationsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(NotificationsHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final DynamoDbClient dynamoDb = DynamoDbClient.builder().build();
    private static final SnsClient sns = SnsClient.builder().build();
    private final String tableName = System.getenv("NOTIFICATIONS_TABLE_NAME");
    private final String snsTopicArn = System.getenv("NOTIFICATIONS_TOPIC_ARN");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Received request: {}", request);
        
        try {
            String httpMethod = request.getHttpMethod();
            String path = request.getPath();
            
            switch (httpMethod) {
                case "GET":
                    return handleGetRequest(path);
                case "POST":
                    return createNotification(request.getBody());
                case "PUT":
                    return handlePutRequest(path);
                case "DELETE":
                    return handleDeleteRequest(path);
                default:
                    return buildResponse(400, "Invalid HTTP method");
            }
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error processing request", e);
            return buildResponse(500, "Internal server error while accessing the database.");
        } catch (SnsException e) {
            logger.error("SNS error processing request", e);
            return buildResponse(500, "Internal server error while sending notification.");
        } catch (JsonSyntaxException e) {
            logger.error("JSON syntax error processing request", e);
            return buildResponse(400, "Invalid JSON format in request.");
        } catch (RuntimeException e) {
            logger.error("Unexpected runtime error processing request", e);
            String errorMsg = String.format("Unexpected server error: %s", e.getMessage());
            return buildResponse(500, errorMsg);
        }
    }

    private APIGatewayProxyResponseEvent handleGetRequest(String path) {
        if (path.matches("/notifications/user/\\w+")) {
            String[] pathParts = path.split("/");
            if (pathParts.length >= 4) {
                String userId = pathParts[3];
                if (userId == null || userId.trim().isEmpty()) {
                    return buildResponse(400, "User ID in path cannot be null or empty");
                }
                return getUserNotifications(userId);
            } else {
                return buildResponse(400, "Invalid path format for user notifications");
            }
        } else if (path.matches("/notifications/\\w+")) {
            return getNotification(path.substring(path.lastIndexOf("/") + 1));
        }
        return listNotifications();
    }

    private APIGatewayProxyResponseEvent handlePutRequest(String path) {
        if (path.matches("/notifications/\\w+/read")) {
            return markNotificationAsRead(path.split("/")[2]);
        }
        return buildResponse(400, "Invalid request");
    }

    private APIGatewayProxyResponseEvent handleDeleteRequest(String path) {
        if (path.matches("/notifications/\\w+")) {
            return deleteNotification(path.substring(path.lastIndexOf("/") + 1));
        }
        return buildResponse(400, "Invalid request");
    }

    private APIGatewayProxyResponseEvent getNotification(String notificationId) {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("notificationId", AttributeValue.builder().s(notificationId).build()))
                .projectionExpression("notificationId, message, timestamp, read") 
                .build());

            if (response.hasItem()) {
                return buildResponse(200, gson.toJson(response.item()));
            } else {
                return buildResponse(404, "Notification not found");
            }
        } catch (DynamoDbException e) {
            logger.error("Error getting notification from DynamoDB", e);
            return buildResponse(500, "Error retrieving notification from database");
        }
    }

    private APIGatewayProxyResponseEvent getUserNotifications(String userId) {
        try {
            List<Map<String, AttributeValue>> items = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;
            final int MAX_ITEMS = 100; // Maximum number of items to fetch per query

            QueryRequest.Builder queryBuilder = QueryRequest.builder()
                .tableName(tableName)
                .indexName("userId-index")
                .keyConditionExpression("userId = :uid")
                .expressionAttributeValues(Map.of(":uid", AttributeValue.builder().s(userId).build()))
                .limit(MAX_ITEMS);

            do {
                if (lastEvaluatedKey != null) {
                    queryBuilder.exclusiveStartKey(lastEvaluatedKey);
                }

                QueryResponse response = dynamoDb.query(queryBuilder.build());
                items.addAll(response.items());
                lastEvaluatedKey = response.lastEvaluatedKey();

                if (items.size() >= MAX_ITEMS) {
                    break; // Stop fetching if we've reached the maximum number of items
                }
            } while (lastEvaluatedKey != null);

            Map<String, Object> result = new HashMap<>();
            result.put("items", items);
            if (lastEvaluatedKey != null) {
                result.put("nextToken", gson.toJson(lastEvaluatedKey));
            }

            return buildResponse(200, gson.toJson(result));
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error getting user notifications", e);
            return buildResponse(500, "DynamoDB error retrieving user notifications");
        }
    }


    private APIGatewayProxyResponseEvent listNotifications() {
        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;
            final int MAX_ITEMS = 100; // Maximum number of items to fetch per scan

            do {
                ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                    .tableName(tableName)
                    .limit(MAX_ITEMS);

                if (lastEvaluatedKey != null) {
                    scanRequestBuilder.exclusiveStartKey(lastEvaluatedKey);
                }

                ScanResponse response = dynamoDb.scan(scanRequestBuilder.build());
                allItems.addAll(response.items());
                lastEvaluatedKey = response.lastEvaluatedKey();

                if (allItems.size() >= MAX_ITEMS) {
                    break; // Stop fetching if we've reached the maximum number of items
                }
            } while (lastEvaluatedKey != null);

            Map<String, Object> result = new HashMap<>();
            result.put("items", allItems);
            if (lastEvaluatedKey != null) {
                result.put("nextToken", gson.toJson(lastEvaluatedKey));
            }

            return buildResponse(200, gson.toJson(result));
        } catch (Exception e) {
            logger.error("Error listing notifications", e);
            return buildResponse(500, "Error listing notifications");
        }
    }

    private APIGatewayProxyResponseEvent createNotification(String notificationJson) {
        try {
            if (notificationJson == null || notificationJson.isEmpty()) {
                return buildResponse(400, "Notification JSON cannot be null or empty");
            }
            Map<String, AttributeValue> item = new HashMap<>();
            JsonObject jsonObject = gson.fromJson(notificationJson, JsonObject.class);
            String notificationId = UUID.randomUUID().toString();
            item.put("notificationId", AttributeValue.builder().s(notificationId).build());
            item.put("read", AttributeValue.builder().bool(false).build());
            item.put("timestamp", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());

            // More efficient conversion of JsonObject to Map<String, AttributeValue>
            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()) {
                    JsonPrimitive primitive = value.getAsJsonPrimitive();
                    if (primitive.isString()) {
                        item.put(key, AttributeValue.builder().s(primitive.getAsString()).build());
                    } else if (primitive.isNumber()) {
                        item.put(key, AttributeValue.builder().n(primitive.getAsString()).build());
                    } else if (primitive.isBoolean()) {
                        item.put(key, AttributeValue.builder().bool(primitive.getAsBoolean()).build());
                    }
                }
            }

            // Store in DynamoDB
            dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            // Publish to SNS if topic ARN is configured
            if (snsTopicArn != null && !snsTopicArn.isEmpty()) {
                sns.publish(PublishRequest.builder()
                    .topicArn(snsTopicArn)
                    .message(gson.toJson(item))
                    .build());
            }

            return buildResponse(201, String.format("Notification created successfully with ID: %s", notificationId));
        } catch (DynamoDbException e) {
            logger.error("Error creating notification in DynamoDB", e);
            return buildResponse(500, "Error creating notification in database");
        } catch (SnsException e) {
            logger.error("Error publishing notification to SNS", e);
            return buildResponse(500, "Error publishing notification");
        } catch (JsonSyntaxException e) {
            logger.error("Error parsing JSON", e);
            return buildResponse(400, "Invalid JSON format");
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument error", e);
            return buildResponse(400, "Invalid input: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error creating notification: {}", e.getMessage(), e);
            return buildResponse(500, "An unexpected error occurred while creating the notification. Please contact support if the problem persists.");
        }
    }

    private APIGatewayProxyResponseEvent markNotificationAsRead(String notificationId) {
        try {
            Map<String, AttributeValue> key = Map.of("notificationId", AttributeValue.builder().s(notificationId).build());
            
            UpdateItemResponse updateItemResponse = dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #read = :val")
                .conditionExpression("attribute_exists(notificationId)")
                .expressionAttributeNames(Map.of("#read", "read"))
                .expressionAttributeValues(Map.of(":val", AttributeValue.builder().bool(true).build()))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build());

            if (updateItemResponse.attributes().isEmpty()) {
                return buildResponse(404, "Notification not found");
            }

            return buildResponse(200, "Notification marked as read");
        } catch (ConditionalCheckFailedException e) {
            logger.error("Notification not found", e);
            return buildResponse(404, "Notification not found");
        } catch (DynamoDbException e) {
            logger.error("Error marking notification as read", e);
            return buildResponse(500, "Error updating notification");
        }
    }

    private APIGatewayProxyResponseEvent deleteNotification(String notificationId) {
        try {
            DeleteItemResponse deleteItemResponse = dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("notificationId", AttributeValue.builder().s(notificationId).build()))
                .returnValues(ReturnValue.ALL_OLD)
                .build());

            if (deleteItemResponse.attributes().isEmpty()) {
                return buildResponse(404, "Notification not found");
            }

            return buildResponse(200, String.format("Notification deleted successfully. Deleted notification ID: %s", notificationId));
        } catch (DynamoDbException e) {
            logger.error("DynamoDB error deleting notification", e);
            return buildResponse(500, "DynamoDB error deleting notification");
        } catch (Exception e) {
            logger.error("Unexpected error deleting notification", e);
            throw new RuntimeException("Unexpected error deleting notification", e);
        }
    }

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
            .withStatusCode(statusCode)
            .withHeaders(HEADERS)
            .withBody(body);

        logger.info("Returning response: {}", response);
        return response;
    }

    private static final Map<String, String> HEADERS;
    static {
        HEADERS = new HashMap<>();
        HEADERS.put("Content-Type", "application/json");
        HEADERS.put("Access-Control-Allow-Origin", "*");
        HEADERS.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        HEADERS.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");
    }
}
