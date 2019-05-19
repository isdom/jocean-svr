package org.jocean.svr.parse;

import rx.functions.Action0;
import rx.functions.Action1;

public interface ParseContext<E> {
    interface MakeSlice extends Action1<Action0> {
    }

    void setMakeSlice(MakeSlice makeSlice);
    void setEntity(E entity);
    void stopParsing();
    boolean canParsing();
}
