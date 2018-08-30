package org.notests.rxfeedbackexample.counter

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.jakewharton.rxbinding2.view.RxView
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import kotlinx.android.synthetic.main.activity_counter.*
import org.notests.rxfeedback.Bindings
import org.notests.rxfeedback.Observables
import org.notests.rxfeedback.bind
import org.notests.rxfeedback.system
import org.notests.rxfeedbackexample.R

/**
 * Created by Juraj Begovac on 01/12/2017.
 */

typealias State = Int

enum class Event {
    Increment, Decrement
}

class Counter : AppCompatActivity() {

    private var disposable: Disposable = Disposables.empty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_counter)

        supportActionBar?.title = "Counter"

        disposable = Observables.system(
            initialState = 0,
            reduce = { state, event: Event ->
                when (event) {
                    Event.Increment -> state + 1
                    Event.Decrement -> state - 1
                }
            },
            scheduler = AndroidSchedulers.mainThread(),
            scheduledFeedback = listOf(
                bind { observableSchedulerContext ->
                    val subscriptions = listOf(
                        observableSchedulerContext.source.map { it.toString() }.subscribe { label.text = it }
                    )
                    val events = listOf(
                        RxView.clicks(plus).map { Event.Increment },
                        RxView.clicks(minus).map { Event.Decrement }
                    )
                    return@bind Bindings(subscriptions, events)
                })
        )
            .subscribe()
    }

    override fun onDestroy() {
        disposable.dispose()
        super.onDestroy()
    }
}
