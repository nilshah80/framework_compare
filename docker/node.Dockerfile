FROM node:22-alpine
ARG FRAMEWORK
WORKDIR /app

# Copy shared pgstore first
COPY node/pgstore/ ./pgstore/
RUN cd pgstore && npm install

# Copy the framework
COPY node/${FRAMEWORK}/ ./${FRAMEWORK}/
RUN cd ${FRAMEWORK} && npm install

WORKDIR /app/${FRAMEWORK}
ENV NODE_ENV=production
CMD ["node", "main.js"]
