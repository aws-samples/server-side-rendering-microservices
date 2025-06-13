FROM public.ecr.aws/amazoncorretto/17-alpine:latest

WORKDIR /app

# Copy the fat JAR for the web server
COPY target/java-ssr-micro_service-0.1-web.jar /app/

EXPOSE 80

CMD ["java", "-jar", "java-ssr-micro_service-0.1-web.jar"]
