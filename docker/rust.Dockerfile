FROM rust:1.94-slim AS builder
ARG FRAMEWORK
WORKDIR /app

# Copy shared pgstore crate first
COPY rust/pgstore/ ./pgstore/

# Copy the framework
COPY rust/${FRAMEWORK}/ ./${FRAMEWORK}/

# Build the framework (which depends on ../pgstore)
RUN cd ${FRAMEWORK} && cargo build --release
RUN cp ${FRAMEWORK}/target/release/benchmark-${FRAMEWORK} /server

# Copy Rocket.toml if it exists
RUN if [ -f ${FRAMEWORK}/Rocket.toml ]; then cp ${FRAMEWORK}/Rocket.toml /Rocket.toml; else touch /Rocket.toml; fi

FROM gcr.io/distroless/cc-debian12
COPY --from=builder /server /server
COPY --from=builder /Rocket.toml /Rocket.toml
CMD ["/server"]
