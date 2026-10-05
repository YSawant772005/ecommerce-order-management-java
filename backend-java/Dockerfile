FROM eclipse-temurin:17-jre-alpine

ENV DEBIAN_FRONTEND=noninteractive

RUN apk add --no-cache curl

WORKDIR /app

# Build the jar first on the host:  mvn -f backend-java/pom.xml -DskipTests package
COPY backend-java/target/order-management-1.0.0.jar /app/app.jar

EXPOSE 8000

CMD ["java", "-jar", "/app/app.jar"]