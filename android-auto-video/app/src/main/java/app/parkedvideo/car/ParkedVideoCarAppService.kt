package app.parkedvideo.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class ParkedVideoCarAppService : CarAppService() {

    // This app is sideloaded for personal use, so any Android Auto host is accepted.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = ParkedVideoSession()
}
