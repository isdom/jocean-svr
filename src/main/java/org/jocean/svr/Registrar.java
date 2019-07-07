/**
 *
 */
package org.jocean.svr;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.ContentDecoder;
import org.jocean.http.ContentEncoder;
import org.jocean.http.ContentUtil;
import org.jocean.http.FullMessage;
import org.jocean.http.InteractBuilder;
import org.jocean.http.MessageBody;
import org.jocean.http.MessageUtil;
import org.jocean.http.RpcExecutor;
import org.jocean.http.WriteCtrl;
import org.jocean.http.internal.DefaultRpcExecutor;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.Beans;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Haltable;
import org.jocean.idiom.HaltableUtil;
import org.jocean.idiom.Pair;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.Regexs;
import org.jocean.idiom.Stepable;
import org.jocean.idiom.StepableUtil;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.jocean.j2se.spring.SpringBeanHolder;
import org.jocean.j2se.tracing.Tracing;
import org.jocean.j2se.unit.UnitAgent;
import org.jocean.j2se.unit.UnitListener;
import org.jocean.j2se.util.BeanHolders;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.opentracing.TracingUtil;
import org.jocean.svr.FinderUtil.CallerContext;
import org.jocean.svr.ZipUtil.Unzipper;
import org.jocean.svr.ZipUtil.ZipBuilder;
import org.jocean.svr.ZipUtil.Zipper;
import org.jocean.svr.mbean.RestinIndicator;
import org.jocean.svr.tracing.TraceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Controller;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.opentracing.References;
import io.opentracing.Span;
import io.opentracing.Tracer;
import rx.Completable;
import rx.Observable;
import rx.Scheduler;
import rx.functions.Actions;
import rx.functions.Func0;

/**
 * @author isdom
 */
public class Registrar implements BeanHolderAware, MBeanRegisterAware {

    private static final Logger LOG = LoggerFactory.getLogger(Registrar.class);

    static class DefaultTradeContext implements TradeContext {

        DefaultTradeContext(final HttpTrade trade, final Tracer tracer, final Span span, final TradeScheduler ts) {
            this._trade = trade;
            this._tracer = tracer;
            this._span = span;
            this._ts = ts;
        }

        @Override
        public WriteCtrl writeCtrl() {
            return _trade.writeCtrl();
        }

        @Override
        public Haltable haltable() {
            return _trade;
        }

        @Override
        public AllocatorBuilder allocatorBuilder() {
            return buildAllocatorBuilder(_trade);
        }

        @Override
        public InteractBuilder interactBuilder() {
            return new InteractBuilderImpl(_trade, _span, Observable.just(_tracer), _ts.scheduler());
        }

        public InteractBuilder interactBuilderOutofTrade(final Span parentSpan, final int delayInSeconds) {
            return new InteractBuilderImpl(HaltableUtil.delay(delayInSeconds, TimeUnit.SECONDS), parentSpan,
                    Observable.just(_tracer), _ts.scheduler());
        }

        @Override
        public void enableRepeatDecode() {
            if (!(this._trade instanceof AutoreadTrade)) {
                this._trade = AutoreadTrade.enableAutoread(_trade);
            }
        }

        @Override
        public <T> Observable<T> decodeBodyAs(final ContentDecoder decoder, final Class<T> type) {
            return _trade.inbound().flatMap(MessageUtil.fullmsg2body()).compose(MessageUtil.body2bean(decoder, type, Actions.empty()))
                    .doOnNext(TraceUtil.setTag4bean(_span, "req.bd.", "record.reqbean.error"));
        }

        @Override
        public <T> Observable<T> decodeBodyAs(final Class<T> type) {
            return decodeBodyAs(null, type);
        }

        @Override
        public TradeScheduler scheduler() {
            return _ts;
        }

        HttpTrade _trade;
        final Tracer _tracer;
        final Span _span;
        final TradeScheduler _ts;
    }

    public void start() {
        final ConfigurableListableBeanFactory[] factorys = this._beanHolder.allBeanFactory();
        for (final ConfigurableListableBeanFactory factory : factorys) {
            scanAndRegisterResource(factory);
        }
        if (this._beanHolder instanceof UnitAgent) {
            final UnitAgent agent = (UnitAgent)this._beanHolder;
            agent.addUnitListener(this._unitListener);
        }
    }

    public void stop() {
        if (this._beanHolder instanceof UnitAgent) {
            final UnitAgent agent = (UnitAgent)this._beanHolder;
            agent.removeUnitListener(this._unitListener);
        }
        this._resCtxs.clear();
        this._pathMatchers.clear();
    }

    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = (SpringBeanHolder) beanHolder;
    }

    private void scanAndRegisterResource(final ConfigurableListableBeanFactory factory) {
        for ( final String name : factory.getBeanDefinitionNames() ) {
            final BeanDefinition def = factory.getBeanDefinition(name);
            if (null!=def && null != def.getBeanClassName()) {
                try {
                    final Class<?> cls = Class.forName(def.getBeanClassName());
                    if ( null!= cls.getAnnotation(Controller.class)) {
                        register(cls);
                    }
                } catch (final Exception e) {
                    LOG.warn("exception when scanAndRegisterResource, detail: {}",
                            ExceptionUtils.exception2detail(e));
                }
            } else {
                LOG.warn("scanAndRegisterResource: bean named {} 's definition is empty.", name);
            }
        }
    }

    private void unregisterAllResource(final ConfigurableListableBeanFactory factory) {
        for ( final String name : factory.getBeanDefinitionNames() ) {
            final BeanDefinition def = factory.getBeanDefinition(name);
            if (null!=def && null != def.getBeanClassName()) {
                try {
                    final Class<?> cls = Class.forName(def.getBeanClassName());
                    if ( null!= cls.getAnnotation(Controller.class)) {
                        unregister(cls);
                    }
                } catch (final Exception e) {
                    LOG.warn("exception when unregisterAllResource, detail: {}",
                            ExceptionUtils.exception2detail(e));
                }
            } else {
                LOG.warn("unregisterAllResource: bean named {} 's definition is empty.", name);
            }
        }
    }

    public void setClasses(final Set<Class<?>> classes) {
        this._resCtxs.clear();
        this._pathMatchers.clear();
        for (final Class<?> cls : classes) {
            this.register(cls);
        }
    }

    public void setPathPattern(final String pathPattern) {
        this._pathPattern = Regexs.safeCompilePattern(pathPattern);;
    }

    public Registrar register(final Class<?> cls) {

        final Class<?> resourceCls = checkNotNull(cls);

        // maybe ""
        final String rootPath = SvrUtil.getPathOfClass(resourceCls);

        final Method[] restMethods = ReflectUtils.getAnnotationMethodsOf(resourceCls, Path.class);

        for (final Method m : restMethods) {
            final String methodPath = SvrUtil.genMethodPathOf(rootPath, m);
            if (Regexs.isMatched(this._pathPattern, methodPath)) {
                final PathMatcher pathMatcher = PathMatcher.create(methodPath);
                if (null != pathMatcher) {
                    // Path !WITH! parameters
                    if (registerProcessorWithHttpMethod(resourceCls, m, methodPath, pathMatcher, GET.class)
                            + registerProcessorWithHttpMethod(resourceCls, m, methodPath, pathMatcher, POST.class)
                            + registerProcessorWithHttpMethod(resourceCls, m, methodPath, pathMatcher, PUT.class)
                            + registerProcessorWithHttpMethod(resourceCls, m, methodPath, pathMatcher, DELETE.class)
                            + registerProcessorWithHttpMethod(resourceCls, m, methodPath, pathMatcher, PATCH.class)
                            + registerProcessorWithHttpMethod(resourceCls, m, methodPath, pathMatcher, HEAD.class)
                            + registerProcessorWithHttpMethod(resourceCls, m, methodPath, pathMatcher, OPTIONS.class) == 0) {
                        // NO HttpMethod annotation exist
                        // register with all methods
                        final ResContext resctx = new ResContext(resourceCls, m);
                        this._pathMatchers.put("GET", Pair.of(pathMatcher, resctx));
                        this._pathMatchers.put("POST", Pair.of(pathMatcher, resctx));
                        this._pathMatchers.put("PUT", Pair.of(pathMatcher, resctx));
                        this._pathMatchers.put("DELETE", Pair.of(pathMatcher, resctx));
                        this._pathMatchers.put("PATCH", Pair.of(pathMatcher, resctx));
                        this._pathMatchers.put("HEAD", Pair.of(pathMatcher, resctx));
                        this._pathMatchers.put("OPTIONS", Pair.of(pathMatcher, resctx));
                    }
                } else {
                    // Path without parameters
                    registerProcessorWithFullpath(resourceCls, m, methodPath);
                }
            }
        }
        return this;
    }

    private int registerProcessorWithHttpMethod(final Class<?> resourceCls,
            final Method m,
            final String methodPath,
            final PathMatcher pathMatcher,
            final Class<? extends Annotation> hmtype) {
        if (null!=m.getAnnotation(hmtype)) {
            final javax.ws.rs.HttpMethod rsHttpMethod =
                    hmtype.getAnnotation(javax.ws.rs.HttpMethod.class);
            final ResContext resctx = new ResContext(resourceCls, m);
            this._pathMatchers.put(rsHttpMethod.value(), Pair.of(pathMatcher, resctx));
            if (LOG.isDebugEnabled()) {
                LOG.debug("register Method {} for !Parametered! Path {} with matcher {} & resctx {}",
                        rsHttpMethod.value(), methodPath, pathMatcher, resctx);
            }
            return 1;
        } else {
            return 0;
        }
    }

    private void registerProcessorWithFullpath(final Class<?> resourceCls, final Method method, final String path) {
        if (registerProcessorWithHttpMethod(resourceCls, method, path, GET.class)
            + registerProcessorWithHttpMethod(resourceCls, method, path, POST.class)
            + registerProcessorWithHttpMethod(resourceCls, method, path, PUT.class)
            + registerProcessorWithHttpMethod(resourceCls, method, path, DELETE.class)
            + registerProcessorWithHttpMethod(resourceCls, method, path, PATCH.class)
            + registerProcessorWithHttpMethod(resourceCls, method, path, HEAD.class)
            + registerProcessorWithHttpMethod(resourceCls, method, path, OPTIONS.class) == 0) {
            // NO HttpMethod annotation exist
            // register with only path
            this._resCtxs.put(path, new ResContext(resourceCls, method));
            if (LOG.isDebugEnabled()) {
                LOG.debug("register Path {}", path);
            }
        }
    }

    private int registerProcessorWithHttpMethod(final Class<?> resourceCls,
            final Method m,
            final String methodPath,
            final Class<? extends Annotation> hmtype) {
        if (null!=m.getAnnotation(hmtype)) {
            final javax.ws.rs.HttpMethod rsHttpMethod =
                    hmtype.getAnnotation(javax.ws.rs.HttpMethod.class);
            this._resCtxs.put(rsHttpMethod.value() + ":" + methodPath, new ResContext(resourceCls, m));
            if (LOG.isDebugEnabled()) {
                LOG.debug("register Method {}/Path {}", rsHttpMethod.value(), methodPath);
            }
            return 1;
        } else {
            return 0;
        }
    }

    public Registrar unregister(final Class<?> cls) {
        LOG.info("unregister {}'s entry.", cls);
        {
            final Iterator<Map.Entry<String, ResContext>> itr =
                    this._resCtxs.entrySet().iterator();
            while ( itr.hasNext()) {
                final Map.Entry<String, ResContext> entry = itr.next();
                if (entry.getValue()._cls.equals(cls)) {
                    itr.remove();
                    LOG.info("remove {} from resources.", entry.getKey());
                }
            }
        }

        {
            final Iterator<Map.Entry<String, Pair<PathMatcher, ResContext>>> itr =
                    this._pathMatchers.entries().iterator();
            while ( itr.hasNext()) {
                final Map.Entry<String, Pair<PathMatcher, ResContext>> entry = itr.next();
                if (entry.getValue().second._cls.equals(cls)) {
                    itr.remove();
                    LOG.info("remove {} from _pathMatchers.", entry.getKey());
                }
            }
        }
        return this;
    }

    static class ArgsCtx {
        public Method processor;
        public Type[] genericParameterTypes;
        public Annotation[][] parameterAnnotations;
        public HttpTrade trade;
        public HttpRequest request;
        public Map<String, String> pathParams;
        public MethodInterceptor[] interceptors;

        public ArgsCtx(final Method processor,
                final Type[] genericParameterTypes,
                final Annotation[][] parameterAnnotations,
                final HttpTrade trade,
                final HttpRequest request,
                final Map<String, String> pathParams,
                final MethodInterceptor[] interceptors) {
            this.processor = processor;
            this.genericParameterTypes = genericParameterTypes;
            this.parameterAnnotations = parameterAnnotations;
            this.trade = trade;
            this.request = request;
            this.pathParams = pathParams;
            this.interceptors = interceptors;
        }
    }

    public Observable<? extends Object> buildResource(
            final HttpRequest request,
            final HttpTrade trade,
            final Tracer tracer,
            final Span span,
            final TradeScheduler ts,
            final RestinIndicator restin) throws Exception {

        // try direct path match
        final Pair<ResContext, Map<String, String>> pair = findResourceCtx(request);

        if (null != pair) {
            final Method processor = selectProcessor(pair.first, request.method());

            final Object resource = this._beanHolder.getBean(pair.first._cls);

            if (null!=resource) {
                final String operationName = resource.getClass().getSimpleName() + "." + processor.getName();

                restin.incTradeCount(operationName);

                trade.doOnHalt(() -> restin.recordTradeDuration(operationName, System.currentTimeMillis() - trade.startTimeMillis()));

                span.setOperationName(operationName);

                final Deque<MethodInterceptor> interceptors = new LinkedList<>();
                final MethodInterceptor.Context interceptorCtx = new MethodInterceptor.Context() {
                    @Override
                    public Object resource() {
                        return resource;
                    }
                    @Override
                    public HttpRequest request() {
                        return request;
                    }
                    @Override
                    public Method processor() {
                        return processor;
                    }
                    @Override
                    public Observable<FullMessage<HttpRequest>> obsRequest() {
                        return trade.inbound();
                    }
                    @Override
                    public Observable<Object> obsResponse() {
                        return null;
                    }
                    @Override
                    public Completable requestCompleted() {
                        return trade.inboundCompleted();
                    }
                };
                final Observable<? extends Object> aheadObsResponse = doPreInvoke(interceptorCtx, interceptors);
                if (null != aheadObsResponse) {
                    //  interceptor 直接响应
                    return doPostInvoke(interceptors, copyCtxOverrideResponse(interceptorCtx, aheadObsResponse));
                } else {
                    final ArgsCtx argctx = new ArgsCtx(processor,
                            processor.getGenericParameterTypes(),
                            processor.getParameterAnnotations(),
                            trade,
                            request,
                            pair.second,
                            interceptors.toArray(new MethodInterceptor[0]));
                    final Observable<? extends Object> obsResponse = invokeProcessor(
                            trade,
                            request,
                            resource,
                            processor,
                            argctx,
                            tracer,
                            span,
                            ts);
                    return doPostInvoke(interceptors, copyCtxOverrideResponse(interceptorCtx, obsResponse));
                }
            }
        }
        return RxNettys.response404NOTFOUND(request.protocolVersion()).delaySubscription(
                trade.inbound().flatMap(fullmsg -> fullmsg.body()).flatMap(body -> body.content())
                .compose(StepableUtil.autostep2element2()).doOnNext(bbs -> bbs.dispose()).ignoreElements());
    }

    private Observable<? extends Object> invokeProcessor(
            final HttpTrade trade,
            final HttpRequest request,
            final Object resource,
            final Method processor,
            final ArgsCtx argsctx,
            final Tracer tracer,
            final Span span,
            final TradeScheduler ts
            ) {
        try {
            final DefaultTradeContext tctx = new DefaultTradeContext(trade, tracer, span, ts);
            final Object returnValue = processor.invoke(resource, buildArgs(resource, tctx, argsctx));
            if (null!=returnValue) {
                final Observable<? extends Object> obsResponse = returnValue2ObsResponse(
                        tctx,
                        request,
                        processor,
                        returnValue);
                if (null!=obsResponse) {
                    return obsResponse;
                }
            }
        } catch (final Exception e) {
            LOG.warn("exception when invoke process {}, detail: {}",
                    processor,
                    ExceptionUtils.exception2detail(e));
        }
        return RxNettys.response404NOTFOUND(request.protocolVersion());
        //  TODO
//                .delaySubscription(obsRequest.last());
    }

    @SuppressWarnings("unchecked")
    private Observable<? extends Object> returnValue2ObsResponse(
            final DefaultTradeContext tctx,
            final HttpRequest request,
            final Method processor,
            final Object returnValue) {
        if (isObservableType(processor.getGenericReturnType())) {
            //  return type is Observable<XXX>
            final Type gt1st = getGenericTypeOf(processor.getGenericReturnType(), 0);
            if (gt1st.equals(HttpObject.class)) {
                return (Observable<HttpObject>)returnValue;
            } else if (gt1st.equals(String.class)) {
                return strings2Response((Observable<String>)returnValue, request);
            } else /*if (gt1st.equals(Object.class))*/ {
                return objs2Response((Observable<Object>)returnValue, tctx, produceTypes(processor), request.protocolVersion());
            }
        } else if (null != returnValue) {
            if (returnValue instanceof Observable) {
                return objs2Response((Observable<Object>)returnValue, tctx, produceTypes(processor), request.protocolVersion());
            } else if (String.class.equals(returnValue.getClass())) {
                return strings2Response(Observable.just((String)returnValue), request);
            } else {
                return fullmsg2hobjs(fullmsgOf(returnValue, request.protocolVersion(), tctx, produceTypes(processor)));
            }
            // return is NOT Observable<?>
        }
        return null;
    }

    private Observable<? extends Object> doPreInvoke(
            final MethodInterceptor.Context ctx,
            final Deque<MethodInterceptor> interceptors) {
        final Class<? extends MethodInterceptor>[] types = interceptorTypesOf(ctx.resource().getClass());
        if (null != types && types.length > 0) {
            for (final Class<? extends MethodInterceptor> type : types) {
                try {
                    final MethodInterceptor interceptor = this._beanHolder.getBean(type);
                    if (null!=interceptor) {
                        final Observable<? extends Object> obsResponse = interceptor.preInvoke(ctx);
                        interceptors.addFirst(interceptor);
                        if (null != obsResponse) {
                            return obsResponse;
                        }
                    }
                } catch (final Exception e) {
                    LOG.warn("exception when preInvoke by interceptor type {}, detail: {}",
                            type, ExceptionUtils.exception2detail(e));
                }
            }
        }
        return null;
    }

    // TODO: 使用 cache 进行优化
    @SuppressWarnings("unchecked")
    private Class<? extends MethodInterceptor>[] interceptorTypesOf(final Class<?> cls) {
        final Class<? extends MethodInterceptor>[] inters4rt = this._type2interceptors.get(cls);
        final Class<? extends MethodInterceptor>[] inters4anno = getInterceptorTypesOfAnnotation(cls);
        if (null!=inters4rt && null==inters4anno) {
            return inters4rt;
        } else if (null==inters4rt && null!=inters4anno) {
            return inters4anno;
        } else if (null!=inters4rt && null!=inters4anno) {
            return Sets.union(ImmutableSet.copyOf(inters4rt), ImmutableSet.copyOf(inters4anno))
                    .toArray(new Class[0]);
        } else {
            for (final Map.Entry<String, Class<? extends MethodInterceptor>[]> entry :  this._pkg2interceptors.entrySet()) {
                if (cls.getPackage().getName().startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    private Class<? extends MethodInterceptor>[] getInterceptorTypesOfAnnotation(final Class<?> cls) {
        final Interceptors interceptorsAnno = cls.getAnnotation(Interceptors.class);
        if (null!=interceptorsAnno) {
            return interceptorsAnno.value();
        } else {
            return null;
        }
    }

    private Observable<? extends Object> doPostInvoke(
            final Collection<MethodInterceptor> interceptors,
            MethodInterceptor.Context ctx) {
        for (final MethodInterceptor interceptor : interceptors) {
            try {
                final Observable<? extends Object> obsResponse = interceptor.postInvoke(ctx);
                if (null != obsResponse) {
                    ctx = copyCtxOverrideResponse(ctx, obsResponse);
                }
            } catch (final Exception e) {
                LOG.warn("exception when get do {}.postInvoke, detail: {}",
                        interceptor, ExceptionUtils.exception2detail(e));
            }
        }
        return ctx.obsResponse();
    }

    private MethodInterceptor.Context copyCtxOverrideResponse(final MethodInterceptor.Context ctx,
            final Observable<? extends Object> obsResponse) {
        return new MethodInterceptor.Context() {
            @Override
            public Object resource() {
                return ctx.resource();
            }
            @Override
            public Method processor() {
                return ctx.processor();
            }

            @Override
            public HttpRequest request() {
                return ctx.request();
            }

            @Override
            public Observable<FullMessage<HttpRequest>> obsRequest() {
                return ctx.obsRequest();
            }

            @Override
            public Observable<? extends Object> obsResponse() {
                return obsResponse;
            }
            @Override
            public Completable requestCompleted() {
                return ctx.requestCompleted();
            }};
    }

    private Method selectProcessor(final ResContext ctx, final HttpMethod httpMethod) {
        return ctx._processor;
    }

    private Pair<ResContext, Map<String, String>> findResourceCtx(final HttpRequest request) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        final String rawPath = getRawPath(decoder.path());
        final ResContext ctx = findByFixedPath(request.method().name(), rawPath);
        if (null!=ctx) {
            return Pair.of(ctx, null);
        }
        return findByParamsPath(request.method().name(), rawPath);
    }

    private Pair<ResContext, Map<String, String>> findByParamsPath(final String method, final String rawPath) {
        final Collection<Pair<PathMatcher, ResContext>> matchers = this._pathMatchers.get(method);
        if (null != matchers) {
            for (final Pair<PathMatcher, ResContext> matcher : matchers) {
                final Map<String, String> paramValues = matcher.getFirst().match(rawPath);
                if (null != paramValues) {
                    return Pair.of(matcher.getSecond(), paramValues);
                }
            }
        }
        return null;
    }

    private ResContext findByFixedPath(final String method, final String rawPath) {
        final ResContext ctx = this._resCtxs.get( method + ":" + rawPath);
        if (null!=ctx) {
            return ctx;
        }
        return this._resCtxs.get(rawPath);
    }

    private static Type getGenericTypeOf(final Type type, final int idx) {
        return type instanceof ParameterizedType
                ? ((ParameterizedType)type).getActualTypeArguments()[idx]
                : null;
    }

    private static Type getParameterizedRawType(final Type type) {
        return (type instanceof ParameterizedType) ? ((ParameterizedType)type).getRawType() : null;
    }

    private static boolean isObservableType(final Type type) {
        return Observable.class.equals(getParameterizedRawType(type));
    }

    private Observable<? extends Object> objs2Response(final Observable<Object> objs,
            final DefaultTradeContext tctx,
            final String[] mimeTypes,
            final HttpVersion version) {
        final AtomicInteger bodyCnt = new AtomicInteger(0);
        return objs.flatMap(obj -> {
                if (obj instanceof HttpObject) {
                    return Observable.just(obj);
                } else if (obj instanceof DisposableWrapper) {
                    return Observable.just(obj);
                } else if (obj instanceof FullMessage) {
                    @SuppressWarnings({ "unchecked" })
                    final FullMessage<HttpResponse> fullmsg = (FullMessage<HttpResponse>)obj;
                    return fullmsg2hobjs(fullmsg);
                } else if (obj instanceof Stepable) {
                    @SuppressWarnings("unchecked")
                    final Stepable<Object> stepable = (Stepable<Object>)obj;
                    return Observable.just(stepable);
                } else if (obj instanceof MessageBody) {
                    if (bodyCnt.get() == 0) {
                        bodyCnt.incrementAndGet();
                        final HttpResponse resp = new DefaultHttpResponse(version, HttpResponseStatus.OK);
                        return fullmsg2hobjs(new FullMessage<HttpResponse>() {
                            @Override
                            public HttpResponse message() {
                                return resp;
                            }
                            @Override
                            public Observable<? extends MessageBody> body() {
                                return Observable.just((MessageBody)obj);
                            }});
                    } else {
                        LOG.warn("NOT support multipart body, ignore body {}", obj);
                        return Observable.empty();
                    }
                } else {
                    return fullmsg2hobjs(fullmsgOf(obj, version, tctx, mimeTypes));
                }
            });
    }

    private FullMessage<HttpResponse> fullmsgOf(
            final Object obj,
            final HttpVersion version,
            final DefaultTradeContext tctx,
            final String[] mimeTypes) {
        final HttpResponse resp = new DefaultHttpResponse(version, HttpResponseStatus.OK);
        Observable<? extends MessageBody> body = Observable.empty();

        if (obj instanceof ResponseBean) {
            final ResponseBean responseBean = (ResponseBean)obj;
            if (responseBean.withStatus() != null) {
                resp.setStatus(HttpResponseStatus.valueOf(responseBean.withStatus().status()));
            }
            if (responseBean.withHeader() != null) {
                fillHeaders(responseBean.withHeader(), resp);
            }
            if (responseBean.withBody() != null) {
                body = buildBody(responseBean.withBody(), tctx, mimeTypes);
            }
        } else {
            if (obj instanceof WithStatus) {
                resp.setStatus(HttpResponseStatus.valueOf(((WithStatus)obj).status()));
            }
            fillHeaders(obj, resp);
            if (obj instanceof WithBody) {
                body = buildBody((WithBody)obj, tctx, mimeTypes);
            } else {
                // not withBody instance
                body = fromContent(tctx, obj, encoderOf(mimeTypes));
            }
        }

        final Observable<? extends MessageBody> finalyBody = body;
        return new FullMessage<HttpResponse>() {
            @Override
            public HttpResponse message() {
                return resp;
            }

            @Override
            public Observable<? extends MessageBody> body() {
                return finalyBody;
            }};
    }

    private Observable<? extends MessageBody> buildBody(
            final WithBody withBody,
            final DefaultTradeContext tctx,
            final String[] mimeTypes) {
        if (withBody instanceof WithRawBody) {
            return ((WithRawBody)withBody).body();
        } else if (withBody instanceof WithContent) {
            return fromContent(tctx, ((WithContent)withBody).content(),
                    getEncoder(((WithContent)withBody).contentType(), mimeTypes));
        } else if (withBody instanceof WithStepable) {
            return fromStepable((WithStepable<?>)withBody, tctx);
        } else if (withBody instanceof WithSlice) {
            return fromSlice((WithSlice)withBody, tctx);
        } else {
            return Observable.error(new RuntimeException("unknown WithBody type:" + withBody.getClass()));
        }
    }

    private Observable<MessageBody> fromContent(
            final DefaultTradeContext tctx,
            final Object content,
            final ContentEncoder encoder) {
        if (null != content) {
            TraceUtil.setTag4bean(content, tctx._span, "resp.", "record.respbean.error");

            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(
                    tctx.allocatorBuilder().build(512),
                    dwb->dwb.unwrap());
            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> dwbs = MessageUtil.out2dwbs(bufout,
                    out -> encoder.encoder().call(content, out));
            final int size = sizeOf(dwbs);
            return Observable.just(new MessageBody() {
                @Override
                public HttpHeaders headers() {
                    return EmptyHttpHeaders.INSTANCE;
                }
                @Override
                public String contentType() {
                    return encoder.contentType();
                }
                @Override
                public int contentLength() {
                    return size;
                }
                @Override
                public Observable<? extends ByteBufSlice> content() {
                    return Observable.just(new ByteBufSlice() {
                        @Override
                        public void step() {}
                        @Override
                        public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            return dwbs;
                        }});
                }});
        } else {
            tctx._span.setTag("resp_body", "(empty)");
            return Observable.empty();
        }
    }

    // TODO
    private ContentEncoder getEncoder(final String contentType, final String[] mimeTypes) {
        return encoderOf(Lists.asList(contentType, mimeTypes).toArray(new String[0]));
    }

    private static String[] produceTypes(final Method processor) {
        final Produces produces =  processor.getAnnotation(Produces.class);
        return produces != null ? produces.value() : new String[0];
    }

    private static String[] consumeTypes(final Method processor) {
        final Consumes consumes =  processor.getAnnotation(Consumes.class);
        return consumes != null ? consumes.value() : new String[0];
    }

    private int sizeOf(final Iterable<? extends DisposableWrapper<? extends ByteBuf>> dwbs) {
        int size = 0;
        for (final DisposableWrapper<? extends ByteBuf> dwb : dwbs) {
            size += dwb.unwrap().readableBytes();
        }
        return size;
    }

    private static ContentEncoder encoderOf(final String... mimeTypes) {
        final ContentEncoder encoder = ContentUtil.selectCodec(mimeTypes, ContentUtil.DEFAULT_ENCODERS);
        return null != encoder ? encoder : ContentUtil.TOJSON;
    }

    private Observable<MessageBody> fromStepable(@SuppressWarnings("rawtypes") final WithStepable withStepable,
            final TradeContext tctx) {
        return Observable.just(new MessageBody() {
            @Override
            public HttpHeaders headers() {
                return EmptyHttpHeaders.INSTANCE;
            }
            @Override
            public String contentType() {
                return withStepable.contentType();
            }

            @Override
            public int contentLength() {
                return -1;
            }

            @SuppressWarnings("unchecked")
            @Override
            public Observable<? extends ByteBufSlice> content() {
                return withStepable.stepables().compose(
                        ByteBufSliceUtil.stepable2bbs(tctx.allocatorBuilder().build(8192), withStepable.output()));
            }
        });
    }

    private Observable<MessageBody> fromSlice(final WithSlice withSlice, final TradeContext tctx) {
        return Observable.just(new MessageBody() {
            @Override
            public HttpHeaders headers() {
                return EmptyHttpHeaders.INSTANCE;
            }
            @Override
            public String contentType() {
                return withSlice.contentType();
            }

            @Override
            public int contentLength() {
                return -1;
            }

            @Override
            public Observable<? extends ByteBufSlice> content() {
                return withSlice.slices();
            }
        });
    }

    private Observable<Object> fullmsg2hobjs(final FullMessage<HttpResponse> fullmsg) {
        final HttpResponse resp = fullmsg.message();
        final AtomicInteger bodyCnt = new AtomicInteger(0);

        return fullmsg.body().concatMap(body -> {
            if (bodyCnt.get() == 0) {
                bodyCnt.incrementAndGet();
                if (null != body.contentType()) {
                    resp.headers().set(HttpHeaderNames.CONTENT_TYPE, body.contentType());
                }
                if ( body.contentLength() > 0 ) {
                    HttpUtil.setContentLength(resp, body.contentLength());
                } else {
                    HttpUtil.setTransferEncodingChunked(resp, true);
                }
                return Observable.<Object>just(resp).concatWith(body.content());
            } else {
                LOG.warn("NOT support multipart body, ignore body {}", body);
                return Observable.empty();
            }
        }).concatWith(Observable.defer(() -> {
            if (bodyCnt.get() > 0) {
                return Observable.just(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                // no body, so force set content-length to 0
                HttpUtil.setContentLength(resp, 0);
                return Observable.just(resp, LastHttpContent.EMPTY_LAST_CONTENT);
            }
        }));
    }

    private void fillHeaders(final Object obj, final HttpResponse resp) {
        if (obj instanceof WithHeader) {
            final WithHeader withHeader = (WithHeader)obj;
            for (final Map.Entry<String, String> entry : withHeader.headers().entrySet()) {
                resp.headers().set(entry.getKey(), entry.getValue());
            }
        } else {
            final Field[] headerFields =
                ReflectUtils.getAnnotationFieldsOf(obj.getClass(), HeaderParam.class);
            for ( final Field field : headerFields ) {
                try {
                    final Object value = field.get(obj);
                    if ( null != value ) {
                        final String headername =
                            field.getAnnotation(HeaderParam.class).value();
                        resp.headers().set(headername, value);
                    }
                } catch (final Exception e) {
                    LOG.warn("exception when get value from headerparam field:[{}], detail:{}",
                            field, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    private Observable<Object> strings2Response(final Observable<String> strings, final HttpRequest request) {
        final HttpResponse response = new DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.OK);

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");

        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
        response.headers().set(HttpHeaderNames.PRAGMA, HttpHeaderValues.NO_CACHE);

        HttpUtil.setTransferEncodingChunked(response, true);


        return Observable.<Object>just(response)
                .concatWith(strings.map(s -> RxNettys.wrap4release(Unpooled.wrappedBuffer(s.getBytes()))))
                .concatWith(Observable.just(LastHttpContent.EMPTY_LAST_CONTENT));
    }

    private Object[] buildArgs(final Object resource, final DefaultTradeContext tradeCtx, final ArgsCtx argCtx) {
        final List<Object> args = new ArrayList<>();
        int idx = 0;
        for (final Type argType : argCtx.genericParameterTypes) {
            args.add(buildArgByType(argType,
                    resource,
                    tradeCtx,
                    argCtx,
                    argCtx.parameterAnnotations[idx]));
            idx++;
        }
        return args.toArray();
    }

    //  TBD: 查表实现
    private Object buildArgByType(final Type argType,
            final Object resource,
            final DefaultTradeContext tradeCtx,
            final ArgsCtx argsCtx,
            final Annotation[] argAnnotations) {
        final HttpRequest request = argsCtx.request;
        final Map<String, String> pathParams = argsCtx.pathParams;
        final HttpTrade trade = argsCtx.trade;
        final MethodInterceptor[] interceptors = argsCtx.interceptors;
        final Method processor = argsCtx.processor;

        if (argType instanceof Class<?>) {
            if (null != getAnnotation(argAnnotations, BeanParam.class)) {
                return buildBeanParam(request, (Class<?>)argType);
            }
            final HeaderParam headerParam = getAnnotation(argAnnotations, HeaderParam.class);
            if (null != headerParam) {
                return buildHeaderParam(request, headerParam.value(), (Class<?>)argType);
            }
            final QueryParam queryParam = getAnnotation(argAnnotations, QueryParam.class);
            if (null != queryParam) {
                return buildQueryParam(request, queryParam.value(), (Class<?>)argType);
            }
            final PathParam pathParam = getAnnotation(argAnnotations, PathParam.class);
            if (null != pathParam && null != pathParams) {
                return buildPathParam(pathParams, pathParam.value(), (Class<?>)argType);
            }
            if (null != getAnnotation(argAnnotations, Autowired.class)) {
                return BeanHolders.getBean(this._beanHolder, (Class<?>)argType, getAnnotation(argAnnotations, Qualifier.class), resource);
            }
        }
        if (argType instanceof ParameterizedType){
            //参数化类型
            if (isObservableType(argType)) {
                final Type gt1st = getGenericTypeOf(argType, 0);
                if (MessageBody.class.equals(gt1st)) {
                    return buildMessageBody(trade, request);
                }
            } else if (UntilRequestCompleted.class.equals(getParameterizedRawType(argType))) {
                return buildURC(trade.inboundCompleted());
            }
        } else if (argType.equals(io.netty.handler.codec.http.HttpMethod.class)) {
            return request.method();
        } else if (argType.equals(HttpRequest.class)) {
            return request;
        } else if (argType.equals(HttpTrade.class)) {
            return trade;
        } else if (argType.equals(Haltable.class)) {
            return trade;
        } else if (argType.equals(BeanHolder.class)) {
            return this._beanHolder;
        } else if (argType.equals(WriteCtrl.class)) {
            return trade.writeCtrl();
        } else if (argType.equals(AllocatorBuilder.class)) {
            return tradeCtx.allocatorBuilder();
        } else if (argType.equals(InteractBuilder.class)) {
            return tradeCtx.interactBuilder();
        } else if (argType.equals(TradeContext.class)) {
            return tradeCtx;
        } else if (argType.equals(ZipBuilder.class)) {
            return buildZipBuilder(tradeCtx);
        } else if (argType.equals(BeanFinder.class)) {
            return this._finder;
        } else if (argType.equals(RpcExecutor.class)) {
            return buildRpcExecutor(processor, tradeCtx.interactBuilder());
        } else if (argType.equals(Tracing.class)) {
            return buildTracing(tradeCtx, tradeCtx._span);
        } else if (argType.equals(Branch.Builder.class)) {
            return buildBranchBuilder(tradeCtx, processor);
        } else {
            for (final MethodInterceptor interceptor : interceptors) {
                if (interceptor instanceof ArgumentBuilder) {
                    final Object arg = ((ArgumentBuilder)interceptor).buildArg(argType);
                    if (null != arg) {
                        return arg;
                    }
                }
            }
        }

        return null;
    }

    private Branch.Builder buildBranchBuilder(final DefaultTradeContext tradeCtx, final Method processor) {
        return new Branch.Builder() {
            @Override
            public Branch buildFollowsFrom(final String branchName) {
                final Span span = tradeCtx._tracer.buildSpan(branchName)
                        .addReference(References.FOLLOWS_FROM, tradeCtx._span.context()).start();
                final Tracing tracing = buildTracing(tradeCtx, span);
                final Scheduler scheduler = tradeCtx.scheduler().scheduler();
                return new Branch() {
                    @Override
                    public Span span() {
                        return span;
                    }

                    @Override
                    public Tracing tracing() {
                        return tracing;
                    }

                    @Override
                    public RpcExecutor rpcExecutor() {
                        return buildRpcExecutor(processor, tradeCtx.interactBuilderOutofTrade(span, 30));
                    }

                    @Override
                    public Scheduler scheduler() {
                        return scheduler;
                    }};
            }
        };
    }

    private Tracing buildTracing(final DefaultTradeContext tradeCtx, final Span span) {
        return new Tracing() {
            @Override
            public Scope activate() {
                final Tracer restore = TracingUtil.get();
                TracingUtil.set(tradeCtx._tracer);
                final io.opentracing.Scope scope = tradeCtx._tracer.scopeManager().activate(span, false);
                return () -> {
                    scope.close();
                    if (tradeCtx._tracer == TracingUtil.get()) {
                        TracingUtil.set(restore);
                    }
                };
            }};
    }

    private RpcExecutor buildRpcExecutor(final Method processor, final InteractBuilder ib) {
        return new DefaultRpcExecutor(FinderUtil.rpc(this._finder, fromMethod(processor)).ib(ib).runner());
    }

    private static CallerContext fromMethod(final Method processor) {
        return new CallerContext() {

            @Override
            public String className() {
                return processor.getDeclaringClass().getName();
            }

            @Override
            public String methodName() {
                return processor.getName();
            }};
    }

    private ZipBuilder buildZipBuilder(final TradeContext tradeCtx) {
        return new ZipBuilder() {
            @Override
            public Zipper zip(final int pageSize, final int bufsize) {
                return ZipUtil.zipEntities(tradeCtx.allocatorBuilder().build(pageSize), tradeCtx.haltable(), bufsize, dwb->dwb.dispose());
            }

            @Override
            public Zipper zipWithPasswd(final int pageSize, final int bufsize, final String passwd) {
                return ZipUtil.zipEntitiesWithPassword(tradeCtx.allocatorBuilder().build(pageSize), tradeCtx.haltable(), bufsize, dwb->dwb.dispose(), passwd);
            }

            @Override
            public Unzipper unzip(final int pageSize, final int bufsize) {
                return ZipUtil.unzipToEntities(tradeCtx.allocatorBuilder().build(pageSize), tradeCtx.haltable(), bufsize, dwb->dwb.dispose());
            }

            @Override
            public Unzipper unzipWithPasswd(final int pageSize, final int bufsize, final String passwd) {
                throw new UnsupportedOperationException("unzipWithPasswd");
            }};
    }

    private static AllocatorBuilder buildAllocatorBuilder(final HttpTrade trade) {
        return new AllocatorBuilder() {
            @Override
            public Func0<DisposableWrapper<? extends ByteBuf>> build(final int pageSize) {
                return MessageUtil.pooledAllocator(trade, pageSize);
            }};
    }

    private Observable<MessageBody> buildMessageBody(final HttpTrade trade, final HttpRequest request) {
//        if (request.method().equals(HttpMethod.POST) && HttpPostRequestDecoder.isMultipart(request)) {
//            return Observable.unsafeCreate(new MultipartBody(trade, request));
//        } else {
        //  对于 multipart content-type 不做特殊处理，也按照标准 body 返回
            return trade.inbound().flatMap(fullreq -> fullreq.body());
//        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Annotation> T getAnnotation(final Annotation[] annotations, final Class<T> type) {
        for (final Annotation annotation : annotations) {
            if (annotation.annotationType().equals(type)) {
                return (T)annotation;
            }
        }
        return null;
    }

    private Object buildBeanParam(final HttpRequest request, final Class<?> argType) {
        try {
            final Object bean = ReflectUtils.newInstance(argType);

            if (null != bean) {
                ParamUtil.request2HeaderParams(request, bean);
                ParamUtil.request2QueryParams(request, bean);
            } else {
                LOG.warn("buildBeanParam: failed to newInstance for type {}", argType);
            }
            return bean;
        } catch (final Exception e) {
            LOG.warn("exception when buildBeanParam for type {}, detail: {}", ExceptionUtils.exception2detail(e));
            throw new RuntimeException(e);
        }
    }

    private Object buildHeaderParam(final HttpRequest request, final String name, final Class<?> argType) {
        return Beans.fromString(request.headers().get(name), argType);
    }

    private Object buildQueryParam(final HttpRequest request, final String name, final Class<?> argType) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        if (!"".equals(name) && null != decoder.parameters()) {
            // for case: QueryParam("demo")
            return ParamUtil.getAsType(decoder.parameters().get(name), argType);
        }

        if ("".equals(name)) {
            // for case: QueryParam(""), means fill with entire query string
            return Beans.fromString(ParamUtil.rawQuery(request.uri()), argType);
        }

        return null;
    }

    private Object buildPathParam(final Map<String, String> pathParams, final String name, final Class<?> argType) {
        return Beans.fromString(pathParams.get(name), argType);
    }

    private UntilRequestCompleted<Object> buildURC(final Completable inboundComplete) {
        return any -> any.delay(obj -> inboundComplete.toObservable());
    }

    private String getRawPath(final String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            //  http://wangsz.xicp.net:10900/call/zmccCallEndNotify
            final int schemeIdx = path.indexOf("://");
            final String pathWithoutScheme = path.substring(schemeIdx + 3);
            final int rawPathIdx = pathWithoutScheme.indexOf('/');
            return (rawPathIdx > 0) ? pathWithoutScheme.substring(rawPathIdx) : "/";
        } else {
            return path;
        }
    }

    private static class ResContext {
        ResContext(final Class<?> cls, final Method processor) {
            this._cls = cls;
            this._processor = processor;
        }

        private final Class<?> _cls;
        private final Method _processor;
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
//        this._register = register;
    }

    private final Map<String, ResContext> _resCtxs = new HashMap<String, ResContext>();

    private final Multimap<String, Pair<PathMatcher, ResContext>> _pathMatchers = ArrayListMultimap.create();

    private final UnitListener _unitListener = new UnitListener() {
        @Override
        public void postUnitCreated(final String unitPath,
                final ConfigurableApplicationContext appctx) {
            scanAndRegisterResource(appctx.getBeanFactory());
        }
        @Override
        public void beforeUnitClosed(final String unitPath,
                final ConfigurableApplicationContext appctx) {
            unregisterAllResource(appctx.getBeanFactory());
        }
    };

    @Inject
    @Named("type2interceptors")
    Map<Class<?>, Class<? extends MethodInterceptor>[]> _type2interceptors;

    @Inject
    @Named("pkg2interceptors")
    Map<String, Class<? extends MethodInterceptor>[]> _pkg2interceptors;

    @Inject
    private BeanFinder _finder;

    private SpringBeanHolder _beanHolder;
    private Pattern _pathPattern;
}
