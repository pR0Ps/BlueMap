/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.common.web.http;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;


/**
 * Implements WebSockets (RFC 6455) over an already-established TCP socket.
 */
public class WebSocketConnection implements Closeable {

    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // define a sensible max data size limit (note this is lower than the *actual* limit)
    private static final int MAX_FRAME_PAYLOAD = 65536;  // 64KB  (actual max: 1<<31)

    // masks for frame/opcode data
    private static final int FRAME_FIN            = 0x80;
    private static final int FRAME_RSV            = 0x70;
    private static final int FRAME_OPCODE         = 0x0F;
    private static final int FRAME_MASKED_PAYLOAD = 0x80;
    private static final int FRAME_PAYLOAD_LEN    = 0x7F;
    private static final int OPCODE_IS_CONTROL    = 0x08;

    // opcodes
    private static final int OPCODE_CONTINUATION = 0x0;
    private static final int OPCODE_TEXT         = 0x1;
    private static final int OPCODE_BINARY       = 0x2;
    private static final int OPCODE_CLOSE        = 0x8;
    private static final int OPCODE_PING         = 0x9;
    private static final int OPCODE_PONG         = 0xA;

    // sentinels for payload length
    private static final int PAYLOAD_LEN_16BIT = 126;
    private static final int PAYLOAD_LEN_64BIT = 127;

    // common close status codes
    private static final int CLOSE_NORMAL           = 1000;
    private static final int CLOSE_GOING_AWAY       = 1001;
    private static final int CLOSE_PROTOCOL_ERROR   = 1002;
    private static final int CLOSE_INVALID_DATATYPE = 1003;
    private static final int CLOSE_INVALID_DATA     = 1007;
    private static final int CLOSE_BAD_REQUEST      = 1008;
    private static final int CLOSE_MESSAGE_TOO_BIG  = 1009;
    private static final int CLOSE_UNEXPECTED       = 1011;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean closed = false;

    // fragmented frames state
    private ByteArrayOutputStream fragmentBuffer = null;
    private int fragmentOpcode = -1;

    private WebSocketConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * Perform the WebSocket upgrade handshake
     */
    public static WebSocketConnection upgrade(Socket socket, HttpRequest request) throws IOException {
        HttpHeader keyHeader = request.getHeader("Sec-WebSocket-Key");
        if (keyHeader == null) throw new IOException("WebSocket upgrade missing Sec-WebSocket-Key header");

        // compute the accept header
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        };
        String accept = Base64.getEncoder().encodeToString(
            sha1.digest((keyHeader.getValue().trim() + WS_MAGIC).getBytes(StandardCharsets.UTF_8))
        );

        String handshake =
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + accept + "\r\n" +
            "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        socket.setSoTimeout(0);  // persistent connection - no idle timeout
        return new WebSocketConnection(socket);
    }

    /**
     * Serialize an int to length bytes (big/network endianness)
     */
    private static byte[] intToBytes(int value, int length) {
        byte[] bytes = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return bytes;
    }

    /**
     * Decode a long int from bytes (big/network endianness)
     */
    private static long bytesToInt(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFFL);
        }
        return value;
    }

    /**
     * Read a long int from an InputStream (big/network endianness)
     */
    private static long readInt(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Unexpected end of stream");

        return bytesToInt(bytes);
    }

    /**
     * Called by {@link #readLoop()} when a complete message has been received.
     * Subclasses can override this to process the incoming data.
     */
    protected void onMessage(int opcode, byte[] payload) {}

    /**
     * Blocks and reads client frames until the connection is closed.
     * Will:
     *  - Handle fragmented messages
     *  - Respond to PING frames with PONG
     *  - Call {@link #onMessage(int, byte[])} for each complete received message
     */
    public void readLoop() {
        try {
            while (!isClosed()) {
                // read frame header
                int b0 = in.read();
                int b1 = in.read();
                if (b0 == -1 || b1 == -1) break;

                if ((b0 & FRAME_RSV) != 0){
                    // no negotiated extensions but RSV1/2/3 set - error
                    sendClose(CLOSE_PROTOCOL_ERROR);
                }

                // get payload len
                long payloadLen = b1 & FRAME_PAYLOAD_LEN;
                if (payloadLen == PAYLOAD_LEN_16BIT) {
                    payloadLen = readInt(in, 2);
                } else if (payloadLen == PAYLOAD_LEN_64BIT) {
                    payloadLen = readInt(in, 8);
                }
                if (payloadLen > MAX_FRAME_PAYLOAD) {
                    sendClose(CLOSE_MESSAGE_TOO_BIG);
                    return;
                }

                // get payload data
                byte[] maskKey = (b1 & FRAME_MASKED_PAYLOAD) != 0 ? in.readNBytes(4) : null;
                byte[] payload = in.readNBytes((int) payloadLen);
                if (payload.length != payloadLen) throw new EOFException("Unexpected end of stream");
                if (maskKey != null) {
                    for (int i = 0; i < payload.length; i++)
                        payload[i] ^= maskKey[i % 4];
                }

                // process framing protocol
                int opcode = b0 & FRAME_OPCODE;
                boolean isFin = (b0 & FRAME_FIN) != 0;

                if ((opcode & OPCODE_IS_CONTROL) != 0) {  // control frame
                    if (!isFin || payloadLen >= PAYLOAD_LEN_16BIT){
                        // fragmented or oversized control frame - error
                        sendClose(CLOSE_PROTOCOL_ERROR);
                        return;
                    }
                    switch (opcode) {
                        case OPCODE_CLOSE -> {
                            sendClose();  // TODO: echo received status code
                            return;
                        }
                        case OPCODE_PING -> sendPong(payload);
                        default -> {}  // ignore OPCODE_PONG and unknown control frames
                    }
                } else if (opcode == OPCODE_CONTINUATION) {  // continuation frame
                    if (fragmentBuffer == null) {
                        // nothing to continue - error
                        sendClose(CLOSE_PROTOCOL_ERROR);
                        return;
                    }
                    fragmentBuffer.write(payload);
                    if (isFin) {
                        // final fragment - process it and reset fragment state
                        onMessage(fragmentOpcode, fragmentBuffer.toByteArray());
                        fragmentBuffer = null;
                        fragmentOpcode = -1;
                    }

                } else {  // data frame
                    if (fragmentBuffer != null) {
                        // data frame while processing a continuation - error
                        sendClose(CLOSE_PROTOCOL_ERROR);
                        return;
                    }
                    if (isFin) {
                        // unfragmented message
                        onMessage(opcode, payload);
                    } else {
                        // first of a fragmented message - update state
                        fragmentOpcode = opcode;
                        fragmentBuffer = new ByteArrayOutputStream();
                        fragmentBuffer.write(payload);
                    }
                }
            }
        } catch (IOException ignore) {
            // Normal when the socket is closed from either side
        }
    }

    /**
     * Low-level function to write a frame to the connection.
     * @param firstByte contains FIN and/or the opcode
     * @param payload is the actual data to send
     */
    private synchronized void writeFrame(int firstByte, byte[] payload) throws IOException {
        if (closed) throw new IOException("WebSocket connection is closed");

        int len = payload.length;

        out.write(firstByte);
        if (len < PAYLOAD_LEN_16BIT) {
            out.write(len);
        } else if (len < 1<<16) {
            out.write(PAYLOAD_LEN_16BIT);
            out.write(intToBytes(len, 2));
        } else {
            // max size of java array is 64bit so this won't overflow
            out.write(PAYLOAD_LEN_64BIT);
            out.write(intToBytes(len, 8));
        }
        out.write(payload);
        out.flush();
    }

    /**
     * Send a utf8-encoded message in a websocket text frame
     */
    public synchronized void send(String message) throws IOException {
        writeFrame(FRAME_FIN | OPCODE_TEXT, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Send binary data in a websocket binary frame
     */
    public synchronized void send(byte[] data) throws IOException {
        writeFrame(FRAME_FIN | OPCODE_BINARY, data);
    }

    /**
     * Send a close frame
     */
    private synchronized void sendClose() {
        try {
            writeFrame(FRAME_FIN | OPCODE_CLOSE, new byte[0]);
        } catch (IOException ignore) {}
        closed = true;
    }

    /**
     * Send a close frame with a status code
     */
    private synchronized void sendClose(int statusCode) {
        // TODO: support sending an optional reason (utf8-encoded string after the status code)
        if (statusCode < 1000 || statusCode >= 5000 ){
            throw new IllegalArgumentException("statusCode must be 1000-4999");
        }

        try {
            writeFrame(FRAME_FIN | OPCODE_CLOSE, intToBytes(statusCode, 2));
        } catch (IOException ignore) {}
        closed = true;
    }

    /**
     * Send a PONG to the client (in response to a PING)
     */
    private synchronized void sendPong(byte[] payload) throws IOException {
        writeFrame(FRAME_FIN | OPCODE_PONG, payload);
    }

    /**
     * Check if the websocket is closed
     */
    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    /**
     * Close the websocket.
     * NOTE: will *not* send a close frame, use {@link #sendClose(int)} to do that first.
     */
    @Override
    public void close() throws IOException {
        closed = true;
        socket.close();
    }

}
