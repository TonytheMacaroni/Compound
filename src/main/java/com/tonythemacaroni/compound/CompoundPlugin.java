package com.tonythemacaroni.compound;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.function.Function;
import java.lang.reflect.InvocationTargetException;

import org.apache.commons.lang.exception.ExceptionUtils;

import com.google.common.collect.HashMultimap;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.AnnotationParameterValueList;

import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
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

        try (
            ScanResult result = new ClassGraph()
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
        components.forEach((name, componentData) -> {
            if (!componentData.isLoaded()) return;

            Object component = componentData.getComponent();

            if (component instanceof LoadableComponent) {
                logger.info("Unloading component '" + name + "'...");

                try {
                    ((LoadableComponent) component).unload();
                } catch (Exception e) {
                    logger.severe("Error when unloading component '" + name + "'.");
                    e.printStackTrace();

                    componentData.setFailException(ExceptionUtils.getStackTrace(e));
                }
            }
        });

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
            components.put(componentData.getName(), componentData);

            if (!injectConfig(component)) {
                String msg = "Unable to inject config into component '" + componentName + "'.";

                logger.severe(msg);
                componentData.addFailReason(msg);

                return false;
            }

            if (component instanceof LoadableComponent) {
                LoadableComponent lc = (LoadableComponent) component;
                if (!lc.load()) {
                    componentData.addFailReason("Load failed for component '" + componentName + "'.");
                    return false;
                }
            }
        } catch (Exception e) {
            logger.severe("Unexpected error when loading component '" + componentName + "'.");

            componentData.setFailException(ExceptionUtils.getStackTrace(e));
            e.printStackTrace();

            return false;
        }

        componentData.setLoaded(true);
        return true;
    }

    public boolean injectConfig(Object object) {
        return injectConfig(object, null, null, null, null);
    }

    public boolean injectConfig(Object object, String defaultPath, String baseKey) {
        YamlConfiguration defaultConfig = null;
        if (defaultPath != null) defaultConfig = loadConfig(defaultPath);

        return injectConfig(object, null, defaultConfig, defaultPath, baseKey);
    }

    public boolean injectConfig(Object object, ConfigurationSection defaultConfig, String defaultPath, String baseKey) {
        return injectConfig(object, null, defaultConfig, defaultPath, baseKey);
    }

    public boolean injectConfig(Object object, ComponentData componentData, ConfigurationSection defaultConfig, String defaultPath, String baseKey) {
        Class<?> objectClass = object.getClass();

        while (objectClass != Object.class) {
            YamlConfiguration classConfig = null;
            String classPath = null;

            Config configClass = objectClass.getAnnotation(Config.class);
            if (configClass != null) {
                classPath = configClass.path();
                classConfig = loadConfig(classPath);
            }

            Field[] fields = objectClass.getDeclaredFields();
            for (Field field : fields) {
                Config config = field.getAnnotation(Config.class);
                if (config == null) continue;

                boolean required = config.required();

                String key = config.key().isEmpty() ? field.getName() : config.key();
                if (baseKey != null) key = baseKey + "." + key;

                ConfigurationSection fieldConfig;
                String path;
                if (!config.path().isEmpty()) {
                    path = config.path();
                    fieldConfig = loadConfig(path);
                } else if (classConfig != null) {
                    path = classPath;
                    fieldConfig = classConfig;
                } else {
                    path = defaultPath;
                    fieldConfig = defaultConfig;
                }

                if (fieldConfig == null) {
                    if (required) {
                        String msg = "Could not load config file for key '" + key + "' of field '" + field.getName()
                            + "' in class '" + objectClass.getName() + "'.";

                        if (componentData != null) componentData.addFailReason(msg);
                        logger.warning(msg);

                        return false;
                    }

                    continue;
                }

                if (!fieldConfig.contains(key)) {
                    if (required) {
                        String msg = "Config '" + path + "' does not contain required key '" + key + "' for field '"
                            + field.getName() + "' in class '" + objectClass.getName() + "'.";

                        if (componentData != null) componentData.addFailReason(msg);
                        logger.warning(msg);

                        return false;
                    }

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
                            String msg = "Resolver for key '" + key + "' of field '" + field.getName() + "' in class '"
                                + objectClass.getName() + "' does not match target class.";

                            logger.warning(msg);

                            if (required) {
                                if (componentData != null) componentData.addFailReason(msg);
                                return false;
                            }

                            continue;
                        }

                        Object from = fieldConfig.get(key);
                        if (fromClass.isInstance(from)) {
                            String msg = "Invalid or missing value for key '" + key + "' of field '" + field.getName()
                                + "' in class '" + objectClass.getName() + "' from config '" + path + "'.";

                            logger.warning(msg);

                            if (required) {
                                if (componentData != null) componentData.addFailReason(msg);
                                return false;
                            }

                            continue;
                        }

                        Function<?, ?> resolverInstance = resolver.newInstance();
                        obj = apply.invoke(resolverInstance, from);
                    } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                        String msg = "Invalid resolver for key '" + key + "' of field '" + field.getName()
                            + "' in class '" + objectClass.getName() + "'.";

                        logger.warning(msg);

                        if (required) {
                            if (componentData != null) {
                                componentData.addFailReason(msg);
                                componentData.setFailException(ExceptionUtils.getStackTrace(e));
                            }

                            return false;
                        }

                        continue;
                    }
                } else obj = fieldConfig.get(key);

                if (obj == null) {
                    if (required) {
                        String msg = "Missing value for key '" + key + "' of field '" + field.getName()
                            + "' in class '" + objectClass.getName() + "' from config '" + path + "'.";

                        logger.warning(msg);

                        if (componentData != null) componentData.addFailReason(msg);
                        return false;
                    }

                    continue;
                }

                fieldType = primitiveToWrapper(fieldType);
                if (Number.class.isAssignableFrom(fieldType))
                    obj = convertNumber(obj, fieldType);

                if (obj instanceof String && config.colorize())
                    obj = ChatColor.translateAlternateColorCodes('&', (String) obj);

                if (!fieldType.isInstance(obj)) {
                    String msg = "Invalid value for key '" + key + "' of field '" + field.getName()
                        + "' in class '" + objectClass.getName() + "' from config '" + path + "'.";
                    logger.warning(msg);

                    if (required) {
                        if (componentData != null) componentData.addFailReason(msg);
                        return false;
                    }

                    continue;
                }

                try {
                    field.setAccessible(true);
                    field.set(object, obj);
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    String msg = "Unable to set value from key '" + key + "' of field '" + field.getName()
                        + "' in class '" + objectClass.getName() + "' from config '" + path + "'.";

                    logger.warning(msg);

                    if (required) {
                        if (componentData != null) {
                            componentData.addFailReason(msg);
                            componentData.setFailException(ExceptionUtils.getStackTrace(e));
                        }

                        return false;
                    }
                }
            }

            objectClass = objectClass.getSuperclass();
        }

        return true;
    }

    private Object convertNumber(Object obj, Class<?> numberClass) {
        if (numberClass.isInstance(obj)) return obj;
        if (!(obj instanceof Number)) return obj;

        Number number = (Number) obj;
        if (numberClass == int.class || numberClass == Integer.class) return number.intValue();
        if (numberClass == long.class || numberClass == Long.class) return number.longValue();
        if (numberClass == byte.class || numberClass == Byte.class) return number.byteValue();
        if (numberClass == float.class || numberClass == Float.class) return number.floatValue();
        if (numberClass == double.class || numberClass == Double.class) return number.doubleValue();
        if (numberClass == short.class || numberClass == Short.class) return number.shortValue();

        return obj;
    }

    private Class<?> primitiveToWrapper(Class<?> type) {
        if (!type.isPrimitive()) return type;

        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == void.class) return Void.class;

        return type;
    }

    public YamlConfiguration loadConfig(String path) {
        File file = new File(getDataFolder(), path);
        return YamlConfiguration.loadConfiguration(file);
    }

}
