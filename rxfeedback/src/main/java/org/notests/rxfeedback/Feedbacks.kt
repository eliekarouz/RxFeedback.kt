@file:Suppress("unused")

package org.notests.rxfeedback

import io.reactivex.Emitter
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import org.notests.sharedsequence.*

/**
 * State: State type of the system.
 * Query: Subset of state used to control the feedback loop.
 *
 * When query returns some value, that value is being passed into `effects` lambda to decide which effects should be performed.
 * In case new `query` is different from the previous one, new effects are calculated by using `effects` lambda and then performed.
 *
 * When `query` returns null, feedback loops doesn't perform any effect.
 *
 * @param query Part of state that controls feedback loop.
 * @param areEqual Part of state that controls feedback loop.
 * @param effects Chooses which effects to perform for certain query result.
 * @return Feedback loop performing the effects.
 */
fun <State, Query, Event> react(
        query: (State) -> Query?,
        areEqual: (Query, Query) -> Boolean,
        effects: (Query) -> Observable<Event>
): (ObservableSchedulerContext<State>) -> Observable<Event> = react(
        queries = { state: State ->
            query(state)?.let { mapOf(ConstHashable(it, areEqual) to it) } ?: mapOf()
        },
        effects = { initial: Query, _ ->
            effects(initial)
        }
)


/**
 * State: State type of the system.
 * Query: Subset of state used to control the feedback loop.
 *
 * When query returns some value, that value is being passed into `effects` lambda to decide which effects should be performed.
 * In case new `query` is different from the previous one, new effects are calculated by using `effects` lambda and then performed.
 *
 * When `query` returns null, feedback loops doesn't perform any effect.
 *
 * @param query Part of state that controls feedback loop.
 * @param effects Chooses which effects to perform for certain query result.
 * @return Feedback loop performing the effects.
 */
fun <State, Query, Event> react(
        query: (State) -> Query?,
        effects: (Query) -> Observable<Event>
): (ObservableSchedulerContext<State>) -> Observable<Event> =
        react(query, { lhs, rhs -> lhs == rhs }, effects)


/**
 * State: State type of the system.
 * Query: Subset of state used to control the feedback loop.
 *
 * When query returns some value, that value is being passed into `effects` lambda to decide which effects should be performed.
 * In case new `query` is different from the previous one, new effects are calculated by using `effects` lambda and then performed.
 *
 * When `query` returns null, feedback loops doesn't perform any effect.
 *
 * @param query Part of state that controls feedback loop.
 * @param areEqual Part of state that controls feedback loop.
 * @param effects Chooses which effects to perform for certain query result.
 * @return Feedback loop performing the effects.
 */
fun <State, Query, Event> reactSafe(
        query: (State) -> Query?,
        areEqual: (Query, Query) -> Boolean,
        effects: (Query) -> Signal<Event>
): (Driver<State>) -> Signal<Event> =
        { state ->
            val observableSchedulerContext = ObservableSchedulerContext(
                    state.asObservable(),
                    Signal.scheduler
            )
            react(query, areEqual, { effects(it).asObservable() })(observableSchedulerContext)
                    .asSignal(Signal.empty())
        }


/**
 * State: State type of the system.
 * Query: Subset of state used to control the feedback loop.
 *
 * When query returns some value, that value is being passed into `effects` lambda to decide which effects should be performed.
 * In case new `query` is different from the previous one, new effects are calculated by using `effects` lambda and then performed.
 *
 * When `query` returns null, feedback loops doesn't perform any effect.
 *
 * @param query Part of state that controls feedback loop.
 * @param effects Chooses which effects to perform for certain query result.
 * @return Feedback loop performing the effects.
 */
fun <State, Query, Event> reactSafe(
        query: (State) -> Query?,
        effects: (Query) -> Signal<Event>
): (Driver<State>) -> Signal<Event> =
        reactSafe(query, { lhs, rhs -> lhs == rhs }, effects)


/**
 * State: State type of the system.
 * Query: Subset of state used to control the feedback loop.
 *
 * When `query` returns some set of values, each value is being passed into `effects` lambda to decide which effects should be performed.
 *
 * Effects are not interrupted for elements in the new `query` that were present in the `old` query.
 * Effects are cancelled for elements present in `old` query but not in `new` query.
 * In case new elements are present in `new` query (and not in `old` query) they are being passed to the `effects` lambda and resulting effects are being performed.
 *
 * @param query Part of state that controls feedback loop.
 * @param effects Chooses which effects to perform for certain query element.
 * @return Feedback loop performing the effects.
 */
fun <State, Query, Event> reactSet(
        query: (State) -> Set<Query>,
        effects: (Query) -> Observable<Event>
): (ObservableSchedulerContext<State>) -> Observable<Event> = react(
        queries = { state: State ->
            query(state).associateBy { it }
        },
        effects = { initial: Query, _ ->
            effects(initial)
        }
)

/**
 * State: State type of the system.
 * Query: Subset of state used to control the feedback loop.
 *
 * When `query` returns some set of values, each value is being passed into `effects` lambda to decide which effects should be performed.
 *
 * Effects are not interrupted for elements in the new `query` that were present in the `old` query.
 * Effects are cancelled for elements present in `old` query but not in `new` query.
 * In case new elements are present in `new` query (and not in `old` query) they are being passed to the `effects` lambda and resulting effects are being performed.
 *
 * @param query Part of state that controls feedback loop.
 * @param effects Chooses which effects to perform for certain query element.
 * @return Feedback loop performing the effects.
 */
fun <State, Query, Event> reactSetSafe(
        query: (State) -> Set<Query>,
        effects: (Query) -> Signal<Event>
): (Driver<State>) -> Signal<Event> =
        { state ->
            val observableSchedulerContext = ObservableSchedulerContext(
                    state.asObservable(),
                    Signal.scheduler
            )
            reactSet(query, { effects(it).asObservable() })(observableSchedulerContext)
                    .asSignal(Signal.empty())
        }


/**
 * The purpose of QueryLifetimeTracking is to activate, deactivate,
 * as well as update already activated effects with the new Query that was returned.
 */
private class QueryLifetimeTracking<Query, QueryID, Event>(
        val effects: (intialQuery: Query, state: Observable<Query>) -> Observable<Event>,
        val scheduler: Scheduler,
        val emitter: Emitter<Event>) {

    /**
     * Used to make sure that we only get events from the effects that are still active.
     * Prevents concurrency issues caused by reactivation of the same effect (same QueryID)
     */
    private class LifetimeToken

    /**
     * Used to track the effect that was started and update it with the new value of the Query.
     */
    private data class QueryLifetime<Query>(val subscription: Disposable,
                                    val lifetimeIdentifier: LifetimeToken,
                                    val latestQuery: BehaviorSubject<Query>)

    /**
     * State controls the effects by QueryID.
     */
    private data class State<QueryID, Query>(var isDisposed: Boolean,
                                     var lifetimeByIdentifier: MutableMap<QueryID, QueryLifetime<Query>>)

    private val state = AsyncSynchronized(State<QueryID, Query>(false, mutableMapOf()))


    /**
     * When a new QueryID is provided in the queries parameter and was not previously present in the state.lifeTimeByIdentifier,
     * we start the effect with corresponding Query as initial value.
     * The effect will remain active and generated events will be pushed back to the system
     * as long as the same QueryID that started the effect is still present in subsequent calls.
     *
     *
     * When for the same QueryID, a new distinct value of Query is provided, the already started effect
     * will receive the new Query.
     *
     * When a QueryID that is present in state.lifeTimeByIdentifier but is not present in the provided
     * queries the corresponding effect is disposed.
     *
     * @param queries used to activate, deactivate, update effects.
     */
    fun forwardQueries(queries: Map<QueryID, Query>) {
        this.state.enqueueOrExecuteAll { state ->
            if (state.isDisposed) {
                return@enqueueOrExecuteAll
            }
            val lifetimeToUnsubscribeByIdentifier = state.lifetimeByIdentifier.toMutableMap()
            for ((queryID, query) in queries) {
                val queryLifetime = state.lifetimeByIdentifier[queryID]
                if (queryLifetime != null) {
                    lifetimeToUnsubscribeByIdentifier.remove(queryID)
                    if (queryLifetime.latestQuery.value != query) {
                        queryLifetime.latestQuery.onNext(query)
                    } else continue
                } else {
                    val latestQuerySubject = BehaviorSubject.createDefault(query)
                    val lifetime = LifetimeToken()

                    fun valid(state: State<QueryID, Query>): Boolean {
                        return !state.isDisposed && state.lifetimeByIdentifier[queryID]?.lifetimeIdentifier === lifetime
                    }

                    val queriesSubscription = this.effects(query, latestQuerySubject)
                            .observeOn(this.scheduler)
                            .subscribe({ event: Event ->
                                this.state.enqueueOrExecuteAll {
                                    if (valid(it)) {
                                        emitter.onNext(event)
                                    }
                                }
                            }, { throwable: Throwable ->
                                this.state.enqueueOrExecuteAll {
                                    if (valid(it)) {
                                        emitter.onError(throwable)
                                    }
                                }
                            })

                    state.lifetimeByIdentifier[queryID] = QueryLifetime(
                            subscription = queriesSubscription,
                            lifetimeIdentifier = lifetime,
                            latestQuery = latestQuerySubject
                    )
                }
            }
            lifetimeToUnsubscribeByIdentifier.keys.forEach { queryID ->
                state.lifetimeByIdentifier.remove(queryID)
            }
            lifetimeToUnsubscribeByIdentifier.values.forEach {
                it.subscription.dispose()
            }
        }
    }

    fun dispose() {
        this.state.enqueueOrExecuteAll { state ->
            state.lifetimeByIdentifier.values.forEach { it.subscription.dispose() }
            state.lifetimeByIdentifier = mutableMapOf()
            state.isDisposed = true
        }
    }
}


/**
 * State: State type of the system.
 * Request: Subset of state used to control the feedback loop.
 * For every uniquely identifiable request `effects` closure is invoked with the initial value of the request and future requests corresponding to the same identifier.
 * Subsequent equal values of request are not emitted from the effects state parameter.
 *
 * @param queries: Requests to perform some effects.
 * @param effects: The request effects.
 * @param initial: Initial request.
 * @param state: Latest request state.
 * @return The feedback loop performing the effects.
 */
fun <State, Query, QueryID, Event> react(queries: (State) -> Map<QueryID, Query>,
                                         effects: (initial: Query, state: Observable<Query>) -> Observable<Event>
): (ObservableSchedulerContext<State>) -> Observable<Event> {
    return { stateContext ->
        Observable.create { emitter ->
            val state = QueryLifetimeTracking<Query, QueryID, Event>(effects, stateContext.scheduler, emitter)
            val subscription = stateContext.source
                    .map(queries)
                    .subscribe({ queries ->
                        state.forwardQueries(queries)
                    }, { throwable: Throwable ->
                        emitter.onError(throwable)
                    }, {
                        emitter.onComplete()
                    })

            emitter.setCancellable {
                state.dispose()
                subscription.dispose()
            }
        }
    }
}


/**
 * State: State type of the system.
 * Request: Subset of state used to control the feedback loop.
 * For every uniquely identifiable request `effects` closure is invoked with the initial value of the request and future requests corresponding to the same identifier.
 * Subsequent equal values of request are not emitted from the effects state parameter.
 *
 * @param queries: Requests to perform some effects.
 * @param effects: The request effects.
 * @param initial: Initial request.
 * @param state: Latest request state.
 * @return The feedback loop performing the effects.
 */
@Suppress("NAME_SHADOWING")
fun <State, Query, QueryID, Event> reactSafe(queries: (State) -> Map<QueryID, Query>,
                                             effects: (initial: Query, state: Driver<Query>) -> Signal<Event>
): (Driver<State>) -> Signal<Event> {
    return { state: Driver<State> ->
        val observableSchedulerContext = ObservableSchedulerContext(
                state.asObservable(),
                Signal.scheduler
        )
        react(queries = queries,
                effects = { initial, state ->
                    effects(initial,
                            state.asDriver(Driver.empty())
                    ).asObservable()
                }
        )(observableSchedulerContext)
                .asSignal(Signal.empty())
    }
}

fun <Element> Observable<Element>.enqueue(scheduler: Scheduler): Observable<Element> =
        this
                // observe on is here because results should be cancelable
                .observeOn(scheduler)
                // subscribe on is here because side-effects also need to be cancelable
                // (smooths out any glitches caused by start-cancel immediatelly)
                .subscribeOn(scheduler)


/**
 * Contains subscriptions and events.
 *
 * @param subscriptions map a system state to UI presentation.
 * @param events map events from UI to events of a given system.
 */
data class Bindings<Event>(val subscriptions: Iterable<Disposable>, val events: Iterable<Observable<Event>>) : Disposable {

    companion object {
        fun <Event> safe(subscriptions: Iterable<Disposable>, events: Iterable<Signal<Event>>): Bindings<Event> =
                Bindings(subscriptions, events.map { it.asObservable() })
    }

    override fun dispose() {
        for (subscription in subscriptions) {
            subscription.dispose()
        }
    }

    override fun isDisposed(): Boolean {
        return false
    }
}

/**
Bi-directional binding of a system State to external state machine and events from it.
 */
fun <State, Event> bind(bindings: (ObservableSchedulerContext<State>) -> (Bindings<Event>)): (ObservableSchedulerContext<State>) -> Observable<Event> =
        { state: ObservableSchedulerContext<State> ->
            Observable.using({
                bindings(state)
            }, { bindings: Bindings<Event> ->
                Observable.merge(bindings.events).concatWith(Observable.never())
                        .enqueue(state.scheduler)
            }, { it.dispose() })
        }

/**
Bi-directional binding of a system State to external state machine and events from it.
 */
fun <State, Event> bindSafe(bindings: (Driver<State>) -> (Bindings<Event>)): (Driver<State>) -> Signal<Event> =
        { state: Driver<State> ->
            Observable.using({
                bindings(state)
            }, { bindings: Bindings<Event> ->
                Observable.merge(bindings.events).concatWith(Observable.never())
            }, { it.dispose() })
                    .enqueue(Signal.scheduler)
                    .asSignal(Signal.empty<Event>())
        }


/**
 * This looks like a performance issue, but it is ok when there is a single value present. Used in a `react` feedback loop.
 */
private class ConstHashable<Value>(val value: Value, val areEqual: (Value, Value) -> Boolean) {

    override fun hashCode(): Int {
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConstHashable<*>

        @Suppress("UNCHECKED_CAST")
        val otherValue = other.value as Value
        return areEqual(value, otherValue)
    }
}
