/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.io;

import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;
import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;
import org.xnio.channels.Channels;
import org.xnio.channels.EmptyStreamSourceChannel;
import org.xnio.channels.StreamSourceChannel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.xnio.Bits.allAreClear;
import static org.xnio.Bits.anyAreSet;

/**
 * Input stream that reads from the underlying channel. This stream delays creation
 * of the channel till it is actually used.
 *
 * @author Stuart Douglas
 */
public class UndertowInputStream extends InputStream {

    private final StreamSourceChannel channel;
    private final ByteBufferPool bufferPool;

    /**
     * If this stream is ready for a read
     */
    private static final int FLAG_CLOSED = 1;
    private static final int FLAG_FINISHED = 1 << 1;

    private int state;
    private PooledByteBuffer pooled;

    public UndertowInputStream(final HttpServerExchange exchange) {
        if (exchange.isRequestChannelAvailable()) {
            this.channel = exchange.getRequestChannel();
        } else {
            this.channel = new EmptyStreamSourceChannel(exchange.getIoThread());
        }
        this.bufferPool = exchange.getConnection().getByteBufferPool();
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int read = read(b);
        if (read == -1) {
            return -1;
        }
        return b[0] & 0xff;
    }

    @Override
    public int read(final byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if(Thread.currentThread() == channel.getIoThread()) {
            throw UndertowMessages.MESSAGES.blockingIoFromIOThread();
        }
        if (anyAreSet(state, FLAG_CLOSED)) {
            throw UndertowMessages.MESSAGES.streamIsClosed();
        }
        readIntoBuffer();
        if (anyAreSet(state, FLAG_FINISHED)) {
            return -1;
        }
        if (len == 0) {
            return 0;
        }
        ByteBuffer buffer = pooled.getBuffer();
        int copied = Math.min(buffer.remaining(), len);
        buffer.get(b, off, copied);
        if (!buffer.hasRemaining()) {
            pooled.close();
            pooled = null;
        }
        return copied;
    }

    private void readIntoBuffer() throws IOException {
        if (pooled == null && !anyAreSet(state, FLAG_FINISHED)) {
            pooled = bufferPool.allocate();

            int res = Channels.readBlocking(channel, pooled.getBuffer());
            pooled.getBuffer().flip();
            if (res == -1) {
                state |= FLAG_FINISHED;
                pooled.close();
                pooled = null;
            }
        }
    }

    private void readIntoBufferNonBlocking() throws IOException {
        if (pooled == null && !anyAreSet(state, FLAG_FINISHED)) {
            pooled = bufferPool.allocate();
            int res = channel.read(pooled.getBuffer());
            if (res == 0) {
                pooled.close();
                pooled = null;
                return;
            }
            pooled.getBuffer().flip();
            if (res == -1) {
                state |= FLAG_FINISHED;
                pooled.close();
                pooled = null;
            }
        }
    }

    @Override
    public int available() throws IOException {
        if (anyAreSet(state, FLAG_CLOSED)) {
            throw UndertowMessages.MESSAGES.streamIsClosed();
        }
        readIntoBufferNonBlocking();
        if (anyAreSet(state, FLAG_FINISHED)) {
            return -1;
        }
        if (pooled == null) {
            return 0;
        }
        return pooled.getBuffer().remaining();
    }

    @Override
    public void close() throws IOException {
        if (anyAreSet(state, FLAG_CLOSED)) {
            return;
        }
        state |= FLAG_CLOSED;
        try {
            while (allAreClear(state, FLAG_FINISHED)) {
                readIntoBuffer();
                if (pooled != null) {
                    pooled.close();
                    pooled = null;
                }
            }
        } finally {
            if (pooled != null) {
                pooled.close();
                pooled = null;
            }
            channel.shutdownReads();
            state |= FLAG_FINISHED;
        }
    }
}
