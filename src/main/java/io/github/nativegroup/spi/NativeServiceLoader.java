/*
 * Copyright 2023 native-group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.nativegroup.spi;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * native services loader.
 * <p>
 * refer to dubbo spi
 *
 * @author llnancy admin@lilu.org.cn
 * @since JDK8 2023/5/30
 */
@Slf4j
public class NativeServiceLoader<T> {

    private static final String NATIVE_SERVICES_DIRECTORY = "META-INF/native-services/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    private static final ConcurrentMap<Class<?>, NativeServiceLoader<?>> NATIVE_SERVICE_LOADERS = Maps.newConcurrentMap();

    private static final ConcurrentMap<Class<?>, Object> NATIVE_SERVICE_INSTANCES = Maps.newConcurrentMap();

    private final Class<?> type;

    private final ConcurrentMap<String, Holder<Object>> cachedInstances = Maps.newConcurrentMap();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private String cachedDefaultName;

    public NativeServiceLoader(Class<?> type) {
        this.type = type;
    }

    /**
     * get NativeServiceLoader by type
     *
     * @param type Class
     * @param <T>  T
     * @return NativeServiceLoader
     */
    @SuppressWarnings("unchecked")
    public static <T> NativeServiceLoader<T> getNativeServiceLoader(Class<T> type) {
        Preconditions.checkNotNull(type, "native service type == null");
        Preconditions.checkArgument(type.isInterface(), "native service type (" + type + ") is not an interface!");
        Preconditions.checkArgument(
                Objects.nonNull(type.getAnnotation(SPI.class)),
                "native service type (" + type + ") is not an native service, because it is NOT annotated with @"
                        + SPI.class.getSimpleName() + "!"
        );
        // find in local cache
        NativeServiceLoader<T> loader = (NativeServiceLoader<T>) NATIVE_SERVICE_LOADERS.get(type);
        if (Objects.isNull(loader)) {
            // can invoke computeIfAbsent?
            NATIVE_SERVICE_LOADERS.putIfAbsent(type, new NativeServiceLoader<>(type));
            loader = (NativeServiceLoader<T>) NATIVE_SERVICE_LOADERS.get(type);
        }
        return loader;
    }

    /**
     * Get native service's instance. Return <code>null</code> if native service is not found or is not initialized. Pls. note
     * that this method will not trigger native service load.
     * <p>
     * In order to trigger native service load, call {@link #getNativeService(String)} instead.
     *
     * @param name native service name
     * @return native service instance
     * @see #getNativeService(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedNativeService(String name) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "native service name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (Objects.isNull(holder)) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    public Set<String> getSupportedNativeServices() {
        Map<String, Class<?>> classes = getNativeServiceClasses();
        return Collections.unmodifiableSet(Sets.newTreeSet(classes.keySet()));
    }

    /**
     * Return the list of native services which are already loaded.
     * <p>
     * Usually {@link #getSupportedNativeServices()} should be called in order to get all native services.
     *
     * @return Set of loaded native service instance.
     * @see #getSupportedNativeServices()
     */
    public Set<String> getLoadedNativeServices() {
        return Collections.unmodifiableSet(Sets.newTreeSet(cachedInstances.keySet()));
    }

    /**
     * Find the native service with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     *
     * @param name native service name
     * @return native service instance
     */
    @SuppressWarnings("unchecked")
    public T getNativeService(String name) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "native service name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (Objects.isNull(holder)) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (Objects.isNull(instance)) {
            synchronized (holder) {
                instance = holder.get();
                if (Objects.isNull(instance)) {
                    instance = createNativeService(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Get the native service by specified name if found, or {@link #getDefaultNativeService() returns the default one}
     *
     * @param name the name of native service
     * @return non-null
     */
    public T getOrDefaultNativeService(String name) {
        return getNativeServiceClasses().containsKey(name) ? getNativeService(name) : getDefaultNativeService();
    }

    /**
     * Return default native service, return <code>null</code> if it's not configured.
     */
    public T getDefaultNativeService() {
        getNativeServiceClasses();
        if (StringUtils.isBlank(cachedDefaultName)) {
            return null;
        }
        return getNativeService(cachedDefaultName);
    }

    /**
     * create native service instance by name
     *
     * @param name native service name
     * @return native service instance
     */
    @SuppressWarnings("unchecked")
    private T createNativeService(String name) {
        Class<?> clazz = getNativeServiceClasses().get(name);
        Preconditions.checkNotNull(clazz, "No such native service " + type.getName() + " by name " + name);
        try {
            T instance = (T) NATIVE_SERVICE_INSTANCES.get(clazz);
            if (Objects.isNull(instance)) {
                Constructor<?> constructor = clazz.getDeclaredConstructor();
                Object obj = constructor.newInstance();
                NATIVE_SERVICE_INSTANCES.putIfAbsent(clazz, obj);
                instance = (T) NATIVE_SERVICE_INSTANCES.get(clazz);
            }
            // no inject
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("native service instance(name: " + name + ", class: " + type + ") could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * get loaded native services class map
     *
     * @return k: native service name; v: native service class
     */
    private Map<String, Class<?>> getNativeServiceClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (Objects.isNull(classes)) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (Objects.isNull(classes)) {
                    classes = loadNativeServiceClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    private Map<String, Class<?>> loadNativeServiceClasses() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                Preconditions.checkState(names.length <= 1, "more than 1 default native service name on native service " + type.getName() + ": " + Arrays.toString(names));
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }
        Map<String, Class<?>> nativeServiceClasses = Maps.newHashMap();
        loadDirectory(nativeServiceClasses);
        return nativeServiceClasses;
    }

    /**
     * load directory
     *
     * @param nativeServiceClasses native service class map
     */
    private void loadDirectory(Map<String, Class<?>> nativeServiceClasses) {
        String fileName = NATIVE_SERVICES_DIRECTORY + type.getName();
        try {
            Enumeration<URL> urls;
            ClassLoader classLoader = NativeServiceLoader.class.getClassLoader();
            if (Objects.nonNull(classLoader)) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (Objects.nonNull(urls)) {
                while (urls.hasMoreElements()) {
                    URL resourceUrl = urls.nextElement();
                    loadResource(nativeServiceClasses, classLoader, resourceUrl);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Exception when load native service class(interface: " + type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * load resource
     *
     * @param nativeServiceClasses native service class map
     * @param classLoader          ClassLoader
     * @param resourceUrl          URL
     */
    private void loadResource(Map<String, Class<?>> nativeServiceClasses, ClassLoader classLoader, URL resourceUrl) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceUrl.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // ignore comments after #
                final int ci = line.indexOf('#');
                if (ci >= 0) {
                    line = line.substring(0, ci);
                }
                line = line.trim();
                if (line.length() > 0) {
                    int i = line.indexOf('=');
                    String name;
                    if (i > 0) {
                        name = line.substring(0, i).trim();
                        line = line.substring(i + 1).trim();
                    } else {
                        name = line;
                    }
                    if (line.length() > 0) {
                        loadClass(nativeServiceClasses, Class.forName(line, true, classLoader), name);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Exception when load native service class(interface: " + type + ", class file: " + resourceUrl + ") in " + resourceUrl, t);
        }
    }

    /**
     * load class
     *
     * @param nativeServiceClasses native service class map
     * @param clazz                native service class
     * @param name                 native service name
     */
    private void loadClass(Map<String, Class<?>> nativeServiceClasses, Class<?> clazz, String name) {
        String clazzName = clazz.getName();
        Preconditions.checkState(type.isAssignableFrom(clazz), "Error when load native service class(interface: " + type + ", class line: " + clazzName + "), class " + clazzName + " is not subtype of interface.");
        Class<?> c = nativeServiceClasses.get(name);
        if (Objects.isNull(c)) {
            nativeServiceClasses.put(name, clazz);
        } else if (c != clazz) {
            throw new IllegalStateException("Duplicate native service " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazzName);
        }
    }
}
