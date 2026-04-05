FROM amazoncorretto:26 AS builder
RUN yum install -y maven && yum clean all
ARG FRAMEWORK
WORKDIR /app

# Build and install shared pgstore library first
COPY java/pgstore/ ./pgstore/
RUN cd pgstore && mvn install -DskipTests -q

# Build the framework
COPY java/${FRAMEWORK}/ ./${FRAMEWORK}/
RUN cd ${FRAMEWORK} && mvn package -DskipTests -q

FROM amazoncorretto:26
ARG FRAMEWORK
ARG JAR_PATH
# Copy the entire target directory (Quarkus needs lib/ dirs, Helidon needs libs/)
COPY --from=builder /app/${FRAMEWORK}/target/ /app/target/
# Persist JAR_PATH as env var for runtime
ENV APP_JAR=/app/${JAR_PATH}
WORKDIR /app
ENTRYPOINT ["sh", "-c", "java -jar $APP_JAR"]
