FROM amazonlinux:2

# Install required packages
RUN yum install -y java-17-amazon-corretto-devel wget tar gzip
RUN wget https://archive.apache.org/dist/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz -P /tmp
RUN tar xf /tmp/apache-maven-3.8.6-bin.tar.gz -C /opt
RUN ln -s /opt/apache-maven-3.8.6/bin/mvn /usr/bin/mvn

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

EXPOSE 80
CMD ["java", "-jar", "/app/target/java-ssr-micro_service-0.1.jar"]
