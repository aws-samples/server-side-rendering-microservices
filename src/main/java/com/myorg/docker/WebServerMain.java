package com.myorg.docker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

@SpringBootApplication
@RestController
public class WebServerMain {

    @GetMapping("/health")
    public ResponseEntity<Void> health() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/catalog")
    public String catalog() {
        SsmClient ssm = SsmClient.create();
        LambdaClient lambda = LambdaClient.create();
        String arn = getLambdaArn(ssm, "CATALOG_FUNCTION_ARN");
        return invokeLambda(lambda, arn);
    }

    @GetMapping("/api/review")
    public String review() {
        SsmClient ssm = SsmClient.create();
        LambdaClient lambda = LambdaClient.create();
        String arn = getLambdaArn(ssm, "REVIEW_FUNCTION_ARN");
        return invokeLambda(lambda, arn);
    }

    @GetMapping("/api/notifications")
    public String notifications() {
        SsmClient ssm = SsmClient.create();
        LambdaClient lambda = LambdaClient.create();
        String arn = getLambdaArn(ssm, "NOTIFICATIONS_FUNCTION_ARN");
        return invokeLambda(lambda, arn);
    }

    private String getLambdaArn(SsmClient ssm, String paramName) {
        GetParameterRequest request = GetParameterRequest.builder().name(paramName).build();
        GetParameterResponse response = ssm.getParameter(request);
        return response.parameter().value();
    }

    private String invokeLambda(LambdaClient lambda, String arn) {
        InvokeRequest req = InvokeRequest.builder()
                .functionName(arn)
                .build();
        InvokeResponse response = lambda.invoke(req);
        return response.payload().asUtf8String();
    }

    public static void main(String[] args) {
        SpringApplication.run(WebServerMain.class, args);
    }
}
