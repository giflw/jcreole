package com.admc.play;

import beaver.Symbol;

public class WashedToken extends Token {
    private final String cleanString;

    public WashedToken(String s) {
        cleanString = s;
    }

    public String getCleanString() {
        return cleanString;
    }

    public String toString() {
        return "$" + getId() + "/(" + cleanString + ')';
    }
}
