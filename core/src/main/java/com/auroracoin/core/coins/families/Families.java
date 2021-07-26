package com.auroracoin.core.coins.families;

import com.auroracoin.core.coins.ValueType;

/**
 * @author John L. Jegutanis
 */
public enum Families {
    FIAT("fiat"),
    // same as in org.bitcoinj.params.Networks
    BITCOIN("bitcoin"),
    ;

    public final String family;

    Families(String family) {
        this.family = family;
    }

    @Override
    public String toString() {
        return family;
    }
}
