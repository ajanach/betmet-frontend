package info.czekanski.bet.network

import info.czekanski.bet.model.TokenRequest
import info.czekanski.bet.network.model.*
import io.reactivex.*
import retrofit2.http.*

interface BetApi {

    @POST("/bet/{matchId}")
    fun createBet(@Path("matchId") matchId: String,
                  @Body bet: Bet,
                  @Header("Authorization") token: String): Single<ReturnId>

    @PUT("/bet/{betId}")
    fun updateBet(@Path("betId") betId: String,
                  @Body bet: Bet,
                  @Header("Authorization") token: String): Completable

    @DELETE("/bet/{betId}")
    fun deleteBet(@Path("betId") betId: String,
                  @Header("Authorization") token: String): Completable

    @POST("/bet/{betId}/invite/{userId}")
    fun inviteUser(@Path("betId") betId: String,
                   @Path("userId") userId: String,
                   @Header("Authorization") token: String): Completable


    @POST("/register")
    fun registerDevice(@Body body: TokenRequest,
                       @Header("Authorization") token: String): Completable

    @DELETE("/register")
    fun unregisterDevice(@Header("Authorization") token: String): Completable
}