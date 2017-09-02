package org.jocean.svr;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.lang.reflect.Field;
import java.util.List;

import javax.ws.rs.HeaderParam;

import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;

public class ParamUtil {
    
    private static final Logger LOG
        = LoggerFactory.getLogger(ParamUtil.class);
    
    private ParamUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    public static Transformer<HttpObject, HttpObject> assignHeaderParams(final Object bean) {
        return new Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> req) {
                return req.doOnNext(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject hobj) {
                        if (hobj instanceof HttpRequest) {
                            assignHeaderParams(bean, ((HttpRequest)hobj));
                        }
                    }});
            }};
    }
                
    public static void assignHeaderParams(final Object bean, final HttpRequest req) {
        final Field[] fields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if (null != fields) {
            for (Field field : fields) {
                injectParamValue(req.headers().getAll(field.getAnnotation(HeaderParam.class).value()), 
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
