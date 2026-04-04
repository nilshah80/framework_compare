FROM amazoncorretto:26 AS builder
RUN yum install -y maven && yum clean all
ARG FRAMEWORK
WORKDIR /app
COPY java/${FRAMEWORK}/ .
RUN mvn package -DskipTests -q

FROM amazoncorretto:26
ARG JAR_PATH
COPY --from=builder /app/${JAR_PATH} /app.jar
CMD ["java", "-jar", "/app.jar"]
