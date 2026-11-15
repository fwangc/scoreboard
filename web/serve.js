// Tiny static server so a tablet on the same Wi-Fi can open the scoreboard.
// Usage: node web/serve.js [port]
const http = require("http");
const fs = require("fs");
const path = require("path");

const port = Number(process.argv[2]) || 8080;
const file = path.join(__dirname, "index.html");

http.createServer((req, res) => {
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(500); return res.end("error"); }
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-cache" });
    res.end(data);
  });
}).listen(port, "0.0.0.0", () => console.log(`Scoreboard on http://0.0.0.0:${port}`));
