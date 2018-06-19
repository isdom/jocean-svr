package org.jocean.svr;

import java.util.Arrays;
import java.util.Map;

import javax.inject.Inject;

import org.jocean.http.Interact;
import org.jocean.idiom.BeanFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import rx.Observable.Transformer;

public class DefaultRpcConfig implements RpcConfig {

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("rpc config [before=").append(Arrays.toString(_before)).append(", after=")
                .append(Arrays.toString(_after)).append("]");
        return builder.toString();
    }

    public DefaultRpcConfig() {
        this._children = null;
    }

    public DefaultRpcConfig(final Map<String, RpcConfig> children) {
        this._children = children;
    }

    @Override
    public Transformer<Interact, Interact> before() {
        return interacts -> interacts.compose(FinderUtil.processors(this._finder, this._before));
    }

    @Override
    public <T> Transformer<T, T> after() {
        return source -> source.compose(FinderUtil.processors(this._finder, this._after));
    }

    @Override
    public RpcConfig child(final String name) {
        return null != this._children ? this._children.get(name) : null;
    }

    @Value("${before}")
    public void setBefore(final String before) {
        this._before = StringUtils.commaDelimitedListToStringArray(before);
    }

    @Value("${after}")
    public void setAfter(final String after) {
        this._after = StringUtils.commaDelimitedListToStringArray(after);
    }

    private String[] _before = new String[0];
    private String[] _after = new String[0];

    @Inject
    private BeanFinder _finder;

    private final Map<String, RpcConfig> _children;
}
