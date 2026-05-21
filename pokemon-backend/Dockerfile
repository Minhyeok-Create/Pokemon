FROM openjdk:17-jdk-slim

WORKDIR /app

COPY . .

RUN chmod +x ./gradlew
RUN .gradlew clean buil -x test

ENTRYPOINT ["sh", "-c", "java -jar build/libs/*SNAPSHOT.jar"]