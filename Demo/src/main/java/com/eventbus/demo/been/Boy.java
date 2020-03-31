package com.eventbus.demo.been;

import android.util.Log;

/**
 * @author Yjz
 */
public class Boy extends Person implements Play{
    public final String sex;
    public Boy(String name) {
        super(name);
        sex = "男";
    }

    @Override
    public void play() {
        Log.d("Boy", name + " play");
    }
}
