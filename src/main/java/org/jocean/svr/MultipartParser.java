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
