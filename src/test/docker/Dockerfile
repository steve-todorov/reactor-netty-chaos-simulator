FROM node:lts
ENV WEBSOCKET_PROT=8090
COPY ./ /app
WORKDIR /app
RUN npm install
ENTRYPOINT [ "node", "websocket-server.js" ]
