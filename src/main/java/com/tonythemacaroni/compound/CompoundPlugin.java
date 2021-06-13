package com.tonythemacaroni.compound;

import java.net.URL;
import java.io.File;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.function.Function;
import java.net.MalformedURLException;
import java.lang.reflect.InvocationTargetException;

import com.google.common.collect.HashMultimap;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.AnnotationParameterValueList;

import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import com.tonythemacaroni.compound.annotations.Config;
import com.tonythemacaroni.compound.util.ComponentData;
import com.tonythemacaroni.compound.annotations.Resolve;
import com.tonythemacaroni.compound.util.LoadableComponent;

public class CompoundPlugin extends JavaPlugin {

    protected Map<String, ComponentData> components;
    protected Logger logger;

    @Override
    public void onEnable() {
        logger = getLogger();

        components = new HashMap<>();

        File componentFolder = new File(getDataFolder(), "components");
        if (!componentFolder.exists()) {
            if (!componentFolder.mkdir()) {
                logger.severe("Unable to create component folder.");
                return;
            }
        }

        logger.info("Loading components...");

        File[] componentFiles = componentFolder.listFiles();
        if (componentFiles == null) {
            logger.severe("Unable to load components.");
            return;
        }

        List<URL> urls = new ArrayList<>();
        for (File componentFile : componentFiles) {
            if (!componentFile.getName().endsWith(".jar")) continue;

            try {
                urls.add(componentFile.toURI().toURL());
            } catch (MalformedURLException e) {
                logger.severe("Unable to load component jar '" + componentFile.getName() + "'.");
            }
        }
        URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), getClassLoader());

        try (
            ScanResult result = new ClassGraph()
                .addClassLoader(classLoader)
                .enableClassInfo()
                .enableAnnotationInfo()
                .acceptPackages(getClass().getPackage().getName())
                .scan()
        ) {
            ClassInfoList componentList = result.getClassesWithAnnotation("com.tonythemacaroni.compound.annotations.Component");

            if (componentList.isEmpty()) {
                logger.info("No components found.");
                return;
            }

            Map<String, ComponentData> componentData = new HashMap<>();
            componentList.forEach(c -> {
                AnnotationParameterValueList componentAnnotation = c
                    .getAnnotationInfo()
                    .get("com.tonythemacaroni.compound.annotations.Component")
                    .getParameterValues();

                String name = (String) componentAnnotation.get("name").getValue();
                String description = (String) componentAnnotation.get("description").getValue();
                String[] depends = (String[]) componentAnnotation.get("depends").getValue();
                componentData.put(name, new ComponentData(c, name, description, depends));
            });

            logger.info("Found components: [" + String.join(", ", componentData.keySet()) + "]");

            HashMultimap<ComponentData, ComponentData> dependencies = HashMultimap.create();
            Set<ComponentData> loadedComponents = new HashSet<>();
            Set<ComponentData> failedComponents = new HashSet<>();

            componentData.values().forEach(component -> {
                for (String dependName : component.getDepends()) {
                    ComponentData depend = componentData.get(dependName);
                    if (depend == null) {
                        dependencies.removeAll(component);
                        failedComponents.add(component);

                        logger.severe("Component '" + component.getName() + "' has a missing dependency '"
                            + dependName + "'.");

                        return;
                    }

                    dependencies.put(component, componentData.get(dependName));
                }
            });

            boolean changed;
            while (loadedComponents.size() + failedComponents.size() < componentData.size()) {
                changed = false;

                outer:
                for (ComponentData cd : componentData.values()) {
                    if (!cd.isLoaded() && !failedComponents.contains(cd)) {
                        Set<ComponentData> depends = dependencies.get(cd);

                        for (ComponentData depend : depends) {
                            if (failedComponents.contains(depend)) {
                                logger.severe("Component '" + cd.getName() + "' failed to load because component '"
                                    + depend.getName() + "' failed to load.");
                                failedComponents.add(cd);
                                cd.setLoaded(false);

                                continue outer;
                            }

                            if (!depend.isLoaded()) continue outer;
                        }

                        changed = true;
                        boolean loaded = loadComponent(cd);
                        if (loaded) {
                            logger.info("Component '" + cd.getName() + "' has loaded.");
                            loadedComponents.add(cd);

                            dependencies.values().remove(cd);
                        } else {
                            logger.severe("Component '" + cd.getName() + "' failed to load.");
                            failedComponents.add(cd);
                        }
                    }
                }

                if (!changed) {
                    logger.severe("Component loading deadlocked. Components that failed to load: "
                        + componentData.values().stream()
                        .filter(ComponentData::isLoaded)
                        .map(ComponentData::getName)
                        .collect(Collectors.joining(", ", "[", "]"))
                    );
                }
            }
        } catch (Exception e) {
            logger.severe("Unexpected error when loading up.");
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        components = null;
        logger = null;
    }

    public Object getComponent(String component) {
        return components.get(component).getComponent();
    }

    public boolean loadComponent(ComponentData componentData) {
        ClassInfo classInfo = componentData.getClassInfo();
        String componentName = componentData.getName();

        try {
            Class<?> componentClass = classInfo.loadClass();
            Object component = componentClass.newInstance();
            componentData.setComponent(component);

            injectConfig(component);

            if (component instanceof LoadableComponent) {
                LoadableComponent lc = (LoadableComponent) component;
                if (!lc.load()) return false;
            }
        } catch (Exception e) {
            logger.severe("Unexpected error when loading component '" + componentName + "'.");
            e.printStackTrace();
            return false;
        }

        componentData.setLoaded(true);
        return true;
    }

    public void injectConfig(Object object) {
        injectConfig(object, "");
    }

    public void injectConfig(Object object, String baseKey) {
        Class<?> objectClass = object.getClass();

        YamlConfiguration defaultConfig = null;
        String defaultPath = null;

        Config classConfig = objectClass.getAnnotation(Config.class);
        if (classConfig != null) {
            defaultPath = classConfig.path();
            defaultConfig = loadConfig(defaultPath);
        }

        Field[] fields = objectClass.getDeclaredFields();
        for (Field field : fields) {
            Config config = field.getAnnotation(Config.class);
            if (config == null) continue;

            String key = config.key().isEmpty() ? field.getName() : config.key();
            if (!baseKey.isEmpty()) key = baseKey + "." + key;

            YamlConfiguration fieldConfig;
            String path;
            if (config.path().isEmpty()) {
                path = defaultPath;
                fieldConfig = defaultConfig;
            } else {
                path = config.path();
                fieldConfig = loadConfig(path);
            }

            if (fieldConfig == null) {
                logger.warning("Could not load config file for key '" + key + "' of field '" + field.getName()
                    + "' in class '" + objectClass.getName() + "'.");
                continue;
            }

            if (!fieldConfig.contains(key)) {
                if (config.required())
                    logger.warning("Config '" + path + "' does not contain key '" + key + "' for field '"
                        + field.getName() + "' in class '" + objectClass.getName() + "'.");
                continue;
            }

            Resolve resolve = field.getAnnotation(Resolve.class);
            Class<?> fieldType = field.getType();
            Object obj;
            if (resolve != null) {
                Class<? extends Function<?, ?>> resolver = resolve.resolver();
                Class<?> fromClass = resolve.from();

                try {
                    Method apply = resolver.getMethod("apply", fromClass);

                    if (!fieldType.isAssignableFrom(apply.getReturnType())) {
                        logger.warning("Resolver for key '" + key + "' of field '" + field.getName()
                            + "' in class '" + objectClass.getName() + "' does not match target class.");
                        continue;
                    }

                    Object from = fieldConfig.getObject(key, fromClass);
                    if (from == null) {
                        logger.warning("Invalid value for key '" + key + "' of field '" + field.getName()
                            + "' in class '" + objectClass.getName() + "' from config '" + path + "'.");
                        continue;
                    }

                    Function<?, ?> resolverInstance = resolver.newInstance();
                    obj = apply.invoke(resolverInstance, from);
                } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    logger.warning("Invalid resolver for key '" + key + "' of field '" + field.getName()
                        + "' in class '" + objectClass.getName() + "'.");
                    continue;
                }
            } else obj = fieldConfig.getObject(key, fieldType);

            if (obj == null) {
                logger.warning("Invalid value for key '" + key + "' of field '" + field.getName() + "' in class '"
                    + objectClass.getName() + "' from config '" + path + "'.");
                continue;
            }

            if (obj instanceof String && config.colorize())
                obj = ChatColor.translateAlternateColorCodes('&', (String) obj);

            try {
                field.setAccessible(true);
                field.set(object, obj);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                logger.warning("Unable to set value from key '" + key + "' of field '" + field.getName()
                    + "' in class '" + objectClass.getName() + "' from config '" + path + "'."
                );
            }
        }
    }

    public YamlConfiguration loadConfig(String path) {
        File file = new File(getDataFolder(), path);
        if (!file.exists()) return null;

        return YamlConfiguration.loadConfiguration(file);
    }

}
