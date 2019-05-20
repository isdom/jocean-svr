package org.jocean.svr.parse;

import rx.functions.Action0;
import rx.functions.Action1;

public interface ParseContext<E> {
    interface SliceBuilder extends Action1<Action0> {
    }

    void setSliceBuilder(SliceBuilder sliceBuilder);
    void setEntity(E entity);
    void stopParsing();
}
