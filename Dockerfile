# Test image: builds the SDK and runs its tests (live tests need TRUEUP_API_KEY).
FROM maven:3.9-eclipse-temurin-21
WORKDIR /sdk
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY . .
RUN mvn -q -B -DskipTests package
CMD ["mvn", "-B", "test"]
