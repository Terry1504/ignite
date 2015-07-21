/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.util.nio.ssl;

import org.apache.ignite.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.nio.*;
import org.apache.ignite.internal.util.typedef.internal.*;

import javax.net.ssl.*;
import javax.net.ssl.SSLEngineResult.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.*;
import static javax.net.ssl.SSLEngineResult.Status.*;
import static org.apache.ignite.internal.util.nio.ssl.GridNioSslFilter.*;

/**
 *
 */
public class BlockingSslHandler {
    /** Logger. */
    private IgniteLogger log;

    /** */
    private SocketChannel ch;

    /** */
    private GridFutureAdapter<ByteBuffer> fut;

    /** SSL engine. */
    private SSLEngine sslEngine;

    /** Handshake completion flag. */
    private boolean handshakeFinished;

    /** Engine handshake status. */
    private HandshakeStatus handshakeStatus;

    /** Output buffer into which encrypted data will be written. */
    private ByteBuffer outNetBuf;

    /** Input buffer from which SSL engine will decrypt data. */
    private ByteBuffer inNetBuf;

    /** Empty buffer used in handshake procedure.  */
    private ByteBuffer handshakeBuf = ByteBuffer.allocate(0);

    /** Application buffer. */
    private ByteBuffer appBuf;

    /**
     * @param sslEngine SSLEngine.
     * @param ch Socket channel.
     * @param fut Future.
     * @param log Logger.
     */
    public BlockingSslHandler(SSLEngine sslEngine, SocketChannel ch, GridFutureAdapter<ByteBuffer> fut,
        IgniteLogger log) throws SSLException {
        this.ch = ch;
        this.fut = fut;
        this.log = log;

        this.sslEngine = sslEngine;

        // Allocate a little bit more so SSL engine would not return buffer overflow status.
        int netBufSize = sslEngine.getSession().getPacketBufferSize() + 50;

        outNetBuf = ByteBuffer.allocate(netBufSize);
        inNetBuf = ByteBuffer.allocate(netBufSize);

        // Initially buffer is empty.
        outNetBuf.position(0);
        outNetBuf.limit(0);

        appBuf = allocateAppBuff();

        handshakeStatus = sslEngine.getHandshakeStatus();

        sslEngine.setUseClientMode(true);

        if (log.isDebugEnabled())
            log.debug("Started SSL session [netBufSize=" + netBufSize + ", appBufSize=" + appBuf.capacity() + ']');
    }

    /**
     * Performs handshake procedure with remote peer.
     *
     * @throws GridNioException If filter processing has thrown an exception.
     * @throws SSLException If failed to process SSL data.
     */
    public boolean handshake() throws IgniteCheckedException, SSLException {
        if (log.isDebugEnabled())
            log.debug("Entered handshake. Handshake status: " + handshakeStatus + '.');

        sslEngine.beginHandshake();

        handshakeStatus = sslEngine.getHandshakeStatus();

        boolean loop = true;

        while (loop) {
            switch (handshakeStatus) {
                case NOT_HANDSHAKING:
                case FINISHED: {
                    handshakeFinished = true;

                    if (fut != null) {
                        appBuf.flip();

                        fut.onDone(appBuf);
                    }

                    loop = false;

                    break;
                }

                case NEED_TASK: {
                    handshakeStatus = runTasks();

                    break;
                }

                case NEED_UNWRAP: {
                    Status status = unwrapHandshake();

                    handshakeStatus = sslEngine.getHandshakeStatus();

                    if (status == BUFFER_UNDERFLOW && sslEngine.isInboundDone())
                        // Either there is no enough data in buffer or session was closed.
                        loop = false;

                    break;
                }

                case NEED_WRAP: {
                    // If the output buffer has remaining data, clear it.
                    if (outNetBuf.hasRemaining())
                        U.warn(log, "Output net buffer has unsent bytes during handshake (will clear). ");

                    outNetBuf.clear();

                    SSLEngineResult res = sslEngine.wrap(handshakeBuf, outNetBuf);

                    outNetBuf.flip();

                    handshakeStatus = res.getHandshakeStatus();

                    if (log.isDebugEnabled())
                        log.debug("Wrapped handshake data [status=" + res.getStatus() + ", handshakeStatus=" +
                        handshakeStatus + ']');

                    writeNetBuffer();

                    break;
                }

                default: {
                    throw new IllegalStateException("Invalid handshake status in handshake method [handshakeStatus=" +
                        handshakeStatus + ']');
                }
            }
        }

        if (log.isDebugEnabled())
            log.debug("Leaved handshake. Handshake status:" + handshakeStatus + '.');

        return handshakeFinished;
    }

    /**
     * Encrypts data to be written to the network.
     *
     * @param src data to encrypt.
     * @throws SSLException on errors.
     * @return Output buffer with encrypted data.
     */
    public ByteBuffer encrypt(ByteBuffer src) throws SSLException {
        assert handshakeFinished;

        // The data buffer is (must be) empty, we can reuse the entire
        // buffer.
        outNetBuf.clear();

        // Loop until there is no more data in src
        while (src.hasRemaining()) {
            int outNetRemaining = outNetBuf.capacity() - outNetBuf.position();

            if (outNetRemaining < src.remaining() * 2) {
                outNetBuf = expandBuffer(outNetBuf, Math.max(
                    outNetBuf.position() + src.remaining() * 2, outNetBuf.capacity() * 2));

                if (log.isDebugEnabled())
                    log.debug("Expanded output net buffer: " + outNetBuf.capacity());
            }

            SSLEngineResult res = sslEngine.wrap(src, outNetBuf);

            if (log.isDebugEnabled())
                log.debug("Encrypted data [status=" + res.getStatus() + ", handshakeStaus=" +
                    res.getHandshakeStatus() + ']');

            if (res.getStatus() == OK) {
                if (res.getHandshakeStatus() == NEED_TASK)
                    runTasks();
            }
            else
                throw new SSLException("Failed to encrypt data (SSL engine error) [status=" + res.getStatus() +
                    ", handshakeStatus=" + res.getHandshakeStatus() + ']');
        }

        outNetBuf.flip();

        return outNetBuf;
    }

    /**
     * Called by SSL filter when new message was received.
     *
     * @param buf Received message.
     * @throws GridNioException If exception occurred while forwarding events to underlying filter.
     * @throws SSLException If failed to process SSL data.
     */
    public ByteBuffer decode(ByteBuffer buf) throws IgniteCheckedException, SSLException {
        inNetBuf.clear();

        if (buf.limit() > inNetBuf.remaining()) {
            inNetBuf = expandBuffer(inNetBuf, inNetBuf.capacity() + buf.limit() * 2);

            appBuf = expandBuffer(appBuf, inNetBuf.capacity() * 2);

            if (log.isDebugEnabled())
                log.debug("Expanded buffers [inNetBufCapacity=" + inNetBuf.capacity() + ", appBufCapacity=" +
                    appBuf.capacity() + ']');
        }

        // append buf to inNetBuffer
        inNetBuf.put(buf);

        if (!handshakeFinished)
            handshake();
        else
            unwrapData();

        if (isInboundDone()) {
            int newPosition = buf.position() - inNetBuf.position();

            if (newPosition >= 0) {
                buf.position(newPosition);

                // If we received close_notify but not all bytes has been read by SSL engine, print a warning.
                if (buf.hasRemaining())
                    U.warn(log, "Got unread bytes after receiving close_notify message (will ignore).");
            }

            inNetBuf.clear();
        }

        appBuf.flip();

        return appBuf;
    }

    /**
     * @return {@code True} if inbound data stream has ended, i.e. SSL engine received
     * <tt>close_notify</tt> message.
     */
    boolean isInboundDone() {
        return sslEngine.isInboundDone();
    }

    /**
     * Unwraps user data to the application buffer.
     *
     * @throws SSLException If failed to process SSL data.
     * @throws GridNioException If failed to pass events to the next filter.
     */
    private void unwrapData() throws IgniteCheckedException, SSLException {
        if (log.isDebugEnabled())
            log.debug("Unwrapping received data.");

        // Flip buffer so we can read it.
        inNetBuf.flip();

        SSLEngineResult res = unwrap0();

        // prepare to be written again
        inNetBuf.compact();

        checkStatus(res);

        renegotiateIfNeeded(res);
    }

    /**
     * Runs all tasks needed to continue SSL work.
     *
     * @return Handshake status after running all tasks.
     */
    private HandshakeStatus runTasks() {
        Runnable runnable;

        while ((runnable = sslEngine.getDelegatedTask()) != null) {
            if (log.isDebugEnabled())
                log.debug("Running SSL engine task: " + runnable + '.');

            runnable.run();
        }

        if (log.isDebugEnabled())
            log.debug("Finished running SSL engine tasks. HandshakeStatus: " + sslEngine.getHandshakeStatus());

        return sslEngine.getHandshakeStatus();
    }


    /**
     * Unwraps handshake data and processes it.
     *
     * @return Status.
     * @throws SSLException If SSL exception occurred while unwrapping.
     * @throws GridNioException If failed to pass event to the next filter.
     */
    private Status unwrapHandshake() throws SSLException, IgniteCheckedException {
        // Flip input buffer so we can read the collected data.
        readFromNet();

        inNetBuf.flip();

        SSLEngineResult res = unwrap0();
        handshakeStatus = res.getHandshakeStatus();

        checkStatus(res);

        // If handshake finished, no data was produced, and the status is still ok,
        // try to unwrap more
        if (handshakeStatus == FINISHED && res.getStatus() == OK && inNetBuf.hasRemaining()) {
            res = unwrap0();

            handshakeStatus = res.getHandshakeStatus();

            // prepare to be written again
            inNetBuf.compact();

            renegotiateIfNeeded(res);
        }
        else
            // prepare to be written again
            inNetBuf.compact();

        return res.getStatus();
    }

    /**
     * Performs raw unwrap from network read buffer.
     *
     * @return Result.
     * @throws SSLException If SSL exception occurs.
     */
    private SSLEngineResult unwrap0() throws SSLException {
        SSLEngineResult res;

        do {
            res = sslEngine.unwrap(inNetBuf, appBuf);

            if (log.isDebugEnabled())
                log.debug("Unwrapped raw data [status=" + res.getStatus() + ", handshakeStatus=" +
                    res.getHandshakeStatus() + ']');

            if (res.getStatus() == Status.BUFFER_OVERFLOW)
                appBuf = expandBuffer(appBuf, appBuf.capacity() * 2);
        }
        while ((res.getStatus() == OK || res.getStatus() == Status.BUFFER_OVERFLOW) &&
            (handshakeFinished && res.getHandshakeStatus() == NOT_HANDSHAKING
                || res.getHandshakeStatus() == NEED_UNWRAP));

        return res;
    }

    /**
     * @param res SSL engine result.
     * @throws SSLException If status is not acceptable.
     */
    private void checkStatus(SSLEngineResult res)
        throws SSLException {

        Status status = res.getStatus();

        if (status != OK && status != CLOSED && status != BUFFER_UNDERFLOW)
            throw new SSLException("Failed to unwrap incoming data (SSL engine error). Status: " + status);
    }

    /**
     * Check status and retry the negotiation process if needed.
     *
     * @param res Result.
     * @throws GridNioException If exception occurred during handshake.
     * @throws SSLException If failed to process SSL data
     */
    private void renegotiateIfNeeded(SSLEngineResult res) throws IgniteCheckedException, SSLException {
        if (res.getStatus() != CLOSED && res.getStatus() != BUFFER_UNDERFLOW
            && res.getHandshakeStatus() != NOT_HANDSHAKING) {
            // Renegotiation required.
            handshakeStatus = res.getHandshakeStatus();

            if (log.isDebugEnabled())
                log.debug("Renegotiation requested [status=" + res.getStatus() + ", handshakeStatus = " +
                    handshakeStatus + ']');

            handshakeFinished = false;

            handshake();
        }
    }

    /**
     * Allocate application buffer.
     */
    private ByteBuffer allocateAppBuff() {
        int netBufSize = sslEngine.getSession().getPacketBufferSize() + 50;

        int appBufSize = Math.max(sslEngine.getSession().getApplicationBufferSize() + 50, netBufSize * 2);

        return ByteBuffer.allocate(appBufSize);
    }

    /**
     * Read data from net buffer.
     */
    private void readFromNet() {
        try {
            inNetBuf.clear();

            ch.read(inNetBuf);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Copies data from out net buffer and passes it to the underlying chain.
     *
     * @return Nothing.
     * @throws GridNioException If send failed.
     */
    private void writeNetBuffer() throws IgniteCheckedException {
        try {
            ch.write(outNetBuf);
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Failed to write byte to socket.", e);
        }
    }
}
