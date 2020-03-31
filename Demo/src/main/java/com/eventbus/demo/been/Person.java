package com.eventbus.demo.been;

import android.util.Log;

/**
 * @author Yjz
 */
public class Person implements Think {
    public String name;

    public Person(String name) {
        this.name = name;
    }

    @Override
    public void think() {
        Log.d("Person", name + " think");
    }
}
