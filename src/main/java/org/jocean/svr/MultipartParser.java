package org.jocean.svr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageBody;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.ByteProcessors;
import org.jocean.netty.util.ByteProcessors.IndexOfBytesProcessor;

import com.google.common.base.Charsets;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func0;
import rx.subjects.PublishSubject;

public class MultipartParser implements Transformer<ByteBufSlice, MessageBody> {

    MultipartParser(final Func0<DisposableWrapper<? extends ByteBuf>> allocator, final String multipartDataBoundary) {
        this._allocator = allocator;
        this._multipartDataBoundary = multipartDataBoundary;
        this._CRLFBoundary = ("\r\n" + multipartDataBoundary).getBytes(Charsets.UTF_8);
    }

    private static final byte[] TAG_CRLFCRLF = new byte[]{0x0d, 0x0a, 0x0d, 0x0a};
    private static final byte[] TAG_DASHDASH = "--".getBytes(CharsetUtil.UTF_8);

    interface SliceParser {
        SliceParser parse(BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin,
                BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
                byte[] buf);
        MessageBody msgbody();
        boolean needAutostep();
    }

    class HeaderParser implements SliceParser {
        @Override
        public SliceParser parse(final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin,
                final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
                final byte[] buf) {
            final int bodyStartIdx = bufin.forEachByte(ByteProcessors.indexOfBytes(TAG_CRLFCRLF));

            if (bodyStartIdx >= 0) {
                final int offset = bodyStartIdx + 1;
                try {
                    bufin.skip(offset);
                } catch (final IOException e) {
                    e.printStackTrace();
                } // TODO, parse single part's http headers
                // switch state to recv body
                return new BodyParser();
            }
            else {
                return this;
            }
        }

        @Override
        public MessageBody msgbody() {
            return null;
        }

        @Override
        public boolean needAutostep() {
            return true;
        }
    }

    class BodyParser implements SliceParser {
        @Override
        public SliceParser parse(final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin,
                final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
                final byte[] buf) {
            final List<DisposableWrapper<? extends ByteBuf>> dwbs = new ArrayList<>();
            bufout.setOutput(dwb -> dwbs.add(dwb));

            final IndexOfBytesProcessor ibp = ByteProcessors.indexOfBytes(_CRLFBoundary);
            final int bodyEndIdx = bufin.forEachByte(ibp);
            if (bodyEndIdx > 0) {
                final int length = bodyEndIdx - _CRLFBoundary.length + 1;
                try {
                    in2out(length, bufin, bufout, buf);
                } catch (final Exception e) {
                }
                if (null == _subject) {
                    final ByteBufSlice content = dwbs2bbs(dwbs, null);
                    _body = new MessageBody() {
                        @Override
                        public String contentType() {
                            return null;
                        }
                        @Override
                        public int contentLength() {
                            return -1;
                        }
                        @Override
                        public Observable<? extends ByteBufSlice> content() {
                            return Observable.just(content);
                        }};
                }
                else {
                    _subject.onNext(dwbs2bbs(dwbs, null));
                }
                try {
                    bufin.skip(_CRLFBoundary.length);
                } catch (final Exception e) {
                }

                /*
                if (bufin.available() == 2) { // if meet end of multipart flag: (_multipartDataBoundary + "--")
                    if (bufin.forEachByte(ByteProcessors.indexOfBytes(TAG_DASHDASH)) == 1) {
                        //  end of multipart
                        //  TODO
                        return false;
                    }
                }
                */
                return new HeaderParser();
            }
            else {
                // check ibp's matchedCount
                if (ibp.matchedCount() > 0) {
                    // maybe end tag in-complete, skip push body part, wait for next recving
                    _autostep = true;
                    return this;
                }
                else {
                    // pure body part
                    try {
                        in2out(bufin.available(), bufin, bufout, buf);
                    } catch (final Exception e) {
                    }

                    if (null == _subject) {
                        _subject = PublishSubject.create();
                        final ByteBufSlice content = dwbs2bbs(dwbs, null);
                        _body = new MessageBody() {
                            @Override
                            public String contentType() {
                                return null;
                            }
                            @Override
                            public int contentLength() {
                                return -1;
                            }
                            @Override
                            public Observable<? extends ByteBufSlice> content() {
                                return Observable.just(content).concatWith(_subject);
                            }};
                    }
                    else {
                        _subject.onNext(dwbs2bbs(dwbs, null));
                    }
                    return this;
                }
            }
        }

        @Override
        public MessageBody msgbody() {
            final MessageBody ret = _body;
            _body = null;
            return ret;
        }

        @Override
        public boolean needAutostep() {
            return _autostep;
        }

        private boolean _autostep;
        private MessageBody _body;
        private PublishSubject<ByteBufSlice> _subject;
    }

    @Override
    public Observable<MessageBody> call(final Observable<ByteBufSlice> content) {
        final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(dwb -> dwb.unwrap(), dwb -> dwb.dispose());
        final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(_allocator, dwb->dwb.unwrap());
        final byte[] buf = new byte[512];

        final AtomicReference<SliceParser> currentParser = new AtomicReference<>(new HeaderParser());

        return content.flatMap(bbs -> {
            bufin.appendIterable(bbs.element());
            bufin.markEOS();
            final List<MessageBody> bodys = new ArrayList<>();

            SliceParser lastParser = null;
            try {
                while (bufin.available() > 0) {
                    final SliceParser nextParser = currentParser.get().parse(bufin, bufout, buf);
                    lastParser = currentParser.get();
                    currentParser.set(nextParser);
                    final MessageBody body = lastParser.msgbody();
                    if (null != body) {
                        bodys.add(body);
                    }
                }
            } catch (final Exception e) {
                // TODO
            }

            if (null != lastParser) {
                if (lastParser.needAutostep()) {
                    bbs.step();
                }
            }
            else {
                bbs.step();
            }

            if (bodys.isEmpty()) {
                return Observable.empty();
            }
            else {
                return Observable.from(bodys);
            }
        });
    }

    private static ByteBufSlice dwbs2bbs(final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs, final ByteBufSlice upstream) {
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

    private void in2out(int length,
            final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
            final byte[] buf) throws IOException {
        while (length > 0) {
            final int toread = Math.min(buf.length, length);
            final int readed = bufin.read(buf, 0, toread);
            if (readed > 0) {
                bufout.write(buf, 0, readed);
            }
            length -= readed;
        }
        bufout.flush();
    }

    private final Func0<DisposableWrapper<? extends ByteBuf>> _allocator;
    private final String _multipartDataBoundary;
    private final byte[] _CRLFBoundary;
}
