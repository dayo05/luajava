package party.iroiro.luajava;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import party.iroiro.luajava.util.ClassUtils;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides complex functions for JNI uses
 * <p>
 * Most reflection features on the lua side rely on this class.
 * <p>
 * The code gets a bit ugly as I want to avoid calling back into
 * lua again.
 */
public abstract class JuaAPI {
    public static int load(int id, String module) {
        AbstractLua L = Jua.get(id);
        if (L.loadExternal(module) == Lua.LuaError.OK) {
            return 1;
        } else {
            return 0;
        }
    }

    public static int proxy(int id) {
        Lua L = Jua.get(id);
        int interfaces = L.getTop() - 1;
        LinkedList<Class<?>> classes = new LinkedList<>();
        for (int i = 1; i <= interfaces; i++) {
            try {
                Class<?> c = ClassUtils.forName(L.toString(i), null);
                if (c.isInterface()) {
                    classes.add(c);
                } else {
                    return 0;
                }
            } catch (ClassNotFoundException e) {
                return 0;
            }
        }
        Object o = L.createProxy(classes.toArray(new Class[0]), Lua.Conversion.SEMI);
        L.push(o, Lua.Conversion.NONE);
        return 1;
    }

    public static int javaImport(int id, String className) {
        Lua L = Jua.get(id);
        if (className.endsWith(".*")) {
            L.createTable(0, 0);
            L.createTable(0, 1);
            String packageName = className.substring(0, className.length() - 1);
            L.push(l -> {
                String name = l.toString(-1);
                if (name != null) {
                    return javaImport(l.getId(), packageName + name);
                } else {
                    return 0;
                }
            });
            L.setField(-2, "__index");
            L.setMetatable(-2);
            return 1;
        } else {
            try {
                L.pushJavaClass(ClassUtils.forName(className, null));
                return 1;
            } catch (ClassNotFoundException e) {
                return 0;
            }
        }
    }

    /**
     * Converts a value on top of the stack into a more Lua-style form
     *
     * @param id the state id
     * @return the number of return values, always 1
     */
    public static int luaify(int id) {
        Lua L = Jua.get(id);
        Object o = L.toJavaObject(-1);
        if (o != null) {
            L.push(o, Lua.Conversion.FULL);
        }
        return 1;
    }

    /**
     * Allocates an id for a thread created on the Lua side
     *
     * @param mainId the main thread id
     * @param ptr    the pointer to the lua state who does not have an id
     * @return an allocated id
     */
    public static int threadNewId(int mainId, long ptr) {
        return AbstractLua.adopt(mainId, ptr);
    }

    /**
     * Obtains the value of a certain field of an object
     *
     * <p>
     * This method is mainly used by the JNI side. See {@code jni/luajava/jua.cpp}.
     * </p>
     *
     * <p>
     * For static fields, use {@link #classIndex(int, Class, String)} instead.
     * </p>
     *
     * @param index  the id of {@link Jua} thread calling this method
     * @param object the object
     * @param name   the name of the field
     * @return 1 if a field is found, 2 otherwise
     * @see #fieldIndex(int, Class, Object, String)
     */
    public static int objectIndex(int index, @NotNull Object object, String name) {
        return fieldIndex(index, object.getClass(), object, name);
    }

    /**
     * Invokes a member method of an object
     *
     * <p>
     * This method is mainly used by the JNI side. See {@code jni/luajava/jua.cpp}.
     * If {@code name} is {@code null}, we assume that the object is a {@link JFunction}.
     * </p>
     *
     * <p>
     * For static fields, use {@link #classInvoke(int, Class, String, int)} instead.
     * </p>
     *
     * @param index      the id of {@link Jua} thread calling this method
     * @param obj        the object
     * @param name       the name of the field
     * @param paramCount number of parameters (on the lua stack)
     * @return the number result pushed on stack
     * @see #methodInvoke(int, Class, Object, String, int)
     */
    public static int objectInvoke(int index, @NotNull Object obj, @Nullable String name, int paramCount) {
        if (name == null) {
            return juaFunctionCall(index, obj, paramCount);
        } else {
            return methodInvoke(index, obj.getClass(), obj, name, paramCount);
        }
    }

    /**
     * Calls a {@link JFunction}
     *
     * @param index   the id of {@link Jua} thread calling this method
     * @param obj     the {@link JFunction} object
     * @param ignored parameter count, but we are not using it
     * @return the number result pushed on stack
     */
    private static int juaFunctionCall(int index, Object obj, int ignored) {
        Lua L = Jua.get(index);
        if (obj instanceof JFunction) {
            return ((JFunction) obj).__call(L);
        } else {
            return 0;
        }
    }

    public static int objectInvoke(int index, Object obj, String name,
                                   String notSignature, int paramCount) {
        return methodInvoke(index, obj.getClass(), obj, name, notSignature, paramCount);
    }

    public static int objectNewIndex(int index, Object obj, String name) {
        return fieldNewIndex(index, obj.getClass(), obj, name);
    }

    public static int classNew(int index, Class<?> clazz, int paramCount) {
        Lua L = Jua.get(index);
        Object[] objects = new Object[paramCount];
        Constructor<?> constructor = matchMethod(L, clazz.getConstructors(), null, objects);
        if (constructor != null) {
            try {
                Object obj = constructor.newInstance(objects);
                L.pushJavaObject(obj);
                return 1;
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                return 0;
            }
        }
        return 0;
    }

    public static int classIndex(int index, Class<?> clazz, String name) {
        if (name.equals("class")) {
            Lua L = Jua.get(index);
            L.pushJavaObject(clazz);
            return 1;
        } else {
            return fieldIndex(index, clazz, null, name);
        }
    }

    public static int classInvoke(int index, Class<?> clazz, String name, int paramCount) {
        return methodInvoke(index, clazz, null, name, paramCount);
    }

    public static int classInvoke(int index, Class<?> clazz, String name,
                                  String notSignature, int paramCount) {
        return methodInvoke(index, clazz, null, name, notSignature, paramCount);
    }

    public static int classNewIndex(int index, Class<?> clazz, String name) {
        return fieldNewIndex(index, clazz, null, name);
    }

    public static int arrayIndex(int index, Object obj, int i) {
        try {
            Object e = Array.get(obj, i - 1);
            Jua.get(index).push(e, Lua.Conversion.SEMI);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    public static int arrayNewIndex(int index, Object obj, int i) {
        try {
            Lua L = Jua.get(index);
            Array.set(obj, i - 1, L.toObject(L.getTop(), obj.getClass().getComponentType()));
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static int arrayLength(Object obj) {
        try {
            return Array.getLength(obj);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Calls the given method <code>{obj}.{name}(... params from stack)</code>
     * <p>
     * Judge param types from the lua stack and pushes results afterwards.
     * The params are expected to be on stack positions from <code>-paramCount</code>
     * to <code>-1</code>. For example, if you have two params, then the first
     * is expected to be at -2, and the second be at -1.
     *
     * @param index      the index of Java-side {@link Jua}
     * @param clazz      the {@link Class}
     * @param obj        the object (nullable when calling static methods)
     * @param name       the method name
     * @param paramCount number of supplied params
     * @return the number result pushed on stack
     */
    public static int methodInvoke(int index, Class<?> clazz, @Nullable Object obj,
                                   String name, int paramCount) {
        Lua L = Jua.get(index);
        /* Storage of converted params */
        Object[] objects = new Object[paramCount];
        Method method = matchMethod(L, clazz.getMethods(), name, objects);
        if (method == null) {
            return -1;
        } else {
            return methodInvoke(L, method, obj, objects);
        }
    }

    public static int methodInvoke(int index, Class<?> clazz, Object obj, String name,
                                   String notSignature, int paramCount) {
        Lua L = Jua.get(index);
        Method method = matchMethod(clazz, name, notSignature);
        if (method != null) {
            Object[] objects = new Object[paramCount];
            if (matchMethod(L, new Method[]{method}, name, objects) != null) {
                return methodInvoke(L, method, obj, objects);
            }
        }
        return 0;
    }

    public static int methodInvoke(Lua L, Method method, @Nullable Object obj, Object[] objects) {
        Object ret;
        try {
            ret = method.invoke(obj, objects);
        } catch (IllegalAccessException e) {
            L.push(e.toString());
            return -1;
        } catch (InvocationTargetException e) {
            L.push(e.getCause().toString());
            return -1;
        }
        if (ret == null) {
            return 0;
        } else {
            L.push(ret, Lua.Conversion.SEMI);
            return 1;
        }
    }

    /**
     * Tries to fetch field from an object
     * <p>
     * When a matching field is found, it is pushed to the corresponding lua stack
     *
     * @param index  the index of {@link Jua} state
     * @param clazz  the {@link Class}
     * @param object the object
     * @param name   the name of the field / method
     * @return 1 if a field is found, 2 otherwise
     */
    public static int fieldIndex(int index, Class<?> clazz, @Nullable Object object, String name) {
        Lua L;
        try {
            Field field = clazz.getField(name);
            Object obj = field.get(object);
            L = Jua.get(index);
            L.push(obj, Lua.Conversion.SEMI);
            return 1;
        } catch (NoSuchFieldException | IllegalAccessException | NullPointerException ignored) {
            return 2;
        }
    }

    private static int fieldNewIndex(int index, Class<?> clazz, Object object, String name) {
        try {
            Field field = clazz.getField(name);
            Lua L = Jua.get(index);
            Class<?> type = field.getType();
            Object o = convertFromLua(L, type, 3);
            field.set(object, o);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException ignored) {
        }
        return 0;
    }

    @Nullable
    private static <T extends Executable> T matchMethod(Lua L, T[] methods,
                                                        @Nullable String name, Object[] params) {
        for (T method : methods) {
            if (method.getParameterCount() == params.length) {
                if (name == null || name.equals(method.getName())) {
                    Class<?>[] classes = method.getParameterTypes();
                    try {
                        for (int i = 0; i != params.length; ++i) {
                            params[i] = convertFromLua(L, classes[i], -params.length + i);
                        }
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    return method;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Method matchMethod(Class<?> clazz, String name, String notSignature) {
        Class<?>[] classes = getClasses(notSignature);
        try {
            return clazz.getMethod(name, classes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Converts an element on the lua statck at <code>index</code> to Java
     *
     * @param L     the lua state
     * @param clazz the expected return type
     * @param index a <b>lua</b> index (that is, starts from 1)
     * @return the converted element
     * @throws IllegalArgumentException when unable to convert
     */
    @Nullable
    public static Object convertFromLua(Lua L, Class<?> clazz, int index)
            throws IllegalArgumentException {
        Lua.LuaType type = L.type(index);
        if (type == Lua.LuaType.NIL) {
            if (clazz.isPrimitive()) {
                throw new IllegalArgumentException("Primitive not accepting null values");
            } else {
                return null;
            }
        } else if (type == Lua.LuaType.BOOLEAN) {
            if (clazz == boolean.class || clazz == Boolean.class) {
                return L.toBoolean(index);
            }
        } else if (type == Lua.LuaType.STRING && clazz.isAssignableFrom(String.class)) {
            return L.toString(index);
        } else if (type == Lua.LuaType.NUMBER) {
            if (clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)) {
                return convertNumber(L.toNumber(index), clazz);
            } else if (Character.class == clazz) {
                return (char) L.toNumber(index);
            } else if (Boolean.class == clazz) {
                return L.toNumber(index) != 0;
            }
        } else if (type == Lua.LuaType.USERDATA) {
            Object object = L.toJavaObject(index);
            if (object != null && clazz.isAssignableFrom(object.getClass())) {
                return object;
            }
        } else if (type == Lua.LuaType.TABLE) {
            if (clazz.isAssignableFrom(List.class)) {
                return L.toList(index);
            } else if (clazz.isArray() && clazz.getComponentType() == Object.class) {
                return Objects.requireNonNull(L.toList(index)).toArray(new Object[0]);
            } else if (clazz.isAssignableFrom(Map.class)) {
                return L.toMap(index);
            }
        }
        throw new IllegalArgumentException("Unable to convert to " + clazz.getName());
    }

    private static Object convertNumber(double toNumber, Class<?> clazz)
            throws IllegalArgumentException {
        if (clazz.isPrimitive()) {
            if (boolean.class == clazz) {
                return toNumber != 0;
            }
            if (char.class == clazz) {
                return (char) (byte) toNumber;
            } else if (byte.class == clazz) {
                return (byte) toNumber;
            } else if (short.class == clazz) {
                return (short) toNumber;
            } else if (int.class == clazz) {
                return (int) toNumber;
            } else if (long.class == clazz) {
                return (long) toNumber;
            } else if (float.class == clazz) {
                return (float) toNumber;
            } else /* if (double.class == clazz) */ {
                return toNumber;
            }
        } else {
            return convertBoxedNumber(toNumber, clazz);
        }
    }

    private static Number convertBoxedNumber(double toNumber, Class<?> clazz)
            throws IllegalArgumentException {
        if (Byte.class == clazz) {
            return (byte) toNumber;
        } else if (Short.class == clazz) {
            return (short) toNumber;
        } else if (Integer.class == clazz) {
            return (int) toNumber;
        } else if (Long.class == clazz) {
            return (long) toNumber;
        } else if (Float.class == clazz) {
            return (float) toNumber;
        } else if (Double.class == clazz) {
            return toNumber;
        }
        throw new IllegalArgumentException("Unsupported conversion");
    }

    public static Class<?>[] getClasses(String notSignature) {
        if (notSignature == null || notSignature.isEmpty()) {
            return new Class<?>[0];
        }
        return ClassUtils.toClassArray(Arrays.stream(notSignature.split(",")).map(s -> {
            try {
                return ClassUtils.forName(s, null);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }).collect(Collectors.toList()));
    }
}