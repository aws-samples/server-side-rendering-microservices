import software.amazon.awssdk.services.lambda.model.APIGatewayProxyRequestEvent;
import software.amazon.awssdk.services.lambda.model.APIGatewayProxyResponseEvent;
import software.amazon.awscdk.services.lambda.runtime.Context;
import software.amazon.awscdk.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;

import java.util.Map;

public class CatalogHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        EcsClient ecsClient = EcsClient.builder().build();

        RunTaskRequest runTaskRequest = RunTaskRequest.builder()
                .cluster("ServiceCluster")
                .taskDefinition("TaskDef")
                .launchType("FARGATE")
                .build();

        RunTaskResponse runTaskResponse = ecsClient.runTask(runTaskRequest);

        String htmlFragment = "<div>Content from Catalog</div>";

        return APIGatewayProxyResponseEvent.builder()
                .statusCode(200)
                .headers(Map.of("Content-Type", "text/html"))
                .body(htmlFragment)
                .build();
    }
}
