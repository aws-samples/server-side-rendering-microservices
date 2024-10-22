package com.myorg;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;
import software.amazon.awscdk.services.cloudfront.*;
import software.amazon.awscdk.services.cloudfront.origins.*;

import java.util.List;
import java.util.Map;

public class JavaSsrMicroServiceStack extends Stack {
    public JavaSsrMicroServiceStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public JavaSsrMicroServiceStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // S3 bucket for static assets
        Bucket staticAssetsBucket = Bucket.Builder.create(this, "StaticAssetsBucket-123456543")
                .blockPublicAccess(BlockPublicAccess.Builder.create()
                        .blockPublicAcls(false)
                        .ignorePublicAcls(false)
                        .blockPublicPolicy(false)
                        .restrictPublicBuckets(false)
                        .build())
                .websiteIndexDocument("index.html")
                .versioned(true)
                .build();

                 // Deploy static assets to S3 bucket
        BucketDeployment.Builder.create(this, "DeployStaticAssets")
                .sources(List.of(Source.asset("src/main/java/com/myorg/static/resources/indexl.html")))
                .destinationBucket(staticAssetsBucket)
                .build();

        // Lambda functions for HTML fragments
        Function catalogFunction = Function.Builder.create(this, "CatalogFunction")
                .architecture(Architecture.ARM_64)
                .runtime(Runtime.JAVA_17)
                .handler("com.myorg.resources.CatalogHandler::handleRequest")
                .code(Code.fromAsset("target/java-ssr-micro_service-0.1-catalog.jar"))
                .build();

        Function reviewFunction = Function.Builder.create(this, "ReviewFunction")
                .architecture(Architecture.ARM_64)
                .runtime(Runtime.JAVA_17)                
                .handler("com.myorg.resources.ReviewHandler::handleRequest")
                .code(Code.fromAsset("target/java-ssr-micro_service-0.1-review.jar"))
                .build();

        Function notificationsFunction = Function.Builder.create(this, "NotificationsFunction")
                .architecture(Architecture.ARM_64)
                .runtime(Runtime.JAVA_17)
                .handler("com.myorg.resources.NotificationsHandler::handleRequest")
                .code(Code.fromAsset("target/java-ssr-micro_service-0.1-notifications.jar"))
                .build();

        // Grant permissions to the Fargate service to invoke the Lambda functions
        PolicyStatement invokeLambdaPolicy = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("lambda:InvokeFunction"))
                .resources(List.of(
                        catalogFunction.getFunctionArn(),
                        reviewFunction.getFunctionArn(),
                        notificationsFunction.getFunctionArn()
                ))
                .build();

        // ECS Cluster
        Cluster cluster = Cluster.Builder.create(this, "ServiceCluster")
                .build();

        // Fargate Task Definition
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this, "TaskDef")
                .memoryLimitMiB(512)
                .cpu(256)
                .build();

        ContainerDefinitionOptions containerOptions = ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromAsset("."))
                .memoryLimitMiB(512)
                .cpu(256)
                .portMappings(List.of(PortMapping.builder()
                        .containerPort(80)
                        .build()))
                .environment(Map.of(
                        "CATALOG_FUNCTION_NAME", catalogFunction.getFunctionName(),
                        "REVIEW_FUNCTION_NAME", reviewFunction.getFunctionName(),
                        "NOTIFICATIONS_FUNCTION_NAME", notificationsFunction.getFunctionName()
                ))
                .build();

        taskDefinition.addContainer("Container", containerOptions);

        ApplicationLoadBalancedFargateService fargateService = ApplicationLoadBalancedFargateService.Builder.create(this, "FargateService")
                .cluster(cluster)
                .taskDefinition(taskDefinition)
                .publicLoadBalancer(true)
                .build();

        // Attach the policy to the task role
        taskDefinition.getTaskRole().addToPrincipalPolicy(invokeLambdaPolicy);

        // CloudFront Distribution
        Distribution distribution = Distribution.Builder.create(this, "CloudFrontDistribution")
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(new S3Origin(staticAssetsBucket))
                        .build())
                .additionalBehaviors(Map.of(
                        "/api/*", BehaviorOptions.builder()
                                .origin(new LoadBalancerV2Origin(fargateService.getLoadBalancer()))
                                .build()
                ))
                .build();

        // Outputs
        new CfnOutput(this, "StaticAssetsBucketName", CfnOutputProps.builder()
                .value(staticAssetsBucket.getBucketName())
                .build());

        new CfnOutput(this, "LoadBalancerDNS", CfnOutputProps.builder()
                .value(fargateService.getLoadBalancer().getLoadBalancerDnsName())
                .build());

        new CfnOutput(this, "CloudFrontURL", CfnOutputProps.builder()
                .value(distribution.getDistributionDomainName())
                .build());
    }
}
