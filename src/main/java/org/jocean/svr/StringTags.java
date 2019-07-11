package org.jocean.svr;

import java.util.Arrays;

public class StringTags {
    public StringTags(final String... array) {
        this._array = array;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(_array);
        return result;
    }


    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final StringTags other = (StringTags) obj;
        if (!Arrays.equals(_array, other._array)) {
            return false;
        }
        return true;
    }


    private final String[] _array;
}
