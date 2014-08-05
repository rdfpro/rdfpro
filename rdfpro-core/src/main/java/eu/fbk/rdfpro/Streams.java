/*
 * RDFpro - An extensible tool for building stream-oriented RDF processing libraries.
 * 
 * Written in 2014 by Francesco Corcoglioniti <francesco.corcoglioniti@gmail.com> with support by
 * Marco Rospocher, Marco Amadori and Michele Mostarda.
 * 
 * To the extent possible under law, the author has dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is
 * distributed without any warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package eu.fbk.rdfpro;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.ProcessBuilder.Redirect;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Streams {

    private static final Logger LOGGER = LoggerFactory.getLogger(Streams.class);

    // sequential read performances varying buffer size on test TQL file
    // 8k - 380781 tr/s
    // 16k - 383115 tr/s
    // 32k - 386237 tr/s
    // 64k - 394210 tr/s
    // 128k - 398547 tr/s
    // 256k - 396883 tr/s
    // 512k - 389184 tr/s
    // 1M - 383701 tr/s
    // note: pipe buffer on linux is 64k

    private static final int BUFFER_SIZE = Integer.parseInt(Util.settingFor("rdfpro.buffer.size",
            "" + 64 * 1024));

    // parallel read performances varying queue size on test TQL file, buffer = 64K
    // 16 * 64k (1M) - 603k tr/s
    // 64 * 64k (4M) - 605k-616k tr/s
    // 128 * 64k (8M) - 601k-618k tr/s
    // 256 * 64k (16M) - 624k-631k tr/s
    // 1024 * 64k (64M) - 625k tr/s

    private static final int BUFFER_NUM_READ = Integer.parseInt(Util.settingFor(
            "rdfpro.buffer.numr", "256"));

    private static final int BUFFER_NUM_WRITE = Integer.parseInt(Util.settingFor(
            "rdfpro.buffer.numw", "16"));

    public static InputStream read(@Nullable final File file) throws IOException {

        InputStream in = System.in;

        if (file != null) {
            final String name = file.getName();

            String cmd = null;
            if (name.endsWith(".bz2")) {
                cmd = Util.settingFor("rdfpro.cmd.bzip2", "bzip2") + " -dck";
            } else if (name.endsWith(".gz")) {
                cmd = Util.settingFor("rdfpro.cmd.gzip", "gzip") + " -dc";
            } else if (name.endsWith(".xz")) {
                cmd = Util.settingFor("rdfpro.cmd.xz", "xz") + " -dc";
            } else if (name.endsWith(".7z")) {
                cmd = Util.settingFor("rdfpro.cmd.7za", "7za") + " -so e";
            }

            if (cmd == null) {
                in = new FileInputStream(file);
                LOGGER.debug("Reading from {}", file);
            } else {
                cmd += " " + file.getAbsolutePath();
                final Process process = new ProcessBuilder(cmd.split("\\s+")) //
                        .redirectError(Redirect.INHERIT).start();
                in = process.getInputStream();
                LOGGER.debug("Reading from {} using {}", file, cmd);
            }
        } else {
            LOGGER.debug("Reading from STDIN");
        }

        return in;
    }

    public static OutputStream write(@Nullable final File file) throws IOException {

        OutputStream out = System.out;

        if (file != null) {
            final String name = file.getName();

            String cmd = null;
            if (name.endsWith(".bz2")) {
                cmd = Util.settingFor("rdfpro.cmd.bzip2", "bzip2") + " -c -9";
            } else if (name.endsWith(".gz")) {
                cmd = Util.settingFor("rdfpro.cmd.gzip", "gzip") + " -c -9";
            } else if (name.endsWith(".xz")) {
                cmd = Util.settingFor("rdfpro.cmd.xz", "xz") + " -c -9";
            }

            if (cmd == null) {
                out = new FileOutputStream(file);
                LOGGER.debug("Writing to {}", file);
            } else {
                final Process process = new ProcessBuilder(cmd.split("\\s+")) //
                        .redirectOutput(file).redirectError(Redirect.INHERIT).start();
                out = process.getOutputStream();
                LOGGER.debug("Writing to {} using {}", file, cmd);
            }
        } else {
            LOGGER.debug("Writing to STDOUT");
        }

        return out;
    }

    public static InputStream buffer(final InputStream stream) {
        return new SimpleBufferedInputStream(stream);
    }

    public static OutputStream buffer(final OutputStream stream) {
        return new SimpleBufferedOutputStream(stream);
    }

    @SuppressWarnings("resource")
    public static Reader buffer(final Reader reader, @Nullable final Character fetchDelimiter) {
        return fetchDelimiter != null ? new NewlineBufferedReader(reader, fetchDelimiter)
                : new SimpleBufferedReader(reader);
    }

    @SuppressWarnings("resource")
    public static Writer buffer(final Writer writer, @Nullable final Character flushDelimiter) {
        return flushDelimiter != null ? new NewlineBufferedWriter(writer, flushDelimiter)
                : new SimpleBufferedWriter(writer);
    }

    private Streams() {
    }

    private static final class SimpleBufferedInputStream extends InputStream {

        private final InputStream stream;

        private final byte buffer[];

        private int count;

        private int pos;

        private volatile boolean closed;

        public SimpleBufferedInputStream(final InputStream stream) {
            this.stream = Util.checkNotNull(stream);
            this.buffer = new byte[BUFFER_SIZE];
            this.count = 0;
            this.pos = 0;
            this.closed = false;
        }

        @Override
        public int read() throws IOException {
            if (this.pos >= this.count) {
                fill();
                if (this.pos >= this.count) {
                    return -1;
                }
            }
            return this.buffer[this.pos++] & 0xFF;
        }

        @Override
        public int read(final byte buf[], int off, int len) throws IOException {
            if ((off | len | off + len | buf.length - (off + len)) < 0) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                checkNotClosed();
                return 0;
            }
            int result = 0;
            while (true) {
                final int available = this.count - this.pos;
                if (available > 0) {
                    final int n = available > len ? len : available;
                    System.arraycopy(this.buffer, this.pos, buf, off, n);
                    this.pos += n;
                    off += n;
                    len -= n;
                    result += n;
                    if (len == 0 || this.stream.available() == 0) {
                        return result;
                    }
                }
                if (len >= BUFFER_SIZE) {
                    final int n = this.stream.read(buf, off, len);
                    result += n < 0 ? 0 : n;
                    return result == 0 ? -1 : result;
                } else if (len > 0) {
                    fill();
                    if (this.count == 0) {
                        return result == 0 ? -1 : result;
                    }
                }
            }
        }

        @Override
        public long skip(final long n) throws IOException {
            if (n <= 0) {
                checkNotClosed();
                return 0;
            }
            final int available = this.count - this.pos;
            if (available <= 0) {
                return this.stream.skip(n);
            }
            final long skipped = available < n ? available : n;
            this.pos += skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            final int n = this.count - this.pos;
            final int available = this.stream.available();
            return n > Integer.MAX_VALUE - available ? Integer.MAX_VALUE : n + available;
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("Mark not supported");
        }

        @Override
        public void mark(final int readlimit) {
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() throws IOException {
            if (!this.closed) {
                this.closed = true;
                this.count = this.pos; // fail soon in case a new write request is received
                this.stream.close();
            }
        }

        private void fill() throws IOException {
            checkNotClosed();
            final int n = this.stream.read(this.buffer);
            this.count = n < 0 ? 0 : n;
            this.pos = 0;
        }

        private void checkNotClosed() throws IOException {
            if (this.closed) {
                throw new IOException("Stream has been closed");
            }
        }

    }

    private static final class SimpleBufferedOutputStream extends OutputStream {

        private final OutputStream stream;

        private final byte[] buffer;

        private int count; // num of bytes in the buffer

        SimpleBufferedOutputStream(final OutputStream stream) {
            this.stream = Util.checkNotNull(stream);
            this.buffer = new byte[BUFFER_SIZE];
            this.count = 0;
        }

        @Override
        public void write(final int b) throws IOException {
            if (this.count >= BUFFER_SIZE) {
                flushBuffer();
            }
            this.buffer[this.count++] = (byte) b;
        }

        @Override
        public void write(final byte buf[], int off, int len) throws IOException {
            if (len >= BUFFER_SIZE) {
                flushBuffer();
                this.stream.write(buf, off, len);
                return;
            }
            final int available = BUFFER_SIZE - this.count;
            if (available < len) {
                System.arraycopy(buf, off, this.buffer, this.count, available);
                this.count += available;
                off += available;
                len -= available;
                flushBuffer();
            }
            System.arraycopy(buf, off, this.buffer, this.count, len);
            this.count += len;
        }

        @Override
        public void flush() throws IOException {
            flushBuffer();
            this.stream.flush();
        }

        @Override
        public void close() throws IOException {
            flushBuffer();
            this.stream.close();
            this.count = BUFFER_SIZE; // fail soon in case a new write request is received
        }

        private void flushBuffer() throws IOException {
            if (this.count > 0) {
                this.stream.write(this.buffer, 0, this.count);
                this.count = 0;
            }
        }

    }

    private static final class SimpleBufferedReader extends Reader {

        private final Reader reader;

        private final char[] buffer;

        private int count;

        private int pos;

        private volatile boolean closed;

        public SimpleBufferedReader(final Reader reader) {
            this.reader = reader;
            this.buffer = new char[BUFFER_SIZE];
            this.count = 0;
            this.pos = 0;
            this.closed = false;
        }

        @Override
        public int read() throws IOException {
            if (this.pos >= this.count) {
                fill();
                if (this.pos >= this.count) {
                    return -1;
                }
            }
            return this.buffer[this.pos++] & 0xFFFF;
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) throws IOException {
            if ((off | len | off + len | cbuf.length - (off + len)) < 0) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                checkNotClosed();
                return 0;
            }
            int available = this.count - this.pos;
            if (available == 0) {
                if (len >= BUFFER_SIZE) {
                    return this.reader.read(cbuf, off, len);
                } else {
                    fill();
                    available = this.count - this.pos;
                    if (available == 0) {
                        return -1;
                    }
                }
            }
            final int n = available > len ? len : available;
            System.arraycopy(this.buffer, this.pos, cbuf, off, n);
            this.pos += n;
            return n;
        }

        @Override
        public long skip(final long n) throws IOException {
            if (n <= 0) {
                checkNotClosed();
                return 0;
            }
            final int available = this.count - this.pos;
            if (available == 0) {
                return this.reader.skip(n);
            }
            final long skipped = available < n ? available : n;
            this.pos += skipped;
            return skipped;
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("Mark not supported");
        }

        @Override
        public void mark(final int readlimit) {
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() throws IOException {
            if (!this.closed) {
                this.closed = true;
                this.count = this.pos; // fail soon in case a new write request is received
                this.reader.close();
            }
        }

        private void fill() throws IOException {
            checkNotClosed();
            final int n = this.reader.read(this.buffer);
            this.count = n < 0 ? 0 : n;
            this.pos = 0;
        }

        private void checkNotClosed() throws IOException {
            if (this.closed) {
                throw new IOException("Reader has been closed");
            }
        }

    }

    private static final class SimpleBufferedWriter extends Writer {

        private final Writer writer;

        private final char[] buffer;

        private int count; // num of chars in the buffer

        SimpleBufferedWriter(final Writer writer) {
            this.writer = Util.checkNotNull(writer);
            this.buffer = new char[BUFFER_SIZE];
            this.count = 0;
        }

        @Override
        public void write(final int c) throws IOException {
            if (this.count >= BUFFER_SIZE) {
                flushBuffer();
            }
            this.buffer[this.count++] = (char) c;
        }

        @Override
        public void write(final char[] cbuf, int off, int len) throws IOException {
            if (len >= BUFFER_SIZE) {
                flushBuffer();
                this.writer.write(cbuf, off, len);
                return;
            }
            final int available = BUFFER_SIZE - this.count;
            if (available < len) {
                System.arraycopy(cbuf, off, this.buffer, this.count, available);
                this.count += available;
                off += available;
                len -= available;
                flushBuffer();
            }
            System.arraycopy(cbuf, off, this.buffer, this.count, len);
            this.count += len;
        }

        @Override
        public void write(final String str, int off, int len) throws IOException {
            if (len >= BUFFER_SIZE) {
                flushBuffer();
                this.writer.write(str, off, len);
                return;
            }
            final int available = BUFFER_SIZE - this.count;
            if (available < len) {
                str.getChars(off, off + available, this.buffer, this.count);
                this.count += available;
                off += available;
                len -= available;
                flushBuffer();
            }
            str.getChars(off, off + len, this.buffer, this.count);
            this.count += len;
        }

        @Override
        public void flush() throws IOException {
            flushBuffer();
            this.writer.flush();
        }

        @Override
        public void close() throws IOException {
            flushBuffer();
            this.writer.close();
            this.count = BUFFER_SIZE; // fail soon in case a new write request is received
        }

        private void flushBuffer() throws IOException {
            if (this.count > 0) {
                this.writer.write(this.buffer, 0, this.count);
                this.count = 0;
            }
        }

    }

    private static final class NewlineBufferedReader extends Reader {

        private final Fetcher fetcher;

        private final List<CharBuffer> buffers;

        private int index;

        private char[] buffer;

        private int count;

        private int pos;

        private volatile boolean closed;

        NewlineBufferedReader(final Reader reader, final char delimiter) {
            this.fetcher = Fetcher.forReader(reader, delimiter);
            this.buffers = new ArrayList<CharBuffer>();
            this.index = 0;
            this.buffer = null;
            this.count = 0;
            this.pos = 0;
            this.closed = false;
            this.fetcher.open();
        }

        @Override
        public int read() throws IOException {
            if (this.pos >= this.count) {
                fill();
                if (this.count == 0) {
                    return -1;
                }
            }
            return this.buffer[this.pos++] & 0xFFFF;
        }

        @Override
        public int read(final char[] cbuf, final int off, final int len) throws IOException {
            if ((off | len | off + len | cbuf.length - (off + len)) < 0) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                checkNotClosed();
                return 0;
            }
            final int available = this.count - this.pos;
            if (available == 0) {
                fill();
                if (this.count == 0) {
                    return -1;
                }
            }
            final int n = available > len ? len : available;
            System.arraycopy(this.buffer, this.pos, cbuf, off, n);
            this.pos += n;
            return n;
        }

        @Override
        public long skip(final long n) throws IOException {
            if (n <= 0) {
                checkNotClosed();
                return 0;
            }
            int available = this.count - this.pos;
            if (available == 0) {
                fill();
                available = this.count;
            }
            final long skipped = available < n ? available : n;
            this.pos += skipped;
            return skipped;
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("Mark not supported");
        }

        @Override
        public void mark(final int readlimit) {
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() throws IOException {
            if (!this.closed) {
                this.closed = true;
                this.count = this.pos;
                this.fetcher.close();
            }
        }

        private void fill() throws IOException {
            checkNotClosed();
            if (this.buffer != null) {
                this.buffer = null;
                this.pos = 0;
                this.count = 0;
            }
            if (this.index == this.buffers.size()) {
                this.fetcher.fetch(this.buffers);
                this.index = 0;
            }
            if (this.index < this.buffers.size()) {
                final CharBuffer cb = this.buffers.get(this.index++);
                this.buffer = cb.array();
                this.count = cb.limit();
            }
        }

        private void checkNotClosed() throws IOException {
            if (this.closed) {
                throw new IOException("Reader has been closed");
            }
        }

        private static final class Fetcher implements Runnable {

            private static final Map<Reader, Fetcher> FETCHERS = new WeakHashMap<Reader, Fetcher>();

            private static final Object EOF = new Object();

            private final BlockingQueue<Object> queue;

            private final Reader reader;

            private final char delimiter;

            private final List<CharBuffer> buffers;

            private int references;

            private Throwable exception;

            private final CountDownLatch latch;

            private Fetcher(final Reader reader, final char delimiter) {
                this.queue = new ArrayBlockingQueue<Object>(BUFFER_NUM_READ, false);
                this.reader = reader;
                this.delimiter = delimiter;
                this.buffers = new ArrayList<CharBuffer>();
                this.references = 0;
                this.exception = null;
                this.latch = new CountDownLatch(1);

                Threads.getMiscPool().submit(this);
            }

            private void release(final CharBuffer buffer) {
                synchronized (this.buffers) {
                    if (this.buffers.size() < BUFFER_NUM_READ + Threads.CORES + 1) {
                        buffer.clear();
                        this.buffers.add(buffer);
                    }
                }
            }

            private CharBuffer allocate() {
                synchronized (this.buffers) {
                    if (!this.buffers.isEmpty()) {
                        return this.buffers.remove(this.buffers.size() - 1);
                    }
                }
                return CharBuffer.allocate(BUFFER_SIZE);
            }

            public void open() {
                synchronized (this) {
                    if (this.references < 0) {
                        throw new IllegalStateException("Reader has been closed");
                    }
                    ++this.references;
                }
            }

            public void close() throws IOException {
                synchronized (this) {
                    --this.references;
                    if (this.references != 0) {
                        return;
                    }
                    this.references = -1; // prevent further open() to occur
                }
                this.queue.clear(); // nobody will use queued buffers
                while (true) {
                    try {
                        this.latch.await();
                        break;
                    } catch (final InterruptedException ex) {
                        // ignore
                    }
                }
                synchronized (this) {
                    if (this.exception != null) {
                        Util.propagateIfPossible(this.exception, IOException.class);
                        throw new IOException(this.exception);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            public void fetch(final List<CharBuffer> buffers) throws IOException {
                try {
                    synchronized (this) {
                        if (this.exception != null) {
                            throw this.exception;
                        }
                    }
                    for (final CharBuffer buffer : buffers) {
                        release(buffer);
                    }
                    buffers.clear();
                    final Object object = this.queue.take();
                    if (object == EOF) {
                        this.queue.add(EOF);
                        return;
                    }
                    buffers.addAll((List<CharBuffer>) object);
                } catch (final Throwable ex) {
                    Util.propagateIfPossible(ex, IOException.class);
                    throw new IOException(ex);
                }
            }

            @Override
            public void run() {

                try {
                    CharBuffer restBuffer = allocate();
                    List<CharBuffer> buffers = new ArrayList<CharBuffer>();

                    boolean eof = false;
                    while (!eof) {

                        synchronized (this) {
                            if (this.references < 0) {
                                break;
                            }
                        }

                        final CharBuffer curBuffer = restBuffer;
                        while (!eof && curBuffer.hasRemaining()) {
                            final int n = this.reader.read(curBuffer);
                            eof = n < 0;
                        }
                        curBuffer.flip();
                        buffers.add(curBuffer);

                        restBuffer = allocate();
                        if (!eof) {
                            final char[] curChars = curBuffer.array();
                            final int curLastIndex = curBuffer.limit() - 1;
                            for (int i = curLastIndex; i >= 0; --i) {
                                if (curChars[i] == this.delimiter) {
                                    restBuffer.position(curLastIndex - i);
                                    System.arraycopy(curChars, i + 1, restBuffer.array(), 0,
                                            restBuffer.position());
                                    curBuffer.limit(i + 1);
                                    this.queue.put(buffers);
                                    buffers = new ArrayList<CharBuffer>();
                                    break;
                                }
                            }
                        }
                    }

                    this.queue.put(buffers);

                } catch (final Throwable ex) {
                    synchronized (this) {
                        this.exception = ex;
                    }
                }

                try {
                    Util.closeQuietly(this.reader);

                    while (true) {
                        try {
                            this.queue.put(EOF);
                            break;
                        } catch (final InterruptedException ex) {
                            // ignore
                        }
                    }
                } finally {
                    this.latch.countDown();
                }
            }

            public static Fetcher forReader(final Reader reader, final char delimiter) {
                synchronized (FETCHERS) {
                    Fetcher manager = FETCHERS.get(reader);
                    if (manager == null) {
                        manager = new Fetcher(reader, delimiter);
                        FETCHERS.put(reader, manager);
                    } else if (manager.delimiter != delimiter) {
                        throw new IllegalStateException("Already reading from reader " + reader
                                + " using delimiter " + delimiter);
                    }
                    return manager;
                }
            }

        }

    }

    private static final class NewlineBufferedWriter extends Writer {

        private final Emitter emitter;

        private final char delimiter;

        private final List<CharBuffer> buffers;

        private char[] buffer;

        private int count; // from 0 to BUFFER_SIZE

        private int threshold;

        private volatile boolean closed;

        NewlineBufferedWriter(final Writer writer, final char delimiter) {
            this.emitter = Emitter.forWriter(writer);
            this.delimiter = delimiter;
            this.buffers = new ArrayList<CharBuffer>();
            this.buffer = new char[2 * BUFFER_SIZE];
            this.count = 0;
            this.threshold = BUFFER_SIZE;
            this.emitter.open();
        }

        @Override
        public void write(final int c) throws IOException {
            if (this.count < this.threshold) {
                this.buffer[this.count++] = (char) c;
            } else {
                writeAndTryFlush((char) c);
            }
        }

        @Override
        public void write(final char[] cbuf, int off, int len) throws IOException {
            final int available = this.threshold - this.count;
            if (available >= len) {
                System.arraycopy(cbuf, off, this.buffer, this.count, len);
                this.count += len;
                return;
            }
            if (available > 0) {
                System.arraycopy(cbuf, off, this.buffer, this.count, available);
                this.count += available;
                off += available;
                len -= available;
            }
            final int end = off + len;
            while (off < end) {
                writeAndTryFlush(cbuf[off++]);
            }
        }

        @Override
        public void write(final String str, int off, int len) throws IOException {
            final int available = this.threshold - this.count;
            final int end = off + len;
            if (available >= len) {
                str.getChars(off, end, this.buffer, this.count);
                this.count += len;
                return;
            }
            if (available > 0) {
                str.getChars(off, off + available, this.buffer, this.count);
                this.count += available;
                off += available;
                len -= available;
            }
            while (off < end) {
                writeAndTryFlush(str.charAt(off++));
            }
        }

        @Override
        public void flush() throws IOException {
            flushBuffers();
        }

        @Override
        public void close() throws IOException {
            if (!this.closed) {
                flushBuffers();
                this.closed = true;
                this.emitter.close();
            }
        }

        private void writeAndTryFlush(final char c) throws IOException {
            this.buffer[this.count++] = c;
            if (c == this.delimiter) {
                flushBuffers();
            } else if (this.count == this.buffer.length) {
                checkNotClosed();
                this.buffers.add(CharBuffer.wrap(this.buffer));
                this.buffer = new char[BUFFER_SIZE];
                this.count = 0;
                this.threshold = 0;
            }
        }

        private void flushBuffers() throws IOException {
            checkNotClosed();
            if (this.count > 0) {
                final CharBuffer cb = CharBuffer.wrap(this.buffer);
                cb.limit(this.count);
                this.buffers.add(cb);
            }
            this.emitter.emit(this.buffers);
            if (!this.buffers.isEmpty()) {
                this.buffer = this.buffers.get(0).array();
                this.buffers.clear();
            }
            this.count = 0;
            this.threshold = BUFFER_SIZE;
        }

        private void checkNotClosed() throws IOException {
            if (this.closed) {
                throw new IOException("Writer has been closed");
            }
        }

        private static class Emitter implements Runnable {

            private static final Map<Writer, Emitter> EMITTERS = new WeakHashMap<Writer, Emitter>();

            private static final Object EOF = new Object();

            private final BlockingQueue<Object> queue;

            private final List<CharBuffer> buffers;

            private final Writer writer;

            private int references;

            private Throwable exception;

            private final CountDownLatch latch;

            private Emitter(final Writer writer) {
                this.queue = new ArrayBlockingQueue<Object>(BUFFER_NUM_WRITE, false);
                this.writer = writer;
                this.buffers = new ArrayList<CharBuffer>();
                this.references = 0;
                this.exception = null;
                this.latch = new CountDownLatch(1);
                Threads.getMiscPool().submit(this);
            }

            private void release(final CharBuffer buffer) {
                synchronized (this.buffers) {
                    if (this.buffers.size() < BUFFER_NUM_WRITE + Threads.CORES + 1) {
                        buffer.clear();
                        this.buffers.add(buffer);
                    }
                }
            }

            private CharBuffer allocate() {
                synchronized (this.buffers) {
                    if (!this.buffers.isEmpty()) {
                        return this.buffers.remove(this.buffers.size() - 1);
                    }
                }
                return CharBuffer.allocate(2 * BUFFER_SIZE);
            }

            public void open() {
                synchronized (this) {
                    if (this.references < 0) {
                        throw new IllegalStateException("Stream has been closed");
                    }
                    ++this.references;
                }
            }

            public void close() throws IOException {
                synchronized (this) {
                    --this.references;
                    if (this.references != 0) {
                        return;
                    }
                    this.references = -1; // prevent further open() to occur
                }
                while (true) {
                    try {
                        this.queue.put(EOF);
                        break;
                    } catch (final InterruptedException ex) {
                        // ignore
                    }
                }
                while (true) {
                    try {
                        this.latch.await();
                        break;
                    } catch (final InterruptedException ex) {
                        // ignore
                    }
                }
                synchronized (this) {
                    if (this.exception != null) {
                        Util.propagateIfPossible(this.exception, IOException.class);
                        throw new IOException(this.exception);
                    }
                }
            }

            public void emit(final List<CharBuffer> buffers) throws IOException {
                try {
                    synchronized (this) {
                        if (this.exception != null) {
                            throw this.exception;
                        }
                    }
                    this.queue.put(new ArrayList<CharBuffer>(buffers));
                    buffers.clear();
                    buffers.add(allocate());
                } catch (final Throwable ex) {
                    Util.propagateIfPossible(ex, IOException.class);
                    throw new IOException(ex);
                }
            }

            @SuppressWarnings("unchecked")
            @Override
            public void run() {
                try {
                    while (true) {
                        final Object object = this.queue.take();
                        if (object == EOF) {
                            break;
                        }
                        final List<CharBuffer> buffers = (List<CharBuffer>) object;
                        for (final CharBuffer buffer : buffers) {
                            this.writer.write(buffer.array(), buffer.position(), buffer.limit());
                        }
                        if (!buffers.isEmpty()) {
                            release(buffers.get(0));
                        }
                    }
                } catch (final Throwable ex) {
                    synchronized (this) {
                        this.exception = ex;
                    }
                    this.queue.clear();
                } finally {
                    Util.closeQuietly(this.writer);
                    this.latch.countDown();
                }
            }

            public static Emitter forWriter(final Writer writer) {
                synchronized (EMITTERS) {
                    Emitter manager = EMITTERS.get(writer);
                    if (manager == null) {
                        manager = new Emitter(writer);
                        EMITTERS.put(writer, manager);
                    }
                    return manager;
                }
            }

        }

    }

}