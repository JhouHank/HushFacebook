package com.facebook.litho;

import android.content.Context;

/** The kept, final, read-only root getter shared by both 582 ABIs. */
public class LithoView extends BaseMountingView {
    public Object root;

    public LithoView(Context context) {
        super(context);
    }

    public final Object getRootComponent() {
        return root;
    }
}
