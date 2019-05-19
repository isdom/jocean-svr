package org.jocean.svr.parse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import rx.Observable;
import rx.functions.Action0;
import rx.subjects.PublishSubject;

public abstract class AbstractParseContext<E, CTX extends ParseContext<E>> implements ParseContext<E> {

    public Observable<? extends E> parseEntity(final Action0 dostep) {
        while (noMakeSlice() && canParsing()) {
            parse();
        }

        if (noMakeSlice()) {
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
            final E entity = doMakeSlice(() -> parseLeft(bodySubject, dostep));
            return null == entity ? bodySubject : Observable.just(entity).concatWith(bodySubject);
        }
        else {
            final E entity = doMakeSlice(dostep);
            return null == entity ? Observable.empty() : Observable.just(entity);
        }
    }

    private void parseLeft(final PublishSubject<E> subject, final Action0 dostep) {
        while (noMakeSlice() && canParsing()) {
            parse();
        }

        if (noMakeSlice()) {
            // this bbs has been consumed
            subject.onCompleted();
            // no downstream msgbody or slice generate, auto step updtgream
            dostep.call();
        }
        else if (canParsing()) {
            // can continue parsing
            // makeslices.size() == 1
            //  如果该 bbs 是上一个 part 的结尾部分，并附带了后续的1个或多个 part (部分内容)
            //  均可能出现 makeslices.size() == 1，但 bodys.size() == 0 的情况
            //  因此需要分别处理 body != null 及 body == null
            final E entity = doMakeSlice(() -> parseLeft(subject, dostep));
            if (null != entity) {
                subject.onNext(entity);
            }
        }
        else {
            final E entity = doMakeSlice(dostep);

            if (null != entity) {
                subject.onNext(entity);
            }

            // this bbs has been consumed
            subject.onCompleted();
        }
    }

    @Override
    public void setMakeSlice(final MakeSlice makeSlice) {
        _makesliceRef.set(makeSlice);
    }

    @Override
    public void setEntity(final E body) {
        _entityRef.set(body);
    }

    @Override
    public void stopParsing() {
        _parsing.set(false);
    }

    private boolean noMakeSlice() {
        return null == _makesliceRef.get();
    }

    private E doMakeSlice(final Action0 dostep) {
        _makesliceRef.getAndSet(null).call(dostep);
        return _entityRef.getAndSet(null);
    }

    @SuppressWarnings("unchecked")
    private void parse() {
        _currentParser.set(_currentParser.get().parse((CTX)this));
    }

    protected final AtomicBoolean _parsing = new AtomicBoolean(true);
    protected final AtomicReference<MakeSlice> _makesliceRef = new AtomicReference<>();
    protected final AtomicReference<E> _entityRef = new AtomicReference<>();
    protected final AtomicReference<EntityParser<E, CTX>> _currentParser = new AtomicReference<>(null);
}
