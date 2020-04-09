package org.jocean.svr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageBody;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.ByteProcessors;
import org.jocean.netty.util.ByteProcessors.IndexOfBytesProcessor;
import org.jocean.svr.parse.AbstractParseContext;
import org.jocean.svr.parse.EntityParser;
import org.jocean.svr.parse.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.AppendableCharSequence;
import io.netty.util.internal.StringUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func0;
import rx.subjects.PublishSubject;

//  HEX DUMP for multipart/form-data with 2 tiny files part
//         +-------------------------------------------------+
//         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
//+--------+-------------------------------------------------+----------------+
//|00000000| 50 4f 53 54 20 2f 6e 65 77 72 65 73 74 2f 68 65 |POST /newrest/he|
//|00000010| 6c 6c 6f 20 48 54 54 50 2f 31 2e 31 0d 0a 43 6f |llo HTTP/1.1..Co|
//|00000020| 6e 74 65 6e 74 2d 54 79 70 65 3a 20 6d 75 6c 74 |ntent-Type: mult|
//|00000030| 69 70 61 72 74 2f 66 6f 72 6d 2d 64 61 74 61 3b |ipart/form-data;|
//|00000040| 20 62 6f 75 6e 64 61 72 79 3d 2d 2d 2d 2d 2d 2d | boundary=------|
//|00000050| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |----------------|
//|00000060| 2d 2d 2d 2d 34 37 31 31 38 34 36 34 35 34 30 36 |----471184645406|
//|00000070| 34 30 36 34 32 30 34 37 34 33 39 39 0d 0a 63 61 |406420474399..ca|
//|00000080| 63 68 65 2d 63 6f 6e 74 72 6f 6c 3a 20 6e 6f 2d |che-control: no-|
//|00000090| 63 61 63 68 65 0d 0a 50 6f 73 74 6d 61 6e 2d 54 |cache..Postman-T|
//|000000a0| 6f 6b 65 6e 3a 20 63 36 37 32 32 33 35 38 2d 36 |oken: c6722358-6|
//|000000b0| 32 65 63 2d 34 63 65 62 2d 61 61 63 33 2d 61 65 |2ec-4ceb-aac3-ae|
//|000000c0| 65 39 35 39 31 30 37 66 66 38 0d 0a 55 73 65 72 |e959107ff8..User|
//|000000d0| 2d 41 67 65 6e 74 3a 20 50 6f 73 74 6d 61 6e 52 |-Agent: PostmanR|
//|000000e0| 75 6e 74 69 6d 65 2f 37 2e 36 2e 30 0d 0a 41 63 |untime/7.6.0..Ac|
//|000000f0| 63 65 70 74 3a 20 2a 2f 2a 0d 0a 48 6f 73 74 3a |cept: */*..Host:|
//|00000100| 20 31 32 37 2e 30 2e 30 2e 31 3a 39 30 39 30 0d | 127.0.0.1:9090.|
//|00000110| 0a 61 63 63 65 70 74 2d 65 6e 63 6f 64 69 6e 67 |.accept-encoding|
//|00000120| 3a 20 67 7a 69 70 2c 20 64 65 66 6c 61 74 65 0d |: gzip, deflate.|
//|00000130| 0a 63 6f 6e 74 65 6e 74 2d 6c 65 6e 67 74 68 3a |.content-length:|
//|00000140| 20 33 36 34 0d 0a 43 6f 6e 6e 65 63 74 69 6f 6e | 364..Connection|
//|00000150| 3a 20 6b 65 65 70 2d 61 6c 69 76 65 0d 0a 0d 0a |: keep-alive....|
//|00000160| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |----------------|
//|00000170| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 34 37 31 31 |------------4711|
//|00000180| 38 34 36 34 35 34 30 36 34 30 36 34 32 30 34 37 |8464540640642047|
//|00000190| 34 33 39 39 0d 0a 43 6f 6e 74 65 6e 74 2d 44 69 |4399..Content-Di|
//|000001a0| 73 70 6f 73 69 74 69 6f 6e 3a 20 66 6f 72 6d 2d |sposition: form-|
//|000001b0| 64 61 74 61 3b 20 6e 61 6d 65 3d 22 66 69 6c 65 |data; _name="file|
//|000001c0| 31 22 3b 20 66 69 6c 65 6e 61 6d 65 3d 22 31 2e |1"; filename="1.|
//|000001d0| 74 78 74 22 0d 0a 43 6f 6e 74 65 6e 74 2d 54 79 |txt"..Content-Ty|
//|000001e0| 70 65 3a 20 74 65 78 74 2f 70 6c 61 69 6e 0d 0a |pe: text/plain..|
//|000001f0| 0d 0a 41 42 43 44 45 0d 0a 2d 2d 2d 2d 2d 2d 2d |..ABCDE..-------|
//|00000200| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |----------------|
//|00000210| 2d 2d 2d 2d 2d 34 37 31 31 38 34 36 34 35 34 30 |-----47118464540|
//|00000220| 36 34 30 36 34 32 30 34 37 34 33 39 39 0d 0a 43 |6406420474399..C|
//|00000230| 6f 6e 74 65 6e 74 2d 44 69 73 70 6f 73 69 74 69 |ontent-Dispositi|
//|00000240| 6f 6e 3a 20 66 6f 72 6d 2d 64 61 74 61 3b 20 6e |on: form-data; n|
//|00000250| 61 6d 65 3d 22 66 69 6c 65 32 22 3b 20 66 69 6c |ame="file2"; fil|
//|00000260| 65 6e 61 6d 65 3d 22 32 2e 74 78 74 22 0d 0a 43 |ename="2.txt"..C|
//|00000270| 6f 6e 74 65 6e 74 2d 54 79 70 65 3a 20 74 65 78 |ontent-Type: tex|
//|00000280| 74 2f 70 6c 61 69 6e 0d 0a 0d 0a 30 31 32 33 34 |t/plain....01234|
//|00000290| 35 36 0d 0a 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |56..------------|
//|000002a0| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |----------------|
//|000002b0| 34 37 31 31 38 34 36 34 35 34 30 36 34 30 36 34 |4711846454064064|
//|000002c0| 32 30 34 37 34 33 39 39 2d 2d 0d 0a             |20474399--..    |
//+--------+-------------------------------------------------+----------------+


// HEX DUMP for multipart/xxx without body
//         +-------------------------------------------------+
//         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
//+--------+-------------------------------------------------+----------------+
//|00000000| 50 4f 53 54 20 2f 6e 65 77 72 65 73 74 2f 68 65 |POST /newrest/he|
//|00000010| 6c 6c 6f 20 48 54 54 50 2f 31 2e 31 0d 0a 43 6f |llo HTTP/1.1..Co|
//|00000020| 6e 74 65 6e 74 2d 54 79 70 65 3a 20 6d 75 6c 74 |ntent-Type: mult|
//|00000030| 69 70 61 72 74 2f 66 6f 72 6d 2d 64 61 74 61 3b |ipart/form-data;|
//|00000040| 20 62 6f 75 6e 64 61 72 79 3d 2d 2d 2d 2d 2d 2d | boundary=------|
//|00000050| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |----------------|
//|00000060| 2d 2d 2d 2d 38 37 36 35 35 38 33 30 30 35 35 35 |----876558300555|
//|00000070| 38 39 34 30 37 30 35 37 37 36 39 35 0d 0a 63 61 |894070577695..ca|
//|00000080| 63 68 65 2d 63 6f 6e 74 72 6f 6c 3a 20 6e 6f 2d |che-control: no-|
//|00000090| 63 61 63 68 65 0d 0a 50 6f 73 74 6d 61 6e 2d 54 |cache..Postman-T|
//|000000a0| 6f 6b 65 6e 3a 20 38 36 35 38 66 32 64 66 2d 37 |oken: 8658f2df-7|
//|000000b0| 37 38 30 2d 34 30 39 32 2d 61 62 35 34 2d 64 64 |780-4092-ab54-dd|
//|000000c0| 61 65 61 61 62 37 35 36 30 38 0d 0a 55 73 65 72 |aeaab75608..User|
//|000000d0| 2d 41 67 65 6e 74 3a 20 50 6f 73 74 6d 61 6e 52 |-Agent: PostmanR|
//|000000e0| 75 6e 74 69 6d 65 2f 37 2e 36 2e 30 0d 0a 41 63 |untime/7.6.0..Ac|
//|000000f0| 63 65 70 74 3a 20 2a 2f 2a 0d 0a 48 6f 73 74 3a |cept: */*..Host:|
//|00000100| 20 31 32 37 2e 30 2e 30 2e 31 3a 39 30 39 30 0d | 127.0.0.1:9090.|
//|00000110| 0a 61 63 63 65 70 74 2d 65 6e 63 6f 64 69 6e 67 |.accept-encoding|
//|00000120| 3a 20 67 7a 69 70 2c 20 64 65 66 6c 61 74 65 0d |: gzip, deflate.|
//|00000130| 0a 63 6f 6e 74 65 6e 74 2d 6c 65 6e 67 74 68 3a |.content-length:|
//|00000140| 20 30 0d 0a 43 6f 6e 6e 65 63 74 69 6f 6e 3a 20 | 0..Connection: |
//|00000150| 6b 65 65 70 2d 61 6c 69 76 65 0d 0a 0d 0a       |keep-alive....  |
//+--------+-------------------------------------------------+----------------+

public class MultipartTransformer implements Transformer<ByteBufSlice, MessageBody> {

    private static final Logger LOG = LoggerFactory.getLogger(MultipartTransformer.class);

    public static String getBoundary(final String contentType) {
        final String[] dataBoundary = getMultipartDataBoundary(contentType);

        if (dataBoundary != null) {
            return dataBoundary[0];
        } else {
            return null;
        }
    }

    /**
     * Check from the request ContentType if this request is a Multipart request.
     * @return an array of String if multipartDataBoundary exists with the multipartDataBoundary
     * as first element, charset if any as second (missing if not set), else null
     */
    protected static String[] getMultipartDataBoundary(final String contentType) {
        // Check if Post using "multipart/form-data; boundary=--89421926422648 [; charset=xxx]"
        final String[] headerContentType = splitHeaderContentType(contentType);
        final String multiPartHeader = HttpHeaderValues.MULTIPART_FORM_DATA.toString();
        if (headerContentType[0].regionMatches(true, 0, multiPartHeader, 0 , multiPartHeader.length())) {
            int mrank;
            int crank;
            final String boundaryHeader = HttpHeaderValues.BOUNDARY.toString();
            if (headerContentType[1].regionMatches(true, 0, boundaryHeader, 0, boundaryHeader.length())) {
                mrank = 1;
                crank = 2;
            } else if (headerContentType[2].regionMatches(true, 0, boundaryHeader, 0, boundaryHeader.length())) {
                mrank = 2;
                crank = 1;
            } else {
                return null;
            }
            String boundary = StringUtil.substringAfter(headerContentType[mrank], '=');
            if (boundary == null) {
                throw new ErrorDataDecoderException("Needs a boundary value");
            }
            if (boundary.charAt(0) == '"') {
                final String bound = boundary.trim();
                final int index = bound.length() - 1;
                if (bound.charAt(index) == '"') {
                    boundary = bound.substring(1, index);
                }
            }
            final String charsetHeader = HttpHeaderValues.CHARSET.toString();
            if (headerContentType[crank].regionMatches(true, 0, charsetHeader, 0, charsetHeader.length())) {
                final String charset = StringUtil.substringAfter(headerContentType[crank], '=');
                if (charset != null) {
                    return new String[] {"--" + boundary, charset};
                }
            }
            return new String[] {"--" + boundary};
        }
        return null;
    }

    /**
     * Split the very first line (Content-Type value) in 3 Strings
     *
     * @return the array of 3 Strings
     */
    private static String[] splitHeaderContentType(final String sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;
        aStart = findNonWhitespace(sb, 0);
        aEnd =  sb.indexOf(';');
        if (aEnd == -1) {
            return new String[] { sb, "", "" };
        }
        bStart = findNonWhitespace(sb, aEnd + 1);
        if (sb.charAt(aEnd - 1) == ' ') {
            aEnd--;
        }
        bEnd =  sb.indexOf(';', bStart);
        if (bEnd == -1) {
            bEnd = findEndOfString(sb);
            return new String[] { sb.substring(aStart, aEnd), sb.substring(bStart, bEnd), "" };
        }
        cStart = findNonWhitespace(sb, bEnd + 1);
        if (sb.charAt(bEnd - 1) == ' ') {
            bEnd--;
        }
        cEnd = findEndOfString(sb);
        return new String[] { sb.substring(aStart, aEnd), sb.substring(bStart, bEnd), sb.substring(cStart, cEnd) };
    }

    /**
     * Find the first non whitespace
     * @return the rank of the first non whitespace
     */
    static int findNonWhitespace(final String sb, final int offset) {
        int result;
        for (result = offset; result < sb.length(); result ++) {
            if (!Character.isWhitespace(sb.charAt(result))) {
                break;
            }
        }
        return result;
    }

    /**
     * Find the end of String
     * @return the rank of the end of string
     */
    static int findEndOfString(final String sb) {
        int result;
        for (result = sb.length(); result > 0; result --) {
            if (!Character.isWhitespace(sb.charAt(result - 1))) {
                break;
            }
        }
        return result;
    }

    public MultipartTransformer(final Func0<DisposableWrapper<? extends ByteBuf>> allocator, final String multipartDataBoundary) {
        this(allocator, multipartDataBoundary, 8192, 128);
    }

    public MultipartTransformer(
            final Func0<DisposableWrapper<? extends ByteBuf>> allocator, final String multipartDataBoundary,
            final int maxHeaderSize,
            final int initialBufferSize) {
        this._allocator = allocator;
        this._CRLFBoundary = ("\r\n" + multipartDataBoundary).getBytes(Charsets.UTF_8);
        this._boundaryCRLF = (multipartDataBoundary + "\r\n").getBytes(Charsets.UTF_8);
        this._endBoundary = (multipartDataBoundary + "--\r\n").getBytes(Charsets.UTF_8);
        this._seq = new AppendableCharSequence(initialBufferSize);
        this._lineParser = new LineParser(this._seq, maxHeaderSize);
    }

    private static final byte[] TAG_CRLFCRLF = new byte[]{0x0d, 0x0a, 0x0d, 0x0a};

    interface MultipartContext extends ParseContext<MessageBody> {
        BufsInputStream<?> in();
        Iterable<DisposableWrapper<? extends ByteBuf>> in2dwbs(final int size);
    }

    interface MultipartParser extends EntityParser<MessageBody, MultipartContext> {
        @Override
        MultipartParser parse(final MultipartContext ctx);
    }

    static class DefaultMultipartContext extends AbstractParseContext<MessageBody, MultipartContext> implements MultipartContext {

        DefaultMultipartContext(final BufsInputStream<?> bufin,
                final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
                final int bufsize,
                final MultipartParser initParser) {
            super(initParser);
            this._bufin = bufin;
            this._bufout = bufout;
            this._buf = new byte[bufsize];
        }

        @Override
        protected boolean hasData() {
            return _bufin.available() > 0;
        }

        @Override
        public BufsInputStream<?> in() {
            return _bufin;
        }

        @Override
        public Iterable<DisposableWrapper<? extends ByteBuf>> in2dwbs(int size) {
            final List<DisposableWrapper<? extends ByteBuf>> dwbs = new ArrayList<>();
            _bufout.setOutput(dwb -> dwbs.add(dwb));

            try {
                while (size > 0) {
                    final int toread = Math.min(_buf.length, size);
                    final int readed = _bufin.read(_buf, 0, toread);
                    if (readed > 0) {
                        _bufout.write(_buf, 0, readed);
                    }
                    size -= readed;
                }
                _bufout.flush();
            } catch (final Exception e) {
            }

            return dwbs;
        }

        final BufsInputStream<?> _bufin;
        final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> _bufout;
        final byte[] _buf;
    }

    private static final String EMPTY_VALUE = "";

    private static int findNonWhitespace(final AppendableCharSequence sb, final int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            if (!Character.isWhitespace(sb.charAtUnsafe(result))) {
                return result;
            }
        }
        return sb.length();
    }

    private static int findEndOfString(final AppendableCharSequence sb) {
        for (int result = sb.length() - 1; result > 0; --result) {
            if (!Character.isWhitespace(sb.charAtUnsafe(result))) {
                return result + 1;
            }
        }
        return 0;
    }

    private static class LineParser implements ByteProcessor {
        private final AppendableCharSequence seq;
        private final int maxLength;
        private int size;

        LineParser(final AppendableCharSequence seq, final int maxLength) {
            this.seq = seq;
            this.maxLength = maxLength;
        }

        public AppendableCharSequence parse(final BufsInputStream<?> bufin) {
            final int oldSize = size;
            seq.reset();
            final int i = bufin.forEachByte(this);
            if (i == -1) {
                size = oldSize;
                return null;
            }
            try {
                bufin.skip(i + 1);
            } catch (final IOException e) {
            }
            return seq;
        }

        public void reset() {
            size = 0;
        }

        @Override
        public boolean process(final byte value) throws Exception {
            final char nextByte = (char) (value & 0xFF);
            if (nextByte == HttpConstants.CR) {
                return true;
            }
            if (nextByte == HttpConstants.LF) {
                return false;
            }

            if (++ size > maxLength) {
                // TODO: Respond with Bad Request and discard the traffic
                //    or close the connection.
                //       No need to notify the upstream handlers - just log.
                //       If decoding a response, just throw an exception.
                throw newException(maxLength);
            }

            seq.append(nextByte);
            return true;
        }

        protected TooLongFrameException newException(final int maxLength) {
            return new TooLongFrameException("HTTP header is larger than " + maxLength + " bytes.");
        }
    }

    private final MultipartParser _headerParser = new MultipartParser() {
        @Override
        public String toString() {
            return "headerParser";
        }

        @Override
        public MultipartParser parse(final MultipartContext ctx) {
            final int headerStartIdx = ctx.in().forEachByte(ByteProcessors.indexOfBytes(_boundaryCRLF));
            if (headerStartIdx == -1) {
                // need more data
                ctx.stopParsing();
                LOG.debug("headerParser: can't find header start {}, need more data", new String(_boundaryCRLF, Charsets.UTF_8));
                return this;
            }

            final int bodyStartIdx = ctx.in().forEachByte(ByteProcessors.indexOfBytes(TAG_CRLFCRLF));
            if (bodyStartIdx == -1) {
                // need more data
                ctx.stopParsing();
                LOG.debug("headerParser: can't find body start CRLFCRLF, need more data");
                return this;
            }

            final HttpHeaders headers = new DefaultHttpHeaders();
            final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin =
                  new BufsInputStream<>(dwb -> dwb.unwrap(), dwb -> dwb.dispose());

            // transfer bytes from _boundaryCRLF(exclude) ==> TAG_CRLFCRLF(include) to bufin
            // then try to parse headers within one part
            // NOTE: after meet valid bodyStartIdx, it's time for skip _boundaryCRLF prefix
            // SEE above case:
            /*
            +-------------------------------------------------+
            |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
            +--------+-------------------------------------------------+----------------+
            |00000000| 50 4f 53 54 20 2f 65 61 73 79 2d 61 72 2f 64 65 |POST /easy-ar/de|
            |00000010| 74 65 63 74 4f 62 6a 65 63 74 3f 73 69 7a 65 3d |tectObject?size=|
            |00000020| 38 33 32 35 26 6d 64 35 3d 33 31 31 63 33 65 37 |8325&md5=311c3e7|
            |00000030| 32 63 31 63 30 34 64 36 35 61 31 32 65 37 38 64 |2c1c04d65a12e78d|
            |00000040| 33 34 39 38 36 37 61 63 33 20 48 54 54 50 2f 31 |349867ac3 HTTP/1|
            |00000050| 2e 31 0d 0a 52 65 6d 6f 74 65 49 70 3a 20 31 32 |.1..RemoteIp: 12|
            |00000060| 33 2e 31 35 37 2e 32 31 39 2e 32 0d 0a 48 6f 73 |3.157.219.2..Hos|
            |00000070| 74 3a 20 61 70 69 2e 32 67 64 74 2e 63 6f 6d 0d |t: api.2gdt.com.|
            |00000080| 0a 58 2d 46 6f 72 77 61 72 64 65 64 2d 46 6f 72 |.X-Forwarded-For|
            |00000090| 3a 20 31 32 33 2e 31 35 37 2e 32 31 39 2e 32 0d |: 123.157.219.2.|
            |000000a0| 0a 43 6f 6e 74 65 6e 74 2d 4c 65 6e 67 74 68 3a |.Content-Length:|
            |000000b0| 20 38 36 31 39 0d 0a 78 2d 61 70 70 62 75 69 6c | 8619..x-appbuil|
            |000000c0| 64 3a 20 31 0d 0a 78 2d 73 69 64 3a 20 37 32 38 |d: 1..x-sid: 728|
            |000000d0| 61 35 65 65 35 61 34 65 32 34 31 30 64 38 61 61 |a5ee5a4e2410d8aa|
            |000000e0| 65 38 61 33 64 66 63 63 35 35 66 63 33 0d 0a 52 |e8a3dfcc55fc3..R|
            |000000f0| 65 66 65 72 65 72 3a 20 68 74 74 70 73 3a 2f 2f |eferer: https://|
            |00000100| 73 65 72 76 69 63 65 77 65 63 68 61 74 2e 63 6f |servicewechat.co|
            |00000110| 6d 2f 77 78 61 30 63 34 37 62 65 39 66 64 31 34 |m/wxa0c47be9fd14|
            |00000120| 31 63 32 39 2f 64 65 76 74 6f 6f 6c 73 2f 70 61 |1c29/devtools/pa|
            |00000130| 67 65 2d 66 72 61 6d 65 2e 68 74 6d 6c 0d 0a 55 |ge-frame.html..U|
            |00000140| 73 65 72 2d 41 67 65 6e 74 3a 20 4d 6f 7a 69 6c |ser-Agent: Mozil|
            |00000150| 6c 61 2f 35 2e 30 20 28 69 50 68 6f 6e 65 3b 20 |la/5.0 (iPhone; |
            |00000160| 43 50 55 20 69 50 68 6f 6e 65 20 4f 53 20 31 30 |CPU iPhone OS 10|
            |00000170| 5f 32 20 6c 69 6b 65 20 4d 61 63 20 4f 53 20 58 |_2 like Mac OS X|
            |00000180| 29 20 41 70 70 6c 65 57 65 62 4b 69 74 2f 36 30 |) AppleWebKit/60|
            |00000190| 32 2e 33 2e 31 32 20 28 4b 48 54 4d 4c 2c 20 6c |2.3.12 (KHTML, l|
            |000001a0| 69 6b 65 20 47 65 63 6b 6f 29 20 4d 6f 62 69 6c |ike Gecko) Mobil|
            |000001b0| 65 2f 31 34 43 39 32 20 53 61 66 61 72 69 2f 36 |e/14C92 Safari/6|
            |000001c0| 30 31 2e 31 20 77 65 63 68 61 74 64 65 76 74 6f |01.1 wechatdevto|
            |000001d0| 6f 6c 73 2f 31 2e 30 32 2e 31 39 30 37 31 36 30 |ols/1.02.1907160|
            |000001e0| 20 4d 69 63 72 6f 4d 65 73 73 65 6e 67 65 72 2f | MicroMessenger/|
            |000001f0| 37 2e 30 2e 34 20 4c 61 6e 67 75 61 67 65 2f 7a |7.0.4 Language/z|
            |00000200| 68 5f 43 4e 20 77 65 62 76 69 65 77 2f 0d 0a 63 |h_CN webview/..c|
            |00000210| 6f 6e 74 65 6e 74 2d 74 79 70 65 3a 20 6d 75 6c |ontent-type: mul|
            |00000220| 74 69 70 61 72 74 2f 66 6f 72 6d 2d 64 61 74 61 |tipart/form-data|
            |00000230| 3b 20 62 6f 75 6e 64 61 72 79 3d 2d 2d 2d 2d 2d |; boundary=-----|
            |00000240| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |----------------|
            |00000250| 2d 2d 2d 2d 2d 31 37 30 35 30 33 33 30 30 31 35 |-----17050330015|
            |00000260| 35 37 30 35 38 38 33 36 39 34 33 38 35 0d 0a 58 |5705883694385..X|
            |00000270| 2d 46 6f 72 77 61 72 64 65 64 2d 50 72 6f 74 6f |-Forwarded-Proto|
            |00000280| 3a 20 68 74 74 70 73 0d 0a 53 4c 42 2d 49 50 3a |: https..SLB-IP:|
            |00000290| 20 34 37 2e 39 34 2e 37 32 2e 35 39 0d 0a 53 4c | 47.94.72.59..SL|
            |000002a0| 42 2d 49 44 3a 20 6c 62 2d 32 7a 65 76 77 30 6d |B-ID: lb-2zevw0m|
            |000002b0| 6a 36 78 6a 74 33 37 61 30 78 79 63 38 6c 0d 0a |j6xjt37a0xyc8l..|
            |000002c0| 63 6f 6e 6e 65 63 74 69 6f 6e 3a 20 6b 65 65 70 |connection: keep|
            |000002d0| 2d 61 6c 69 76 65 0d 0a 75 62 65 72 2d 74 72 61 |-alive..uber-tra|
            |000002e0| 63 65 2d 69 64 3a 20 62 63 63 38 64 64 38 66 31 |ce-id: bcc8dd8f1|
            |000002f0| 38 33 62 62 32 32 65 25 33 41 66 34 35 37 63 38 |83bb22e%3Af457c8|
            |00000300| 66 63 35 34 39 39 31 34 65 30 25 33 41 62 63 63 |fc549914e0%3Abcc|
            |00000310| 38 64 64 38 66 31 38 33 62 62 32 32 65 25 33 41 |8dd8f183bb22e%3A|
            |00000320| 31 0d 0a 0d 0a 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |1....-----------|
            |00000330| 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d |----------------|
            |00000340| 2d 31 37 30 35 30 33 33 30 30 31 35 35 37 30 35 |-170503300155705|
            |00000350| 38 38 33 36 39 34 33 38 35 0d 0a 43 6f 6e 74 65 |883694385..Conte|
            |00000360| 6e 74 2d 44 69 73 70 6f 73 69 74 69 6f 6e 3a 20 |nt-Disposition: |
            |00000370| 66 6f 72 6d 2d 64 61 74 61 3b 20 6e 61 6d 65 3d |form-data; name=|
            |00000380| 22 66 69 6c 65 22 3b 20 66 69 6c 65 6e 61 6d 65 |"file"; filename|
            |00000390| 3d 22 77 78 61 30 63 34 37 62 65 39 66 64 31 34 |="wxa0c47be9fd14|
            |000003a0| 31 63 32 39 2e 6f 36 7a 41 4a 73 32 45 36 57 52 |1c29.o6zAJs2E6WR|
            |000003b0| 56 38 39 53 70 76 38 68 50 64 6b 5f 54 44 4f 42 |V89Spv8hPdk_TDOB|
            |000003c0| 4d 2e 5a 78 4a 45 78 56 36 70 51 71 34 7a 38 34 |M.ZxJExV6pQq4z84|
            |000003d0| 31 66 63 34 31 64 39 63 63 38 36 36 63 35 61 34 |1fc41d9cc866c5a4|
            |000003e0| 65 36 32 37 65 61 35 30 66 33 65 39 35 39 2e 6a |e627ea50f3e959.j|
            |000003f0| 70 67 22 0d 0a 43 6f 6e 74 65 6e 74 2d 54 79 70 |pg"..Content-Typ|
            +--------+-------------------------------------------------+----------------+

            multipart split inside header

                     +-------------------------------------------------+
                     |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
            +--------+-------------------------------------------------+----------------+
            |00000000| 65 3a 20 69 6d 61 67 65 2f 6a 70 65 67 0d 0a 0d |e: image/jpeg...|
            |00000010| 0a ff d8 ff e0 00 10 4a 46 49 46 00 01 01 00 00 |.......JFIF.....|
            |00000020| 01 00 01 00 00 ff db 00 43 00 1b 12 14 17 14 11 |........C.......|

             */

            try {
                // skip _boundaryCRLF bytes
                ctx.in().skip(headerStartIdx + 1);
            } catch (final IOException e1) {
            }

            bufin.appendIterable(ctx.in2dwbs(bodyStartIdx - headerStartIdx));
            bufin.markEOS();

            parseHeaders(headers, bufin);

            // switch state to recv body
            LOG.debug("headerParser: find body start CRLFCRLF, change to BodyParser with headers: {}", headers);
            return new BodyParser(headers);
        }

        private void parseHeaders(final HttpHeaders headers, final BufsInputStream<?> bufin) {
            _lineParser.reset();
            // reset _name and _value fields
            _name = null;
            _value = null;

            AppendableCharSequence line = _lineParser.parse(bufin);
            if (line == null) {
                return;
            }

//            通常HTTP消息包括客户机向服务器的请求消息和服务器向客户机的响应消息。这两种类型的消息由一个起始行，一个或者多个头域，一个只是头域结束的空行和可
//            选的消息体组成。HTTP的头域包括通用头，请求头，响应头和实体头四个部分。每个头域由一个域名，冒号（:）和域值三部分组成。域名是大小写无关的，域
//            值前可以添加任何数量的空格符，头域可以被扩展为多行，在每行开始处，使用至少一个空格或制表符。
            if (line.length() > 0) {
                do {
                    final char firstChar = line.charAt(0);
                    if (_name != null && (firstChar == ' ' || firstChar == '\t')) {
                        // please do not make one line from below code
                        // as it breaks +XX:OptimizeStringConcat optimization
                        final String trimmedLine = line.toString().trim();
                        final String valueStr = String.valueOf(_value);
                        _value = valueStr + ' ' + trimmedLine;
                    } else {
                        if (_name != null) {
                            headers.add(_name, _value);
                        }
                        splitHeader(line);
                    }

                    line = _lineParser.parse(bufin);
                    if (line == null) {
                        break;
                    }
                } while (line.length() > 0);
            }

            // Add the last header.
            if (_name != null) {
                headers.add(_name, _value);
            }
            // reset _name and _value fields
            _name = null;
            _value = null;
        }

        private void splitHeader(final AppendableCharSequence sb) {
            final int length = sb.length();
            int nameStart;
            int nameEnd;
            int colonEnd;
            int valueStart;
            int valueEnd;

            nameStart = findNonWhitespace(sb, 0);
            for (nameEnd = nameStart; nameEnd < length; nameEnd ++) {
                final char ch = sb.charAt(nameEnd);
                if (ch == ':' || Character.isWhitespace(ch)) {
                    break;
                }
            }

            for (colonEnd = nameEnd; colonEnd < length; colonEnd ++) {
                if (sb.charAt(colonEnd) == ':') {
                    colonEnd ++;
                    break;
                }
            }

            _name = sb.subStringUnsafe(nameStart, nameEnd);
            valueStart = findNonWhitespace(sb, colonEnd);
            if (valueStart == length) {
                _value = EMPTY_VALUE;
            } else {
                valueEnd = findEndOfString(sb);
                _value = sb.subStringUnsafe(valueStart, valueEnd);
            }
        }

        // These will be updated by splitHeader(...)
        private CharSequence _name;
        private CharSequence _value;
    };

    class BodyParser implements MultipartParser {
        @Override
        public String toString() {
            return "BodyParser";
        }

        public BodyParser(final HttpHeaders headers) {
            this._headers = headers;
        }

        @Override
        public MultipartParser parse(final MultipartContext ctx) {
            final IndexOfBytesProcessor ibp = ByteProcessors.indexOfBytes(_CRLFBoundary);
            final int bodyEndIdx = ctx.in().forEachByte(ibp);
            if (bodyEndIdx > 0) {
                final int length = bodyEndIdx - _CRLFBoundary.length + 1;
                final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs = ctx.in2dwbs(length);
                if (null == _subject) {
                    // begin of MessageBody
                    ctx.appendContent(dwbs, content -> new MessageBody() {
                            @Override
                            public String toString() {
                                return "Subpart with full content: " + _headers;
                            }
                            @Override
                            public HttpHeaders headers() {
                                return _headers;
                            }
                            @Override
                            public String contentType() {
                                return _headers.get(HttpHeaderNames.CONTENT_TYPE);
                            }
                            @Override
                            public int contentLength() {
                                return length;
                            }
                            @Override
                            public Observable<? extends ByteBufSlice> content() {
                                return Observable.just(content);
                            }});
                }
                else {
                    ctx.appendContent(dwbs, content -> {
                        _subject.onNext(content);
                        // part end, so notify downstream onCompleted event
                        _subject.onCompleted();
                        return null;
                    });
                }
                try {
                    ctx.in().skip(2);   // skip CRLF
                } catch (final Exception e) {
                }

                LOG.debug("BodyParser: find body end {}, change to endParser", new String(_CRLFBoundary, Charsets.UTF_8));
                return _endParser;
            }
            else {
                // check ibp's matchedCount
                if (ibp.matchedCount() > 0) {
                    // maybe end tag in-complete, skip push body part, wait for next recving
                }
                else {
                    // pure body part
                    final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs = ctx.in2dwbs(ctx.in().available());

                    if (null == _subject) {
                        _subject = PublishSubject.create();
                        ctx.appendContent( dwbs, content -> new MessageBody() {
                                @Override
                                public String toString() {
                                    return "Subpart with part content: " + _headers;
                                }

                                @Override
                                public HttpHeaders headers() {
                                    return _headers;
                                }
                                @Override
                                public String contentType() {
                                    return _headers.get(HttpHeaderNames.CONTENT_TYPE);
                                }
                                @Override
                                public int contentLength() {
                                    final String value = _headers.get(HttpHeaderNames.CONTENT_LENGTH);
                                    if (value != null) {
                                        return Integer.parseInt(value);
                                    }
                                    return -1;
                                }
                                @Override
                                public Observable<? extends ByteBufSlice> content() {
                                    return Observable.just(content).concatWith(_subject);
                                }});
                    }
                    else {
                        ctx.appendContent(dwbs, content -> {
                            _subject.onNext(content);
                            return null;
                        });
                    }
                }
                ctx.stopParsing();
                LOG.debug("BodyParser: can't find body end {}, need more data", new String(_CRLFBoundary, Charsets.UTF_8));
                return this;
            }
        }

        private PublishSubject<ByteBufSlice> _subject;
        private final HttpHeaders _headers;
    }

    private final MultipartParser _endParser = new MultipartParser() {
        @Override
        public String toString() {
            return "endParser";
        }

        @Override
        public MultipartParser parse(final MultipartContext ctx) {
            //     Boundary + CRLF: next part
            // or  Boundary + "--" + CRLF: end of multipart body

            if (ctx.in().available() >= _endBoundary.length) {
                if (ctx.in().forEachByte(ByteProcessors.indexOfBytes(_endBoundary)) == _endBoundary.length - 1) {
                    // if meet end of multipart flag: (_multipartDataBoundary + "--\r\n")
                    //  meet end of multipart body
                    LOG.debug("endParser: meet end of multipart body");
                    return null;
                }
                else {
                    LOG.debug("endParser: start next multipart body, change to headerParser");
                    return _headerParser;
                }
            }
            else {
                ctx.stopParsing();
                LOG.debug("endParser: need more data");
                return this;
            }
        }
    };

    @Override
    public Observable<MessageBody> call(final Observable<ByteBufSlice> content) {
        final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(dwb -> dwb.unwrap(), dwb -> dwb.dispose());
        final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(_allocator, dwb->dwb.unwrap());

        final DefaultMultipartContext mctx = new DefaultMultipartContext(bufin, bufout, 512, _headerParser);
        bufin.markEOS();

        return content.flatMap(bbs -> {
            bufin.appendIterable(bbs.element());
            mctx.resetParsing();

            return mctx.parseEntity(() -> bbs.step());
        });
    }

    private final AppendableCharSequence _seq;
    private final LineParser _lineParser;

    private final Func0<DisposableWrapper<? extends ByteBuf>> _allocator;
    private final byte[] _CRLFBoundary;
    private final byte[] _boundaryCRLF;
    private final byte[] _endBoundary;
}
