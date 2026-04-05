FROM golang:1.26-alpine AS builder
RUN apk add --no-cache git
ARG FRAMEWORK
WORKDIR /app

# Copy shared pgstore module first
COPY go/pgstore/ ./pgstore/

# Copy the framework
COPY go/${FRAMEWORK}/ ./${FRAMEWORK}/

ENV GONOSUMCHECK='*'
ENV GONOSUMDB='*'
RUN cd ${FRAMEWORK} && go build -ldflags="-s -w" -o /server .

FROM alpine:3.21
COPY --from=builder /server /server
CMD ["/server"]
