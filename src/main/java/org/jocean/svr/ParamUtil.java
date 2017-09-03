package org.jocean.svr;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.lang.reflect.Field;
import java.util.List;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.QueryParam;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import rx.functions.Action1;

public class ParamUtil {
    
    private static final Logger LOG
        = LoggerFactory.getLogger(ParamUtil.class);
    
    private ParamUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    public static Action1<HttpRequest> injectQueryParams(final Object bean) {
        return new Action1<HttpRequest>() {
            @Override
            public void call(final HttpRequest request) {
                request2QueryParams(request, bean);
            }};
    }
    
    public static void request2QueryParams(final HttpRequest request, final Object bean) {
        final Field[] fields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), QueryParam.class);
        if (null != fields) {
            final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

            for (Field field : fields) {
                final String key = field.getAnnotation(QueryParam.class).value();
                if (!"".equals(key) && null != decoder.parameters()) {
                    // for case: QueryParam("demo")
                    injectParamValue(decoder.parameters().get(key), bean, field);
                }
                if ("".equals(key)) {
                    // for case: QueryParam(""), means fill with entire query string
                    injectValueToField(rawQuery(request.uri()), bean, field);
                }
            }
        }
    }
    
    private static String rawQuery(final String uri) {
        final int pos = uri.indexOf('?');
        if (-1 != pos) {
            return uri.substring(pos+1);
        } else {
            return null;
        }
    }
    
    public static Action1<HttpRequest> injectHeaderParams(final Object bean) {
        return new Action1<HttpRequest>() {
            @Override
            public void call(final HttpRequest request) {
                request2HeaderParams(request, bean);
            }};
    }
    
    public static void request2HeaderParams(final HttpRequest request, final Object bean) {
        final Field[] fields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if (null != fields) {
            for (Field field : fields) {
                injectParamValue(request.headers().getAll(field.getAnnotation(HeaderParam.class).value()), 
                    bean,
                    field
                );
            }
        }
    }
    
    private static void injectParamValue(
            final List<String> values,
            final Object obj,
            final Field field) {
        if (null != values && values.size() > 0) {
            injectValueToField(values.get(0), obj, field);
        }
    }

    /**
     * @param value
     * @param obj
     * @param field
     */
    private static void injectValueToField(
            final String value,
            final Object obj,
            final Field field) {
        if (null != value) {
            try {
                // just check String field
                if (field.getType().equals(String.class)) {
                    field.set(obj, value);
                } else {
                    final PropertyEditor editor = PropertyEditorManager.findEditor(field.getType());
                    if (null != editor) {
                        editor.setAsText(value);
                        field.set(obj, editor.getValue());
                    } else {
                        LOG.warn("can't found PropertyEditor for field{}, skip inject value {}.",
                                field.getName(), value);
                    }
                }
            } catch (Exception e) {
                LOG.warn("exception when set obj({}).{} with value({}), detail:{} ",
                        obj, field.getName(), value, ExceptionUtils.exception2detail(e));
            }
        }
    }
}
