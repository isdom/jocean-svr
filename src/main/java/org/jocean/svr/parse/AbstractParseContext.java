package org.jocean.svr.parse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.ByteBufSlice;
import org.jocean.idiom.DisposableWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subscriptions.Subscriptions;

public abstract class AbstractParseContext<E, CTX extends ParseContext<E>> implements ParseContext<E> {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractParseContext.class);

    protected AbstractParseContext(final EntityParser<E, CTX> initParser) {
        this._currentParser.set(initParser);
    }

    public void resetParsing() {
        this._parsing.set(true);
    }

    public Observable<? extends E> parseEntity(final Action0 dostep) {
        while (!hasContent() && canParsing()) {
            LOG.debug("parseEntity: do parse with current parse: {}", _currentParser.get());
            parse();
        }
        LOG.debug("parseEntity: leave while parse() with current parse: {}", _currentParser.get());

        if (!hasContent()) {
            LOG.debug("parseEntity: !hasContent() case");
            // no downstream msgbody or slice generate, auto step updtgream
            dostep.call();
            return Observable.empty();
        }
        else if (canParsing()) {
            LOG.debug("parseEntity: begin canParsing() case");
            // can continue parsing
            // makeslices.size() == 1
            final PublishSubject<E> subject = PublishSubject.create();

            //  如果该 bbs 是上一个 part 的结尾部分，并附带了后续的1个或多个 part (部分内容)
            //  均可能出现 makeslices.size() == 1，但 bodys.size() == 0 的情况
            //  因此需要分别处理 body != null 及 body == null
            final E entity = buildEntity(() -> parseRemains(subject, dostep));
            LOG.debug("parseEntity: endof canParsing() case with entity({})", entity);
            return null == entity ? subject : Observable.just(entity).concatWith(subject);
        }
        else {
            LOG.debug("parseEntity: begin !canParsing() case");
            final E entity = buildEntity(dostep);
            LOG.debug("parseEntity: endof !canParsing() with entity({})", entity);
            return null == entity ? Observable.empty() : Observable.just(entity);
        }
    }

    private void parseRemains(final PublishSubject<E> subject, final Action0 dostep) {
        while (!hasContent() && canParsing()) {
            LOG.debug("parseRemains: do parse with current parse: {}", _currentParser.get());
            parse();
        }

        LOG.debug("parseRemains: leave while parse() with current parse: {}", _currentParser.get());
        if (!hasContent()) {
            LOG.debug("parseRemains: !hasContent() case");
            // this bbs has been consumed
            subject.onCompleted();
            // no downstream entity or slice generate, auto step upstream
            dostep.call();
        }
        else if (canParsing()) {
            LOG.debug("parseRemains: begin canParsing() case");
            // can continue parsing
            // makeslices.size() == 1
            //  如果该 bbs 是上一个 part 的结尾部分，并附带了后续的1个或多个 part (部分内容)
            //  均可能出现 makeslices.size() == 1，但 bodys.size() == 0 的情况
            //  因此需要分别处理 body != null 及 body == null
            final E entity = buildEntity(() -> parseRemains(subject, dostep));
            LOG.debug("parseRemains: endof canParsing() case with entity({})", entity);
            if (null != entity) {
                subject.onNext(entity);
            }
        }
        else {
            LOG.debug("parseRemains: begin !canParsing() case");
            final E entity = buildEntity(dostep);

            LOG.debug("parseRemains: endof !canParsing() with entity({})", entity);
            if (null != entity) {
                subject.onNext(entity);
            }

            // this bbs has been consumed
            subject.onCompleted();
        }
    }

    @Override
    public void appendContent(final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs, final Func1<ByteBufSlice, E> content2entity) {
        _content2entityRef.set(dostep -> content2entity.call(dwbs2bbs(dwbs, dostep)));
    }

    @Override
    public void stopParsing() {
        _parsing.set(false);
    }

    protected abstract boolean hasData();

    private boolean canParsing() {
        return hasData() && _parsing.get() && null != _currentParser.get();
    }

    private boolean hasContent() {
        return null != _content2entityRef.get();
    }

    private E buildEntity(final Action0 dostep) {
        return _content2entityRef.getAndSet(null).call(dostep);
    }

    @SuppressWarnings("unchecked")
    private void parse() {
        _currentParser.set(_currentParser.get().parse((CTX)this));
    }

    private static ByteBufSlice dwbs2bbs(final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs, final Action0 dostep) {
        final Subscription subscription = Subscriptions.create(dostep);
        return new ByteBufSlice() {
            @Override
            public void step() {
                subscription.unsubscribe();
            }

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return dwbs;
            }};
    }

    protected final AtomicBoolean _parsing = new AtomicBoolean(true);
    protected final AtomicReference<Func1<Action0, E>> _content2entityRef = new AtomicReference<>();
    protected final AtomicReference<EntityParser<E, CTX>> _currentParser = new AtomicReference<>(null);
}
