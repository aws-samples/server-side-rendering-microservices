# Java-based Serverless Microservices Architecture with CDK

This project demonstrates a serverless microservices architecture built using Java and the AWS Cloud Development Kit (CDK). It implements a scalable system with three main services: Catalog, Review, and Notifications, leveraging AWS Lambda, API Gateway, and other AWS services.

## Architecture Overview

The application is built with the following components:

1. **Catalog Service**: Handles product catalog management and queries
2. **Review Service**: Manages customer reviews and ratings
3. **Notifications Service**: Handles system notifications and alerts

Each service is implemented as a separate Lambda function, providing isolation and independent scaling.

### Key AWS Services Used:
- AWS Lambda for serverless compute
- Amazon API Gateway for REST API management
- Amazon DynamoDB for data storage
- AWS Secrets Manager for sensitive configuration
- Amazon CloudWatch for monitoring and logging

## Prerequisites

1. Java 17 or later
2. Maven 3.8+
3. AWS CLI configured with appropriate credentials
4. AWS CDK CLI installed (`npm install -g aws-cdk`)

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/aws-samples/server-side-rendering-microservices.git
   cd java-ssr-micro_service
   ```

2. Build the project:
   ```bash
   mvn clean package
   ```

3. Deploy to AWS:
   ```bash
   cdk deploy
   ```

## Environment Configuration

The application supports multiple environments through CDK contexts:

- Development: `cdk deploy -c env=dev`
- Staging: `cdk deploy -c env=staging`
- Production: `cdk deploy -c env=prod`

## Monitoring and Logging

All Lambda functions are configured with CloudWatch logging. Access logs through:
1. AWS Console > CloudWatch > Log Groups
2. Each function has its own log group: `/aws/lambda/<function-name>`

## Troubleshooting

Common issues and solutions:

1. **Deployment Failures**
   - Ensure AWS credentials are properly configured
   - Check CloudFormation console for detailed error messages
   - Verify sufficient IAM permissions

2. **Runtime Errors**
   - Check CloudWatch logs for each Lambda function
   - Verify environment variables and configurations
   - Ensure DynamoDB tables are properly provisioned

3. **API Gateway Issues**
   - Verify API Gateway deployment stage
   - Check CORS configurations if applicable
   - Validate Lambda function permissions

## Contributing

Contributions to this project are welcome! If you find any issues or have suggestions for improvements, please open an issue or submit a pull request.

## License

This project is licensed under the [MIT License](LICENSE).