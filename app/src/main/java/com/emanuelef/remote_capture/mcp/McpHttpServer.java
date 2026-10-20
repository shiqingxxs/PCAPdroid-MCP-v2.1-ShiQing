/* This file is part of PCAPdroid.
 * PCAPdroid is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.  See <http://www.gnu.org/licenses/>.
 * Copyright 2026 - Emanuele Faranda
 */
package com.emanuelef.remote_capture.mcp;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.Utils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/* Minimal self-contained HTTP server for the MCP integration. It follows the
 * design of pcap_dump.HTTPServer (ServerSocket + thread pool + raw HTTP parsing)
 * but is an independent class, so the PCAP dump feature is unaffected. Java
 * standard library only. Stage 1: GET /health; stage 2 wires /sse, /messages. */
public class McpHttpServer implements Runnable {
    private static final String TAG = "McpHttpServer";
    public static final int DEFAULT_PORT = 8080;
    private static final int MAX_CLIENTS = 16;
    private static final int INPUT_BUFSIZE = 8192;

    private final int mPort;
    private ServerSocket mSocket;
    private boolean mRunning;
    private Thread mThread;
    private final ExecutorService mPool = Executors.newFixedThreadPool(MAX_CLIENTS);

    public McpHttpServer(int port) { mPort = port; }

    public int getPort() { return mPort; }

    /* Advertised host IP used to build the SSE endpoint URL (set from MainActivity). */
    private volatile String mServerIp;

    /* Source of the connections served by the "list-recent-connections" tool. */
    private volatile ConnectionProvider mConnectionProvider;

    /* Active SSE sessions, keyed by session id. */
    private final Map<String, SseSession> mSessions = new HashMap<>();

    /* Provides the data for the MCP tools. */
    public interface ConnectionProvider {
        /** Returns the recent connections as a list of JSON-ready name/value maps. */
        List<Map<String, Object>> getRecentConnections();

        /** Returns the request/response payload of the connection identified by incr_id, as a
         *  JSON string. direction is "request", "response" or "both"; max_bytes caps the bytes
         *  returned for each direction. Runs on a POST worker thread and may read the payload
         *  on demand from the captured PCAP file; if it is no longer reachable (e.g. overwritten
         *  by the ring buffer) the returned JSON reports status "partial"/"unavailable". */
        String getConnectionPayload(int incr_id, String direction, int max_bytes);
    }

    public void setServerIp(String ip) { mServerIp = ip; }

    public void setConnectionProvider(ConnectionProvider provider) { mConnectionProvider = provider; }

    public synchronized void start() throws IOException {
        if(mRunning) return;
        mSocket = new ServerSocket();
        mSocket.setReuseAddress(true);
        mSocket.bind(new InetSocketAddress(mPort));
        mRunning = true;
        mThread = new Thread(this, "McpHttpServer");
        mThread.setDaemon(true);
        mThread.start();
        Log.i(TAG, "MCP HTTP server listening on port " + mPort);
    }

    public synchronized void stop() {
        mRunning = false;
        Utils.safeClose(mSocket); /* wake up the accept() loop */

        /* Close any open SSE sessions so their blocked reads unblock. */
        synchronized(mSessions) {
            for(SseSession s : mSessions.values())
                s.close();
            mSessions.clear();
        }

        if(mThread != null) {
            try { mThread.join(1000); } catch(InterruptedException ignored) {}
        }
        mPool.shutdown();
        try {
            if(!mPool.awaitTermination(2, TimeUnit.SECONDS))
                mPool.shutdownNow();
        } catch(InterruptedException ignored) {}
    }

    public boolean isRunning() { return mRunning; }

    @Override
    public void run() {
        while(mRunning) {
            try {
                Socket client = mSocket.accept();
                try {
                    mPool.submit(new ClientHandler(client));
                } catch(RejectedExecutionException e) {
                    Utils.safeClose(client);
                }
            } catch(IOException e) {
                if(mRunning) Log.d(TAG, e.getLocalizedMessage());
            }
        }
        Utils.safeClose(mSocket);
    }

    /* Request entry point. Stage 2 wires the MCP /sse and /messages endpoints here. */
    private void handleRequest(String method, String path, byte[] body, OutputStream out,
                               InputStream in, Socket socket) throws IOException {
        if(method.equals("GET") && path.equals("/health"))
            sendJson(out, 200, "{\"status\":\"ok\"}");
        else if(method.equals("GET") && path.equals("/sse"))
            handleSse(socket, in, out);
        else if(method.equals("POST") && path.startsWith("/messages"))
            handleMessages(path, body, out);
        else if(method.equals("GET"))
            sendJson(out, 404, "{\"error\":\"not_found\"}");
        else
            sendJson(out, 405, "{\"error\":\"method_not_supported\"}");
    }

    /* Open a long-lived Server-Sent Events stream for one MCP client session. */
    private void handleSse(Socket socket, InputStream in, OutputStream out) throws IOException {
        String sessionId = Long.toHexString(ThreadLocalRandom.current().nextLong());
        SseSession session = new SseSession(sessionId, out, socket);
        synchronized(mSessions) { mSessions.put(sessionId, session); }

        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: text/event-stream\r\n")
                .append("Cache-Control: no-cache\r\n")
                .append("Connection: keep-alive\r\n")
                .append("Access-Control-Allow-Origin: *\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String ip = (mServerIp != null) ? mServerIp : "127.0.0.1";
        String endpoint = "http://" + ip + ":" + mPort + "/messages?sessionId=" + sessionId;
        session.sendEvent("endpoint", endpoint);
        Log.i(TAG, "SSE session " + sessionId + " established, endpoint " + endpoint);

        /* Hold the connection open until the client disconnects. The MCP client
         * sends data only over the /messages POST endpoint, so this read blocks. */
        try {
            byte[] buf = new byte[512];
            while(mRunning && (in.read(buf) >= 0)) { }
        } catch(IOException ignored) {
            /* connection dropped */
        } finally {
            synchronized(mSessions) { mSessions.remove(sessionId); }
            session.close();
            Log.d(TAG, "SSE session " + sessionId + " closed");
        }
    }

    /* Handle a JSON-RPC message POSTed to /messages. The JSON-RPC response is
     * delivered as a "message" SSE event on the matching session stream. */
    private void handleMessages(String path, byte[] body, OutputStream out) throws IOException {
        String sessionId = getQueryParam(path, "sessionId");
        SseSession session;
        synchronized(mSessions) { session = (sessionId != null) ? mSessions.get(sessionId) : null; }
        if(session == null) {
            sendJson(out, 400, "{\"error\":\"unknown_session\"}");
            return;
        }

        String reply = processJsonRpc(new String(body, StandardCharsets.UTF_8));
        sendAccepted(out);
        if(reply != null) {
            try {
                session.sendEvent("message", reply);
            } catch(IOException e) {
                Log.d(TAG, "SSE send failed: " + e.getLocalizedMessage());
                synchronized(mSessions) { mSessions.remove(sessionId); }
                session.close();
            }
        }
    }

    /* ---- JSON-RPC / MCP handling ------------------------------------------------ */

    /* Process a single JSON-RPC message and return the JSON response string to be
     * sent over the SSE stream, or null if no response is expected (notifications). */
    private String processJsonRpc(String body) {
        Object parsed;
        try {
            parsed = new JsonParser(body).parse();
        } catch(Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"},\"id\":null}";
        }
        if(!(parsed instanceof Map))
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"},\"id\":null}";

        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) parsed;
        Object id = msg.get("id");
        Object method = msg.get("method");
        if(method == null)
            return jsonError(id, -32600, "Invalid Request");
        if(id == null)
            return null; /* notification, nothing to reply */
        if(method.toString().startsWith("notifications/"))
            return null; /* notifications must not produce a response */

        Map<String, Object> params = asMap(msg.get("params"));
        switch(method.toString()) {
            case "initialize":
                return jsonResult(id, initializeResult());
            case "tools/list":
                return jsonResult(id, toolsListResult());
            case "tools/call":
                return callTool(id, params);
            case "ping":
                return jsonResult(id, "{}");
            default:
                return jsonError(id, -32601, "Method not found: " + method);
        }
    }

    private String initializeResult() {
        return "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                + "\"serverInfo\":{\"name\":\"PCAPdroid MCP\",\"version\":\"0.2.0\"},\"instructions\":\"Use the "
                + "list-recent-connections tool to inspect the network connections captured by PCAPdroid.\"}";
    }

    private String toolsListResult() {
        return "{\"tools\":[{\"name\":\"list-recent-connections\",\"description\":\"Lists the most recent "
                + "network connections captured by PCAPdroid. Each entry reports the app, uid, destination IP "
                + "and port, transport protocol, sent/received byte counts and the last-seen time. It does not "
                + "return any HTTP payload and does not control the capture.\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}},"
                + "{\"name\":\"get-connection-payload\",\"description\":\"Returns the request/response payload of a "
                + "specific connection, identified by its incr_id. The bytes are read on demand from the captured "
                + "PCAP file (or from memory for a live capture). If the connection or its payload can no longer be "
                + "read, e.g. because it was overwritten by the ring buffer, the tool returns status \\\"partial\\\" or "
                + "\\\"unavailable\\\" instead of failing. Use it to inspect the raw body an app sent/received.\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"incr_id\":{\"type\":\"integer\",\"description\":\"The connection id (incr_id) shown in the PCAPdroid connection list.\"},\"direction\":{\"type\":\"string\",\"enum\":[\"request\",\"response\",\"both\"],\"description\":\"Which direction to return; default 'both'.\"},\"max_bytes\":{\"type\":\"integer\",\"description\":\"Max bytes read per direction from the captured payload; default 32768.\"}},\"required\":[\"incr_id\"],\"additionalProperties\":false}}]}";
    }

    private String callTool(Object id, Map<String, Object> params) {
        String name = str(params == null ? null : params.get("name"));
        if(name == null)
            return jsonError(id, -32602, "Tool name missing");
        if("list-recent-connections".equals(name)) {
            List<Map<String, Object>> conns = (mConnectionProvider != null)
                    ? mConnectionProvider.getRecentConnections()
                    : new ArrayList<Map<String, Object>>();
            String listJson = jsonArray(conns);
            String result = "{\"content\":[{\"type\":\"text\",\"text\":\"" + jsonEscape(listJson) + "\"}]}";
            return jsonResult(id, result);
        }
        if("get-connection-payload".equals(name)) {
            Map<String, Object> args = asMap(params == null ? null : params.get("arguments"));
            int incr_id = getInt(args, "incr_id", -1);
            if(incr_id < 0)
                return jsonError(id, -32602, "Missing or invalid 'incr_id' argument");
            String direction = str(args.get("direction"));
            int max_bytes = getInt(args, "max_bytes", 32768);
            String payloadJson = (mConnectionProvider != null)
                    ? mConnectionProvider.getConnectionPayload(incr_id, direction, max_bytes)
                    : "{\"status\":\"unavailable\",\"reason\":\"connection provider not set\"}";
            String result = "{\"content\":[{\"type\":\"text\",\"text\":\"" + jsonEscape(payloadJson) + "\"}]}";
            return jsonResult(id, result);
        }
        return jsonError(id, -32602, "Unknown tool: " + name);
    }

    private static String jsonArray(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for(Map<String, Object> map : items) {
            if(!first) sb.append(',');
            first = false;
            sb.append('{');
            boolean firstKey = true;
            for(Map.Entry<String, Object> e : map.entrySet()) {
                if(!firstKey) sb.append(',');
                firstKey = false;
                sb.append('"').append(jsonEscape(e.getKey())).append("\":");
                sb.append(jsonValue(e.getValue()));
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonValue(Object v) {
        if(v == null) return "null";
        if(v instanceof Number) return v.toString();
        return "\"" + jsonEscape(v.toString()) + "\"";
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch(c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if(c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /* Echo back the request id preserving its original representation. */
    private static String jsonId(Object id) {
        if(id == null) return "null";
        if(id instanceof JsonNumber) return ((JsonNumber) id).raw;
        return "\"" + jsonEscape(id.toString()) + "\"";
    }

    private static String jsonResult(Object id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"result\":" + resultJson + ",\"id\":" + jsonId(id) + "}";
    }

    private static String jsonError(Object id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + jsonEscape(message)
                + "\"},\"id\":" + jsonId(id) + "}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static String str(Object o) {
        return (o != null) ? o.toString() : null;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        if(map == null) return def;
        Object v = map.get(key);
        if(v == null) return def;
        if(v instanceof Number) return ((Number) v).intValue();
        if(v instanceof JsonNumber) return (int) ((JsonNumber) v).value;
        try { return Integer.parseInt(v.toString()); } catch(NumberFormatException e) { return def; }
    }

    private static String getQueryParam(String path, String name) {
        int q = path.indexOf('?');
        if(q < 0) return null;
        for(String part : path.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if((eq > 0) && part.substring(0, eq).equals(name))
                return part.substring(eq + 1);
        }
        return null;
    }

    /* Minimal recursive-descent JSON parser (Java stdlib, no third-party libs). */
    private static class JsonParser {
        private final String s;
        private int i;
        JsonParser(String text) { s = text; }

        Object parse() {
            ws();
            return value();
        }

        private void ws() {
            while((i < s.length()) && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if(c == '{') return object();
            if(c == '[') return array();
            if(c == '"') return string();
            if(s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if(s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if(s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new HashMap<>();
            i++; /* '{' */
            ws();
            if((i < s.length()) && (s.charAt(i) == '}')) { i++; return map; }
            while(i < s.length()) {
                ws();
                String key = string();
                ws();
                i++; /* ':' */
                map.put(key, value());
                ws();
                char c = s.charAt(i++);
                if(c == '}') break; /* ',' otherwise */
            }
            return map;
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++; /* '[' */
            ws();
            if((i < s.length()) && (s.charAt(i) == ']')) { i++; return list; }
            while(i < s.length()) {
                list.add(value());
                ws();
                char c = s.charAt(i++);
                if(c == ']') break; /* ',' otherwise */
            }
            return list;
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; /* '"' */
            while(i < s.length()) {
                char c = s.charAt(i++);
                if(c == '"') break;
                if(c == '\\') {
                    char e = s.charAt(i++);
                    switch(e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        private Object number() {
            int start = i;
            while((i < s.length()) && ("0123456789+-.eE".indexOf(s.charAt(i)) >= 0)) i++;
            return new JsonNumber(Double.parseDouble(s.substring(start, i)), s.substring(start, i));
        }
    }

    /* Number wrapper that keeps the raw text so the request id can be echoed verbatim. */
    private static class JsonNumber {
        final double value;
        final String raw;
        JsonNumber(double value, String raw) { this.value = value; this.raw = raw; }
    }

    /* One MCP client session. The SSE output stream is shared by the GET /sse
     * handler (which writes the "endpoint" event) and the POST /messages handlers
     * (which push "message" events), so all writes are synchronized on the session. */
    private class SseSession {
        final String mId;
        private final OutputStream mOut;
        private final Socket mSocket;
        private volatile boolean mClosed;

        SseSession(String id, OutputStream out, Socket socket) { mId = id; mOut = out; mSocket = socket; }

        synchronized void sendEvent(String event, String data) throws IOException {
            if(mClosed) return;
            StringBuilder sb = new StringBuilder()
                    .append("event: ").append(event).append('\n')
                    .append("data: ").append(data).append("\n\n");
            mOut.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            mOut.flush();
        }

        synchronized void close() {
            mClosed = true;
            Utils.safeClose(mOut);
            Utils.safeClose(mSocket);
        }
    }

    private static void sendJson(OutputStream out, int code, String body) throws IOException {
        String status;
        switch(code) {
            case 202: status = "202 Accepted"; break;
            case 400: status = "400 Bad Request"; break;
            case 404: status = "404 Not Found"; break;
            case 405: status = "405 Method Not Allowed"; break;
            default:  status = "200 OK";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder response = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(response.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /* The MCP SSE transport answers a message POST with 202 Accepted and an empty
     * body; the actual JSON-RPC response is pushed through the SSE "message" event. */
    private static void sendAccepted(OutputStream out) throws IOException {
        StringBuilder response = new StringBuilder()
                .append("HTTP/1.1 202 Accepted\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: 0\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(response.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private class ClientHandler implements Runnable {
        private final Socket mSocket;
        ClientHandler(Socket socket) { mSocket = socket; }

        @Override
        public void run() {
            try(InputStream in = mSocket.getInputStream();
                OutputStream out = mSocket.getOutputStream()) {
                byte[] buf = new byte[INPUT_BUFSIZE];
                int sofar = 0, req_size = 0;
                while(req_size <= 0) { /* read up to the end of the HTTP request headers */
                    int n = in.read(buf, sofar, buf.length - sofar);
                    if(n < 0) return;
                    sofar += n;
                    req_size = Utils.getEndOfHTTPHeaders(buf);
                }
                String method, path;
                int contentLength = 0;
                try(BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(buf, 0, req_size), StandardCharsets.US_ASCII))) {
                    String line = reader.readLine();
                    if(line == null) return;
                    StringTokenizer tk = new StringTokenizer(line);
                    method = tk.nextToken();
                    path = tk.nextToken();
                    String hdr;
                    while((hdr = reader.readLine()) != null) {
                        if(hdr.toLowerCase().startsWith("content-length:")) {
                            int sep = hdr.indexOf(':');
                            try {
                                contentLength = Integer.parseInt(hdr.substring(sep + 1).trim());
                                if(contentLength > INPUT_BUFSIZE) contentLength = INPUT_BUFSIZE;
                            } catch(NumberFormatException ignored) {}
                        }
                    }
                }
                byte[] body = new byte[contentLength];
                int read = 0;
                if(req_size < sofar) { /* use what is already buffered after the headers */
                    int avail = Math.min(contentLength, sofar - req_size);
                    System.arraycopy(buf, req_size, body, 0, avail);
                    read = avail;
                }
                while(read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if(n < 0) break;
                    read += n;
                }
                handleRequest(method, path, body, out, in, mSocket);
            } catch(IOException e) {
                Log.d(TAG, e.getLocalizedMessage());
            } finally {
                Utils.safeClose(mSocket);
            }
        }
    }
}
