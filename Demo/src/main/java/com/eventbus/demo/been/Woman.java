package com.eventbus.demo.been;

/**
 * @author Yjz
 */
public class Woman extends Girl{
    public boolean isPregnant;
    public Woman(String name, boolean isPregnant) {
        super(name);
        this.isPregnant = isPregnant;
    }
}
