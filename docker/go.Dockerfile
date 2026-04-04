FROM golang:1.26-alpine AS builder
ARG FRAMEWORK
WORKDIR /app
COPY go/${FRAMEWORK}/ .
ENV GONOSUMCHECK='*'
ENV GONOSUMDB='*'
RUN go build -ldflags="-s -w" -o server .

FROM alpine:3.21
COPY --from=builder /app/server /server
CMD ["/server"]
