package com.x.billing;

public final class Ledger {

    private Posting posting;

    public Ledger() {
    }

    public void record(Posting value) {
        this.posting = value;
    }
}
