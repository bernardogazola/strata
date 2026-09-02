package com.x.billing;

public final class Posting {

    private Invoice invoice;

    public Posting() {
    }

    public void bill(Invoice value) {
        this.invoice = value;
    }
}
