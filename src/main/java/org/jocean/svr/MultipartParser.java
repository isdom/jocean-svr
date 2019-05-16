package org.jocean.svr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageBody;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Stepable;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.ByteProcessors;
import org.jocean.netty.util.ByteProcessors.IndexOfBytesProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.subjects.PublishSubject;

//  HEX DUMP for multipart/form-data with 2 tiny files part
//+-------------------------------------------------+
//|  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
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
//|000001b0| 64 61 74 61 3b 20 6e 61 6d 65 3d 22 66 69 6c 65 |data; name="file|
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

public class MultipartParser implements Transformer<ByteBufSlice, MessageBody> {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(MultipartParser.class);

    public MultipartParser(final Func0<DisposableWrapper<? extends ByteBuf>> allocator, final String multipartDataBoundary) {
        this._allocator = allocator;
        this._CRLFBoundary = ("\r\n" + multipartDataBoundary).getBytes(Charsets.UTF_8);
    }

    private static final byte[] TAG_CRLFCRLF = new byte[]{0x0d, 0x0a, 0x0d, 0x0a};
    private static final byte[] TAG_DASHDASH = "--".getBytes(CharsetUtil.UTF_8);

    interface MakeSlice extends Action1<Stepable<?>> {
    }

    interface ParseContext {
        BufsInputStream<DisposableWrapper<? extends ByteBuf>> in();
        Iterable<DisposableWrapper<? extends ByteBuf>> in2dwbs(final int size);
        void appendMakeSlice(MakeSlice makeSlice);
        void appendMessageBody(MessageBody body);
        void stopParsing();
    }

    interface SliceParser {
        SliceParser parse(ParseContext ctx);
    }

    private final SliceParser _headerParser = new SliceParser() {
        @Override
        public SliceParser parse(final ParseContext ctx) {
            final int bodyStartIdx = ctx.in().forEachByte(ByteProcessors.indexOfBytes(TAG_CRLFCRLF));

            if (bodyStartIdx >= 0) {
                final int offset = bodyStartIdx + 1;
                try {
                    // TODO, parse single part's http headers
                    ctx.in().skip(offset);
                } catch (final IOException e) {
                    // TODO
                    e.printStackTrace();
                }
                // switch state to recv body
                return new BodyParser();
            }
            else {
                ctx.stopParsing();
                return this;
            }
        }
    };

    class BodyParser implements SliceParser {
        @Override
        public SliceParser parse(final ParseContext ctx) {
            final IndexOfBytesProcessor ibp = ByteProcessors.indexOfBytes(_CRLFBoundary);
            final int bodyEndIdx = ctx.in().forEachByte(ibp);
            if (bodyEndIdx > 0) {
                final int length = bodyEndIdx - _CRLFBoundary.length + 1;
                final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs = ctx.in2dwbs(length);
                if (null == _subject) {
                    // begin of MessageBody
                    ctx.appendMakeSlice(stepable -> {
                        final ByteBufSlice content = dwbs2bbs(dwbs, stepable);
                        ctx.appendMessageBody(new MessageBody() {
                            @Override
                            public String contentType() {
                                // TODO, try to parse content-type from part's http headers
                                return null;
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
                    ctx.appendMakeSlice(stepable -> _subject.onNext(dwbs2bbs(dwbs, stepable)) );
                }
                try {
                    ctx.in().skip(_CRLFBoundary.length);
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
                        ctx.appendMakeSlice( stepable -> {
                            final ByteBufSlice content = dwbs2bbs(dwbs, stepable);
                            ctx.appendMessageBody(new MessageBody() {
                                @Override
                                public String contentType() {
                                    // TODO, try to parse content-type from part's http headers
                                    return null;
                                }
                                @Override
                                public int contentLength() {
                                    // TODO, try to parse content-length from part's http headers
                                    return -1;
                                }
                                @Override
                                public Observable<? extends ByteBufSlice> content() {
                                    return Observable.just(content).concatWith(_subject);
                                }});
                        });
                    }
                    else {
                        ctx.appendMakeSlice(stepable -> _subject.onNext(dwbs2bbs(dwbs, stepable)) );
                    }
                }
                ctx.stopParsing();
                return this;
            }
        }

        private PublishSubject<ByteBufSlice> _subject;
    }

    private final SliceParser _endParser = new SliceParser() {
        @Override
        public SliceParser parse(final ParseContext ctx) {
            if (ctx.in().available() >= 2) {
                if (ctx.in().forEachByte(ByteProcessors.indexOfBytes(TAG_DASHDASH)) == 1) {
                    // if meet end of multipart flag: (_multipartDataBoundary + "--")
                    //  meet end of multipart
                    return null;
                }
                else {
                    return _headerParser;
                }
            }
            ctx.stopParsing();
            return this;
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
            final List<MakeSlice> makeslices = new ArrayList<>();
            final List<MessageBody> bodys = new ArrayList<>();

            final AtomicBoolean parsing = new AtomicBoolean(true);

            final ParseContext ctx = new ParseContext() {

                @Override
                public BufsInputStream<DisposableWrapper<? extends ByteBuf>> in() {
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
                public void appendMakeSlice(final MakeSlice makeSlice) {
                    makeslices.add(makeSlice);
                }

                @Override
                public void appendMessageBody(final MessageBody body) {
                    bodys.add(body);
                }

                @Override
                public void stopParsing() {
                    parsing.set(false);
                }};

            while (bufin.available() > 0 && parsing.get() && null != currentParser.get()) {
                currentParser.set(currentParser.get().parse(ctx));
            }

            if (makeslices.isEmpty()) {
                // no downstream msgbody or slice generate, auto step updtgream
                bbs.step();
                return Observable.empty();
            }

            // make sure forward stepable.step() on last slice
            while (makeslices.size() > 1) {
                makeslices.remove(0).call(null);
            }

            makeslices.remove(0).call(bbs);

            if (bodys.isEmpty()) {
                return Observable.empty();
            }
            else {
                return Observable.from(bodys);
            }
        });
    }

    private static ByteBufSlice dwbs2bbs(final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs, final Stepable<?> upstream) {
        return new ByteBufSlice() {
            @Override
            public void step() {
                if (null != upstream) {
                    upstream.step();
                }
            }

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return dwbs;
            }};
    }

    private final Func0<DisposableWrapper<? extends ByteBuf>> _allocator;
    private final byte[] _CRLFBoundary;
}
