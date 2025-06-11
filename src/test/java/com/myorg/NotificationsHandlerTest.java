package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class NotificationsHandlerTest {
    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private SnsClient sns;

    @Mock
    private Context context;

    private NotificationsHandler handler;

    // Constants for test data
    private static final String TEST_USER_ID = "user-1";
    private static final String TEST_NOTIFICATION_MESSAGE = "Test notification";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new NotificationsHandler();
        // Inject mocked clients
        // Note: This would require making the fields accessible or adding constructors/setters
    }

    @Test
    void testGetNotification_Success() {
        // Arrange
        String notificationId = "test-notification-1";
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("notificationId", AttributeValue.builder().s(notificationId).build());
        item.put("userId", AttributeValue.builder().s(TEST_USER_ID).build());
        item.put("message", AttributeValue.builder().s(TEST_NOTIFICATION_MESSAGE).build());
        item.put("read", AttributeValue.builder().bool(false).build());

        GetItemResponse getItemResponse = GetItemResponse.builder()
                .item(item)
                .build();

        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(getItemResponse);

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/notifications/" + notificationId);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(notificationId));
    }

    @Test
    void testGetUserNotifications_Success() {
        // Arrange
        String userId = "test-user-1";
        Map<String, AttributeValue> notification1 = new HashMap<>();
        notification1.put("notificationId", AttributeValue.builder().s("notification-1").build());
        notification1.put("userId", AttributeValue.builder().s(userId).build());
        notification1.put("message", AttributeValue.builder().s("Notification 1").build());

        Map<String, AttributeValue> notification2 = new HashMap<>();
        notification2.put("notificationId", AttributeValue.builder().s("notification-2").build());
        notification2.put("userId", AttributeValue.builder().s(userId).build());
        notification2.put("message", AttributeValue.builder().s("Notification 2").build());

        QueryResponse queryResponse = QueryResponse.builder()
                .items(notification1, notification2)
                .build();

      // Import statements for exception handling

        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(queryResponse)
                .thenThrow(RequestLimitExceededException.class)
                .thenThrow(SdkClientException.class)
                .thenThrow(DynamoDbException.class)
               .thenThrow(SdkException.class)
                .thenThrow(AwsServiceException.class)
                .thenThrow(InternalServerErrorException.class)
                .thenThrow(ResourceNotFoundException.class)
                .thenThrow(ProvisionedThroughputExceededException.class)
               .thenThrow(UnsupportedOperationException.class);
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/notifications/user/" + userId);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Notification 1"));
        assertTrue(response.getBody().contains("Notification 2"));
    }

    @Test
    void testCreateNotification_Success() {
        // Arrange
        String notificationJson = "{\"userId\":\"user-1\",\"message\":\"New notification\"}";
        // Import statements for exception handling
        // import software.amazon.awssdk.services.dynamodb.model.*;
        // import software.amazon.awssdk.core.exception.*;
        when(dynamoDb.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build())
            .thenThrow(
                RequestLimitExceededException.class,
                ItemCollectionSizeLimitExceededException.class,
                SdkClientException.class,
                DynamoDbException.class,
                SdkException.class,
                AwsServiceException.class,
                TransactionConflictException.class,
                InternalServerErrorException.class,
                ResourceNotFoundException.class,
                ConditionalCheckFailedException.class,
                ProvisionedThroughputExceededException.class,
                UnsupportedOperationException.class
            );
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(UpdateItemResponse.builder().build())
            .thenThrow(
                RequestLimitExceededException.class,
                ItemCollectionSizeLimitExceededException.class,
                SdkClientException.class,
                DynamoDbException.class,
                SdkException.class,
                AwsServiceException.class,
                TransactionConflictException.class,
                InternalServerErrorException.class,
                ResourceNotFoundException.class,
                ConditionalCheckFailedException.class,
                ProvisionedThroughputExceededException.class,
                UnsupportedOperationException.class
            );
        when(sns.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().build());

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withPath("/notifications")
                .withBody(notificationJson);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("created successfully"));
    }

    @Test
    void testMarkNotificationAsRead_Success() {
        // Arrange
        String notificationId = "test-notification-1";
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("PUT")
                .withPath("/notifications/" + notificationId + "/read");

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("marked as read"));
    }

    @Test
    void testDeleteNotification_Success() {
        // Arrange
        String notificationId = "test-notification-1";
        // Import statements for exception handling
        // import software.amazon.awssdk.services.dynamodb.model.*;
        // import software.amazon.awssdk.core.exception.*;
        when(dynamoDb.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(DeleteItemResponse.builder().build())
                .thenThrow(
                    RequestLimitExceededException.class,
                    ItemCollectionSizeLimitExceededException.class,
                    SdkClientException.class,
                    DynamoDbException.class,
                    SdkException.class,
                    AwsServiceException.class,
                    TransactionConflictException.class,
                    InternalServerErrorException.class,
                    ResourceNotFoundException.class,
                    ConditionalCheckFailedException.class,
                    ProvisionedThroughputExceededException.class,
                    UnsupportedOperationException.class
                );

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("DELETE")
                .withPath("/notifications/" + notificationId);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("deleted successfully"));
    }

    @Test
    void testInvalidHttpMethod() {
        // Arrange
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("PATCH")
                .withPath("/notifications/123");

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid HTTP method"));
    }
}