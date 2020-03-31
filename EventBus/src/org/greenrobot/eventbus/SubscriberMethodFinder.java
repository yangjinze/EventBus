/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    //缓存的是订阅者类对应的订阅者方法列表， 比如FirstActivity.class -> List<SubscriberMethod>
    //下次直接从缓存拿，不再重新解析
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    private final boolean strictMethodVerification;
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    /**
     * 获取subscriberClass中符合条件的订阅者方法列表
     *
     * @param subscriberClass
     * @return
     */
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        //判断这个类相关订阅方法是否解析过了，是则从缓存中读取，
        // 比如这样就避免了每次进入FirstActivity时执行register都需要重新再解析一遍
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        //没缓存，重新解析

        //ignoreGeneratedIndex默认是false TODO
        if (ignoreGeneratedIndex) {
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            //解析得到订阅者方法列表
            subscriberMethods = findUsingInfo(subscriberClass);
        }

        if (subscriberMethods.isEmpty()) {
            //调用register注册的订阅者，如果不存在符合条件的订阅者方法，则抛出异常
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            //缓存解析到的订阅者类对应的订阅者方法列表， 比如 FirstActivity - List<subscriberMethod>
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            //获取订阅者的相关信息
            findState.subscriberInfo = getSubscriberInfo(findState);
            //一开始为null
            if (findState.subscriberInfo != null) {
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                //解析该subscriberClass,比如FirstActivity， 将其中的订阅者方法存放在列表
                findUsingReflectionInSingleClass(findState);
            }
            //递归搜索父类中的订阅者方法，默认会进行父类搜索
            //所以在父类中的订阅者方法也会一并被继承过来并且生效
            findState.moveToSuperclass();
        }
        //返回解析到的订阅者方法列表，并释放资源
        return getMethodsAndRelease(findState);
    }

    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        //解析到的订阅者方法的list
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        //释放充值相关资源
        findState.recycle();
        //放回缓存池，下次循环利用
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        return subscriberMethods;
    }

    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    private SubscriberInfo getSubscriberInfo(FindState findState) {
        //一开始为null
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findUsingReflectionInSingleClass(findState);
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            //获取订阅者类所有的方法
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            try {
                methods = findState.clazz.getMethods();
            } catch (LinkageError error) { // super class of NoClassDefFoundError to be a bit more broad...
                String msg = "Could not inspect methods of " + findState.clazz.getName();
                if (ignoreGeneratedIndex) {
                    msg += ". Please consider using EventBus annotation processor to avoid reflection.";
                } else {
                    msg += ". Please make this class visible to EventBus annotation processor to avoid reflection.";
                }
                throw new EventBusException(msg, error);
            }
            findState.skipSuperClasses = true;
        }
        //遍历获取到的方法
        for (Method method : methods) {
            int modifiers = method.getModifiers();
            //method必须是public，并且不能是static、abstract、桥接方法，订阅者方法的第一点限制
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                //获取method入参列表对应的class数组，按顺序
                Class<?>[] parameterTypes = method.getParameterTypes();
                //这边限制了参数个数只能是1个,订阅者方法的第二点限制
                if (parameterTypes.length == 1) {
                    //获取该方法上的@Subscribe注解,订阅者方法的第三点限制，必须要用@Subscribe注解声明
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    if (subscribeAnnotation != null) {
                        //得到入参对应的class类，比如String.class
                        Class<?> eventType = parameterTypes[0];
                        //TODO 这个判断不是很理解
                        if (findState.checkAdd(method, eventType)) {
                            //获取注解中threadMode的值,用于指定订阅者方法运行在哪个线程
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            //将订阅者方法存放在该list中; SubscriberMethod构造对应订阅者方法method，入参class，注解中的3个参数
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    static class FindState {
        //存放找到的所有的订阅者方法，@Subscribe标识的符合条件的方法
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        Class<?> subscriberClass;
        Class<?> clazz;
        boolean skipSuperClasses;
        SubscriberInfo subscriberInfo;

        void initForSubscriber(Class<?> subscriberClass) {
            //订阅者对应的Class，比如FirstActivity.class
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        /**
         * 判断是否需要解析并添加相关订阅方法
         *
         * @param method     通过注解@Subscribe声明的订阅者方法
         * @param eventType  订阅者方法入参对应的class，比如String.class
         * @return true需要
         */
        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.

            //put,返回旧的value，为null代表原本没有解析过入参类为eventType的订阅者方法，需要进行解析
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
                return true;
            } else {
                //TODO 暂时看不到是啥意思
                if (existing instanceof Method) {
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    anyMethodByEventType.put(eventType, this);
                }
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());

            String methodKey = methodKeyBuilder.toString();
            Class<?> methodClass = method.getDeclaringClass();
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                // Skip system classes, this degrades performance.
                // Also we might avoid some ClassNotFoundException (see FAQ for background).
                //排除掉系统类
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") ||
                        clazzName.startsWith("android.") || clazzName.startsWith("androidx.")) {
                    clazz = null;
                }
            }
        }
    }

}
