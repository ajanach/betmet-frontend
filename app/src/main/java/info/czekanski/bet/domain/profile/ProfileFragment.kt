package info.czekanski.bet.domain.profile


import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.view.*
import android.widget.Toast
import info.czekanski.bet.R
import info.czekanski.bet.di.utils.BaseFragment
import info.czekanski.bet.domain.login.LoginActivity
import info.czekanski.bet.misc.*
import info.czekanski.bet.user.UserProvider
import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.fragment_profile.*
import timber.log.Timber
import javax.inject.Inject

class ProfileFragment : BaseFragment() {
    @Inject lateinit var userProvider: UserProvider

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    @SuppressLint("PrivateResource")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textNick.text = userProvider.nick ?: "Gost"
        buttonChangeNick.setOnClickListener {
            startActivity(
                    Intent(context, LoginActivity::class.java)
            )
        }

        buttonDeleteAccount.setOnClickListener {
            val dialog = AlertDialog.Builder(requireContext(), R.style.Base_Theme_MaterialComponents_Light_Dialog)
                    .setTitle("Jesi li siguran")
                    .setMessage("Brisanje računa je nepovratno!")
                    .setNegativeButton("Otkaži", { dialogInterface, i ->
                        dialogInterface.dismiss()
                    })
                    .setPositiveButton("Izbrisati", { dialogInterface, i ->
                        userProvider.setNick(null)
                                .andThen(Completable.defer { userProvider.removeFcmToken() })
                                .andThen(Completable.defer { userProvider.logout() })
                                .doOnSubscribe { progress.show() }
                                .doAfterTerminate {
                                    progress.hide()
                                    dialogInterface.dismiss()
                                }
                                .subscribeBy(onComplete = {
                                    Toast.makeText(context, "Račun izbrisan", Toast.LENGTH_SHORT).show()
                                    requireActivity().finish()
                                    requireActivity().recreate()
                                }, onError = {
                                    Toast.makeText(context, "Problem s brisanjem računa, pokušajte ponovno kasnije", Toast.LENGTH_LONG).show()
                                    Timber.e(it, "DeleteAccount")
                                })
                    }).create()

            dialog.show()
        }

    }

}