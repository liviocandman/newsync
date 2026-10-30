// T-19 infrastructure only; the product uses actual DNS, sockets, and SSRF protection without bypasses.
import { createServer } from 'node:http';
let requests = 0;
const xml = `<rss version="2.0"><channel><title>Ensaio B08</title><link>https://example.invalid</link><description>Teste</description><item><guid>b08:stable</guid><title>PostgreSQL no leitor instalado</title><description><![CDATA[<p>Texto seguro.</p><script>window.__hostile=true</script><img src="https://hostile.invalid/track"><iframe src="https://hostile.invalid/frame"></iframe><style>body{background:url(https://hostile.invalid/css)}</style>]]></description></item></channel></rss>`;
createServer((request, response) => {
  if (request.url === '/stats') {
    response.end(JSON.stringify({ requests }));
    return;
  }
  requests++;
  response.setHeader('ETag', '"b08-v1"');
  response.setHeader('Content-Type', 'application/rss+xml');
  if (request.headers['if-none-match'] === '"b08-v1"') {
    response.writeHead(304);
    response.end();
  } else response.end(xml);
}).listen(8080, '0.0.0.0');
