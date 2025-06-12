package com.myorg;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
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
import software.amazon.awscdk.services.iam.ServicePrincipal;
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
import software.amazon.awscdk.services.wafv2.*;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class JavaSsrMicroServiceStack extends Stack {
    public JavaSsrMicroServiceStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public JavaSsrMicroServiceStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // 1. S3 bucket for static assets
        Bucket staticAssetsBucket = Bucket.Builder.create(this, "StaticAssetsBucket")
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .versioned(true)
                .build();

        // 2. Deploy static assets (EXTRACT your static.zip before this step!)
        BucketDeployment.Builder.create(this, "DeployStaticAssets")
                .sources(List.of(Source.asset("src/main/java/com/myorg/static/resources"))) // this path should be a folder, not a zip
                .destinationBucket(staticAssetsBucket)
                .build();

        // 3. Lambda functions
        Function catalogFunction = Function.Builder.create(this, "CatalogFunction")
                .architecture(Architecture.ARM_64)
                .runtime(Runtime.JAVA_17)
                .handler("com.myorg.CatalogHandler::handleRequest")
                .code(Code.fromAsset("target/java-ssr-micro_service-0.1-catalog.jar"))
                .retryAttempts(2)
                .build();

        Function reviewFunction = Function.Builder.create(this, "ReviewFunction")
                .architecture(Architecture.ARM_64)
                .runtime(Runtime.JAVA_17)
                .handler("com.myorg.ReviewHandler::handleRequest")
                .code(Code.fromAsset("target/java-ssr-micro_service-0.1-review.jar"))
                .retryAttempts(2)
                .build();

        Function notificationsFunction = Function.Builder.create(this, "NotificationsFunction")
                .architecture(Architecture.ARM_64)
                .runtime(Runtime.JAVA_17)
                .handler("com.myorg.NotificationsHandler::handleRequest")
                .code(Code.fromAsset("target/java-ssr-micro_service-0.1-notifications.jar"))
                .retryAttempts(2)
                .build();

        // 4. Grant permissions to the Fargate service to invoke Lambda functions
        PolicyStatement invokeLambdaPolicy = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("lambda:InvokeFunction"))
                .resources(List.of(
                        catalogFunction.getFunctionArn(),
                        reviewFunction.getFunctionArn(),
                        notificationsFunction.getFunctionArn()
                ))
                .build();

        // 5. ECS Cluster
        Cluster cluster = Cluster.Builder.create(this, "ServiceCluster")
                .build();

        // 6. Fargate Task Definition
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
                .healthCheck(software.amazon.awscdk.services.ecs.HealthCheck.builder()
                        .command(List.of("CMD-SHELL",
                                "curl -f http://localhost:80/health || exit 1"))
                        .interval(software.amazon.awscdk.Duration.seconds(60))
                        .timeout(software.amazon.awscdk.Duration.seconds(10))
                        .retries(5)
                        .startPeriod(software.amazon.awscdk.Duration.seconds(120))
                        .build())
                .build();

        taskDefinition.addContainer("Container", containerOptions);
        taskDefinition.getTaskRole().addToPrincipalPolicy(invokeLambdaPolicy);

        // 7. Internal (private) ALB, only reachable in VPC
        ApplicationLoadBalancedFargateService fargateService = ApplicationLoadBalancedFargateService.Builder.create(this, "FargateService")
                .cluster(cluster)
                .taskDefinition(taskDefinition)
                .publicLoadBalancer(false) // Make ALB private/internal
                .build();

        fargateService.getTargetGroup()
                .configureHealthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder()
                .path("/health")
                .interval(Duration.seconds(30))
                .healthyThresholdCount(2)
                .build());

        // 8. WAF for CloudFront
        CfnWebACL webAcl = CfnWebACL.Builder.create(this, "CloudFrontWAF")
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build())
                        .build())
                .scope("CLOUDFRONT")
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .cloudWatchMetricsEnabled(true)
                        .metricName("CloudFrontWAFMetrics")
                        .sampledRequestsEnabled(true)
                        .build())
                .rules(Arrays.asList(
                        // Rate limiting rule
                        CfnWebACL.RuleProperty.builder()
                                .name("RateLimitRule")
                                .priority(1)
                                .action(CfnWebACL.RuleActionProperty.builder()
                                        .block(CfnWebACL.BlockActionProperty.builder().build())
                                        .build())
                                .statement(CfnWebACL.StatementProperty.builder()
                                        .rateBasedStatement(CfnWebACL.RateBasedStatementProperty.builder()
                                                .limit(2000)
                                                .aggregateKeyType("IP")
                                                .forwardedIpConfig(CfnWebACL.ForwardedIPConfigurationProperty.builder()
                                                        .headerName("X-Forwarded-For")
                                                        .fallbackBehavior("MATCH")
                                                        .build())
                                                .build())
                                        .build())
                                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                                        .cloudWatchMetricsEnabled(true)
                                        .metricName("RateLimitRule")
                                        .sampledRequestsEnabled(true)
                                        .build())
                                .build(),
                        // AWS Managed Rules - Common Rule Set
                        CfnWebACL.RuleProperty.builder()
                                .name("AWSManagedRulesCommonRuleSet")
                                .priority(2)
                                .statement(CfnWebACL.StatementProperty.builder()
                                        .managedRuleGroupStatement(CfnWebACL.ManagedRuleGroupStatementProperty.builder()
                                                .vendorName("AWS")
                                                .name("AWSManagedRulesCommonRuleSet")
                                                .excludedRules(Arrays.asList())
                                                .build())
                                        .build())
                                .overrideAction(CfnWebACL.OverrideActionProperty.builder()
                                        .none(Map.of())
                                        .build())
                                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                                        .cloudWatchMetricsEnabled(true)
                                        .metricName("AWSManagedRulesCommonRuleSetMetric")
                                        .sampledRequestsEnabled(true)
                                        .build())
                                .build()
                ))
                .build();

        // 9. S3 Origin Access Identity (OAI)
        OriginAccessIdentity oai = OriginAccessIdentity.Builder.create(this, "OAI")
                .comment("OAI for static assets bucket")
                .build();

        // 10. CloudFront Distribution with OAI (S3 integration)
        S3Origin s3Origin = S3Origin.Builder.create(staticAssetsBucket)
                .originAccessIdentity(oai)
                .build();
        Distribution distribution = Distribution.Builder.create(this, "CloudFrontDistribution")
                .webAclId(webAcl.getAttrArn())
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(s3Origin)
                        .build())
                .additionalBehaviors(Map.of(
                        "/api/*", BehaviorOptions.builder()
                                .origin(new LoadBalancerV2Origin(fargateService.getLoadBalancer()))
                                .build()
                ))
                .build();

        // 11. Restrict S3 to only allow CloudFront via OAC
        // The OAC principal is not just cloudfront.amazonaws.com—it's tied to the OAC
        staticAssetsBucket.addToResourcePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(Arrays.asList("s3:GetObject"))
                .resources(Arrays.asList(staticAssetsBucket.arnForObjects("*")))
                .principals(Arrays.asList(new ServicePrincipal("cloudfront.amazonaws.com")))
                // .conditions(...) // TODO: Complete or remove this line as needed
                .build());

        new CfnOutput(this, "CloudFrontURL", CfnOutputProps.builder()
                .value(distribution.getDistributionDomainName())
                .build());

        // Lambda function ARNs outputs
        new CfnOutput(this, "CatalogFunctionArn", CfnOutputProps.builder()
                .value(catalogFunction.getFunctionArn())
                .build());
        new CfnOutput(this, "ReviewFunctionArn", CfnOutputProps.builder()
                .value(reviewFunction.getFunctionArn())
                .build());
        new CfnOutput(this, "NotificationsFunctionArn", CfnOutputProps.builder()
                .value(notificationsFunction.getFunctionArn())
                .build());
        new CfnOutput(this, "WAFWebACLArn", CfnOutputProps.builder()
                .value(webAcl.getAttrArn())
                .description("ARN of the WAF Web ACL protecting the CloudFront distribution")
                .build());
    }
}
// Note: Ensure you have the necessary dependencies in your build tool (Maven/Gradle) for AWS CDK, Lambda, ECS, S3, CloudFront, and WAF.
// This code assumes you have the AWS CDK set up and configured in your environment.
// You can deploy this stack using the AWS CDK CLI with `cdk deploy` command.
// Make sure to replace the handler paths and JAR file names with your actual implementation details.
//         when(dynamoDb.getItem(any(GetItemRequest.class)))
//                 .thenReturn(getItemResponse);