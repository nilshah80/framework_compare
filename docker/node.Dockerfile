FROM node:22-alpine
ARG FRAMEWORK
WORKDIR /app
COPY node/${FRAMEWORK}/ .
RUN npm ci 2>/dev/null || npm install
ENV NODE_ENV=production
CMD ["node", "main.js"]
