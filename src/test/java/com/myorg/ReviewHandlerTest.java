package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ReviewHandlerTest {
    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private Context context;

    private ReviewHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ReviewHandler();
        // Inject mocked DynamoDB client
        // Note: This would require making the dynamoDb field accessible or adding a constructor/setter
    }

    @Test
    void testGetReview_Success() {
        // Arrange
        String reviewId = "test-review-1";
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("reviewId", AttributeValue.builder().s(reviewId).build());
        item.put("productId", AttributeValue.builder().s("product-1").build());
        item.put("rating", AttributeValue.builder().n(String.valueOf(5)).build());
        item.put("comment", AttributeValue.builder().s("Great product!").build());

        GetItemResponse getItemResponse = GetItemResponse.builder()
                .item(item)
                .build();

        // Import statements for exception handling
        // import software.amazon.awssdk.services.dynamodb.model.*;
        // import software.amazon.awssdk.core.exception.*;

        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(getItemResponse)
            .thenThrow(RequestLimitExceededException.class)
            .thenThrow(ResourceNotFoundException.class)
            .thenThrow(ProvisionedThroughputExceededException.class)
            .thenThrow(InternalServerErrorException.class)
            .thenThrow(DynamoDbException.class)
            .thenThrow(SdkClientException.class)
            .thenThrow(SdkException.class);

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/reviews/" + reviewId);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(reviewId));
    }

    @Test
    void testGetProductReviews_Success() {
        // Arrange
        String productId = "test-product-1";
        Map<String, AttributeValue> review1 = new HashMap<>();
        review1.put("reviewId", AttributeValue.builder().s("review-1").build());
        review1.put("productId", AttributeValue.builder().s(productId).build());
        review1.put("rating", AttributeValue.builder().n("5").build());

        Map<String, AttributeValue> review2 = new HashMap<>();
        review2.put("reviewId", AttributeValue.builder().s("review-2").build());
        review2.put("productId", AttributeValue.builder().s(productId).build());
        // Using a different method to set the rating attribute
        review2.put("rating", AttributeValue.fromN("4")); // import software.amazon.awssdk.services.dynamodb.model.AttributeValue

        QueryResponse queryResponse = QueryResponse.builder()
                .items(review1, review2)
                .build();

        // Import statements for exception handling
        // import software.amazon.awssdk.services.dynamodb.model.*;
        // import software.amazon.awssdk.core.exception.*;
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(queryResponse)
                .thenThrow(RequestLimitExceededException.class)
                .thenThrow(ResourceNotFoundException.class)
                .thenThrow(ProvisionedThroughputExceededException.class)
                .thenThrow(InternalServerErrorException.class)
                .thenThrow(DynamoDbException.class);
        // import software.amazon.awssdk.services.dynamodb.model.*;
        // import software.amazon.awssdk.core.exception.*;
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(queryResponse)
                .thenThrow(RequestLimitExceededException.class)
                .thenThrow(ResourceNotFoundException.class)
                .thenThrow(ProvisionedThroughputExceededException.class)
                .thenThrow(InternalServerErrorException.class)
                .thenThrow(DynamoDbException.class);

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/reviews/product/" + productId);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("review-1"));
        assertTrue(response.getBody().contains("review-2"));
    }

    @Test
    void testCreateReview_Success() throws JsonProcessingException {
        // Arrange
        // import com.fasterxml.jackson.databind.ObjectMapper
        // ObjectMapper is used to create a JSON string from an object, ensuring proper escaping and formatting
        String reviewJson = new ObjectMapper().writeValueAsString(new Review("product-1", 5, "Excellent!"));
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("POST")
                .withPath("/reviews")
                .withBody(reviewJson);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("created successfully"));
    }

    @Test
    void testUpdateReview_Success() {
        // Arrange
        String reviewId = "test-review-1";
        String reviewJson = "{\"rating\":4,\"comment\":\"Updated comment\"}";
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

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("PUT")
                .withPath("/reviews/" + reviewId)
                .withBody(reviewJson);

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("updated successfully"));
    }

    @Test
    void testDeleteReview_Success() {
        // Arrange
        String reviewId = "test-review-1";
        when(dynamoDb.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(DeleteItemResponse.builder().build());

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("DELETE")
                .withPath("/reviews/" + reviewId);

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
                .withPath("/reviews/123");

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid HTTP method"));
    }
}