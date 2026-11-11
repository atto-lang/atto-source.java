import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';

const port = process.env.ATTO_MCP_TEST_PORT;
const token = process.env.ATTO_MCP_TEST_TOKEN;
if (!port || !token) throw Error('missing test endpoint environment');

const client = new Client(
  { name: 'atto-sdk-integration-smoke', version: '1.0.0' },
  { versionNegotiation: { mode: { pin: '2026-07-28' } } },
);
const transport = new StreamableHTTPClientTransport(
  new URL('http://127.0.0.1:' + port + '/mcp'),
  { requestInit: { headers: { Authorization: 'Bearer ' + token } } },
);
try {
  await client.connect(transport);
  if (client.getProtocolEra() !== 'modern') {
    throw Error('SDK did not negotiate modern MCP');
  }
  const listed = await client.listTools();
  if (listed.tools.length !== 1 || listed.tools[0].name !== 'atto.runtime_info') {
    throw Error('SDK observed unexpected tool inventory: ' + JSON.stringify(listed));
  }
  const reply = await client.callTool({ name: 'atto.runtime_info', arguments: {} });
  if (reply.isError || !JSON.stringify(reply).includes('sdk-fixture')) {
    throw Error('SDK did not obtain complete typed runtime response: ' + JSON.stringify(reply));
  }
  console.log('SDK_COMPAT_VERIFIED: modern MCP discover/listTools/callTool');
} finally {
  await client.close();
}
