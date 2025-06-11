package com.myorg;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
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


class CatalogHandlerTest {
    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private Context context;

    private CatalogHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new CatalogHandler(dynamoDb, "test-catalog-table");
    }

    @Test
    void testGetProduct_Success() {
        // Arrange
        String productId = "test-product-1";
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("productId", AttributeValue.builder().s(productId).build());
        item.put("name", AttributeValue.builder().s("Test Product").build());
        item.put("price", AttributeValue.builder().n("99.99").build());

        GetItemResponse getItemResponse = GetItemResponse.builder()
                .item(item)
                .build();


        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(getItemResponse)
            .thenThrow(
                RequestLimitExceededException.class,
                SdkClientException.class,
                DynamoDbException.class,
                SdkException.class,
                AwsServiceException.class,
                InternalServerErrorException.class,
                ResourceNotFoundException.class,
                ProvisionedThroughputExceededException.class,
                UnsupportedOperationException.class
            );

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/catalog/" + productId);

        // Act
        APIGatewayProxyResponseEvent response = null;
        try {
            response = handler.handleRequest(request, context);
        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getMessage());
        }

        // Assert
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(productId));
    }

    @Test
    void testGetProduct_NotFound() {
        // Arrange
        String productId = "non-existent-product";
        GetItemResponse getItemResponse = GetItemResponse.builder().build();

        // Import statements for exception handling
        // import software.amazon.awssdk.services.dynamodb.model.*;
        // import software.amazon.awssdk.core.exception.*;
        // These imports are needed to handle various exceptions that can be thrown by DynamoDbClient.getItem

        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(getItemResponse)
            .thenThrow(ResourceNotFoundException.class)
            .thenThrow(ProvisionedThroughputExceededException.class)
            .thenThrow(RequestLimitExceededException.class)
            .thenThrow(InternalServerErrorException.class)
            .thenThrow(DynamoDbException.class)
            .thenThrow(SdkClientException.class)
            .thenThrow(SdkException.class);

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/catalog/" + productId);

        // Act & Assert
        try {
            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
            assertNotNull(response);
            assertEquals(404, response.getStatusCode());
            assertTrue(response.getBody().contains("not found"));
        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getMessage());
        }
    }

    @Test
    void testCreateProduct_Success() {
        // Arrange
        String productJson = "{\"name\":\"New Product\",\"price\":29.99}";
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
                .withHttpMethod("POST")
                .withPath("/catalog")
                .withBody(productJson);

        // Act & Assert
        try {
            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            // Assert
            assertNotNull(response);
            assertEquals(201, response.getStatusCode());
            assertTrue(response.getBody().contains("created successfully"));
        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getMessage());
        }
    }

    @Test
    void testListProducts_Success() {
        // Arrange
        Map<String, AttributeValue> item1 = new HashMap<>();
        item1.put("productId", AttributeValue.builder().s("1").build());
        item1.put("name", AttributeValue.builder().s("Product 1").build());

        Map<String, AttributeValue> item2 = new HashMap<>();
        item2.put("productId", AttributeValue.builder().s("2").build());
        item2.put("name", AttributeValue.builder().s("Product 2").build());

        ScanResponse scanResponse = ScanResponse.builder()
                .items(item1, item2)
                .build();

        // Import statements for exception handling
        // import software.amazon.awssdk.services.dynamodb.model.*;
        // import software.amazon.awssdk.core.exception.*;
        when(dynamoDb.scan(any(ScanRequest.class))).thenReturn(scanResponse)
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
                .withPath("/catalog");

        // Act & Assert for success
        try {
            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            // Assert
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains("Product 1"));
            assertTrue(response.getBody().contains("Product 2"));
        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getMessage());
        }

        // Act & Assert for exceptions
        Class<?>[] exceptionClasses = new Class<?>[] {
            RequestLimitExceededException.class,
            SdkClientException.class,
            DynamoDbException.class,
            SdkException.class,
            AwsServiceException.class,
            InternalServerErrorException.class,
            ResourceNotFoundException.class,
            ProvisionedThroughputExceededException.class,
            UnsupportedOperationException.class
        };

        for (Class<?> exClass : exceptionClasses) {
            try {
                handler.handleRequest(request, context);
                fail("Expected exception: " + exClass.getSimpleName());
            } catch (Exception e) {
                assertTrue(exClass.isInstance(e) || e.getCause() != null && exClass.isInstance(e.getCause()),
                        "Expected exception of type " + exClass.getSimpleName() + " but got " + e.getClass().getSimpleName());
            }
        }
    }

    @Test
    void testDeleteProduct_Success() {
        // Arrange
        String productId = "test-product-1";
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
                .withPath("/catalog/" + productId);

        // Act & Assert for success
        try {
            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            // Assert
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains("deleted successfully"));
        } catch (Exception e) {
            fail("Unexpected exception thrown: " + e.getMessage());
        }

        // Act & Assert for exceptions
        Class<?>[] exceptionClasses = new Class<?>[] {
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
        };

        for (Class<?> exClass : exceptionClasses) {
            try {
                handler.handleRequest(request, context);
                fail("Expected exception: " + exClass.getSimpleName());
            } catch (Exception e) {
                assertTrue(exClass.isInstance(e) || (e.getCause() != null && exClass.isInstance(e.getCause())),
                        "Expected exception of type " + exClass.getSimpleName() + " but got " + e.getClass().getSimpleName());
            }
        }
    }

    @Test
    void testInvalidHttpMethod() {
        // Arrange
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                .withHttpMethod("PATCH")
                .withPath("/catalog/123");

        // Act
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        // Assert
        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid HTTP method"));
    }
}