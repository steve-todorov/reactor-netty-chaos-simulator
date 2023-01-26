FROM node:lts
ENV WEBSOCKET_PROT=8090
COPY ./ /app
WORKDIR /app
ENTRYPOINT [ "node", "websocket-server.js" ]
