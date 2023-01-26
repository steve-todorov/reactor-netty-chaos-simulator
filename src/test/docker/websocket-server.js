const WebSocketServer = require('ws').WebSocketServer;

const server = new WebSocketServer({
    port: process.env.WEBSOCKET_PORT ?? 8090
});

server.on('listening', function listening() {
    const { address, family, port } = server.address();
    console.log(
        'Server listening on %s:%d',
        family === 'IPv6' ? `[${address}]` : address,
        port
    );
});

server.on('connection', function connection(ws) {
    console.info("New connection established");
});

// detect broken connections
const interval = setInterval(function ping() {
    server.clients.forEach(function each(ws) {
        if (ws.isAlive === false) {
            console.info(`Connection is not alive -- terminating.`)
            return ws.terminate();
        }
        ws.isAlive = false;
        ws.ping();
    });
}, 30000);

// emit messages every second.
const autoEmit = setInterval(function() {
    const msg = JSON.stringify({"timestamp": Date.now(), "hello": "world!"})
    server.clients.forEach(function(client) {
        client.send(msg);
    });
}, 1000);

server.on('close', function close() {
    clearInterval(interval);
    clearInterval(autoEmit);
});

module.exports = {wss: server};
