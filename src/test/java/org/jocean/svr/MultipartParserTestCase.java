package org.jocean.svr;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Arrays;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageBody;
import org.jocean.http.MessageUtil;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.Stepable;
import org.jocean.netty.util.BufsInputStream;
import org.junit.Test;

import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.functions.Action1;
import rx.observers.TestSubscriber;

public class MultipartParserTestCase {

//  HEX DUMP for multipart/form-data with 2 tiny files part
//  +-------------------------------------------------+
//  |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
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

    final private static byte[] body = new byte[]{
        0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,
        0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x34,0x37,0x31,0x31,
        0x38,0x34,0x36,0x34,0x35,0x34,0x30,0x36,0x34,0x30,0x36,0x34,0x32,0x30,0x34,0x37,
        0x34,0x33,0x39,0x39,0x0d,0x0a,0x43,0x6f,0x6e,0x74,0x65,0x6e,0x74,0x2d,0x44,0x69,
        0x73,0x70,0x6f,0x73,0x69,0x74,0x69,0x6f,0x6e,0x3a,0x20,0x66,0x6f,0x72,0x6d,0x2d,
        0x64,0x61,0x74,0x61,0x3b,0x20,0x6e,0x61,0x6d,0x65,0x3d,0x22,0x66,0x69,0x6c,0x65,
        0x31,0x22,0x3b,0x20,0x66,0x69,0x6c,0x65,0x6e,0x61,0x6d,0x65,0x3d,0x22,0x31,0x2e,
        0x74,0x78,0x74,0x22,0x0d,0x0a,0x43,0x6f,0x6e,0x74,0x65,0x6e,0x74,0x2d,0x54,0x79,
        0x70,0x65,0x3a,0x20,0x74,0x65,0x78,0x74,0x2f,0x70,0x6c,0x61,0x69,0x6e,0x0d,0x0a,
        0x0d,0x0a,0x41,0x42,0x43,0x44,0x45,0x0d,0x0a,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,
        0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,
        0x2d,0x2d,0x2d,0x2d,0x2d,0x34,0x37,0x31,0x31,0x38,0x34,0x36,0x34,0x35,0x34,0x30,
        0x36,0x34,0x30,0x36,0x34,0x32,0x30,0x34,0x37,0x34,0x33,0x39,0x39,0x0d,0x0a,0x43,
        0x6f,0x6e,0x74,0x65,0x6e,0x74,0x2d,0x44,0x69,0x73,0x70,0x6f,0x73,0x69,0x74,0x69,
        0x6f,0x6e,0x3a,0x20,0x66,0x6f,0x72,0x6d,0x2d,0x64,0x61,0x74,0x61,0x3b,0x20,0x6e,
        0x61,0x6d,0x65,0x3d,0x22,0x66,0x69,0x6c,0x65,0x32,0x22,0x3b,0x20,0x66,0x69,0x6c,
        0x65,0x6e,0x61,0x6d,0x65,0x3d,0x22,0x32,0x2e,0x74,0x78,0x74,0x22,0x0d,0x0a,0x43,
        0x6f,0x6e,0x74,0x65,0x6e,0x74,0x2d,0x54,0x79,0x70,0x65,0x3a,0x20,0x74,0x65,0x78,
        0x74,0x2f,0x70,0x6c,0x61,0x69,0x6e,0x0d,0x0a,0x0d,0x0a,0x30,0x31,0x32,0x33,0x34,
        0x35,0x36,0x0d,0x0a,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,
        0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,0x2d,
        0x34,0x37,0x31,0x31,0x38,0x34,0x36,0x34,0x35,0x34,0x30,0x36,0x34,0x30,0x36,0x34,
        0x32,0x30,0x34,0x37,0x34,0x33,0x39,0x39,0x2d,0x2d,0x0d,0x0a
    };

    @Test
    public final void testMultipartParser1() {
        final DisposableWrapper<? extends ByteBuf> dwb = DisposableWrapperUtil.wrap(Unpooled.wrappedBuffer(body), (Action1<ByteBuf>)null);

        final MultipartParser parser = new MultipartParser(MessageUtil.pooledAllocator(null, 128),
                "--" + "--------------------------471184645406406420474399");

        final TestSubscriber<MessageBody> bodySubscriber = new TestSubscriber<>();
        Observable.just(dwbs2bbs(Arrays.asList(dwb), null)).compose(parser).subscribe(bodySubscriber);

        assertEquals(1, bodySubscriber.getValueCount());
        bodySubscriber.assertNoTerminalEvent();
        assertEquals("text/plain", bodySubscriber.getOnNextEvents().get(0).contentType());
        assertEquals("ABCDE", content2bytes(bodySubscriber.getOnNextEvents().get(0).content()));

        // msgbody NO.2 step by content2bytes
        assertEquals(2, bodySubscriber.getValueCount());

        assertEquals("text/plain", bodySubscriber.getOnNextEvents().get(1).contentType());
        assertEquals("0123456", content2bytes(bodySubscriber.getOnNextEvents().get(1).content()));

        bodySubscriber.assertCompleted();
    }

    //  TODO，增加更多的片段组合测试
    //  * part 首部不完整,
    //  * 多 part , 单个 part 分散在前后连续的多个 part 中
    //  * 单个 ByteBufSlice 中 中 包含1个或多个 part， 第一个和最后一个 part 不完整 (首部 及 body 部分)

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

    private String content2bytes(final Observable<? extends ByteBufSlice> content) {
        final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(dwb -> dwb.unwrap(),
                dwb -> {});
        bufin.markEOS();

        content.doOnNext(bbs -> {
            bufin.appendIterable(bbs.element());
            bbs.step();
        }).last().toBlocking().single();

        try {
            return new String(ByteStreams.toByteArray(bufin), CharsetUtil.UTF_8);
        } catch (final IOException e) {
        }
        return "";
    }
}
