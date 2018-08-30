package org.notests.rxfeedbackexample.github.paginated.search

import android.os.Bundle
import android.support.annotation.MainThread
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.jakewharton.rxbinding2.support.v7.widget.RxSearchView
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.android.synthetic.main.activity_github_paginated_search.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.notests.rxfeedback.Bindings
import org.notests.rxfeedback.bindSafe
import org.notests.rxfeedback.reactSafe
import org.notests.rxfeedback.system
import org.notests.rxfeedbackexample.R
import org.notests.rxfeedbackexample.github.paginated.search.RepositoryRecyclerViewAdapter.ViewHolder
import org.notests.sharedsequence.Driver
import org.notests.sharedsequence.Signal
import org.notests.sharedsequence.asSignal
import org.notests.sharedsequence.drive
import org.notests.sharedsequence.empty
import org.notests.sharedsequence.just
import org.notests.sharedsequence.map
import org.notests.sharedsequence.switchMapSignal
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Created by Juraj Begovac on 03/12/2017.
 */

data class Repository(val name: String, val url: String)

data class State(
    var search: String,
    var nextPageUrl: Option<String>,
    var shouldLoadNextPage: Boolean,
    var results: List<Repository>,
    var lastError: Option<GitHubServiceError>
) {
    companion object {}
}

sealed class Event {
    data class SearchChanged(val search: String) : Event()
    data class Response(val response: SearchRepositoriesResponse) : Event()
    object StartLoadingNextPage : Event()
}

val State.Companion.empty: State
    get() = State(search = "", nextPageUrl = None, shouldLoadNextPage = false, results = emptyList(), lastError = None)

// transitions
fun State.Companion.reduce(state: State, event: Event): State =
    when (event) {
        is Event.SearchChanged -> {
            if (event.search.isEmpty()) {
                state.copy(
                    search = event.search,
                    nextPageUrl = None,
                    shouldLoadNextPage = false,
                    results = emptyList(),
                    lastError = None
                )
            } else {
                state.copy(
                    search = event.search,
                    nextPageUrl = Some("https://api.github.com/search/repositories?q=${event.search}"),
                    results = emptyList(),
                    shouldLoadNextPage = true,
                    lastError = None
                )
            }
        }

        Event.StartLoadingNextPage -> state.copy(shouldLoadNextPage = true)

        is Event.Response ->
            when (event.response) {
                is Result.Success ->
                    state.copy(
                        results = state.results.plus(event.response.value.first),
                        shouldLoadNextPage = false,
                        nextPageUrl = event.response.value.second,
                        lastError = None
                    )
                is Result.Failure ->
                    state.copy(
                        shouldLoadNextPage = false,
                        lastError = Some(event.response.error)
                    )
            }
    }

// queries
var State.loadNextPage: Option<String>
    get() =
        if (this.shouldLoadNextPage) this.nextPageUrl else None
    set(_) {}

class GithubPaginatedSearchActivity : AppCompatActivity() {

    private val repositoryService = RepositoryService()
    private var disposable: Disposable = Disposables.empty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github_paginated_search)

        supportActionBar?.title = "Github pagination"

        // UI stuff
        searchView.apply {
            isIconified = false
            setIconifiedByDefault(false)
        }

        recyclerview.apply {
            layoutManager = LinearLayoutManager(this@GithubPaginatedSearchActivity)
            adapter = RepositoryRecyclerViewAdapter(emptyList(), { /* do nothing on click */ })
        }

        // RxFeedback
        val triggerLoadNextPage: (Driver<State>) -> Signal<Event> = { driver ->
            driver.switchMapSignal<State, Event> { state ->
                if (state.shouldLoadNextPage) {
                    return@switchMapSignal Signal.empty<Event>()
                }

                return@switchMapSignal recyclerview
                    .nearBottom()
                    .map { Event.StartLoadingNextPage }
            }
        }

        val searchEvent: Signal<Event> = RxSearchView
            .queryTextChanges(searchView)
            .debounce(500, TimeUnit.MILLISECONDS)
            .distinctUntilChanged()
            .asSignal(onError = Signal.empty())
            .map { Event.SearchChanged(it.toString()) }

        // UI, user feedback
        val bindUI: (Driver<State>) -> Signal<Event> = bindSafe { driver ->
            val subscriptions = listOf(
                driver.map { it.lastError }.drive { showOrHideError(it) },
                driver.map { it.results }.drive { recyclerview.bindItems(it) },
                driver.map { it.loadNextPage }.drive { showQuery(it) }
            )
            val events = listOf(searchEvent, triggerLoadNextPage(driver))
            return@bindSafe Bindings.safe(subscriptions, events)
        }

        // NoUI, automatic feedback
        @Suppress("UNCHECKED_CAST")
        val bindAutomatic = reactSafe<State, String, Event>(
            query = { it.loadNextPage },
            effects = { nextPageUrl ->
                repositoryService.getSearchRepositoriesResponse(nextPageUrl)
                    .asSignal(onError = Signal.just(Result.Failure(GitHubServiceError.Offline) as SearchRepositoriesResponse))
                    .map { Event.Response(it) }
            }
        )

        disposable = Driver.system(
            initialState = State.empty,
            reduce = { state: State, event: Event -> State.reduce(state, event) },
            feedback = listOf(
                bindUI,
                bindAutomatic
            )
        )
            .drive()
    }

    override fun onDestroy() {
        disposable.dispose()
        super.onDestroy()
    }
}

sealed class Result<T, E : Error> {
    data class Success<T>(val value: T) : Result<T, Nothing>()
    data class Failure<E : Error>(val error: E) : Result<Unit, E>()
}

sealed class GitHubServiceError : Error() {
    object Offline : GitHubServiceError()
    object GithubLimitReached : GitHubServiceError()
}

var GitHubServiceError.displayMessage: String
    set(_) {}
    get() {
        return when (this) {
            GitHubServiceError.Offline -> "Ups, no network connectivity"
            GitHubServiceError.GithubLimitReached -> "Reached GitHub throttle limit, wait 60 sec"
        }
    }

private typealias SearchRepositoriesResponse = Result<Pair<List<Repository>, Option<String>>, GitHubServiceError>

// TODO this is not working for now
private fun RepositoryService.loadRepositories(resource: String): Observable<SearchRepositoriesResponse> {

    val maxAttempts = 4

    return this.getSearchRepositoriesResponse(resource)
        .retry(3)
        .retryWhen { errorTrigger ->
            return@retryWhen errorTrigger
                .mapWithIndex(Function<Throwable, Throwable> { it })
                .flatMap<Int> { indexErrorPair ->
                    val attempt = indexErrorPair.first
                    val error = indexErrorPair.second

                    if (attempt >= maxAttempts - 1) {
                        return@flatMap Observable.error<Int>(error)
                    }

                    return@flatMap Observable.timer((attempt + 1).toLong(), TimeUnit.SECONDS)
                        .take(1L)
                        .map { it.toInt() }
                }
        }
}

// REST API
class RepositoryService {

    private val client = OkHttpClient()
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()!!

    fun getSearchRepositoriesResponse(url: String): Observable<SearchRepositoriesResponse> {
        return Observable.create { e: ObservableEmitter<SearchRepositoriesResponse> ->

            val request = Request.Builder()
                .url(url)
                .build()

            val call = client.newCall(request)

            e.setCancellable { call.cancel() }

            if (call.isCanceled || call.isExecuted) {
                return@create
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call?, error: IOException) {
                    if (!e.isDisposed)
                        e.onError(error)
                }

                @Suppress("UNCHECKED_CAST")
                override fun onResponse(call: Call?, response: Response) {
                    if (response.code() == 403) {
                        e.onNext(Result.Failure(GitHubServiceError.GithubLimitReached) as SearchRepositoriesResponse)
                        e.onComplete()
                        return
                    }

                    if (!response.isSuccessful) {
                        if (!e.isDisposed)
                            e.onError(Throwable("Call failed"))
                        return
                    }

                    val repositories = parseRepositories(response.body()!!.string())
                    val nextUrl = parseNextUrl(response)

                    e.onNext(
                        Result.Success(Pair(repositories, nextUrl)) as SearchRepositoriesResponse
                    )
                    e.onComplete()
                }
            })
        }
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.computation())
    }

    private fun parseNextUrl(response: Response): Option<String> {
        val linkHeader = response.headers().get("Link") ?: return None
        val links =
            try {
                parseLinks(linkHeader)
            } catch (e: IllegalStateException) {
                emptyMap<String, String>()
            }

        val nextLink = links["next"] ?: return None
        return Some(nextLink)
    }

    private fun parseRepositories(json: String): List<Repository> {
        return moshi
            .adapter(GithubRepositoryResponse::class.java)
            .fromJson(json)!!
            .items
            .map { Repository(it.name, it.url) }
    }
}

data class GithubRepositoryResponse(val items: List<ItemResponse>)
data class ItemResponse(val name: String, val url: String)

const val parseLinksPattern = "\\s*,?\\s*<([^>]*)>\\s*;\\s*rel=\"([^\"]*)\""
val linkRegex = parseLinksPattern.toRegex()

@Throws(IllegalStateException::class)
fun parseLinks(links: String): Map<String, String> {
    val matches = linkRegex.findAll(links)
    val result: MutableMap<String, String> = HashMap()
    for (m in matches) {
        if (m.groups.size < 3) throw error("Error parsing links")
        result.put(m.groups[2]!!.value, m.groups[1]!!.value)
    }
    return result
}

private fun GithubPaginatedSearchActivity.showOrHideError(error: Option<GitHubServiceError>) {
    if (error is Some) {
        Toast.makeText(this, error.t.displayMessage, Toast.LENGTH_SHORT).show()
    }
}

private fun GithubPaginatedSearchActivity.showQuery(text: Option<String>) =
    if (text is Some) {
        Toast.makeText(this, "Query: ${text.t}", Toast.LENGTH_SHORT).show()
    } else {
        // do nothing
    }

private fun RecyclerView.bindItems(items: List<Repository>) =
    (this.adapter as RepositoryRecyclerViewAdapter).setItems(items)

private fun RecyclerView.nearBottom(): Signal<Unit> =
    (this.adapter as RepositoryRecyclerViewAdapter)
        .bindToListNearBottom()
        .asSignal { Signal.empty() }

private class RepositoryRecyclerViewAdapter(
    private var items: List<Repository>,
    private val onClick: (Repository) -> (Unit)
) : RecyclerView.Adapter<ViewHolder>() {

    private val listReachedBottom: PublishSubject<Unit> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_github_repo, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = item.name
        holder.root.setOnClickListener { onClick(item) }

        if (position == items.size - 1) {
            listReachedBottom.onNext(Unit)
        }
    }

    @MainThread
    fun setItems(items: List<Repository>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    fun bindToListNearBottom(): Observable<Unit> = listReachedBottom

    inner class ViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
        val root: RelativeLayout
            get() = v.findViewById(R.id.root)
        val textView: TextView
            get() = v.findViewById(R.id.repo_name)
    }
}

private fun <T, R> Observable<T>.mapWithIndex(mapper: io.reactivex.functions.Function<in T, out R>): Observable<Pair<Int, R>> =
    this
        .map { mapper.apply(it) }
        .zipWith(Observable.range(0, Int.MAX_VALUE), BiFunction { p0, p1 -> Pair(p1, p0) })
