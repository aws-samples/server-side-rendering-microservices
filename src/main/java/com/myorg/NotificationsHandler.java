package com.myorg;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

public class NotificationsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(NotificationsHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final String tableName;
    private final String snsTopicArn;

    public NotificationsHandler() {
        this(
            DynamoDbClient.builder().build(),
            SnsClient.builder().build(),
            System.getenv("NOTIFICATIONS_TABLE_NAME"),
            System.getenv("NOTIFICATIONS_TOPIC_ARN")
        );
    }

    public NotificationsHandler(DynamoDbClient dynamoDbClient, SnsClient snsClient, String tableName, String snsTopicArn) {
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.tableName = tableName;
        this.snsTopicArn = snsTopicArn;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Received request - HTTP Method: {}, Path: {}, Body: {}", 
            request.getHttpMethod(), request.getPath(), request.getBody());
        
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
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument", e);
            return buildResponse(400, "Bad request: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.error("Runtime error: {}", e.getMessage(), e);
            return buildResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    // import java.util.regex.Pattern

    private APIGatewayProxyResponseEvent handleGetRequest(String path) {
        if (Pattern.matches("/notifications/user/\\w+", path)) {
            java.util.regex.Matcher matcher = Pattern.compile("/notifications/user/(\\w+)").matcher(path);
            if (matcher.matches()) {
                String userId = matcher.group(1);
                return getUserNotifications(userId);
            } else {
                logger.error("Failed to extract userId from path: {}", path);
                return buildResponse(400, "Invalid user notifications path");
            }
        } else if (Pattern.matches("/notifications/\\w+", path)) {
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
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
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
        // amazonq-ignore-next-line
        } catch (Exception e) {
            logger.error("Unexpected error getting notification", e);
            return buildResponse(500, "Unexpected error retrieving notification");
        }
    }

    private APIGatewayProxyResponseEvent getUserNotifications(String userId) {
        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;

            do {
                QueryRequest.Builder queryBuilder = QueryRequest.builder()
                    .tableName(tableName)
                    .indexName("userId-index")
                    .keyConditionExpression("userId = :uid")
                    .expressionAttributeValues(Map.of(":uid", AttributeValue.builder().s(userId).build()));

                if (lastEvaluatedKey != null) {
                    queryBuilder.exclusiveStartKey(lastEvaluatedKey);
                }

                QueryResponse response = dynamoDbClient.query(queryBuilder.build());
                allItems.addAll(response.items());
                lastEvaluatedKey = response.lastEvaluatedKey();

                // Check if there are more results to fetch
                if (response.hasLastEvaluatedKey()) {
                    lastEvaluatedKey = response.lastEvaluatedKey();
                } else {
                    lastEvaluatedKey = null;
                }
            } while (lastEvaluatedKey != null);

            return buildResponse(200, gson.toJson(allItems));
        } catch (DynamoDbException e) {
            logger.error("Error querying DynamoDB", e);
            return buildResponse(500, "An error occurred while processing your request");
        } catch (RuntimeException e) {
            logger.error("Unexpected error", e);
            return buildResponse(500, "An unexpected error occurred");
        }
    }


    private APIGatewayProxyResponseEvent listNotifications() {
        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastEvaluatedKey = null;

            do {
                ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                    .tableName(tableName);

                if (lastEvaluatedKey != null) {
                    scanRequestBuilder.exclusiveStartKey(lastEvaluatedKey);
                }

                ScanResponse response = dynamoDbClient.scan(scanRequestBuilder.build());
                allItems.addAll(response.items());
                lastEvaluatedKey = response.lastEvaluatedKey();
            } while (lastEvaluatedKey != null);

            return buildResponse(200, gson.toJson(allItems));
        } catch (DynamoDbException e) {
            logger.error("Error listing notifications", e);
            return buildResponse(500, "Error listing notifications");
        } catch (Exception e) {
            logger.error("Unexpected error listing notifications", e);
            return buildResponse(500, "Unexpected error listing notifications");
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

            // Convert JsonObject to Map<String, AttributeValue> more efficiently
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
            dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

            // Publish to SNS if topic ARN is configured
            if (snsTopicArn != null && !snsTopicArn.isEmpty()) {
                snsClient.publish(PublishRequest.builder()
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
        } catch (Exception e) {
            logger.error("Unexpected error creating notification", e);
            throw new RuntimeException("Unexpected error creating notification", e);
        }
    }

    private APIGatewayProxyResponseEvent markNotificationAsRead(String notificationId) {
        try {
            Map<String, AttributeValue> key = Map.of("notificationId", AttributeValue.builder().s(notificationId).build());
            
            // Check if the notification exists before updating
            GetItemResponse getItemResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build());

            if (!getItemResponse.hasItem()) {
                return buildResponse(404, "Notification not found");
            }

            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #read = :val")
                .expressionAttributeNames(Map.of("#read", "read"))
                .expressionAttributeValues(Map.of(":val", AttributeValue.builder().bool(true).build()))
                .build());

            return buildResponse(200, "Notification marked as read");
        } catch (DynamoDbException e) {
            logger.error("Error marking notification as read", e);
            return buildResponse(500, "Error updating notification");
        } catch (Exception e) {
            logger.error("Unexpected error marking notification as read", e);
            return buildResponse(500, "Unexpected error updating notification");
        }
    }

    private APIGatewayProxyResponseEvent deleteNotification(String notificationId) {
        try {
            DeleteItemResponse deleteItemResponse = dynamoDbClient.deleteItem(DeleteItemRequest.builder()
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
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");
        HEADERS = Collections.unmodifiableMap(headers);
    }
}