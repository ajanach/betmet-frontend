package info.czekanski.bet.domain.game

import android.arch.lifecycle.*
import android.net.Uri
import com.google.firebase.dynamiclinks.*
import durdinapps.rxfirebase2.RxHandler
import info.czekanski.bet.domain.game.GameViewState.Step
import info.czekanski.bet.misc.*
import info.czekanski.bet.misc.plusAssign
import info.czekanski.bet.network.*
import info.czekanski.bet.network.firebase.model.FirebaseBet
import info.czekanski.bet.network.model.Bet
import info.czekanski.bet.repository.*
import info.czekanski.bet.user.UserProvider
import io.reactivex.*
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.*
import timber.log.Timber
import javax.inject.Inject

class GameViewModel @Inject constructor(
        private val betService: BetApi,
        private val userProvider: UserProvider,
        private val betRepository: BetRepository,
        private val matchRepository: MatchRepository,
        private val friendsRepository: FriendsRepository
) : ViewModel() {
    private val subs = CompositeDisposable()
    private val state = MutableLiveData<GameViewState>()
    private val toast = MutableLiveData<String>()

    override fun onCleared() {
        super.onCleared()
        subs.clear()
    }

    fun getState(arg: GameFragment.Argument? = null): LiveData<GameViewState> {
        if (state.value == null && arg != null) {
            val (matchId, betId) = arg

            if (matchId == null && betId == null) {
                throw RuntimeException("Invalid parameters for MatchFragment - pass either matchId or betId")
            } else if (matchId != null) {
                // New bet - go to bid state
                loadMatch(matchId)
                state.value = GameViewState(step = Step.BID)
            } else if (betId != null) {
                // Existing bet - go to list
                loadBet(betId)
                state.value = GameViewState(step = Step.LIST, showLoader = true)
            }

            state.value = state.v.copy(userId = userProvider.userId)
        }

        return state
    }

    fun getToast(): LiveData<String> {
        toast.value = null
        return toast
    }


    fun buttonClicked(action: Action) {
        when (action) {
            Action.BidMinus -> {
                if (state.v.bid > 0) state.value = state.v.copy(bid = state.v.bid - 5)
            }
            Action.BidPlus -> {
                if (state.v.bid < 100) state.value = state.v.copy(bid = state.v.bid + 5)
            }
            Action.BidAccept -> {
                state.value = state.v.copy(step = Step.SCORE)
            }
            Action.Team1ScoreMinus -> {
                if (state.v.score.first > 0) state.value = state.v.updateScore(first = state.v.score.first - 1)
            }
            Action.Team1ScorePlus -> {
                if (state.v.score.first < 9) state.value = state.v.updateScore(first = state.v.score.first + 1)
            }
            Action.Team2ScoreMinus -> {
                if (state.v.score.second > 0) state.value = state.v.updateScore(second = state.v.score.second - 1)
            }
            Action.Team2ScorePlus -> {
                if (state.v.score.second < 9) state.value = state.v.updateScore(second = state.v.score.second + 1)
            }
            Action.ScoreAccept -> {
                updateOrCreateBet()
            }
            Action.GotoBet -> {
                state.value = state.v.copy(
                        step = Step.BID,
                        bid = 0,
                        score = Pair(0, 0)
                )
            }
            Action.EditBet -> {
                val bet = state.v.bet?.bets?.get(userProvider.userId!!)

                state.value = state.v.copy(
                        step = Step.BID,
                        bid = bet?.bid ?: 0,
                        score = bet?.score?.scoreToPair() ?: Pair(0, 0)
                )
            }
            Action.DeleteBet -> {
                if (state.v.step == Step.LIST && state.v.bet != null) {
                    deleteBet()
                }
            }
            Action.Share -> action@ {
                val userId = userProvider.userId ?: return

                val friendsSingle = friendsRepository.getFriends(userId)
                        .map { friends ->
                            // Filter out friends that are already invited
                            friends.filter { state.v.bet?.users?.containsKey(it.id) == false }
                        }

                Singles.zip(friendsSingle, createShareLink().toSingle(), { friends, link -> Pair(friends, link) })
                        .doOnSubscribe { state.value = state.v.copy(showLoader = true) }
                        .doFinally { state.value = state.v.copy(showLoader = false) }
                        .subscribeBy(onSuccess = {
                            val (friends, link) = it

                            state.value = state.v.copy(
                                    friends = friends,
                                    shareLink = link,
                                    step = Step.FRIENDS
                            )
                        }, onError = {
                            Timber.e(it, "createShareLink")
                        })
            }
        }
    }

    private fun deleteBet() {
        val s = state.v
        if (s.bet != null) {
            subs += betService.deleteBet(s.bet.id, userProvider.userId!!)
                    .applySchedulers()
                    .doOnSubscribe { state.value = this.state.v.copy(showLoader = true) }
                    .doFinally { state.value = this.state.v.copy(showLoader = false) }
                    .subscribeBy(onComplete = {
                        state.value = this.state.v.copy(closeView = true)
                    }, onError = {
                        toast.value = "Unable to delete bet!"
                        Timber.e(it, "deleteBet")
                    })
        }
    }

    private fun updateOrCreateBet() {
        val s = state.v
        if (s.match == null) return
        if (s.bet == null) {
            subs += betService.createBet(s.match.id, Bet(state.v.bid, state.v.scoreAsString()), userProvider.userId!!)
                    .applySchedulers()
                    .doOnSubscribe { state.value = this.state.v.copy(showLoader = true) }
                    .doFinally { state.value = this.state.v.copy(step = Step.LIST, showLoader = false) }
                    .subscribeBy(onSuccess = { result ->
                        if (state.v.bet == null) loadBet(result.id)
                    }, onError = {
                        state.value = state.v.copy(step = Step.BID)
                        toast.value = "Unable to create bet!"
                        Timber.w(it)
                    })
        } else {
            subs += betService.updateBet(s.bet.id, Bet(state.v.bid, state.v.scoreAsString()), userProvider.userId!!)
                    .applySchedulers()
                    .doOnSubscribe { state.value = this.state.v.copy(showLoader = true) }
                    .doFinally { state.value = this.state.v.copy(step = Step.LIST, showLoader = false) }
                    .subscribeBy(onError = {
                        toast.value = "Unable to update bet!"
                        Timber.e(it, "updateOrCreateBet")
                    })
        }
    }


    private fun loadMatch(matchId: String) {
        subs += matchRepository.observeMatch(matchId)
                .subscribeBy(
                        onNext = { state.value = state.v.copy(match = it) },
                        onError = {
                            toast.value = "Unable to load match!"
                            Timber.e(it, "getMatch")
                        }
                )
    }

    private fun loadBet(betId: String) {
        subs += betRepository.observeBet(betId)
                .subscribeBy(onNext = { bet ->
                    state.value = state.v.copy(bet = bet, showLoader = false)

                    loadNicknames(bet)
                    if (state.v.match == null) loadMatch(bet.matchId)
                }, onError = {
                    toast.value = "Unable to load bet!"
                    Timber.e(it, "loadBet")
                })
    }

    private fun loadNicknames(bet: FirebaseBet) {
        var flowable: Flowable<Friend> = Flowable.empty()

        bet.bets.keys.forEach { userId ->
            flowable = flowable.mergeWith(friendsRepository.getName(userId)
                    .map { userName -> Friend(userId, userName) }
                    .toFlowable())
        }

        subs += flowable
                .subscribeBy(onNext = { friend ->
                    val (userId, nick) = friend
                    state.value = state.v.updateNickname(userId, nick)
                }, onError = {
                    Timber.e(it, "loadNicknames")
                })

    }

    // TODO: To provider
    private fun createShareLink(): Maybe<Uri> {
        val betId = state.v.bet?.id ?: return Maybe.error(RuntimeException("No bet id!"))

        val dynamicLink = FirebaseDynamicLinks.getInstance().createDynamicLink()
                .setLink(Uri.parse("http://20.105.175.71:80/bet/$betId"))
                .setDynamicLinkDomain("uzz4b.app.goo.gl")
//                .setDynamicLinkDomain("bet.page.link")
                .setAndroidParameters(DynamicLink.AndroidParameters.Builder().build())
                .setSocialMetaTagParameters(DynamicLink.SocialMetaTagParameters.Builder()
                        .setTitle("Kladite se tko će pobijediti")
                        .setDescription("Preuzmite aplikaciju i pridruži se zabavi")
                        .setImageUrl(Uri.parse("https://i.imgur.com/GwVqwJ0.png"))
                        .build())
                .buildShortDynamicLink()

        return Maybe.create<ShortDynamicLink> { emitter -> RxHandler.assignOnTask(emitter, dynamicLink) }
                .map { it.shortLink }
                .applySchedulers()
    }

    fun shareLinkTo(userId: String) {
        val betId = state.v.bet?.id ?: return
        betService.inviteUser(betId, userId, userProvider.userId!!)
                .applySchedulers()
                .doOnSubscribe { state.value = this.state.v.copy(showLoader = true) }
                .doFinally { state.value = this.state.v.copy(showLoader = false) }
                .subscribeBy(onError = {
                    toast.value = "Unable to share bet!"
                    Timber.e(it, "shareLinkTo")
                })
    }

    fun sharedLink() {
        state.value = state.v.copy(step = Step.LIST)
    }

    fun onBackPressed(): Boolean {
        val s = state.v

        return when {
            s.step == Step.FRIENDS -> {
                state.value = s.copy(step = Step.LIST)
                true
            }
            s.step == Step.BID && s.bet != null -> {
                state.value = s.copy(step = Step.LIST)
                true
            }
            s.step == Step.SCORE && s.bet != null -> {
                state.value = s.copy(step = Step.BID)
                true
            }
            else -> false
        }
    }

    enum class Action {
        BidMinus, BidPlus, BidAccept,
        Team1ScoreMinus, Team1ScorePlus, Team2ScoreMinus, Team2ScorePlus, ScoreAccept,
        GotoBet, DeleteBet, EditBet, Share,
    }
}