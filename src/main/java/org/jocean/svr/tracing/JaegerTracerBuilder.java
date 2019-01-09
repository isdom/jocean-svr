package org.jocean.svr.tracing;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.jaegertracing.internal.JaegerTracer;
import io.opentracing.Tracer;
import io.opentracing.contrib.jdbc.TracingDriver;

public class JaegerTracerBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(JaegerTracerBuilder.class);

    @Value("${app.name}")
    String _serviceName = "unknown";

    @Value("${endpoint}")
    String _endpoint;

    @Value("${username}")
    String _username;

    @Value("${password}")
    String _password;

    public Tracer build() {
        final io.jaegertracing.Configuration config = new io.jaegertracing.Configuration(_serviceName);
        final io.jaegertracing.Configuration.SenderConfiguration sender = new io.jaegertracing.Configuration.SenderConfiguration();
        /**
           *  从链路追踪控制台获取网关（Endpoint）、用户名、密码（userkey）
           *  第一次运行时，请设置当前用户的网关、用户名、密码（userkey）
        */
        sender.withEndpoint(_endpoint);
        // 设置用户名
        sender.withAuthUsername(_username);
        // 设置密码（userkey）
        sender.withAuthPassword(_password);

        config.withSampler(new io.jaegertracing.Configuration.SamplerConfiguration().withType("const").withParam(1));
        config.withReporter(new io.jaegertracing.Configuration.ReporterConfiguration().withSender(sender).withMaxQueueSize(10000));
        final JaegerTracer tracer = config.getTracer();

        setTracer4JDBC(tracer);

        return tracer;
    }

    private void setTracer4JDBC(final Tracer tracer) {
        try {
            final TracingDriver INSTANCE = ReflectUtils.getStaticFieldValue("io.opentracing.contrib.jdbc.TracingDriver.INSTANCE");
            INSTANCE.setTracer(tracer);
        } catch (final Exception e) {
            LOG.warn("exception when setTracer4JDBC, detail: {}", ExceptionUtils.exception2detail(e));
        }
    }
}
