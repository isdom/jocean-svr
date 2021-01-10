/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jocean.svr.wsin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.jocean.http.server.mbean.InboundMXBean;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * An HTTP server which serves Web Socket requests at:
 *
 * http://localhost:8080/websocket
 *
 * Open your browser at <a href="http://localhost:8080/">http://localhost:8080/</a>, then the demo page will be loaded
 * and a Web Socket connection will be made automatically.
 *
 * This server illustrates support for the different web socket specification versions and will work with:
 *
 * <ul>
 * <li>Safari 5+ (draft-ietf-hybi-thewebsocketprotocol-00)
 * <li>Chrome 6-13 (draft-ietf-hybi-thewebsocketprotocol-00)
 * <li>Chrome 14+ (draft-ietf-hybi-thewebsocketprotocol-10)
 * <li>Chrome 16+ (RFC 6455 aka draft-ietf-hybi-thewebsocketprotocol-17)
 * <li>Firefox 7+ (draft-ietf-hybi-thewebsocketprotocol-10)
 * <li>Firefox 11+ (RFC 6455 aka draft-ietf-hybi-thewebsocketprotocol-17)
 * </ul>
 */
public final class WebSocketServer implements MBeanRegisterAware {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);

//    static final boolean SSL = System.getProperty("ssl") != null;

    @Value("${wsin.port:0}")
    int wsport;

    @Value("${app.name}")
    String appname;

    @Value("${wsin.mbeanname:wsin}")
    String _mbeanName;

    EventLoopGroup bossGroup;
    EventLoopGroup workerGroup;
    Channel binded;

    private String _hostname;
    private String _hostip;

    private int _bindedPort;
    private String _bindedIp;

    private MBeanRegister _register;


    public void start() throws Exception {
        String hostname = "unknown";
        String hostip = "0.0.0.0";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
            hostip = InetAddress.getByName(hostname).getHostAddress();
        } catch (final UnknownHostException e) {
        }
        _hostname = hostname;
        _hostip = hostip;

        final String wspath = "/wsin/" + hostname + "/" + appname;
        LOG.info("start wsin with path:{}", wspath);

        // Configure SSL.
//        final SslContext sslCtx;
//        if (SSL) {
//            final SelfSignedCertificate ssc = new SelfSignedCertificate();
//            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
//        } else {
//            sslCtx = null;
//        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        final ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .handler(new LoggingHandler(LogLevel.INFO))
         .childHandler(new WebSocketServerInitializer(/*sslCtx*/null, wspath));

        binded = b.bind(wsport).sync().channel();

        if (binded.localAddress() instanceof InetSocketAddress) {
            final InetSocketAddress addr = (InetSocketAddress)binded.localAddress();
            _bindedPort = addr.getPort();
            _bindedIp = null != addr.getAddress()
                    ? addr.getAddress().getHostAddress()
                    : "0.0.0.0";
        }

        _register.registerMBean("name=" + _mbeanName+",address=" + _bindedIp.replace(':', '_')
                +",port=" + _bindedPort, new InboundMXBean() {

                    @Override
                    public String getHost() {
                        return _hostname;
                    }

                    @Override
                    public String getHostIp() {
                        return _hostip;
                    }

                    @Override
                    public String getBindIp() {
                        return _bindedIp;
                    }

                    @Override
                    public int getPort() {
                        return _bindedPort;
                    }});
    }

    public void stop() {
        try {
            binded.close().sync();
        } catch (final InterruptedException e) {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        this._register = register;
    }
}