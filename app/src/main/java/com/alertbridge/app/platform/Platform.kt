package com.alertbridge.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 음성 안내. Pleos TTS SDK를 나중에 주입한다. */
interface Tts {
    fun speak(text: String)
}

/** 음성 인식. Pleos STT SDK를 나중에 주입한다. */
interface Stt {
    fun start(onResult: (String) -> Unit)
    fun stop()
}

/** 전화 화면을 연다. 번호만 띄우며 발신은 사용자가 직접 누른다. */
interface Dialer {
    /** 전화 화면을 열었으면 true */
    fun dial(number: String): Boolean
}

/** 보호자 알림. 기관 연락에는 쓰지 않는다. */
interface GuardianNotifier {
    fun notify(phone: String, message: String)
}

class AndroidDialer(private val context: Context) : Dialer {
    override fun dial(number: String): Boolean = try {
        context.startActivity(dialIntent(number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false // 전화 앱이 없는 차량 디스플레이일 수 있다.
    }

    companion object {
        fun dialIntent(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() }))
    }
}

/** 디버그 빌드용. 전화 앱을 열지 않고 열었다고 가정만 한다. 실수로 기관에 걸리는 일을 막는다. */
class TestModeDialer(private val onDial: (String) -> Unit) : Dialer {
    override fun dial(number: String): Boolean {
        onDial(number)
        return true
    }
}

/**
 * Android 기본 TTS. Pleos TTS SDK가 들어오면 같은 인터페이스로 바꾼다.
 * 한국어 음성이 없거나 초기화 전이면 소리 없이 [onSpeak]로만 알린다.
 */
class AndroidTts(context: Context, private val onSpeak: (String) -> Unit) : Tts {
    private var ready = false
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS && setKorean()
    }

    private fun setKorean(): Boolean {
        val r = engine.setLanguage(Locale.KOREAN)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun speak(text: String) {
        onSpeak(text)
        if (ready) engine.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() = engine.shutdown()
}

/** 실제 음성을 내지 않고 읽을 문장만 남긴다. 디버그 화면에 보여 준다. */
class RecordingTts(private val onSpeak: (String) -> Unit) : Tts {
    override fun speak(text: String) = onSpeak(text)
}

class FakeStt : Stt {
    override fun start(onResult: (String) -> Unit) = Unit
    override fun stop() = Unit
}

class FakeGuardianNotifier(private val onNotify: (String, String) -> Unit) : GuardianNotifier {
    override fun notify(phone: String, message: String) = onNotify(phone, message)
}
