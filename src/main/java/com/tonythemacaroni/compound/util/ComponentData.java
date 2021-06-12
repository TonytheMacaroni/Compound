package com.tonythemacaroni.compound.util;

import lombok.Getter;
import lombok.Setter;

import io.github.classgraph.ClassInfo;

@Getter
@Setter
public class ComponentData {

    private boolean loaded = false;
    private Object component;

    private ClassInfo classInfo;
    private String description;
    private String[] depends;
    private String name;

    public ComponentData(ClassInfo classInfo, String name, String description, String[] depends) {
        this.name = name;
        this.depends = depends;
        this.classInfo = classInfo;
        this.description = description;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;

        classInfo = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ComponentData that = (ComponentData) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

}
