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
import java.util.regex.Pattern;

import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.Regexs;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;
import org.jocean.j2se.spring.SpringBeanHolder;
import org.jocean.j2se.unit.UnitAgent;
import org.jocean.j2se.unit.UnitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.functions.Func1;

/**
 * @author isdom
 */
public class Registrar implements MBeanRegisterAware {

    private static final Logger LOG
            = LoggerFactory.getLogger(Registrar.class);

    public void start() {
        final ConfigurableListableBeanFactory[] factorys = this._beanHolder.allBeanFactory();
        for (ConfigurableListableBeanFactory factory : factorys) {
            scanAndRegisterResource(factory);
        }
        if (this._beanHolder instanceof UnitAgent) {
            final UnitAgent agent = (UnitAgent)this._beanHolder;
            agent.addUnitListener(_unitListener);
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
    
    public void setBeanHolder(final SpringBeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }

    private void scanAndRegisterResource(final ConfigurableListableBeanFactory factory) {
        for ( String name : factory.getBeanDefinitionNames() ) {
            final BeanDefinition def = factory.getBeanDefinition(name);
            if (null!=def && null != def.getBeanClassName()) {
                try {
                    final Class<?> cls = Class.forName(def.getBeanClassName());
                    if ( null!= cls.getAnnotation(Path.class)) {
                        register(cls);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when scanAndRegisterResource, detail: {}", 
                            ExceptionUtils.exception2detail(e));
                }
            } else {
                LOG.warn("scanAndRegisterResource: bean named {} 's definition is empty.", name);
            }
        }
    }

    private void unregisterAllResource(final ConfigurableListableBeanFactory factory) {
        for ( String name : factory.getBeanDefinitionNames() ) {
            final BeanDefinition def = factory.getBeanDefinition(name);
            if (null!=def && null != def.getBeanClassName()) {
                try {
                    final Class<?> cls = Class.forName(def.getBeanClassName());
                    if ( null!= cls.getAnnotation(Path.class)) {
                        unregister(cls);
                    }
                } catch (Exception e) {
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
        for (Class<?> cls : classes) {
            this.register(cls);
        }
    }

    public void setPathPattern(final String pathPattern) {
        this._pathPattern = Regexs.safeCompilePattern(pathPattern);;
    }
    
    public Registrar register(final Class<?> cls) {

        final Class<?> resourceCls = checkNotNull(cls);

        final String rootPath =
                checkNotNull(checkNotNull(resourceCls.getAnnotation(Path.class),
                                "resource class(%s) must be annotation by Path", resourceCls).value(),
                        "resource class(%s)'s Path must have value setted", resourceCls
                );

        if (!Regexs.isMatched(this._pathPattern, rootPath)) {
            LOG.info("flow {} 's path {} !NOT! match path pattern {}, just ignore",
                    resourceCls, rootPath, this._pathPattern);
            return this;
        }
        
        final Method[] restMethods =
                ReflectUtils.getAnnotationMethodsOf(resourceCls, Path.class);
        
        if (0 == restMethods.length) {
            LOG.info("flow {} 's path {} !NOT! match path pattern {}, just ignore",
                    resourceCls, rootPath, this._pathPattern);
            return this;
        }
        
        for (Method m : restMethods) {
            final String methodPath = genMethodPathOf(rootPath, m);
            if (
                registerProcessorWithHttpMethod(resourceCls, m, methodPath, GET.class)
                +
                registerProcessorWithHttpMethod(resourceCls, m, methodPath, POST.class)
                +
                registerProcessorWithHttpMethod(resourceCls, m, methodPath, PUT.class)
                +
                registerProcessorWithHttpMethod(resourceCls, m, methodPath, HEAD.class)
                +
                registerProcessorWithHttpMethod(resourceCls, m, methodPath, OPTIONS.class)
                == 0) {
                // NO HttpMethod annotation exist
                // register with only path
                this._resCtxs.put(methodPath, new ResContext(resourceCls, m));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("register Path {}", methodPath);
                }
            }
//            final PathMatcher pathMatcher = PathMatcher.create(methodPath);
//            if (null == pathMatcher) {
                //  Path without parameters
//                this._resCtxs.put(methodPath, new ResContext(resourceCls, m));

//            } else {
                // Path !WITH! parameters
//                this._pathMatchers.put(httpMethod, Pair.of(pathMatcher, context));
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("register httpMethod {} for !Parametered! Path {} with matcher {} & context {}",
//                            httpMethod, methodPath, pathMatcher, context);
//                }
//            }
        }
        return this;
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
            Iterator<Map.Entry<String, Pair<PathMatcher, ResContext>>> itr = 
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
    
    public Observable<HttpObject> buildResource(
            final HttpRequest request,
            final HttpTrade trade) throws Exception {

        // try direct path match
        final ResContext ctx = findResourceCtx(request);
        
        if (null != ctx) {
            final Method processor = selectProcessor(ctx, request.method());
            
            final Object resource = this._beanHolder.getBean(ctx._cls);
            
            if (null!=resource) {
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
                    public Observable<? extends HttpObject> obsRequest() {
                        return trade.inbound();
                    }
                    @Override
                    public Observable<HttpObject> obsResponse() {
                        return null;
                    }
                };
                final Observable<HttpObject> obsResponse = doPreInvoke(interceptorCtx, interceptors);
                if (null != obsResponse) {
                    //  interceptor 直接响应
                    return doPostInvoke(interceptors, copyCtxOverrideResponse(interceptorCtx, obsResponse));
                } else {
                    try {
                        final Object returnValue = processor.invoke(resource, 
                                    buildArgs(processor.getGenericParameterTypes(), 
                                            processor.getParameterAnnotations(),
                                            trade, 
                                            request, 
                                            interceptors.toArray(new MethodInterceptor[0])));
                        if (null!=returnValue) {
                            final Observable<HttpObject> resourceObsResponse = 
                                    returnValue2ObsResponse(request, 
                                            processor.getGenericReturnType(), 
                                            returnValue);
                            if (null != resourceObsResponse) {
                                if (!interceptors.isEmpty()) {
                                    return doPostInvoke(interceptors, 
                                            copyCtxOverrideResponse(interceptorCtx, resourceObsResponse));
                                } else {
                                    return resourceObsResponse;
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when invoke process {}, detail{}", 
                                processor,
                                ExceptionUtils.exception2detail(e));
                    }
                }
            }
        }
        return RxNettys.response404NOTFOUND(request.protocolVersion())
                .delaySubscription(trade.inbound().last());
    }

    @SuppressWarnings("unchecked")
    private Observable<HttpObject> returnValue2ObsResponse(
            final HttpRequest request, 
            final Type returnType, 
            final Object returnValue) {
        if (isObservableType(returnType)) {
            //  return type is Observable<XXX>
            final Type gt1st = getGenericTypeOf(returnType, 0);
            if (gt1st.equals(HttpObject.class)) {
                return (Observable<HttpObject>)returnValue;
            } else if (gt1st.equals(String.class)) {
                return strings2Response((Observable<String>)returnValue, request);
            } else if (gt1st.equals(Object.class)) {
                return objs2Response((Observable<Object>)returnValue, request);
            }
        } else {
            // return is NOT Observable<?>
        }
        return null;
    }

    private Observable<HttpObject> doPreInvoke(
            final MethodInterceptor.Context ctx, 
            final Deque<MethodInterceptor> interceptors) {
        final Interceptors interceptorsAnno = ctx.resource().getClass().getAnnotation(Interceptors.class);
        if (interceptors != null) {
            final Class<? extends MethodInterceptor>[] types = interceptorsAnno.value();
            for (Class<? extends MethodInterceptor> type : types) {
                try {
                    final MethodInterceptor interceptor = this._beanHolder.getBean(type);
                    if (null!=interceptor) {
                        final Observable<HttpObject> obsResponse = interceptor.preInvoke(ctx);
                        interceptors.addFirst(interceptor);
                        if (null != obsResponse) {
                            return obsResponse;
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("exception when preInvoke by interceptor type {}, detail: {}", 
                            type, ExceptionUtils.exception2detail(e));
                }
            }
        }
        return null;
    }

    private Observable<HttpObject> doPostInvoke(
            final Collection<MethodInterceptor> interceptors, 
            MethodInterceptor.Context ctx) {
        for (MethodInterceptor interceptor : interceptors) {
            try {
                final Observable<HttpObject> obsResponse = interceptor.postInvoke(ctx);
                if (null != obsResponse) {
                    ctx = copyCtxOverrideResponse(ctx, obsResponse);
                }
            } catch (Exception e) {
                LOG.warn("exception when get do {}.postInvoke, detail: {}", 
                        interceptor, ExceptionUtils.exception2detail(e));
            }
        }
        return ctx.obsResponse();
    }

    private MethodInterceptor.Context copyCtxOverrideResponse(final MethodInterceptor.Context ctx, 
            final Observable<HttpObject> obsResponse) {
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
            public Observable<? extends HttpObject> obsRequest() {
                return ctx.obsRequest();
            }

            @Override
            public Observable<HttpObject> obsResponse() {
                return obsResponse;
            }};
    }

    private Method selectProcessor(final ResContext ctx, final HttpMethod httpMethod) {
        return ctx._processor;
    }

    private ResContext findResourceCtx(final HttpRequest request) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        final String rawPath = getRawPath(decoder.path());
        final ResContext ctx = this._resCtxs.get(request.method().name() + ":" + rawPath);
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

    private Observable<HttpObject> objs2Response(final Observable<Object> objs, final HttpRequest request) {
        return objs.map(new Func1<Object, HttpObject>() {
            @Override
            public HttpObject call(final Object obj) {
                if (obj instanceof HttpObject) {
                    return (HttpObject)obj;
                } else if (obj instanceof MessageResponse) {
                    return buildResponse((MessageResponse)obj, request.protocolVersion());
                } else if (obj instanceof MessageBody) {
                    return new DefaultLastHttpContent(body2content((MessageBody)obj));
                } else {
                    return new DefaultHttpContent(Unpooled.copiedBuffer(obj.toString(), CharsetUtil.UTF_8));
                }
            }});
    }

    private HttpResponse buildResponse(final MessageResponse msgresp, final HttpVersion version) {
        HttpResponse resp = null;
        if (msgresp instanceof MessageBody) {
            final ByteBuf content = body2content((MessageBody)msgresp);
            resp = new DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(msgresp.status()), 
                    content);
            HttpUtil.setContentLength(resp, content.readableBytes());
        } else {
            resp = new DefaultHttpResponse(version, HttpResponseStatus.valueOf(msgresp.status()));
            HttpUtil.setTransferEncodingChunked(resp, true);
        }
        fillParams(msgresp, resp);
        return resp;
    }

    private void fillParams(final Object obj, final HttpResponse resp) {
        final Field[] headerFields = 
            ReflectUtils.getAnnotationFieldsOf(obj.getClass(), HeaderParam.class);
        for ( Field field : headerFields ) {
            try {
                final Object value = field.get(obj);
                if ( null != value ) {
                    final String headername = 
                        field.getAnnotation(HeaderParam.class).value();
                    resp.headers().set(headername, value);
                }
            } catch (Exception e) {
                LOG.warn("exception when get value from headerparam field:[{}], detail:{}",
                        field, ExceptionUtils.exception2detail(e));
            }
        }
    }

    private Observable<HttpObject> strings2Response(final Observable<String> strings, final HttpRequest request) {
        return strings.toList()
        .flatMap(new Func1<List<String>, Observable<HttpObject>>() {
            @Override
            public Observable<HttpObject> call(final List<String> contents) {
                final StringBuilder sb = new StringBuilder();
                for (String s : contents) {
                    sb.append(s);
                }
                final FullHttpResponse response = new DefaultFullHttpResponse(
                        request.protocolVersion(), 
                        HttpResponseStatus.OK,
                        (sb.length() > 0 ? Unpooled.copiedBuffer(sb.toString(), CharsetUtil.UTF_8) : Unpooled.buffer(0)));
                
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                
                // Add 'Content-Length' header only for a keep-alive connection.
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                
                response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
                response.headers().set(HttpHeaderNames.PRAGMA, HttpHeaderValues.NO_CACHE);
                return Observable.<HttpObject>just(response);
            }});
    }

    private Object[] buildArgs(final Type[] genericParameterTypes, 
            final Annotation[][] parameterAnnotations, 
            final HttpTrade trade, 
            final HttpRequest request, 
            final MethodInterceptor[] interceptors) {
        final List<Object> args = new ArrayList<>();
        int idx = 0;
        for (Type argType : genericParameterTypes) {
            args.add(buildArgByType(argType, parameterAnnotations[idx], trade, request, interceptors));
            idx++;
        }
        return args.toArray();
    }

    //  TBD: 查表实现
    private Object buildArgByType(final Type argType, 
            final Annotation[] argAnnotations, 
            final HttpTrade trade, 
            final HttpRequest request, 
            final MethodInterceptor[] interceptors) {
        if (argType instanceof Class<?>) {
            final HeaderParam headerParam = getAnnotation(argAnnotations, HeaderParam.class);
            if (null != headerParam) {
                return buildHeaderParam(request, headerParam.value(), (Class<?>)argType);
            }
            final QueryParam queryParam = getAnnotation(argAnnotations, QueryParam.class);
            if (null != queryParam) {
                return buildQueryParam(request, queryParam.value(), (Class<?>)argType);
            }
        }
        if (argType instanceof ParameterizedType){  
            //参数化类型
            if (isObservableType(argType)) {
                final Type gt1st = getGenericTypeOf(argType, 0);
                if (HttpObject.class.equals(gt1st)) {
                    return trade.inbound();
                } else if (MessageDecoder.class.equals(gt1st)) {
                    return buildOMD(trade, request);
                }
            } else if (UntilRequestCompleted.class.equals(getParameterizedRawType(argType))) {
                return buildURC(trade.inbound());
            }
        } else if (argType.equals(io.netty.handler.codec.http.HttpMethod.class)) {
            return request.method();
        } else if (argType.equals(HttpRequest.class)) {
            return request;
        } else {
            for (MethodInterceptor interceptor : interceptors) {
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

    private Observable<MessageDecoder> buildOMD(final HttpTrade trade, final HttpRequest request) {
        if ( request.method().equals(HttpMethod.POST)
            && HttpPostRequestDecoder.isMultipart(request)) {
            return Observable.unsafeCreate(new MultipartOMD(trade, request));
        } else {
            return trade.inbound().last().map(new Func1<Object, MessageDecoder>() {
                @Override
                public MessageDecoder call(final Object last) {
                    return new MessageDecoderUsingHolder(
                        trade.inboundHolder().fullOf(RxNettys.BUILD_FULL_REQUEST), 
                        request.headers().get(HttpHeaderNames.CONTENT_TYPE));
                }});
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Annotation> T getAnnotation(final Annotation[] annotations, final Class<T> type) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().equals(type)) {
                return (T)annotation;
            }
        }
        return null;
    }

    private Object buildHeaderParam(final HttpRequest request, final String name, final Class<?> argType) {
        return ParamUtil.getAsType(request.headers().get(name), argType);
    }

    private Object buildQueryParam(final HttpRequest request, final String key, final Class<?> argType) {
        final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        if (!"".equals(key) && null != decoder.parameters()) {
            // for case: QueryParam("demo")
            return ParamUtil.getAsType(decoder.parameters().get(key), argType);
        }
        
        if ("".equals(key)) {
            // for case: QueryParam(""), means fill with entire query string
            return ParamUtil.getAsType(ParamUtil.rawQuery(request.uri()), argType);
        }
        
        return null;
    }

    private UntilRequestCompleted<Object> buildURC(final Observable<? extends HttpObject> inbound) {
        return new UntilRequestCompleted<Object>() {
            @Override
            public Observable<Object> call(Observable<Object> any) {
                return any.delaySubscription(inbound.last());
            }};
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

    private String genMethodPathOf(final String rootPath, final Method method) {
        final Path methodPath = method.getAnnotation(Path.class);

        if (null != methodPath) {
            return rootPath + methodPath.value();
        } else {
            return rootPath;
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
    
    private ByteBuf body2content(final MessageBody body) {
        return null != body.content() ? body.content() : Unpooled.EMPTY_BUFFER;
    }

    private final Map<String, ResContext> _resCtxs =
            new HashMap<String, ResContext>();

    private final Multimap<String, Pair<PathMatcher, ResContext>> _pathMatchers = 
            ArrayListMultimap.create();

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
    
    private SpringBeanHolder _beanHolder;
    private Pattern _pathPattern;
}
