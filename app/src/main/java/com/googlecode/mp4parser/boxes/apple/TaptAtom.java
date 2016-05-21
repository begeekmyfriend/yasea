package com.googlecode.mp4parser.boxes.apple;

import com.googlecode.mp4parser.AbstractContainerBox;

/**
 * Don't know what it is but it is obviously a container box.
 */
public class TaptAtom extends AbstractContainerBox {
    public static final String TYPE = "tapt";

    public TaptAtom() {
        super(TYPE);
    }


}
