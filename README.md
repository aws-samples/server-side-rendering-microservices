# Server-Side Rendering Microservices with AWS CDK

This project demonstrates a server-side rendering (SSR) architecture using AWS CDK with Java. It implements a scalable system that combines serverless Lambda functions with containerized rendering services.

## Architecture Overview

![Architecture Diagram](https://via.placeholder.com/800x400?text=SSR+Microservices+Architecture)

The application consists of these key components:

1. **CloudFront Distribution**: Entry point for all user requests, with WAF protection
2. **S3 Bucket**: Stores static assets (CSS, JS, images)
3. **ECS Fargate Service**: Orchestrates the server-side rendering process
4. **Lambda Functions**:
   - **Catalog Service**: Manages product data
   - **Review Service**: Handles customer reviews
   - **Notifications Service**: Manages user notifications with SNS integration

### Request Flow

1. User requests arrive at CloudFront
2. Static content is served directly from S3
3. Dynamic API requests route to the Fargate service
4. Fargate containers invoke Lambda functions to fetch data
5. The rendered HTML is returned to the user

## Prerequisites

- Java 17+
- Maven 3.8+
- AWS CLI configured
- AWS CDK CLI installed (`npm install -g aws-cdk`)
- Docker (for local testing)

## Deployment

1. Build the project:
   ```bash
   mvn clean package
   ```

2. Deploy to AWS:
   ```bash
   cdk deploy
   ```

3. Access the application:
   - The CloudFront URL will be displayed in the CDK output
   - API endpoints are available at `/api/*`

## Environment Variables

The Lambda functions require these environment variables:
- `CATALOG_TABLE_NAME`: DynamoDB table for catalog data
- `REVIEW_TABLE_NAME`: DynamoDB table for reviews
- `NOTIFICATIONS_TABLE_NAME`: DynamoDB table for notifications
- `NOTIFICATIONS_TOPIC_ARN`: SNS topic ARN for notifications

## Security Features

- WAF protection for CloudFront
- S3 bucket with blocked public access
- IAM permissions following least privilege principle
- CORS headers for API responses

## Monitoring and Troubleshooting

- CloudWatch logs for Lambda functions and Fargate tasks
- CloudFront distribution metrics
- WAF security metrics

## License

This project is licensed under the [MIT License](LICENSE).