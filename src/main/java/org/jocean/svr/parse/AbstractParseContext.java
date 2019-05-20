package org.jocean.svr.parse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import rx.Observable;
import rx.functions.Action0;
import rx.subjects.PublishSubject;

public abstract class AbstractParseContext<E, CTX extends ParseContext<E>> implements ParseContext<E> {

    protected AbstractParseContext(final EntityParser<E, CTX> initParser) {
        this._currentParser.set(initParser);
    }

    public void resetParsing() {
        this._parsing.set(true);
    }

    public Observable<? extends E> parseEntity(final Action0 dostep) {
        while (noSliceBuilder() && canParsing()) {
            parse();
        }

        if (noSliceBuilder()) {
            // no downstream msgbody or slice generate, auto step updtgream
            dostep.call();
            return Observable.empty();
        }
        else if (canParsing()) {
            // can continue parsing
            // makeslices.size() == 1
            final PublishSubject<E> bodySubject = PublishSubject.create();

            //  如果该 bbs 是上一个 part 的结尾部分，并附带了后续的1个或多个 part (部分内容)
            //  均可能出现 makeslices.size() == 1，但 bodys.size() == 0 的情况
            //  因此需要分别处理 body != null 及 body == null
            final E entity = buildSlice(() -> parseRemains(bodySubject, dostep));
            return null == entity ? bodySubject : Observable.just(entity).concatWith(bodySubject);
        }
        else {
            final E entity = buildSlice(dostep);
            return null == entity ? Observable.empty() : Observable.just(entity);
        }
    }

    private void parseRemains(final PublishSubject<E> subject, final Action0 dostep) {
        while (noSliceBuilder() && canParsing()) {
            parse();
        }

        if (noSliceBuilder()) {
            // this bbs has been consumed
            subject.onCompleted();
            // no downstream entity or slice generate, auto step upstream
            dostep.call();
        }
        else if (canParsing()) {
            // can continue parsing
            // makeslices.size() == 1
            //  如果该 bbs 是上一个 part 的结尾部分，并附带了后续的1个或多个 part (部分内容)
            //  均可能出现 makeslices.size() == 1，但 bodys.size() == 0 的情况
            //  因此需要分别处理 body != null 及 body == null
            final E entity = buildSlice(() -> parseRemains(subject, dostep));
            if (null != entity) {
                subject.onNext(entity);
            }
        }
        else {
            final E entity = buildSlice(dostep);

            if (null != entity) {
                subject.onNext(entity);
            }

            // this bbs has been consumed
            subject.onCompleted();
        }
    }

    @Override
    public void setSliceBuilder(final SliceBuilder makeSlice) {
        _sliceBuilderRef.set(makeSlice);
    }

    @Override
    public void setEntity(final E body) {
        _entityRef.set(body);
    }

    @Override
    public void stopParsing() {
        _parsing.set(false);
    }

    protected abstract boolean hasData();

    private boolean canParsing() {
        return hasData() && _parsing.get() && null != _currentParser.get();
    }

    private boolean noSliceBuilder() {
        return null == _sliceBuilderRef.get();
    }

    private E buildSlice(final Action0 dostep) {
        _sliceBuilderRef.getAndSet(null).call(dostep);
        return _entityRef.getAndSet(null);
    }

    @SuppressWarnings("unchecked")
    private void parse() {
        _currentParser.set(_currentParser.get().parse((CTX)this));
    }

    protected final AtomicBoolean _parsing = new AtomicBoolean(true);
    protected final AtomicReference<SliceBuilder> _sliceBuilderRef = new AtomicReference<>();
    protected final AtomicReference<E> _entityRef = new AtomicReference<>();
    protected final AtomicReference<EntityParser<E, CTX>> _currentParser = new AtomicReference<>(null);
}
