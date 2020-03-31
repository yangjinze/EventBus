/*
 * Copyright (C) 2012-2020 Markus Junginger, greenrobot (http://greenrobot.org)
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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * EventBus is a central publish/subscribe event system for Java and Android.
 * Events are posted ({@link #post(Object)}) to the bus, which delivers it to subscribers that have a matching handler
 * method for the event type.
 * To receive events, subscribers must register themselves to the bus using {@link #register(Object)}.
 * Once registered, subscribers receive events until {@link #unregister(Object)} is called.
 * Event handling methods must be annotated by {@link Subscribe}, must be public, return nothing (void),
 * and have exactly one parameter (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "EventBus";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();

    /**
     *  缓存事件类型对应的继承体系和实现的接口列表，避免每次都重复解析获取
     *
     *  key : 发送的事件对应的class，比如 post("123")， key则为String.class
     *  value : 事件class、事件实现的接口、事件的父类、父类实现的接口、父类的父类......这一连串有继承关系和接口实现关系的类集合
     *
     *  发送一个事件时，事件类型为其父类或者接口的订阅者方法也能被触发，比如：
     *
     *  假设A.class ,  B extends A , B implements C接口
     *  @Subscribe()
     *  public void subsA(A a) {}
     *  @Subscribe()
     *  public void subsC(C c) {}
     *  @Subscribe()
     *  public void subsB(B b) {}
     *
     *  则Post(B)的时候，上述3个订阅者方法都会被执行
     */
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();

    /**
     * 存放订阅方法的接收的消息入参class -->  对应的订阅Subscription对象列表
     *
     *
     * 作用：进行post(消息)的时候,根据 消息.class 就可以取出ubscription对象列表，然后逐一执行里面的订阅者方法
     */
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    /**
     *  存放订阅者 -> 所拥有的订阅方法的入参class列表
     *
     *  订阅者: 调用register传入的对象， 比如 FirstActivity.this
     *  订阅方法的入参class列表:
     *    假设 FirstActivity 存在2个订阅者方法
     *    @Subscribe
     *    public void test(String msg)
     *    @Subscribe
     *    public void test(int type)
     *    则对应列表[String.class, Integer.class]
     *
     *  作用： 下次根据unregister传入的对象，比如 FirstActivity.this就可以获取到列表 [String.class, Integer.class]
     *  然后就可以从 subscriptionsByEventType 得到String.class 和 Integer.class对应的订阅方法对象列表
     *  遍历订阅对象一致，则移除FirstActivity.this包含的所有订阅方法
     */
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    /**
     * 粘性事件缓存,比如postSticky("123")
     * 则缓存String.class --> "123" ，可见对于每一个类型的事件只会缓存最新的粘性事件
     */
    private final Map<Class<?>, Object> stickyEvents;

    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    // @Nullable
    private final MainThreadSupport mainThreadSupport;
    // @Nullable
    private final Poster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    private final int indexCount;
    private final Logger logger;

    /** Convenience singleton for apps using a process-wide EventBus instance. */
    public static EventBus getDefault() {
        EventBus instance = defaultInstance;
        if (instance == null) {
            synchronized (EventBus.class) {
                instance = EventBus.defaultInstance;
                if (instance == null) {
                    instance = EventBus.defaultInstance = new EventBus();
                }
            }
        }
        return instance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        logger = builder.getLogger();
        subscriptionsByEventType = new HashMap<>();
        typesBySubscriber = new HashMap<>();
        stickyEvents = new ConcurrentHashMap<>();
        mainThreadSupport = builder.getMainThreadSupport();
        mainThreadPoster = mainThreadSupport != null ? mainThreadSupport.createPoster(this) : null;
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     */
    public void register(Object subscriber) {
        //subscriber订阅者，比如FirstActivity
        Class<?> subscriberClass = subscriber.getClass(); //获取到订阅者的类型，比如FirstActivity.class
        //获取订阅者中符合条件的@Subscribe标注的订阅者方法列表
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    /**
     *
     * @param subscriber        订阅者，调用register注册的对象，比如FirstActivity.this
     * @param subscriberMethod  订阅者方法列表，比如FirstActivity.class中符合条件的@Subscribe标注的方法
     */
    // Must be called in synchronized block
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        Class<?> eventType = subscriberMethod.eventType;  //method，入参对应的class，比如String.class
        //订阅对象， 存放订阅者对象与订阅方法对象 , FirstActivity.this 与 SubscriberMethod
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        /***
         * CopyOnWriteArrayList是线程安全的List
         *
         * 获取入参类型对应的订阅列表,这样可以在进行post的时候，直接根据发送的消息参数.class
         * 获取到需要执行的订阅者方法了吧，逐一进行调用
         * ***/
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions == null) {
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            //TODO 这个判断怎么理解，为什么能实现效果
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        //根据@Subscribe中参数priority指定的优先级存放找个订阅对象
        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            //priority越大，优先级越高，放在越前面，更早被调用
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        //缓存这个订阅者对象，比如FirstActivity.this，所拥有的订阅方法入参class列表
        //作用可以参考typesBySubscriber属性注释
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            //TODO ArrayList存在不妥的地方，应该用Set集合来避免添加了相同的Class
            //不过一般一个订阅者也不会存在相同入参类型的订阅者方法
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        subscribedEvents.add(eventType);

        //处理粘性事件，有符合事件类型的，则执行
        //注意的是这边粘性事件执行完，并不会从缓存中清除粘性事件，也就是下次再次注册或者有相关事件类型的定义方法注册时，都会再次执行这个粘性事件。
        //粘性事件的删除需要主动删除，因为毕竟框架不清楚到底这个粘性事件有多少地方需要执行
        if (subscriberMethod.sticky) {
            if (eventInheritance) {//默认是true
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).

                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    Class<?> candidateEventType = entry.getKey();
                    //eventType是candidateEventType的父类，或者2者同一类型时，返回true
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        Object stickyEvent = entry.getValue();
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                Object stickyEvent = stickyEvents.get(eventType);
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            postToSubscription(newSubscription, stickyEvent, isMainThread());
        }
    }

    /**
     * Checks if the current thread is running in the main thread.
     * If there is no main thread support (e.g. non-Android), "true" is always returned. In that case MAIN thread
     * subscribers are always called in posting thread, and BACKGROUND subscribers are always called from a background
     * poster.
     */
    private boolean isMainThread() {
        return mainThreadSupport == null || mainThreadSupport.isMainThread();
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /** Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber. */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        //获取入参class所对应的订阅方法对象列表
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            //TODO 列表遍历删除的一种实现方式，与迭代器的删除方式比较下
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                //如果这个订阅者方法是该subscriber所有的，则进行移除
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /** Unregisters the given subscriber from all event classes. */
    /**
     * 取消注册，并且清除对应的订阅方法列表
     * 避免一直缓存了该对象，导致内存泄漏
     *
     * @param subscriber
     */
    public synchronized void unregister(Object subscriber) {
        //获取该对象，比如FirstActivity.this所拥有的订阅方法消息入参class列表
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            //遍历入参class列表
            for (Class<?> eventType : subscribedTypes) {
                //传入订阅者对象，与入参class
                unsubscribeByEventType(subscriber, eventType);
            }
            //移除缓存的这个对象与列表
            typesBySubscriber.remove(subscriber);
        } else {
            logger.log(Level.WARNING, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }
     //发送给定的事件到消息事件总线
    /** Posts the given event to the event bus. */
    public void post(Object event) {
        //TODO 这种缓存池实现用法了解
        PostingThreadState postingState = currentPostingThreadState.get();
        List<Object> eventQueue = postingState.eventQueue;
        //事件队列
        eventQueue.add(event);

        if (!postingState.isPosting) {
            //TODO isMainThread??? 当前是否在主线程
            postingState.isMainThread = isMainThread();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                //先进先出，按顺序处理事件
                while (!eventQueue.isEmpty()) {
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     */
    public void postSticky(Object event) {
        //缓存粘性事件
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        //执行正常的事件发送
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 发送一个事件
     *
     * @param event  要发送的事件,EventBus.getDefault().post(event);
     * @param postingState
     * @throws Error
     */
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        //event要发送的事件对象， 获取事件class类型，比如String.class
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        //TODO eventInheritance作用？， 一开始默认是true
        if (eventInheritance) {
            //递归获取事件class类型的相关父类，实现的接口，父类实现的接口
            //也就是说相应入参为父类、实现的接口的订阅方法，都能被调用到
            // 事件class > 接口 > 父类 > 父类接口 > 父类的父类 > 父类的父类接口.....
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        if (!subscriptionFound) {
            if (logNoSubscriberMessages) {
                logger.log(Level.FINE, "No subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    /**
     * 发送事件
     *
     * @param event         事件对象 ,比如post("123")，则为"123"
     * @param postingState
     * @param eventClass    事件类型， "123"对应String.class ， 看调用的地方，也可能为其父类或者实现的接口
     * @return
     */
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            //获取该事件类型对应的订阅方法对象列表
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            //Subscription存放每个订阅者方法 与 订阅者对象
            for (Subscription subscription : subscriptions) {
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted;
                try {
                    //将事件传递给订阅方法@Subscribe
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    //TODO mainThreadPoster、backgroundPoster、asyncPoster实现了解下
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        //根据@Subscribe里面threadMode所指定的线程做处理
        switch (subscription.subscriberMethod.threadMode) {
            case POSTING://默认的运行方式，运行在与发送方(post调用所在的线程)相同的线程，堵塞并直接执行
                //堵塞并执行的意思是，post之后立马执行订阅者方法后，再接着执行post后面的代码
                invokeSubscriber(subscription, event);
                break;
            case MAIN://运行在主线程
                if (isMainThread) {//如果当前post发送方就是在主线程，堵塞并直接执行
                    invokeSubscriber(subscription, event);
                } else {//如果当前post发送方不在主线程，最终由handler切换到主线程执行
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case MAIN_ORDERED://运行在主线程
                //与main的区别在于，MAIN_ORDERED都是排队执行，如果发送方当前是主线程的话，不会堵塞当前线程，
                //而main则是如果当前是主线程会堵塞主线程直到订阅方法执行完毕
                if (mainThreadPoster != null) {
                    mainThreadPoster.enqueue(subscription, event);
                } else {
                    // temporary: technically not correct as poster not decoupled from subscriber
                    invokeSubscriber(subscription, event);
                }
                break;
            case BACKGROUND://运行在后台线程(非主线程)
                if (isMainThread) {//如果当前post发送方是主线程，进入后台线程排队
                    backgroundPoster.enqueue(subscription, event);
                } else {//如果当前post发送方是后台线程，则直接堵塞该线程并执行
                    invokeSubscriber(subscription, event);
                }
                break;
            case ASYNC://运行在异步线程，无论如何都另起线程来执行订阅方法，与post发送方所在线程不同
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /** Looks up all Class objects including super classes and interfaces. Should also work for interfaces. */

    /**
     * 获取eventClass这个事件类，并递归获取其实现的接口、父类、父类实现的接口
     *
     * @param eventClass
     * @return
     */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    eventTypes.add(clazz);
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /** Recurses through super interfaces. */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    /**
     * 反射执行订阅者方法
     *
     * @param subscription 订阅方法与订阅者对象
     * @param event  事件，比如post("123")，即为"123"
     */
    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            //反射执行订阅者对象subscription.subscriber(比如FirstActivity.this)中的方法subscription.subscriberMethod.method，入参为event
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                logger.log(Level.SEVERE, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                logger.log(Level.SEVERE, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                logger.log(Level.SEVERE, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    final static class PostingThreadState {
        final List<Object> eventQueue = new ArrayList<>();
        boolean isPosting;
        boolean isMainThread;
        Subscription subscription;
        Object event;
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * For internal use only.
     */
    public Logger getLogger() {
        return logger;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
