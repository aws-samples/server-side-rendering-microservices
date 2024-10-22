FROM amazonlinux:2

# Install required packages
RUN yum install -y java-17-amazon-corretto-devel maven

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

EXPOSE 80
CMD ["java", "-jar", "/app/target/java-ssr-micro_service-0.1.jar"]
