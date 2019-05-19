package org.jocean.svr.parse;

public interface EntityParser<E, CTX extends ParseContext<E>> {

    EntityParser<E, CTX> parse(CTX ctx);
}
