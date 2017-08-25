/**
 * 
 */
package org.jocean.svr;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jocean.idiom.Pair;

/**
 * @author isdom
 *
 */
public class PathMatcher {

    public static PathMatcher create(final String pathexp) {
        final ParamMatcher[] matchers = genParamMatchersByExp(pathexp);
        return ( null != matchers ? new PathMatcher(matchers) : null );
    }
    
    private PathMatcher(final ParamMatcher[] matchers) {
        this._matchers = matchers;
    }

    public Map<String, String> match(final String path) {
        final Map<String, String> ret = new HashMap<String, String>();
        int offset = 0;
        for ( ParamMatcher pm : this._matchers ) {
            final Pair<String, Integer> matched = pm.match(path, offset);
            if ( null == matched ) {
                return null;
            }
            else {
                ret.put(pm.getParamName(),  matched.getFirst());
                offset = matched.getSecond();
            }
        }
        return ret;
    }
    
    @Override
    public String toString() {
        return "PathMatcher [parammatchers=" + Arrays.toString(_matchers) + "]";
    }

    private final ParamMatcher[] _matchers;
    
    private final static Pattern PARAM_PATTERN = Pattern.compile("\\{\\w+\\}");
    
    private static ParamMatcher[] genParamMatchersByExp(final String pathexp) {
        
        final Matcher matcher = PARAM_PATTERN.matcher(pathexp);
        final List<ParamMatcher> params = new ArrayList<ParamMatcher>();
        
        int lastIdx = 0;
        String lastPrefix = null, lastName = null;
        while (matcher.find()) { 
            final String prefix = safeSubstring(pathexp, lastIdx, matcher.start());
            if ( null != lastName ) {
                checkNotNull(prefix, "between two params, there is no const string");
                params.add( new ParamMatcher(lastName, lastPrefix, prefix) );
            }
            lastPrefix = prefix;
            lastName = pathexp.substring(matcher.start() + 1, matcher.end() - 1);
            lastIdx = matcher.end();
        }
        if ( null != lastName ) {
            params.add( new ParamMatcher(lastName, lastPrefix, 
                    safeSubstring(pathexp, lastIdx, pathexp.length())) );
        }
        
        return params.isEmpty() ? null : params.toArray(new ParamMatcher[0]);
    }
    
    private static String safeSubstring(final String source, final int start, final int end) {
        if ( start == end ) {
            return null;
        }
        else {
            return source.substring(start, end);
        }
    }
    
    private static final class ParamMatcher {
        
        public ParamMatcher(
                final String name, 
                final String prefix, 
                final String suffix) {
            this._name = checkNotNull(name, "ParamMatcher's name can't be null");
            this._prefix = prefix;
            this._suffix = suffix;
            
            if ( null == this._prefix 
               && null == this._suffix ) {
                throw new RuntimeException("ParamMatcher's prefix and suffix both null.");
            }
        }
        
        String getParamName() {
            return this._name;
        }
        
        // return value and rest string
        Pair<String, Integer> match(final String input, final int offset) {
            if ( null != this._prefix ) {
                return matchPrefix(input, offset);
            }
            else {
                return matchSuffix(input, offset);
            }
        }

        /**
         * @param input
         * @return
         */
        private Pair<String, Integer> matchPrefix(final String input, final int offset) {
            if ( input.startsWith( this._prefix, offset) ) {
                final int restOffset = offset + this._prefix.length();
                if ( null != this._suffix ) {
                    return matchSuffix(input, restOffset);
                }
                else {
                    // _suffix is null
                    return Pair.of(input.substring(restOffset), input.length());
                }
            }
            else {
                // not match
                return null;
            }
        }

        /**
         * @param input
         * @return
         */
        private Pair<String, Integer> matchSuffix(final String input, final int offset) {
            final int suffixIdx = input.indexOf(this._suffix, offset);
            if ( suffixIdx != -1 ) {
                return Pair.of(input.substring(offset, suffixIdx), suffixIdx);
            }
            else {
                // not match
                return null;
            }
        }
        
        private final String _name;
        private final String _prefix;
        private final String _suffix;
        @Override
        public String toString() {
            return "ParamMatcher [name=" + _name + ", prefix=" + _prefix
                    + ", suffix=" + _suffix + "]";
        }
    }
}
