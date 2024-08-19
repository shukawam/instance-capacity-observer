FROM maven:3.9.9 as build
WORKDIR /helidon
ADD pom.xml pom.xml
RUN mvn package -Dmaven.test.skip -Declipselink.weave.skip -Declipselink.weave.skip -DskipOpenApiGenerate
ADD src src
RUN mvn package -DskipTests
RUN echo "done!"

FROM eclipse-temurin:21.0.1_12-jre
WORKDIR /helidon
COPY --from=build /helidon/target/gpu-capacity-exporter.jar ./
COPY --from=build /helidon/target/libs ./libs
CMD ["java", "-jar", "gpu-capacity-exporter.jar"]
EXPOSE 8080
