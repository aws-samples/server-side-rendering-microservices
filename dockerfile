FROM --platform=linux/amd64 amazoncorretto:17

WORKDIR /app

COPY target/java-ssr-micro_service-0.1-web.jar /app/

EXPOSE 80

CMD ["java", "-jar", "java-ssr-micro_service-0.1-web.jar", "--server.port=80"]

