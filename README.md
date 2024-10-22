# Serverless Frontend Architecture with CDK

This project demonstrates a serverless frontend architecture built using the AWS Cloud Development Kit (CDK). It leverages various AWS services, including S3 for hosting the static website, Content Delivery Network (CDN), Application Load Balancer (ALB), Fargate/ECS, Secrets Manager, and Lambda functions, to create a scalable and efficient web application with microservices.

## Architecture Overview

The frontend of the application is built using HTMX, a JavaScript library that enables modern browser capabilities without the need for complex frameworks. The frontend assets are hosted on an S3 bucket, which serves as the static website. The contents of the S3 bucket are distributed through a Content Delivery Network (CDN) for optimal performance and caching.

The Application Load Balancer (ALB) acts as the entry point for incoming traffic, routing requests to the appropriate backend service (Fargate/ECS or Lambda) based on the requested path or endpoint.

The backend of the application consists of two main components:

1. **Fargate/ECS**: This service is responsible for running containerized applications, providing a scalable and secure environment for hosting the backend logic.

2. **Lambda Functions**: AWS Lambda functions handle specific backend tasks, such as data processing, API integrations, and other event-driven computations. These Lambda functions act as microservices, and their ARNs (Amazon Resource Names) are stored securely in AWS Secrets Manager.

## Getting Started

To get started with this project, follow these steps:

1. Clone the repository: `git clone https://github.com/aws-samples/server-side-rendering-microservices.git`
2. Install the required dependencies: `npm install`
3. Configure your AWS credentials
4. Deploy the infrastructure using the CDK: `npm run deploy`

## Contributing

Contributions to this project are welcome! If you find any issues or have suggestions for improvements, please open an issue or submit a pull request.

## License

This project is licensed under the [MIT License](LICENSE).