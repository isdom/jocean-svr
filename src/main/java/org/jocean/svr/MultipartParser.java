package org.jocean.svr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageBody;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.ByteProcessors;
import org.jocean.netty.util.ByteProcessors.IndexOfBytesProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.AppendableCharSequence;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.subjects.PublishSubject;
import rx.subscriptions.Subscriptions;

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

public class MultipartParser implements Transformer<ByteBufSlice, MessageBody> {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(MultipartParser.class);

    public MultipartParser(final Func0<DisposableWrapper<? extends ByteBuf>> allocator, final String multipartDataBoundary) {
        this(allocator, multipartDataBoundary, 8192, 128);
    }

    public MultipartParser(final Func0<DisposableWrapper<? extends ByteBuf>> allocator, final String multipartDataBoundary,
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

    interface MakeSlice extends Action1<Action0> {
    }

    interface ParseContext {
        BufsInputStream<?> in();
        Iterable<DisposableWrapper<? extends ByteBuf>> in2dwbs(final int size);
        void setMakeSlice(MakeSlice makeSlice);
        void setMessageBody(MessageBody body);
        void stopParsing();
        boolean canParsing();
        boolean noMakeSlice();
        void doMakeSlice(Action0 dostep);
        MessageBody getBody();
        void parse();
    }

    interface SliceParser {
        SliceParser parse(ParseContext ctx);
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

    private final SliceParser _headerParser = new SliceParser() {
        @Override
        public SliceParser parse(final ParseContext ctx) {
            final int headerStartIdx = ctx.in().forEachByte(ByteProcessors.indexOfBytes(_boundaryCRLF));
            if (headerStartIdx == -1) {
                // need more data
                ctx.stopParsing();
                return this;
            }

            try {
                // skip _boundaryCRLF bytes
                ctx.in().skip(headerStartIdx + 1);
            } catch (final IOException e1) {
            }

            final int bodyStartIdx = ctx.in().forEachByte(ByteProcessors.indexOfBytes(TAG_CRLFCRLF));
            if (bodyStartIdx == -1) {
                // need more data
                ctx.stopParsing();
                return this;
            }

//          ctx.in().skip(bodyStartIdx + 1);
            final HttpHeaders headers = new DefaultHttpHeaders();
            final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin =
                  new BufsInputStream<>(dwb -> dwb.unwrap(), dwb -> dwb.dispose());

            bufin.appendIterable(ctx.in2dwbs(bodyStartIdx + 1));
            bufin.markEOS();

            parseHeaders(headers, bufin);

            // switch state to recv body
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

    class BodyParser implements SliceParser {
        public BodyParser(final HttpHeaders headers) {
            this._headers = headers;
        }

        @Override
        public SliceParser parse(final ParseContext ctx) {
            final IndexOfBytesProcessor ibp = ByteProcessors.indexOfBytes(_CRLFBoundary);
            final int bodyEndIdx = ctx.in().forEachByte(ibp);
            if (bodyEndIdx > 0) {
                final int length = bodyEndIdx - _CRLFBoundary.length + 1;
                final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs = ctx.in2dwbs(length);
                if (null == _subject) {
                    // begin of MessageBody
                    ctx.setMakeSlice(stepable -> {
                        final ByteBufSlice content = dwbs2bbs(dwbs, stepable);
                        ctx.setMessageBody(new MessageBody() {
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
                    });
                }
                else {
                    ctx.setMakeSlice(stepable -> {
                        _subject.onNext(dwbs2bbs(dwbs, stepable));
                        // part end, so notify downstream onCompleted event
                        _subject.onCompleted();
                    });
                }
                try {
                    ctx.in().skip(2);   // skip CRLF
                } catch (final Exception e) {
                }

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
                        ctx.setMakeSlice( stepable -> {
                            final ByteBufSlice content = dwbs2bbs(dwbs, stepable);
                            ctx.setMessageBody(new MessageBody() {
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
                        });
                    }
                    else {
                        ctx.setMakeSlice(stepable -> _subject.onNext(dwbs2bbs(dwbs, stepable)) );
                    }
                }
                ctx.stopParsing();
                return this;
            }
        }

        private PublishSubject<ByteBufSlice> _subject;
        private final HttpHeaders _headers;
    }

    private final SliceParser _endParser = new SliceParser() {
        @Override
        public SliceParser parse(final ParseContext ctx) {
            //     Boundary + CRLF: next part
            // or  Boundary + "--" + CRLF: end of multipart body

            if (ctx.in().available() >= _endBoundary.length) {
                if (ctx.in().forEachByte(ByteProcessors.indexOfBytes(_endBoundary)) == _endBoundary.length - 1) {
                    // if meet end of multipart flag: (_multipartDataBoundary + "--\r\n")
                    //  meet end of multipart body
                    return null;
                }
                else {
                    return _headerParser;
                }
            }
            else {
                ctx.stopParsing();
                return this;
            }
        }
    };

    @Override
    public Observable<MessageBody> call(final Observable<ByteBufSlice> content) {
        final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(dwb -> dwb.unwrap(), dwb -> dwb.dispose());
        final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(_allocator, dwb->dwb.unwrap());
        final byte[] buf = new byte[512];

        final AtomicReference<SliceParser> currentParser = new AtomicReference<>(_headerParser);
        bufin.markEOS();

        return content.flatMap(bbs -> {
            bufin.appendIterable(bbs.element());

            // TBD: 简化 makeslices & bodys
            final AtomicReference<MakeSlice> makeslices = new AtomicReference<>();
            final AtomicReference<MessageBody> bodys = new AtomicReference<>();

            final AtomicBoolean parsing = new AtomicBoolean(true);

            final ParseContext ctx = new ParseContext() {
                @Override
                public BufsInputStream<?> in() {
                    return bufin;
                }

                @Override
                public Iterable<DisposableWrapper<? extends ByteBuf>> in2dwbs(int size) {
                    final List<DisposableWrapper<? extends ByteBuf>> dwbs = new ArrayList<>();
                    bufout.setOutput(dwb -> dwbs.add(dwb));

                    try {
                        while (size > 0) {
                            final int toread = Math.min(buf.length, size);
                            final int readed = bufin.read(buf, 0, toread);
                            if (readed > 0) {
                                bufout.write(buf, 0, readed);
                            }
                            size -= readed;
                        }
                        bufout.flush();
                    } catch (final Exception e) {
                    }

                    return dwbs;
                }

                @Override
                public void setMakeSlice(final MakeSlice makeSlice) {
                    makeslices.set(makeSlice);
                }

                @Override
                public void setMessageBody(final MessageBody body) {
                    bodys.set(body);
                }

                @Override
                public void stopParsing() {
                    parsing.set(false);
                }

                @Override
                public boolean canParsing() {
                    return bufin.available() > 0 && parsing.get() && null != currentParser.get();
                }

                @Override
                public boolean noMakeSlice() {
                    return null == makeslices.get();
                }

                @Override
                public void doMakeSlice(final Action0 dostep) {
                    makeslices.getAndSet(null).call(dostep);
                }

                @Override
                public MessageBody getBody() {
                    return bodys.getAndSet(null);
                }

                @Override
                public void parse() {
                    currentParser.set(currentParser.get().parse(this));
                }};

            while (ctx.noMakeSlice() && ctx.canParsing()) {
                ctx.parse();
            }

            if (ctx.noMakeSlice()) {
                // no downstream msgbody or slice generate, auto step updtgream
                bbs.step();
                return Observable.empty();
            }
            else if (ctx.canParsing()) {
                // can continue parsing
                // makeslices.size() == 1
                final PublishSubject<MessageBody> bodySubject = PublishSubject.create();

                ctx.doMakeSlice(() -> doParse(ctx, bodySubject, () -> bbs.step()));
                //  如果该 bbs 是上一个 part 的结尾部分，并附带了后续的1个或多个 part (部分内容)
                //  均可能出现 makeslices.size() == 1，但 bodys.size() == 0 的情况
                //  因此需要分别处理 bodys.size() == 1 及 bodys.size() == 0
                final MessageBody body = ctx.getBody();
                return null == body ? bodySubject : Observable.just(body).concatWith(bodySubject);
            }
            else {
                ctx.doMakeSlice(() -> bbs.step());
                final MessageBody body = ctx.getBody();
                return null == body ? Observable.empty() : Observable.just(body);
            }
        });
    }

    private void doParse(final ParseContext ctx,
            final PublishSubject<MessageBody> bodySubject,
            final Action0 dostep) {
        while (ctx.noMakeSlice() && ctx.canParsing()) {
            ctx.parse();
        }

        if (ctx.noMakeSlice()) {
            // this bbs has been consumed
            bodySubject.onCompleted();
            // no downstream msgbody or slice generate, auto step updtgream
            dostep.call();
        }
        else if (ctx.canParsing()) {
            // can continue parsing
            // makeslices.size() == 1
            ctx.doMakeSlice(() -> doParse(ctx, bodySubject, dostep));
            //  如果该 bbs 是上一个 part 的结尾部分，并附带了后续的1个或多个 part (部分内容)
            //  均可能出现 makeslices.size() == 1，但 bodys.size() == 0 的情况
            //  因此需要分别处理 bodys.size() == 1 及 bodys.size() == 0
            final MessageBody body = ctx.getBody();
            if (null != body) {
                bodySubject.onNext(body);
            }
        }
        else {
            ctx.doMakeSlice(dostep);
            final MessageBody body = ctx.getBody();

            if (null != body) {
                bodySubject.onNext(body);
            }

            // this bbs has been consumed
            bodySubject.onCompleted();
        }
    }

    private static ByteBufSlice dwbs2bbs(final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs, final Action0 dostep) {
        final Subscription subscription = Subscriptions.create(dostep);
        return new ByteBufSlice() {
            @Override
            public void step() {
                subscription.unsubscribe();
            }

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return dwbs;
            }};
    }

    private final AppendableCharSequence _seq;
    private final LineParser _lineParser;

    private final Func0<DisposableWrapper<? extends ByteBuf>> _allocator;
    private final byte[] _CRLFBoundary;
    private final byte[] _boundaryCRLF;
    private final byte[] _endBoundary;
}
