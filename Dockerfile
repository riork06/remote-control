FROM node:18-alpine
WORKDIR /app
COPY server/package*.json ./
RUN npm install
COPY server/ ./
EXPOSE 8080
ENV PORT=8080
CMD ["node", "server.js"]
