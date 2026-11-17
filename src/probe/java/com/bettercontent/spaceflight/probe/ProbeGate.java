package com.bettercontent.spaceflight.probe;

/** Fail closed: every invocation selects exactly one authored native gate. */
public enum ProbeGate {
    STATIC, DYNAMIC;

    public static ProbeGate parse(String value) {
        if ("static".equals(value)) return STATIC;
        if ("dynamic".equals(value)) return DYNAMIC;
        throw new IllegalArgumentException("Unknown spaceflight probe gate: " + value);
    }
}
