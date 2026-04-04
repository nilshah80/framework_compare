FROM rust:1.94-slim AS builder
ARG FRAMEWORK
WORKDIR /app
COPY rust/${FRAMEWORK}/ .
RUN cargo build --release
RUN cp target/release/benchmark-${FRAMEWORK} /server
RUN if [ -f Rocket.toml ]; then cp Rocket.toml /Rocket.toml; else touch /Rocket.toml; fi

FROM gcr.io/distroless/cc-debian13
COPY --from=builder /server /server
COPY --from=builder /Rocket.toml /Rocket.toml
CMD ["/server"]
